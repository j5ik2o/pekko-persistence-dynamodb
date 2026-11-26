// -*- mode: Bluespec; -*-
/**
 * keyMigration.qnt with the contract taken away: an application writes to and recovers from the
 * journal while the tool moves rows, or before the index has caught up (docs: "Stop the application
 * before you move the rows" and "Before you start the application").
 *
 * The application, as JournalRowStore does it:
 *  - recover + write (PlainText node): the highest sequence number is read from the index; an event
 *    that the index does not show yet is not seen, and the next event is written with the same
 *    sequence number under the current key, with the condition `attribute_not_exists(pkey) OR
 *    persistence-id = :pid` (sameRowOrNone), which a row of the same persistence id passes;
 *  - recover + write (a node of 1.3.0): the same, under the key of 1.3.0;
 *  - hard delete (`delete`): a DeleteItem under the current key, and then a second DeleteItem under
 *    the old key (deleteLegacyRows), each alone, not in one transaction.
 *
 * Every invariant of keyMigration.qnt that this module breaks is restated here with the deletes of
 * the application taken into account; `quint run --invariant <name>` shows the counterexample.
 *
 * Review O-F3: the second half of the module models the application that follows the documentation
 * (`stepG`): it starts only after a matching --verify of both tables, with no tool running and no
 * change since, and the tools do not run after it started. With `staleReads`, a read of the index can
 * return any value the index held before (a lagging replica, not monotonic); with `headEnabled`, a
 * recovery also reads the highest sequence number of the JournalHead item, with strong consistency.
 * Only the journal has writers here; the snapshot store has none (review C-F8).
 */
module keyMigrationWriters {
  import keyMigration.* from "./keyMigration"

  /** The logical rows that the application deleted, and those whose first DeleteItem was sent. */
  var appDeleted: Set[LRow]
  var appDeleting: Set[LRow]
  /** The logical rows that the application wrote again (a new event with the same sequence number). */
  var appWritten: Set[LRow]
  /** O-F3: the application has started (`stepG`), and as which node: Forward = PlainText, Reverse = 1.3.0. */
  var appStarted: bool
  var appNode: Dir
  /** O-F3: the JournalHead is enabled; a read of the index may return an older value (`idxHist`). */
  var headEnabled: bool
  var staleReads: bool
  /** Every value the journal index held so far (what a lagging replica can still return). */
  var idxHist: Set[Table]
  /** A step of the tool ran after the last --verify of the table (`stepG` only). */
  var toolsSinceVerify: Store -> bool

  pure val NEW_PAYLOAD = 9

  action initW = all {
    init, appDeleted' = Set(), appDeleting' = Set(), appWritten' = Set(),
    appStarted' = false, appNode' = Forward, headEnabled' = false, staleReads' = false, idxHist' = Set(),
    toolsSinceVerify' = STORES.mapBy(_ => false),
  }

  pure def idsInIndex(i: Table): Set[Id] = i.keys().map(k => i.get(k).id)

  /** What a read of the journal index can return: its value, or with `staleReads` any value it held. */
  val indexViews: Set[Table] = if (staleReads) idxHist.union(Set(idx.get(Journal))) else Set(idx.get(Journal))

  /**
   * Write (id, 1) under `key` with sameRowOrNone, after a recovery that saw no event in the index. With the
   * JournalHead (the PlainText node only), the recovery takes the larger of the index and the head item, which
   * holds the event, so it writes the next sequence number instead and replaces nothing.
   */
  action recoverAndWrite(l: LRow, key: Key): bool = {
    val t = tables.get(Journal)
    all {
      presentRows(t).contains(l),                           // the actor has this event in the table
      indexViews.exists(i => not(idsInIndex(i).contains(l._1))),   // the index does not show it (yet)
      not(headEnabled and key == PLAIN.get(l)),
      not(t.has(key)) or t.get(key).id == l._1,              // sameRowOrNone
      tables' = tables.set(Journal, t.put(key, { id: l._1, seq: l._2, payload: NEW_PAYLOAD, deleted: false, created: 0 })),
      appWritten' = appWritten.union(Set(l)),
      appDeleted' = appDeleted, appDeleting' = appDeleting,
      idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
      appStarted' = appStarted, appNode' = appNode, headEnabled' = headEnabled, staleReads' = staleReads,
      idxHist' = idxHist.union(Set(idx.get(Journal))), toolsSinceVerify' = toolsSinceVerify,
    }
  }

  /** The first DeleteItem of a hard delete: the current (PlainText) key, if it holds a row of the id. */
  action hardDeleteCurrent(l: LRow): bool = {
    val t = tables.get(Journal)
    val k = PLAIN.get(l)
    all {
      presentRows(t).contains(l),
      not(appDeleting.contains(l)),
      tables' = tables.set(Journal, if (t.has(k) and t.get(k).id == l._1) t.remove(k) else t),
      appDeleting' = appDeleting.union(Set(l)),
      appDeleted' = appDeleted, appWritten' = appWritten,
      idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
      appStarted' = appStarted, appNode' = appNode, headEnabled' = headEnabled, staleReads' = staleReads,
      idxHist' = idxHist.union(Set(idx.get(Journal))), toolsSinceVerify' = toolsSinceVerify,
    }
  }

  /** The second DeleteItem: the old key, if it holds the same event (sameEvent). */
  action hardDeleteLegacy(l: LRow): bool = {
    val t = tables.get(Journal)
    all {
      appDeleting.contains(l),
      not(appDeleted.contains(l)),
      tables' = tables.set(Journal,
        if (R130.has(l) and t.has(R130.get(l)) and lrow(t.get(R130.get(l))) == l) t.remove(R130.get(l)) else t),
      appDeleted' = appDeleted.union(Set(l)),
      appDeleting' = appDeleting, appWritten' = appWritten,
      idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
      appStarted' = appStarted, appNode' = appNode, headEnabled' = headEnabled, staleReads' = staleReads,
      idxHist' = idxHist.union(Set(idx.get(Journal))), toolsSinceVerify' = toolsSinceVerify,
    }
  }

  /**
   * The application acts on the event b@1 only: b moves directly (its new key is free, it shares no
   * key), so a broken invariant comes from the migration and not from a key that 1.3.0 already
   * shared, the #831 key, or a key of no known form, which a delete of 1.4.0 does not reach anyway.
   */
  pure val APP_ROW: LRow = ("b", 1)

  action appStep = {
    val l = APP_ROW
    any {
      recoverAndWrite(l, PLAIN.get(l)),                                   // a node with the PlainText resolvers
      all { R130.has(l), recoverAndWrite(l, if (R130.has(l)) R130.get(l) else "") },  // a node of 1.3.0
      hardDeleteCurrent(l),
      hardDeleteLegacy(l),
    }
  }

  /** The variables of this module, unchanged (with a step of keyMigration.qnt). */
  action keepAppBut = all {
    appDeleted' = appDeleted, appDeleting' = appDeleting, appWritten' = appWritten,
    appStarted' = appStarted, appNode' = appNode, headEnabled' = headEnabled, staleReads' = staleReads,
    idxHist' = idxHist.union(Set(idx.get(Journal))),
  }
  action keepApp = all { keepAppBut, toolsSinceVerify' = toolsSinceVerify }

  action stepW = any {
    all { step, keepApp },
    appStep,
  }

  // ---- O-F3: the application that follows the documentation

  /**
   * Both tables: the last --verify matched in direction `dir` (forward with the PlainText resolvers), no step of
   * a tool ran after it (a table that a run moved and moved back is equal, but its index can still lag), and the
   * table did not change since that --verify.
   */
  def verifiedForStart(dir: Dir): bool = STORES.forall(st => {
    val v = lastVerify.get(st)
    and {
      v.done, v.pass, v.dir == dir, dir == Reverse or v.form == FPlain,
      v.table == tables.get(st),
      not(toolsSinceVerify.get(st)),
      tools.get(st).phase != Running,
    }
  })

  action appStart(dir: Dir): bool = all {
    not(appStarted),
    verifiedForStart(dir),
    appStarted' = true, appNode' = dir,
    appDeleted' = appDeleted, appDeleting' = appDeleting, appWritten' = appWritten,
    headEnabled' = headEnabled, staleReads' = staleReads, idxHist' = idxHist.union(Set(idx.get(Journal))),
    toolsSinceVerify' = toolsSinceVerify,
    tables' = tables, idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
    epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget,
    rejectShared' = rejectShared,
  }

  /** The application after it started: a PlainText node writes and deletes; a node of 1.3.0 writes its old key. */
  action appStepGuarded = {
    val l = APP_ROW
    all {
      appStarted,
      if (appNode == Forward) any { recoverAndWrite(l, PLAIN.get(l)), hardDeleteCurrent(l), hardDeleteLegacy(l) }
      else recoverAndWrite(l, R130.get(l)),
    }
  }

  /** Before the start: the tools, --verify, and the index; after it: the application and the index only. */
  action stepG = stepGWith(DIRS)
  /** The same, with only PlainText nodes (the nodes that read the JournalHead item). */
  action stepGForward = stepGWith(Set(Forward))

  action stepGWith(nodes: Set[Dir]): bool = {
    nondet st = STORES.oneOf()
    nondet dir = nodes.oneOf()
    nondet form = FORMS.oneOf()
    any {
      all { not(appStarted), toolStep(st), keepAppBut, toolsSinceVerify' = toolsSinceVerify.set(st, true) },
      all { not(appStarted), verify(st, dir, form), keepAppBut, toolsSinceVerify' = toolsSinceVerify.set(st, false) },
      all { propagate(st), keepApp },
      appStart(dir),
      appStepGuarded,
    }
  }

  /** The start of the O-F3 runs: b moves directly and a after it; the index has caught up. */
  pure val START_G: Table = Map(
    "a130" -> { id: "a", seq: 1, payload: 1, deleted: false, created: 0 },
    "b130" -> { id: "b", seq: 1, payload: 1, deleted: false, created: 0 },
  )

  action initGWith(stale: bool, withHead: bool): bool = all {
    initWith(START_G, true),
    appDeleted' = Set(), appDeleting' = Set(), appWritten' = Set(),
    appStarted' = false, appNode' = Forward, headEnabled' = withHead, staleReads' = stale, idxHist' = Set(),
    toolsSinceVerify' = STORES.mapBy(_ => false),
  }
  /** O-F3 1: an index that only catches up (`quint run --init initG --step stepG`). */
  action initG = initGWith(false, false)
  /** O-F3 2: stale reads, with the JournalHead disabled (`--init initGStale`) or enabled (`--init initGStaleHead`). */
  action initGStale = initGWith(true, false)
  action initGStaleHead = initGWith(true, true)

  /** A recovery of the PlainText node can replace the event b@1: a read can miss b and the JournalHead is not read. */
  val overwritePossible: bool = indexViews.exists(i => not(idsInIndex(i).contains(APP_ROW._1))) and not(headEnabled)

  /** O-F3: the application started (a witness). */
  val wAppStarted: bool = appStarted
  val wAppWrote: bool = appStarted and appWritten != Set()

  // ---- the invariants, with the deletes of the application taken into account

  /** W1 A logical row that the application did not delete keeps a copy with its own contents. */
  val noLossW: bool = {
    val t0 = initTables.get(Journal)
    val t = tables.get(Journal)
    presentRows(t0).exclude(appDeleting).forall(l =>
      copiesIn(t, l).exists(k => copiesIn(t0, l).exists(k0 => t0.get(k0).payload == t.get(k).payload)))
  }

  /** W2 No logical row gets more copies than it had (the application adds none for an existing event). */
  val noDuplicateW: bool = {
    val t0 = initTables.get(Journal)
    val t = tables.get(Journal)
    presentRows(t).forall(l => copiesIn(t, l).size() <= max1(copiesIn(t0, l).size()))
  }
  pure def max1(n: int): int = if (n < 1) 1 else n

  /** W3 An event that a hard delete finished deleting is gone. */
  val deletedStayGone: bool = appDeleted.forall(l => copiesIn(tables.get(Journal), l) == Set())

  /** W4 A matching forward --verify says every event of the start is there, with its own contents. */
  val verifySoundW: bool = {
    val v = lastVerify.get(Journal)
    val t0 = initTables.get(Journal)
    (v.done and v.pass and v.dir == Forward and v.form == FPlain) implies
      presentRows(t0).exclude(appDeleting).forall(l =>
        copiesIn(v.table, l).exists(k => copiesIn(t0, l).exists(k0 => t0.get(k0).payload == v.table.get(k).payload)))
  }
}
