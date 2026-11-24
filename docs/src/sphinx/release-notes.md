# Release notes

## Manual releases

After the owner approves a release, confirm that the selected commit's CI has passed, then create the tag and GitHub release from that exact commit:

```bash
git ls-remote --tags origin "refs/tags/v1.2.0" "refs/tags/v1.2.0^{}"
gh release create v1.2.0 --target <SHA> --generate-notes
```

Replace `v1.2.0` and `<SHA>` with the approved version and commit. If the lookup returns no rows, continue. For a lightweight tag, compare the SHA on its row with `<SHA>`; for an annotated tag, compare the SHA on the `^{}` row. Continue only on a match; otherwise stop. `--target` selects the commit only when `gh release create` creates a missing tag. Confirm that the `publish` workflow succeeds. Tag pushes publish the artifacts to Maven Central automatically.

## Unreleased

### Persistence ids with two or more separators no longer share a journal or snapshot key

The default `PersistenceIdBased` partition key and `PersistenceIdWithSeqNr` sort key of the journal and the snapshot
store now split the `persistenceId` at its first separator, and match the separator as plain text (#1009). Releases up
to 1.2.0 kept only the text between the first and the second separator, so `persistenceId`s that differed only after
the second separator could share a key. With the JournalHead disabled, one `persistenceId` silently replaced the
journal rows of the other, with the JournalHead enabled the later one failed with `DuplicateJournalEventsException`,
and snapshots were replaced in both cases. With `persistence-id-separator = "|"`, a single separator was enough.

The key changes for a `persistenceId` that contains the separator two or more times, and with a separator that is a
regular expression metacharacter such as `|`, for every `persistenceId` that contains it. A `persistenceId` that ends
with the separator, and every `persistenceId` with the separator `.`, failed with `ArrayIndexOutOfBoundsException`
before and can now be written. Other `persistenceId`s keep their key.

No migration and no configuration change is needed. Rows written by earlier releases keep their key, and replay, the
highest sequence number, deletes, in-place updates, and snapshot loads and deletes find them there. For a
`persistenceId` whose key changed, a delete, an in-place update, and a snapshot save send one more conditional request
per event or snapshot. Events and snapshots that were already replaced cannot be restored. A release up to 1.2.0 still
replays the rows written by this release, but its deletes do not reach them, so upgrade every node before you delete
events. The compatibility note in {ref}`Persistence id separator <persistence-id-separator>` lists the affected
`persistenceId`s and how to find the ones that shared a key.

An in-place update and a delete through the journal row driver could also change the row of another `persistenceId`
that shared the key. Under an old key, they now change a row only when its `persistence-id` and `sequence-nr` match.

The state plugin keeps its keys: its `skey` is the whole `persistenceId`, so two `persistenceId`s never shared a key.

### JournalHead migration dry-run reports source details

The dry-run now defines a raised item as a journal or snapshot sequence ahead of the head. This can indicate writes
from nodes with head mode disabled, but it does not prove that cause. A serialization rejection creates no journal
row and does not cause a raised item. A later head-enabled write can move the head past earlier sequence gaps, so a
later dry-run may no longer reveal earlier mixed writes. The dry-run also lists and counts persistence ids found in
snapshots but not in the journal so operators can compare them with hard-delete history.
Snapshot-only ids are not treated as errors, and `--execute` behavior is unchanged.

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
