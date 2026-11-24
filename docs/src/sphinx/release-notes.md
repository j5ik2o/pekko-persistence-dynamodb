# Release notes

## Manual releases

After the owner approves a release, confirm that the selected commit's CI has passed, then create the tag and GitHub release from that exact commit:

```bash
git ls-remote --tags origin "refs/tags/v1.2.0" "refs/tags/v1.2.0^{}"
gh release create v1.2.0 --target <SHA> --generate-notes
```

Replace `v1.2.0` and `<SHA>` with the approved version and commit. If the lookup returns no rows, continue. For a lightweight tag, compare the SHA on its row with `<SHA>`; for an annotated tag, compare the SHA on the `^{}` row. Continue only on a match; otherwise stop. `--target` selects the commit only when `gh release create` creates a missing tag. Confirm that the `publish` workflow succeeds. Tag pushes publish the artifacts to Maven Central automatically.

## 1.3.0

```{admonition} Binary incompatibility in pekko-persistence-dynamodb-test
:class: warning

`pekko-persistence-dynamodb-test` 1.3.0 is not binary compatible with 1.2.0. A class that was compiled against the
1.2.0 version of this module and mixes in `DynamoDBContainerHelper` or `DockerControllerHelperUtil` fails with
`AbstractMethodError` when it runs with 1.3.0 without being compiled again. The cause is #922: the helper now
overrides `createDockerContainer`, `startDockerContainer`, `stopDockerContainer`, `removeDockerContainer`, and, on
Scala 3, `run`, and the compiler adds a method for each `super` call to the trait. Compile your test sources again with
1.3.0. A test library that was published compiled against 1.2.0 must be published again.

The other 12 published modules, which run in production, are binary compatible with 1.2.0. MiMa against the 1.2.0
artifacts on Maven Central found no problem in them for Scala 2.12, 2.13, and 3.
```

### A write no longer replaces the journal row or the snapshot of another persistence id

The default key resolvers of the journal and the snapshot store give two `persistenceId`s the same key when they
differ only after the second separator, or when the separator is a regular expression metacharacter such as `|`
(#1009). Up to 1.2.0, with the JournalHead disabled, the later write silently replaced the journal row of the other
`persistenceId`, and its events were lost; snapshots were replaced in every mode.

The keys are unchanged. Every journal write, delete, and in-place update, and every snapshot save and delete, now
carries a condition that the key holds no row or a row of the same `persistenceId`. A write that would replace the
row of another `persistenceId` fails with `JournalRowStoreException.KeyConflict` or `SnapshotStoreException.KeyConflict`,
which name both `persistenceId`s; the actor gets `onPersistFailure` or `SaveSnapshotFailure`. A delete leaves the
other row, and an in-place update is refused with `JournalHeadRowUpdateRejectedException`. Rewrites of rows of the
same `persistenceId`, such as a soft delete or a snapshot saved again at the same sequence number, work as before. With
the JournalHead enabled, a conflicting journal write keeps failing with `DuplicateJournalEventsException`.

A `persistenceId` that conflicts cannot write the sequence numbers whose keys another `persistenceId` holds. The
compatibility note in {ref}`Persistence id separator <persistence-id-separator>` describes the affected
`persistenceId`s and the workaround. Events and snapshots that earlier releases already replaced cannot be restored.

You can be affected if a `persistenceId` contains `persistence-id-separator` (default `-`) two or more times, such as
`shopping-cart-1`, or if the separator is a regular expression metacharacter such as `|`. Two such `persistenceId`s
share a key only when the parts before and after the first separator and their shard number also match. After the
upgrade, a conflict shows up as follows:

- A journal write with the JournalHead disabled fails with `JournalRowStoreException.KeyConflict`, and the actor
  stops through `onPersistFailure`.
- A journal write with the JournalHead enabled fails with `DuplicateJournalEventsException`, as in 1.2.0.
- A snapshot save fails with `SnapshotStoreException.KeyConflict`. The actor receives `SaveSnapshotFailure` and keeps
  running.

Until a later release fixes the key format, give new `persistenceId`s the separator at most once, and do not choose a
metacharacter as the separator of a new table. Do not change `persistence-id-separator` or the `persistenceId`s of
data that already exists: the keys change, and the earlier events and snapshots can no longer be found.

Because `BatchWriteItem` cannot carry a condition, the journal and the snapshot store now write and delete each row
with its own `PutItem` or `DeleteItem`, up to `batch-write-item-limit` (25) requests at a time. The consumed write
capacity is unchanged. A write of N events sends N requests instead of ⌈N/25⌉. The throughput of the plugin did not
change beyond the variation between runs. This was measured on DynamoDB Local only; the latency and the throttling of
DynamoDB itself were not measured. A throttled `PutItem` is retried by the AWS SDK, where a `BatchWriteItem` left unprocessed items for the
plugin to send again.

The IAM policy of the plugin must allow `dynamodb:PutItem` and `dynamodb:DeleteItem` on the journal and snapshot
tables, and `dynamodb:GetItem`, which reads the persistence id that holds a conflicting key. A policy that allowed only
`dynamodb:BatchWriteItem` for multi-row writes and for snapshot deletes fails with an access-denied error after the
upgrade. `dynamodb:BatchWriteItem` is no longer used.

A write whose event conflicts deletes the other new events of the same `persistAll` again, so a restarted actor does
not replay part of it.

`JournalRowStoreException.BatchWriteRetriesExhausted` and `SnapshotStoreException.BatchWriteRetriesExhausted` are no
longer raised by the stores. They remain in the API.

There is no setting to go back to `BatchWriteItem`.

A later release will change the key format so that the `persistenceId`s that conflict can be written, and will
provide a migration tool for the rows that already exist (#1014).

### JournalHead migration dry-run reports source details

The dry-run now defines a raised item as a journal or snapshot sequence ahead of the head. This can indicate writes
from nodes with head mode disabled, but it does not prove that cause. A serialization rejection creates no journal
row and does not cause a raised item. A later head-enabled write can move the head past earlier sequence gaps, so a
later dry-run may no longer reveal earlier mixed writes. The dry-run also lists and counts persistence ids found in
snapshots but not in the journal so operators can compare them with hard-delete history.
Snapshot-only ids are not treated as errors, and `--execute` behavior is unchanged.

### JournalHead migration can select persistence ids and report progress

`JournalHeadMigration` accepts `--persistence-id VALUE`, repeated, to migrate only the given persistence ids, and
`--progress` to print scan diagnostics to stderr (#939). See [JournalHead](journal-head.md) for the details. The
command now rejects unknown flags, extra positional arguments, and equals syntax such as `--persistence-id=value`
before it starts; earlier releases ignored them silently.

### DynamoDBContainerHelper uses a port that Docker assigns

`DynamoDBContainerHelper` in `pekko-persistence-dynamodb-test` now asks Docker for a free host port and reads it back
from the container, instead of choosing a port before the container starts (#922). Reading `dynamoDBPort` starts the
container. The helper starts the container once and keeps it while the suite runs, so the port does not change between
tests. `controller`, `dynamoDBEndpoint`, `dockerControllers`, and `waitPredicatesSettings` are now `lazy val`s; a
suite that overrides one of them must override it with a `lazy val`. See the binary incompatibility note at the top of
this section.

### The test module is covered by the public API check

The public declarations of `pekko-persistence-dynamodb-test` are now recorded in `schema-check/public-api-baseline.txt`, together with those of the other published modules. A change that removes or adds a public declaration in `DynamoDBContainerHelper`, `DockerControllerHelperUtil`, `DynamoDBLocalFixture`, `ConfigHelper`, or `RandomPortUtil` fails `ApiCompatibilitySpec` until the baseline is regenerated (#947).

The check reads the declarations written in PPD's sources. It has two limits for this module:

- Members inherited from docker-controller-scala are not recorded. Examples are `dockerControllers`, `dockerClient`, `disposeResources()`, and the eight `before/after*Containers` hooks. The check records the parent types in the `extends` clause, so replacing `DockerControllerHelper` or `DockerControllerSpecSupport` fails the check. A docker-controller-scala version change that alters the inherited members does not fail the check.
- Protected members are not recorded, as in the other modules. Examples are `dynamoDBClient`, `waitDynamoDBLocal`, and `createJournalTable`, which suites override or call.

Review a docker-controller-scala upgrade and a change to a protected member of the helper by hand.

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
