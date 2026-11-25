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

  action startW = all { initWith(START, true), appDeleted' = Set(), appDeleting' = Set(), appWritten' = Set() }
  action tool(a: bool): bool = all { a, appDeleted' = appDeleted, appDeleting' = appDeleting, appWritten' = appWritten }

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
