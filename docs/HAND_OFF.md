# Work handoff: issue 887

## Current state

The failing tests and the fix are committed.

- `fe160140 test(journal,base): reproduce the unbounded batch write retry`
- `5e6078a2 fix(journal,base,snapshot): type the unprocessed batch failure and bound its retry`

The fix adds `BatchWriteUnprocessedItemsException` in `base`, makes the v1 and v2 response adapters emit it, bounds
`JournalRowStore.runBatch` to `max-restarts + 1` attempts with the write-backoff timings, and returns
`JournalRowStoreException.BatchWriteRetriesExhausted` when the bound is exhausted. `SnapshotStore` now retries the same
typed signal instead of `IllegalStateException`.

Verification passed:

- `journal-base/testOnly *JournalRowStoreSpec` (39), `base-v1/testOnly *V1ResponsesSpec` (6),
  `base-v2/testOnly *V2ResponsesSpec` (6), `snapshot-base/testOnly *SnapshotStoreSpec` (12)
- `schema-check/test` (20), `lint`, `checkModuleBoundaries`
- `++2.12.20` and `++3.6.4 schema-check/Test/compile`
- Docker-backed sync and async suites for journal and snapshot, v1 and v2

## Next step

Push the branch, open one draft PR with a Japanese title and body, and wait for CI.

## Open decisions

- Retry bound follows #892: `max-restarts + 1` attempts, and the backoff timings apply even when `write-backoff.enabled`
  is false.
- The batch path now retries only the typed unprocessed-items signal. Other failures propagate unchanged, including
  `IllegalStateException`.
- Public API adds `BatchWriteUnprocessedItemsException` in `base` and `JournalRowStoreException` (with
  `BatchWriteRetriesExhausted`) in `journal-base`. No declaration is removed.

## PR

Not created yet.
