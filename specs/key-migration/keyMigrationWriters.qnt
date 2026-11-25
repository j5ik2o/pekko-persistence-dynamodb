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
 */
module keyMigrationWriters {
  import keyMigration.* from "./keyMigration"

  /** The logical rows that the application deleted, and those whose first DeleteItem was sent. */
  var appDeleted: Set[LRow]
  var appDeleting: Set[LRow]
  /** The logical rows that the application wrote again (a new event with the same sequence number). */
  var appWritten: Set[LRow]

  pure val NEW_PAYLOAD = 9

  action initW = all { init, appDeleted' = Set(), appDeleting' = Set(), appWritten' = Set() }

  pure def idsInIndex(i: Table): Set[Id] = i.keys().map(k => i.get(k).id)

  /** Write (id, 1) under `key` with sameRowOrNone, after a recovery that saw no event in the index. */
  action recoverAndWrite(l: LRow, key: Key): bool = {
    val t = tables.get(Journal)
    all {
      presentRows(t).contains(l),                           // the actor has this event in the table
      not(idsInIndex(idx.get(Journal)).contains(l._1)),     // the index does not show it (yet)
      not(t.has(key)) or t.get(key).id == l._1,              // sameRowOrNone
      tables' = tables.set(Journal, t.put(key, { id: l._1, seq: l._2, payload: NEW_PAYLOAD, deleted: false, created: 0 })),
      appWritten' = appWritten.union(Set(l)),
      appDeleted' = appDeleted, appDeleting' = appDeleting,
      idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget,
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
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget,
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
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget,
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

  action stepW = any {
    all { step, appDeleted' = appDeleted, appDeleting' = appDeleting, appWritten' = appWritten },
    appStep,
  }

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
