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

  run mergeSnapshotTest = {
    val t = Map("k130" -> { ...row("k", 1), created: 2 }, "kP" -> { ...row("k", 1), created: 1 })
    val r = fullRun(Snapshot, t, ALL_FWD)
    assert(r.table == Map("kP" -> { ...row("k", 1), created: 2 }))
  }

  run conflictTest = {
    val t = Map("k130" -> row("k", 1), "kP" -> row("k", 2))
    val r = fullRun(Journal, t, ALL_FWD)
    all { assert(r.table == t), assert(r.remaining.map(c => c.id) == Set("k")) }
  }

  run lostResponseTest = {
    // the move was applied, the response lost: the next attempt finds the row under the target
    val c = { id: "b", seq: 1, src: "b130", dst: "bP" }
    val t = tableOf(Set(("b130", "b")))
    val e1 = attempt(false, Journal, t, c, false)
    val e2 = attempt(false, Journal, e1.table, c, false)
    all { assert(e1.outcome == Moved), assert(e2.table == e1.table), assert(e2.outcome == Moved),
          assert(settleOutcome(e1.table, c, false) == Moved), assert(settleOutcome(t, c, false) == Failed("gave up")) }
  }

  // ---- a table whose every row can move ends verified forward

  run migratableVerifiesTest = {
    val ks = LROWS.mapBy(l => keysFor(2, l, 3))
    val t = buildTable(Journal, ks, LROWS.mapBy(_ => 1), LROWS.mapBy(_ => false), LROWS.mapBy(_ => 1))
    val r = fullRun(Journal, t, ALL_FWD)
    all {
      assert(stuckKeys(r.table, ALL_FWD) == Set()),
      assert(verifyPass(r.table, r.table, Forward, FPlain)),
    }
  }

  // ---- round trip on a table of 1.3.0 alone

  run roundTripTest = {
    val ks = LROWS.mapBy(l => keysFor(1, l, 1))
    val t = buildTable(Journal, ks, LROWS.mapBy(_ => 1), LROWS.mapBy(_ => false), LROWS.mapBy(_ => 1))
    val fwd = fullRun(Journal, t, ALL_FWD).table
    val back = fullRun(Journal, fwd, ALL_REV).table
    all { assert(fwd != t), assert(back == norm130(t)) }
  }
}
