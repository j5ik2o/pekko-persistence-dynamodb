// -*- mode: Bluespec; -*-
/**
 * The fixed scenarios that the random runs of 5ff2d62d missed (review C-F1 / O-F6, and review R3): --reverse,
 * two ids that share their 1.3.0 key (h, i -> h130), the winner (h, the smaller stored key hP) does not reach
 * the key, and the run is started again.
 *
 *  - finishThenRerunTest, stopThenRerunTest: h gives up after transient failures (C-F1 / O-F6).
 *  - rejectedOnSharedTargetTest: DynamoDB rejects h under h130 (400 KB under the longer key of 1.3.0; R3).
 *
 * With the rule of the code up to edfce815 (the target goes to the next candidate), the first two fail on
 * `withinReference`, and the third on the table it leaves (i takes h130, which the dry-run names as h's: the
 * reference run follows the same rule, so no invariant of the module sees it); with the rule of `reserves`, they pass
 * (docs/design/key-migration-quint-results.md).
 *
 *   quint test keyMigrationGaveUp_test.qnt --main keyMigrationGaveUp_test
 */
module keyMigrationGaveUp_test {
  import keyMigration.* from "./keyMigration"

  pure val REV: Params = { dir: Reverse, form: FPlain, sel: IDS }
  pure def row(id: Id): Row = { id: id, seq: 1, payload: 1, deleted: false, created: 0 }
  pure val START: Table = Map("hP" -> row("h"), "iP" -> row("i"))
  /** What one uninterrupted --reverse run gives: the winner h under h130, i blocked under iP. */
  pure val CLEAN_END: Table = Map("h130" -> row("h"), "iP" -> row("i"))
  pure val H: Cand = { id: "h", seq: 1, src: "hP", dst: "h130" }
  pure val I: Cand = { id: "i", seq: 1, src: "iP", dst: "h130" }

  action keep = all {
    tables' = tables, idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
    epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget,
    rejectShared' = rejectShared,
  }

  /** One step of a run without faults: the next attempt that succeeds, the next round, or nothing once it ended. */
  action drive(st: Store): bool = {
    val tool = tools.get(st)
    nondet c = (if (tool.round == Set()) Set(NO_CAND) else tool.round).oneOf()
    if (tool.round != Set()) attemptOk(st, c)
    else if (tool.phase == Running) nextRound(st)
    else keep
  }

  /** Run 1: --reverse; h is the winner of h130; its two attempts fail without writing; settle gives up. */
  action gaveUpOnH: bool =
    initWith(START, false)
      .then(startRun(Journal, REV))
      .then(nextRound(Journal))
      .then(all { assert(tools.get(Journal).round == Set(H)), keep })
      .then(attemptTransient(Journal, H))
      .then(attemptTransient(Journal, H))
      .then(settle(Journal, H))

  // ---- Scenario 1 (O-F6): run 1 finishes after giving up, run 2 is clean.

  run finishThenRerunTest =
    gaveUpOnH
      .then(10.reps(_ => drive(Journal)))
      .then(all {
        assert(tools.get(Journal).phase == Finished),
        assert(tools.get(Journal).gaveUp),
        // the run that gave up stops short: i is still under its old key, and listed
        assert(withinReference),
        assert(finishedWithinReference),
        assert(tables.get(Journal) == START),
        assert(tools.get(Journal).remaining == Set(H, I)),
        keep,
      })
      .then(startRun(Journal, REV))
      .then(all { assert(tools.get(Journal).epochGaveUp), keep })
      .then(10.reps(_ => drive(Journal)))
      .then(all {
        assert(tools.get(Journal).phase == Finished),
        assert(not(tools.get(Journal).gaveUp)),
        assert(converges),
        assert(wCleanAfterGaveUp),
        assert(tables.get(Journal) == CLEAN_END),
        keep,
      })

  // ---- Scenario 2 (C-F1, the trace of review C 4.2): the run goes on after giving up, stops before it ends, and
  // runs again. b and a move too, so the run has a round after the one that gave up on h.

  pure val START2: Table = START.put("bP", row("b")).put("b130", row("a"))
  pure val CLEAN_END2: Table = CLEAN_END.put("b130", row("b")).put("a130", row("a"))
  pure val A: Cand = { id: "a", seq: 1, src: "b130", dst: "a130" }

  run stopThenRerunTest =
    initWith(START2, false)
      .then(startRun(Journal, REV))
      .then(nextRound(Journal))
      .then(all { assert(tools.get(Journal).round == Set(H, A)), keep })
      .then(attemptOk(Journal, A))
      .then(attemptTransient(Journal, H))
      .then(attemptTransient(Journal, H))
      .then(settle(Journal, H))
      // the next round (b into b130, which a left); one attempt of it, and the process stops before the run ends
      .then(nextRound(Journal))
      .then(drive(Journal))
      .then(all { assert(tools.get(Journal).phase == Running), assert(withinReference), assert(wGaveUpShared), keep })
      .then(stop(Journal))
      .then(all { assert(tools.get(Journal).phase == Idle), assert(withinReference), keep })
      .then(startRun(Journal, REV))
      .then(10.reps(_ => drive(Journal)))
      .then(all {
        assert(tools.get(Journal).phase == Finished),
        assert(converges),
        assert(tables.get(Journal) == CLEAN_END2),
        keep,
      })

  // ---- Scenario 3 (R3): DynamoDB rejects the winner under the shared key, in every run.

  run rejectedOnSharedTargetTest =
    initWithRule(START, false, true)
      .then(startRun(Journal, REV))
      .then(nextRound(Journal))
      .then(all { assert(tools.get(Journal).round == Set(H)), keep })
      .then(attemptOk(Journal, H))
      .then(all {
        assert(tools.get(Journal).outcomes == Set((H, Failed("rejected by DynamoDB")))),
        assert(wRejectedShared),
        keep,
      })
      .then(10.reps(_ => drive(Journal)))
      .then(all {
        assert(tools.get(Journal).phase == Finished),
        assert(not(tools.get(Journal).gaveUp)),
        // i waits for h: the key stays reserved, the table stays as it started, and both are listed
        assert(withinReference),
        assert(finishedWithinReference),
        assert(converges),
        assert(tables.get(Journal) == START),
        assert(tools.get(Journal).remaining == Set(H, I)),
        keep,
      })
      .then(startRun(Journal, REV))
      .then(10.reps(_ => drive(Journal)))
      .then(all {
        assert(tools.get(Journal).phase == Finished),
        assert(converges),
        assert(tables.get(Journal) == START),
        keep,
      })

  // ---- The pure reference.

  run cleanRunTest = all {
    assert(fullRun(Journal, START, REV).table == CLEAN_END),
    assert(fullRun(Journal, START, REV).remaining == Set(I)),
    // DynamoDB rejects h: nothing moves, and both are left
    assert(fullRunWith(true, Journal, START, REV).table == START),
    assert(fullRunWith(true, Journal, START, REV).remaining == Set(H, I)),
  }
}
