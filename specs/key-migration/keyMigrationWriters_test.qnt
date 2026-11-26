// -*- mode: Bluespec; -*-
/**
 * The counterexamples of keyMigrationWriters.qnt as fixed traces: each takes only steps that the
 * model allows (the tool, the asynchronous index, the application), and ends in a state that breaks
 * the invariant named in the test. `quint test` passes when the invariant IS broken.
 */
module keyMigrationWriters_test {
  import keyMigration.* from "./keyMigration"
  import keyMigrationWriters.* from "./keyMigrationWriters"

  pure val B: Row = { id: "b", seq: 1, payload: 1, deleted: false, created: 0 }
  pure val START: Table = Map("b130" -> B)
  pure val FWD: Params = { dir: Forward, form: FPlain, sel: IDS }
  pure val B_CAND: Cand = { id: "b", seq: 1, src: "b130", dst: "bP" }

  action startW = all {
    initWith(START, true), appDeleted' = Set(), appDeleting' = Set(), appWritten' = Set(),
    appStarted' = false, appNode' = Forward, headEnabled' = false, staleReads' = false, idxHist' = Set(),
    toolsSinceVerify' = STORES.mapBy(_ => false),
  }
  action tool(a: bool): bool = all { a, keepApp }

  /** The tool moves b@1 from b130 to bP; the index removes b130 first and has not added bP yet. */
  action moveAndLag = tool(startRun(Journal, FWD))
    .then(tool(nextRound(Journal)))
    .then(tool(attemptOk(Journal, B_CAND)))
    .then(tool(propagateKey(Journal, "b130")))   // the index drops b130 and has not added bP yet

  /** A1: a recovery that misses the moved event writes the same sequence number again over it. */
  run recoveryOverwritesMovedEventTest =
    startW
      .then(moveAndLag)
      .then(recoverAndWrite(("b", 1), "bP"))
      .expect(not(noLossW))

  /** A1': and once the index has caught up, a forward --verify matches the table without the event. */
  run verifyMatchesAfterOverwriteTest =
    startW
      .then(tool(startRun(Journal, FWD)))
      .then(tool(nextRound(Journal)))
      .then(tool(attemptOk(Journal, B_CAND)))
      .then(tool(propagateKey(Journal, "b130")))
      .then(recoverAndWrite(("b", 1), "bP"))
      .then(tool(propagateKey(Journal, "bP")))
      .then(tool(nextRound(Journal)))
      .then(tool(verify(Journal, Forward, FPlain)))
      .expect(lastVerify.get(Journal).pass and not(verifySoundW))

  /** A2: a node of 1.3.0 that misses the moved event writes it again under the old key: two copies. */
  run oldNodeDuplicatesMovedEventTest =
    startW
      .then(tool(startRun(Journal, FWD)))
      .then(tool(nextRound(Journal)))
      .then(tool(attemptOk(Journal, B_CAND)))
      .then(tool(propagateKey(Journal, "b130")))
      .then(recoverAndWrite(("b", 1), "b130"))
      .expect(not(noDuplicateW))

  /** A3: a hard delete whose two DeleteItems straddle the move leaves the deleted event. */
  run hardDeleteMissesMovedEventTest =
    startW
      .then(tool(startRun(Journal, FWD)))
      .then(tool(nextRound(Journal)))
      .then(hardDeleteCurrent(("b", 1)))          // bP: nothing there yet
      .then(tool(attemptOk(Journal, B_CAND)))       // the tool moves b130 -> bP
      .then(hardDeleteLegacy(("b", 1)))            // b130: nothing there any more
      .expect(not(deletedStayGone))

  // ---- O-F3: the application that follows the documentation

  pure val A_CAND: Cand = { id: "a", seq: 1, src: "a130", dst: "b130" }

  /** Both tables of START_G moved (b, then a into b130), the index caught up, and --verify matched twice. */
  action migrateAndVerifyTwice(stale: bool, withHead: bool): bool =
    initGWith(stale, withHead)
      .then(tool(startRun(Journal, FWD))).then(tool(nextRound(Journal)))
      .then(tool(attemptOk(Journal, B_CAND)))
      .then(tool(propagateKey(Journal, "b130")))       // the index lags: b is in no key of the index
      .then(tool(nextRound(Journal))).then(tool(attemptOk(Journal, A_CAND))).then(tool(nextRound(Journal)))
      .then(tool(startRun(Snapshot, FWD))).then(tool(nextRound(Snapshot)))
      .then(tool(attemptOk(Snapshot, B_CAND))).then(tool(nextRound(Snapshot)))
      .then(tool(attemptOk(Snapshot, A_CAND))).then(tool(nextRound(Snapshot)))
      .then(tool(propagateKey(Journal, "bP"))).then(tool(propagateKey(Journal, "b130")))
      .then(tool(propagateKey(Journal, "a130")))
      .then(tool(propagateKey(Snapshot, "bP"))).then(tool(propagateKey(Snapshot, "b130")))
      .then(tool(propagateKey(Snapshot, "a130")))
      .then(tool(verify(Journal, Forward, FPlain))).then(tool(verify(Snapshot, Forward, FPlain)))
      .then(tool(verify(Journal, Forward, FPlain))).then(tool(verify(Snapshot, Forward, FPlain)))
      .then(all {
        assert(idx.get(Journal) == tables.get(Journal)),
        assert(lastVerify.get(Journal).pass and lastVerify.get(Snapshot).pass),
        keepApp, tables' = tables, idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
        epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget,
        rejectShared' = rejectShared,
      })
      .then(appStart(Forward))

  /** O-F3 1: an index that only catches up: after the start, no recovery misses b, so nothing is replaced. */
  run guardedStartTest =
    migrateAndVerifyTwice(false, false)
      .expect(appStarted and not(overwritePossible) and noLossW and noDuplicateW and verifySoundW)

  /** O-F3 2: a read that returns an older value of the index replaces b after two matching --verify (A1). */
  run staleReadAfterTwoMatchesTest =
    migrateAndVerifyTwice(true, false)
      .then(recoverAndWrite(("b", 1), "bP"))
      .expect(not(noLossW))

  /** O-F3 2: with the JournalHead, the same stale read replaces nothing (the head item holds b@1). */
  run staleReadWithHeadTest =
    migrateAndVerifyTwice(true, true)
      .expect(appStarted and not(overwritePossible) and noLossW)

  /** A0: the same steps without the application keep every invariant of keyMigration.qnt. */
  run withoutTheApplicationTest =
    startW
      .then(tool(startRun(Journal, FWD)))
      .then(tool(nextRound(Journal)))
      .then(tool(attemptOk(Journal, B_CAND)))
      .then(tool(propagateKey(Journal, "b130")))
      .then(tool(propagateKey(Journal, "bP")))
      .then(tool(nextRound(Journal)))
      .then(tool(verify(Journal, Forward, FPlain)))
      .expect(lastVerify.get(Journal).pass and safety and noLossW and noDuplicateW and verifySoundW)
}
