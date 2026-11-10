# issue 890: typed queue failures

Goal: replace the generic `Exception` returned when the journal write queue is full or closed with two recoverable, typed failures, and cover both branches with a test that fails before the change.

## Bug fix playbook (verbatim)

1. Reproduce it yourself on the matching surface via the driver skill (Non-negotiables), even when a debug or instrumentation protocol says to ask the user to reproduce. Ask the user only with a stated, specific reason the control surface cannot reach the target, and only after driving it as far as it goes. If it won't reproduce directly, synthesize the trigger, tighten conditions, or instrument until it fires.
2. Binary-search the cause. Form the candidate hypotheses, then rule them out until one survives. Seed them with `how` over the affected subsystem and the **why** skill for regression history. Each pass, take the split that cuts the most remaining problem space, get runtime evidence, eliminate. When program state is unclear, add instrumentation or logging and read it as the code runs. Don't guess. Drive a long or stubborn hunt with Claude Code's `loop` skill. Confirm the surviving *mechanism* with runtime evidence before the step-3 architect/interrogate fan-out.
3. Plan the fix. If it crosses a function boundary, `architect` first. Delegate implementation to a subagent using your configured bug-fix model (default in poteto-mode's Models section) with a specific scope.
4. Verify on the same surface. The original repro now passes. "Inconclusive" or wrong-surface is not a pass. Flag it. Unit tests show branch behavior, not bug absence.
5. Stage the commits so the failing repro lands before the fix in git history. See the **tdd** skill for the failing-test-first cadence when the bug has a cheap local test path. Skip it when the test would be expensive, integration-heavy, or unclear.
   This is the canonical **sequence-verifiable-units** principle skill, the failing test first and the fix on top.
6. Run **Opening a PR**.

## Task todos

- [ ] Step 1: reproduce. Add `WriteJournalDaoQueueSpec` in journal-base, run it red against the current code. skip-delegation note below.
- [ ] Step 2: cause is explicit: `WriteJournalDaoImpl` lines 235-242 and 279-286 map `QueueOfferResult.Dropped` and `QueueClosed` to `new Exception(...)`.
- [ ] Step 3: skip architect (no function boundary change; the fix is a sealed type family and two match arms). skip delegation: the production change is two small classes plus four match arms; a subagent adds handoff risk without buying coverage.
- [ ] Step 4: verify the same spec turns green.
- [ ] Step 5: commit the failing test first, then the fix on top.
- [ ] Step 6: open one draft PR (Japanese title/body, issue URL), link it.

## Constraints

- Do not touch `journal/journal-base/.../journal/store/JournalRowStore.scala`.
- Do not touch any file changed by PR #886 (`fix/844-head-async-update-event`). `docs/HAND_OFF.md` is added by #886 but the task explicitly requires maintaining it; note the merge-conflict risk.
- Do not merge, close the issue, force-push, push main, release, or edit AGENTS.md.
- No `docker prune`. No full test run; run the related module only.
- If the DynamoDB Local port collides, report and stop.

## Decisions

- Test lives in `journal-base` because the queue is implemented there, and it starts no Docker container by using a local `JournalRowWriteDriver` double and overriding the driver factory class name.
- New public types: `JournalWriteQueueException` (sealed base), `JournalWriteQueueFullException`, `JournalWriteQueueClosedException`.
- Regenerate `schema-check/public-api-baseline.txt` to record the new public declarations.
