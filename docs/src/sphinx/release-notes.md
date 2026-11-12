# Release notes

## Manual releases

After the owner approves a release, confirm that the selected commit's CI has passed, then create the tag and GitHub release from that exact commit:

```bash
git ls-remote --tags origin "refs/tags/v1.2.0" "refs/tags/v1.2.0^{}"
gh release create v1.2.0 --target <SHA> --generate-notes
```

Replace `v1.2.0` and `<SHA>` with the approved version and commit. If the lookup returns no rows, continue. For a lightweight tag, compare the SHA on its row with `<SHA>`; for an annotated tag, compare the SHA on the `^{}` row. Continue only on a match; otherwise stop. `--target` selects the commit only when `gh release create` creates a missing tag. Confirm that the `publish` workflow succeeds. Tag pushes publish the artifacts to Maven Central automatically.

## Unreleased

### JournalHead migration dry-run reports source details

The dry-run now defines a raised item as a journal or snapshot sequence ahead of the head. This can indicate writes
from nodes with head mode disabled, but it does not prove that cause. A serialization rejection creates no journal
row and does not cause a raised item. A later head-enabled write can move the head past earlier sequence gaps, so a
later dry-run may no longer reveal earlier mixed writes. The dry-run also lists and counts persistence ids found in
snapshots but not in the journal so operators can compare them with hard-delete history.
Snapshot-only ids are not treated as errors, and `--execute` behavior is unchanged.

### Docker Compose controller users must add s2util

The published `pekko-persistence-dynamodb-test` artifact no longer includes `org.seasar.util:s2util` through `docker-controller-scala`. Maven Central does not host `s2util`, so Central-only users could not resolve the `test` module (#942).

`DynamoDBContainerHelper` starts DynamoDB Local without `s2util`, so PPD's helper is unaffected. If you pass docker-controller-scala's `DockerComposeController` to `dockerControllers` to use Compose features, the controller may fail at runtime with `NoClassDefFoundError`.

To use Compose features, add `s2util` and the Seasar resolver to your build:

```scala
resolvers += "Seasar Repository" at "https://maven.seasar.org/maven2/"
libraryDependencies += "org.seasar.util" % "s2util" % "0.0.1"
```

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
