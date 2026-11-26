// -*- mode: Bluespec; -*-
/**
 * The fixed scenario of the state between the moves of two copies of one row (#1028): the copies of n under n130 and
 * n831 both need k130. The winner n130 moves first, and the run stops before n831 merges into it. Up to the change
 * of #1028, `withinReference` asked each row to be as at the start or as at the reference end, so this state broke
 * it (the run 1 of ci-runs.txt, seed 0x7, with n in the random tables). The next run merges n831, and the table ends
 * as one uninterrupted run leaves it.
 *
 *   quint test keyMigrationTwoCopies_test.qnt --main keyMigrationTwoCopies_test
 */
module keyMigrationTwoCopies_test {
  import keyMigration.* from "./keyMigration"

  pure val FWD: Params = { dir: Forward, form: FPlain, sel: IDS }
  pure def row(id: Id): Row = { id: id, seq: 1, payload: 1, deleted: false, created: 0 }
  pure val START: Table = Map("n130" -> row("n"), "n831" -> row("n"), "k130" -> row("k"))
  pure val K: Cand = { id: "k", seq: 1, src: "k130", dst: "kP" }
  pure val N130: Cand = { id: "n", seq: 1, src: "n130", dst: "k130" }
  pure val N831: Cand = { id: "n", seq: 1, src: "n831", dst: "k130" }

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

  /** The row as at the start or as at the reference end: the form of `withinReference` before #1028. */
  pure def asStartOrEnd(t0: Table, ref: Table, t: Table, l: LRow): bool = rowsOf(t, l) == rowsOf(t0, l) or rowsOf(t, l) == rowsOf(ref, l)

  /** k moves to kP; n130 moves to k130 after it; the run stops before n831. */
  action stopBetweenTheCopies: bool =
    initWith(START, false)
      .then(startRun(Journal, FWD))
      .then(nextRound(Journal))
      .then(all { assert(tools.get(Journal).round == Set(K)), keep })
      .then(attemptOk(Journal, K))
      .then(nextRound(Journal))
      .then(all { assert(tools.get(Journal).round == Set(N130)), keep })
      .then(attemptOk(Journal, N130))
      .then(stop(Journal))

  run stopBetweenTheCopiesTest =
    stopBetweenTheCopies
      .then(all {
        val t = tables.get(Journal)
        val ref = fullRun(Journal, START, FWD).table
        all {
          assert(t.keys() == Set("kP", "k130", "n831")),
          assert(t.get("k130").id == "n"),
          // the state between the copies: neither as at the start nor as at the end, but within, copy by copy
          assert(not(asStartOrEnd(START, ref, t, N_ROW))),
          assert(rowWithin(START, ref, t, N_ROW)),
          assert(withinReference),
          assert(withinDryRun),
          keep,
        }
      })
      .then(startRun(Journal, FWD))
      .then(10.reps(_ => drive(Journal)))
      .then(all {
        assert(tools.get(Journal).phase == Finished),
        assert(converges),
        assert(tables.get(Journal) == fullRun(Journal, START, FWD).table),
        assert(tables.get(Journal) == Map("kP" -> row("k"), "k130" -> row("n"))),
        assert(dryRunMatches),
        keep,
      })

  /** `rowWithin` lets only the state between the copies through: each mutation of a copy breaks it. */
  run rowWithinIsTightTest = {
    val ref = fullRun(Journal, START, FWD).table
    val between = Map("kP" -> row("k"), "k130" -> row("n"), "n831" -> row("n"))
    all {
      assert(rowWithin(START, ref, between, N_ROW)),
      // a Put without its Delete: the winner under k130 and still under n130
      assert(not(rowWithin(START, ref, between.put("n130", row("n")), N_ROW))),
      // a copy whose contents changed under the key it was under
      assert(not(rowWithin(START, ref, between.put("n831", { ...row("n"), payload: 2 }), N_ROW))),
      // a copy under the target with contents that no copy had
      assert(not(rowWithin(START, ref, between.put("k130", { ...row("n"), payload: 2 }), N_ROW))),
      // a copy under a key that neither the start nor the reference gives the row
      assert(not(rowWithin(START, ref, between.put("kX", row("n")), N_ROW))),
      // the row lost
      assert(not(rowWithin(START, ref, Map("kP" -> row("k")), N_ROW))),
    }
  }
}
