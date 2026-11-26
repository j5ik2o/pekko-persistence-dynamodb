// -*- mode: Bluespec; -*-
/** Tests of the pure functions of keyMigration.qnt (`quint test`). */
module keyMigration_test {
  import keyMigration.* from "./keyMigration"

  pure val ALL_FWD: Params = { dir: Forward, form: FPlain, sel: IDS }
  pure val ALL_REV: Params = { dir: Reverse, form: FPlain, sel: IDS }
  pure def row(id: Id, p: int): Row = { id: id, seq: 1, payload: p, deleted: false, created: 0 }
  pure def tableOf(rows: Set[(Key, Id)]): Table = rows.fold(Map(), (t, kr) => t.put(kr._1, row(kr._2, 1)))
  pure def stuckKeys(t: Table, p: Params): Set[Key] =
    t.keys().filter(k => not(isInPlace(placement(p.dir, p.form, lrow(t.get(k)), k))))

  // ---- the plan (ResolverKeyPlan.placement), per case of the universe

  run placementForwardTest = all {
    assert(placement(Forward, FPlain, ("b", 1), "b130") == Move({ to: "bP", from: F130 })),
    assert(placement(Forward, FPlain, ("b", 1), "bP") == InPlace),
    assert(placement(Forward, FPlain, ("g", 1), "g130") == InPlace),            // no separator: same key
    assert(placement(Forward, FPlain, ("g", 1), "g831") == Move({ to: "g130", from: F831 })),
    assert(placement(Forward, F130, ("g", 1), "g831") == Move({ to: "g130", from: F831 })), // #831 also with 1.3.0
    assert(placement(Forward, F130, ("b", 1), "bP") == Unmovable("stored under the key of the new format; run with --reverse")),
    assert(placement(Forward, F130, ("j", 1), "jP") == Unmovable("the configured key resolvers cannot build a key for this persistence id")),
    assert(placement(Forward, FPlain, ("u", 1), "uX") == Unmovable(UNKNOWN_REASON)),
  }

  run placementReverseTest = all {
    assert(placement(Reverse, FPlain, ("b", 1), "bP") == Move({ to: "b130", from: FPlain })),
    assert(placement(Reverse, FPlain, ("b", 1), "b130") == InPlace),
    assert(placement(Reverse, FPlain, ("g", 1), "g831") == Move({ to: "g130", from: F831 })),
    assert(placement(Reverse, FPlain, ("j", 1), "jP") == Unmovable("1.3.0 cannot build a key for this persistence id, so its rows cannot go back")),
    assert(placement(Reverse, FPlain, ("h", 1), "hP") == Move({ to: "h130", from: FPlain })),
    assert(placement(Reverse, FPlain, ("i", 1), "iP") == Move({ to: "h130", from: FPlain })),
    // 1.3.0 cannot build a key for j, and j is under a key of no known form (not in the universe of the runs)
    assert(placement(Reverse, FPlain, ("j", 1), "uX") == Unmovable(UNKNOWN_REASON)),
  }

  // ---- the rounds

  run orderedMoveTest = {
    // a needs b130, which b holds and leaves: b first, then a
    val t = tableOf(Set(("a130", "a"), ("b130", "b")))
    val r = fullRun(Journal, t, ALL_FWD)
    all {
      assert(readyOf(scanCandidates(t, ALL_FWD), Set()).map(c => c.id) == Set("b")),
      assert(r.table.keys() == Set("b130", "bP")),
      assert(r.table.get("b130").id == "a"),
      assert(r.remaining == Set()),
    }
  }

  run cycleTest = {
    val t = tableOf(Set(("c130", "c"), ("d130", "d")))
    val r = fullRun(Journal, t, ALL_FWD)
    all { assert(r.table == t), assert(r.remaining.map(c => c.id) == Set("c", "d")) }
  }

  run blockedTest = {
    // e needs f130; f cannot move (too large under fP)
    val t = tableOf(Set(("e130", "e"), ("f130", "f")))
    val r = fullRun(Journal, t, ALL_FWD)
    all { assert(r.table == t), assert(r.remaining.map(c => c.id) == Set("e", "f")) }
  }

  run sharedReverseTest = {
    // h and i share h130: the smaller stored key (hP) wins, i is blocked
    val t = tableOf(Set(("hP", "h"), ("iP", "i")))
    val r = fullRun(Journal, t, ALL_REV)
    all {
      assert(r.table == tableOf(Set(("h130", "h"), ("iP", "i")))),
      assert(r.remaining.map(c => c.id) == Set("i")),
      assert(sharedGroupsPresent(r.table)),
      assert(not(verifyPass(r.table, r.table, Reverse, FPlain))),
    }
  }

  run mergeJournalTest = {
    val t = Map("k130" -> { ...row("k", 1), deleted: true }, "kP" -> row("k", 1))
    val r = fullRun(Journal, t, ALL_FWD)
    assert(r.table == Map("kP" -> { ...row("k", 1), deleted: true }))
  }

  run mergeJournalTargetDeletedTest = {
    // the copy under the target key is the deleted one: it stays deleted
    val t = Map("k130" -> row("k", 1), "kP" -> { ...row("k", 1), deleted: true })
    assert(fullRun(Journal, t, ALL_FWD).table == Map("kP" -> { ...row("k", 1), deleted: true }))
  }

  run mergeSnapshotTest = {
    val t = Map("k130" -> { ...row("k", 1), created: 2 }, "kP" -> { ...row("k", 1), created: 1 })
    val r = fullRun(Snapshot, t, ALL_FWD)
    assert(r.table == Map("kP" -> { ...row("k", 1), created: 2 }))
  }

  run mergeSnapshotTargetLaterTest = {
    // the copy under the target key was saved later: it stays
    val t = Map("k130" -> { ...row("k", 1), created: 1 }, "kP" -> { ...row("k", 1), created: 2 })
    assert(fullRun(Snapshot, t, ALL_FWD).table == Map("kP" -> { ...row("k", 1), created: 2 }))
  }

  run conflictTest = {
    val t = Map("k130" -> row("k", 1), "kP" -> row("k", 2))
    val r = fullRun(Journal, t, ALL_FWD)
    all { assert(r.table == t), assert(r.remaining.map(c => c.id) == Set("k")) }
  }

  // ---- the attribute that the plugin does not write is compared; the column of the rule is not

  run conflictJournalExtraTest = {
    // the same payload, `deleted` differs (merged by the rule), and only kP carries the extra attribute
    val t = Map("k130" -> { ...row("k", 1), deleted: true }, "kP" -> { ...row("k", 1), created: 1 })
    val c = { id: "k", seq: 1, src: "k130", dst: "kP" }
    val r = fullRun(Journal, t, ALL_FWD)
    all {
      assert(attempt(false, Journal, t, c, false).outcome == Conflict),
      assert(r.table == t),
      assert(r.remaining == Set(c)),
    }
  }

  run conflictSnapshotExtraTest = {
    // the same payload, `created` differs (the larger one stays by the rule), and only kP carries the extra attribute
    val t = Map("k130" -> { ...row("k", 1), created: 2 }, "kP" -> { ...row("k", 1), created: 1, deleted: true })
    val c = { id: "k", seq: 1, src: "k130", dst: "kP" }
    val r = fullRun(Snapshot, t, ALL_FWD)
    all {
      assert(attempt(false, Snapshot, t, c, false).outcome == Conflict),
      assert(r.table == t),
      assert(r.remaining == Set(c)),
    }
  }

  run mergeExtraOnBothTest = all {
    // both copies carry the extra attribute with the same value: they are the same row
    assert(fullRun(Journal, Map("k130" -> { ...row("k", 1), created: 1, deleted: true }, "kP" -> { ...row("k", 1), created: 1 }), ALL_FWD).table
      == Map("kP" -> { ...row("k", 1), created: 1, deleted: true })),
    assert(fullRun(Snapshot, Map("k130" -> { ...row("k", 1), created: 2, deleted: true }, "kP" -> { ...row("k", 1), created: 1, deleted: true }), ALL_FWD).table
      == Map("kP" -> { ...row("k", 1), created: 2, deleted: true })),
  }

  // ---- the row left its stored key and is not under the target key (another writer deleted it)

  run goneReservesTest = {
    // the scan saw h under hP and i under iP (both need h130); h's row is gone before its attempt
    val h = { id: "h", seq: 1, src: "hP", dst: "h130" }
    val i = { id: "i", seq: 1, src: "iP", dst: "h130" }
    val t = tableOf(Set(("iP", "i")))
    val s0 = { table: t, pending: Set(h, i), remaining: Set(), reserved: Set() }
    val s1 = roundStep(false, Journal, s0)
    val s2 = roundStep(false, Journal, s1)
    all {
      assert(attempt(false, Journal, t, h, false) == { table: t, outcome: Gone }),
      assert(attempt(false, Journal, t, h, true) == { table: t, outcome: Gone }),
      // h does not reach h130, so h130 stays reserved for it and i does not take it in this run
      assert(s1.reserved == Set("h130")),
      assert(s1.remaining == Set()),
      assert(s2.table == t),
      assert(s2.remaining == Set(i)),
    }
  }

  run settleNeitherKeyTest = {
    // after the last attempt, the row is under neither key (another writer deleted it)
    val c = { id: "b", seq: 1, src: "b130", dst: "bP" }
    all {
      assert(settleOutcome(Map(), c, false) == Failed("outcome unknown")),
      assert(settleOutcome(Map(), c, true) == Failed("outcome unknown")),
      assert(reserves(Failed("outcome unknown"))),
    }
  }

  run lostResponseTest = {
    // the move was applied, the response lost: the next attempt finds the row under the target
    val c = { id: "b", seq: 1, src: "b130", dst: "bP" }
    val t = tableOf(Set(("b130", "b")))
    val e1 = attempt(false, Journal, t, c, false)
    val e2 = attempt(false, Journal, e1.table, c, false)
    all { assert(e1.outcome == Moved), assert(e2.table == e1.table), assert(e2.outcome == Moved),
          assert(settleOutcome(e1.table, c, false) == Moved), assert(settleOutcome(t, c, false) == Failed("gave up")),
          // the lost transaction was a merge: the next attempt and settle report Merged
          assert(attempt(false, Journal, e1.table, c, true).outcome == Merged),
          assert(settleOutcome(e1.table, c, true) == Merged) }
  }

  // ---- outcomesTruthful: outcomes that leave the same keys are told apart

  pure def seen(b: Table, a: Table, m: bool, st: bool): Seen = { before: b, after: a, merging: m, settled: st }

  run truthfulTest = {
    val c = { id: "k", seq: 1, src: "k130", dst: "kP" }
    val both = Map("k130" -> row("k", 1), "kP" -> row("k", 1))
    val moved = Map("kP" -> row("k", 1))
    val start = Map("k130" -> row("k", 1))
    all {
      // a move, a merge, and the attempt after a lost merge
      assert(truthful(seen(start, moved, false, false), c, Moved) and not(truthful(seen(start, moved, false, false), c, Merged))),
      assert(truthful(seen(both, moved, false, false), c, Merged) and not(truthful(seen(both, moved, false, false), c, Moved))),
      assert(truthful(seen(moved, moved, true, false), c, Merged) and not(truthful(seen(moved, moved, true, false), c, Moved))),
      assert(truthful(seen(moved, moved, true, true), c, Merged) and not(truthful(seen(moved, moved, true, true), c, Moved))),
      // under neither key: Gone from an attempt, "outcome unknown" from settle
      assert(truthful(seen(Map(), Map(), false, false), c, Gone)),
      assert(not(truthful(seen(Map(), Map(), false, false), c, Failed("outcome unknown")))),
      assert(truthful(seen(Map(), Map(), false, true), c, Failed("outcome unknown"))),
      assert(not(truthful(seen(Map(), Map(), false, true), c, Gone))),
      assert(not(truthful(seen(Map(), Map(), false, false), c, Moved))),
      // under the stored key: "gave up" from settle, "rejected by DynamoDB" from an attempt
      assert(truthful(seen(start, start, false, true), c, Failed("gave up"))),
      assert(not(truthful(seen(start, start, false, false), c, Failed("gave up")))),
      assert(truthful(seen(start, start, false, false), c, Failed("rejected by DynamoDB"))),
      assert(not(truthful(seen(start, start, false, true), c, Failed("rejected by DynamoDB")))),
      assert(truthful(seen(both, both, false, false), c, Conflict)),
    }
  }

  // ---- a table whose every row can move ends verified forward

  run migratableVerifiesTest = {
    val ks = LROWS.mapBy(l => keysFor(2, l, 3))
    val t = buildTable(Journal, ks, LROWS.mapBy(_ => 1), LROWS.mapBy(_ => false), LROWS.mapBy(_ => 1), LROWS.mapBy(_ => 0))
    val r = fullRun(Journal, t, ALL_FWD)
    all {
      assert(stuckKeys(r.table, ALL_FWD) == Set()),
      assert(verifyPass(r.table, r.table, Forward, FPlain)),
    }
  }

  // ---- round trip on a table of 1.3.0 alone

  run roundTripTest = {
    val ks = LROWS.mapBy(l => keysFor(1, l, 1))
    val t = buildTable(Journal, ks, LROWS.mapBy(_ => 1), LROWS.mapBy(_ => false), LROWS.mapBy(_ => 1), LROWS.mapBy(_ => 0))
    val fwd = fullRun(Journal, t, ALL_FWD).table
    val back = fullRun(Journal, fwd, ALL_REV).table
    all { assert(fwd != t), assert(back == norm130(t)) }
  }
}
