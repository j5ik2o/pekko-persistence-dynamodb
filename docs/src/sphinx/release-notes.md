# Release notes

## Unreleased

### Unbounded batch-write retry flow is deprecated

`StreamWriteClient.recursiveBatchWriteItemFlow` in both `base-v1` and `base-v2` is now deprecated.
Its public signature and runtime behavior remain unchanged.
The flow resubmits unprocessed items for the configured table without a retry limit and accumulates responses until completion.
Persistent unprocessed items can therefore prevent completion and keep increasing memory use.

For ordinary journal and snapshot operations, use the existing shared stores, `JournalRowStore` and `SnapshotStore`.
For low-level operations, `adapter.run(Operation.BatchWrite(...))` reports unprocessed items as a failure with `BatchWriteUnprocessedItemsException`.
The caller must define an explicit bounded retry policy, including when to stop and how to handle exhaustion and partial writes.

`batchWriteItemFlow` does not resubmit unprocessed items and is not a bounded retry replacement.
Its optional `RestartFlow` backoff restarts the stream after failures but does not guarantee replay of the failed element.

### Java 11 is the documented minimum version

The README and the overview claimed Java 1.8+, while CI tested Java 11, 17, and 19. The product decision is to document the versions that CI tests, so the documents now name Java 11, 17, and 19. Java 8 is no longer a supported configuration.

The build still targets Java 8 bytecode, so an existing Java 8 deployment can run these artifacts. CI does not test that configuration.

The `schema-check` version check fails when the documented versions and the CI matrix disagree. To keep Java 8 support, add it to the matrix in `.github/workflows/ci.yml` and to both documents.
