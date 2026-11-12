# Working on pekko-persistence-dynamodb

This file is for an agent that changes this repository. It names the module that owns each part of the code, the files to edit for the four common changes, the build matrix, the Docker cost, the verification commands, and the rules that the build enforces.

Read this file before you edit. If a rule names an enforcement path, run that check after your change.

## Module map

Every module is an sbt project in `build.sbt`. The `Depends on` column lists the compile dependencies, so a module can use the types of the modules it depends on. A `(test only)` entry is used by tests and is not part of the published dependency.

| Module | Directory | It owns | Depends on |
|:--|:--|:--|:--|
| `test` | `test/` | The DynamoDB Local controller, table creation, and `ConfigHelper` for tests | none |
| `schema-check` | `schema-check/` | Repository checks that read files instead of running the product code | `base`; `journal-base`, `snapshot-base`, and `state-base` (test only) |
| `base` | `base/base/` | Configuration parsing, the model wrappers, metrics and trace interfaces, and the client-neutral `PluginContext` | `test` (test only) |
| `base-v1` | `base/base-v1/` | AWS SDK v1 client builders and client factories, including DAX | `base` |
| `base-v2` | `base/base-v2/` | AWS SDK v2 client builders and client factories, including DAX | `base` |
| `journal-base` | `journal/journal-base/` | The journal plugin, its config, the shared `JournalRowStore`, the row codec and schema, the serializer, and the JournalHead types and validation | `base` |
| `journal-v1` | `journal/journal-v1/` | The v1 binding: the deprecated journal row drivers and their factories | `journal-base`, `base-v1`, `snapshot-base` (test only) |
| `journal-v2` | `journal/journal-v2/` | The v2 binding: the deprecated journal row drivers and their factories, and the JournalHead migration tool | `journal-base`, `base-v2`, `snapshot-base` and `snapshot-v2` (test only) |
| `snapshot-base` | `snapshot/snapshot-base/` | The shared `SnapshotStore`, the row codec and schema, the config, and the key resolvers | `base` |
| `snapshot-v1` | `snapshot/snapshot-v1/` | The v1 binding: the deprecated snapshot DAO and factory | `snapshot-base`, `base-v1` |
| `snapshot-v2` | `snapshot/snapshot-v2/` | The v2 binding: the deprecated snapshot DAO and factory | `snapshot-base`, `base-v2` |
| `state-base` | `state/state-base/` | The shared `StateStore`, the row codec and schema, the config, and the key resolvers | `base` |
| `state-v1` | `state/state-v1/` | The v1 binding: the deprecated durable state store and factory | `state-base`, `base-v1` |
| `state-v2` | `state/state-v2/` | The v2 binding: the deprecated durable state store and factory | `state-base`, `base-v2` |
| `benchmark` | `benchmark/` | JMH benchmarks; not published | `test`, the journal and snapshot modules |
| `example` | `example/` | Usage examples; not published | `test`, the journal, snapshot, and state modules |
| `root` | `.` | Aggregates every module so that `sbt test` covers all of them | all modules |

The AWS SDK is confined to `base-v1`, `base-v2`, and the allowlisted files in `project/module-boundaries.allowlist`. The files of `base-v1` and `base-v2` import the SDK without an entry. Each other file that imports the SDK needs an entry in the form `<path>  # <reason>`, and only a module that already holds SDK imports accepts one. `ModuleBoundaryCheck.Modules` in `project/ModuleBoundaryCheck.scala` lists those modules: the v1 and v2 bindings, `test`, `example`, and `metrics/metrics-v2`. The core modules use the neutral types in `base`, `journal-base`, `snapshot-base`, and `state-base`, and an entry does not admit an SDK import there.

The v1, v2, and DAX modules are bindings. They build the SDK client, choose the client adapter, and instantiate a deprecated facade that delegates to the shared store. `JournalRowStore`, `SnapshotStore`, and `StateStore` own the row encoding, the key derivation, and the DynamoDB operations, so new code uses them instead of the deprecated drivers and DAOs.

## Add a column

The column names of each plugin live in one config value. Add the column to that value first, then thread it through the paths below.

1. Edit the `columns-def` value of the plugin. Journal uses `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/config/JournalColumnsDefConfig.scala`. Snapshot uses `snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/config/SnapshotColumnsDefConfig.scala`. State uses `state/state-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/state/config/StateColumnsDefConfig.scala`. Add the key constant, the case class field, and the `fromConfig` read.
2. Add the key with its default value to the `columns-def` block of the module's `src/main/resources/reference.conf`.
3. Add the column to the plugin's schema value. Journal uses `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/schema/JournalSchema.scala`, snapshot uses `snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/schema/SnapshotSchema.scala`, and state uses `state/state-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/state/schema/StateSchema.scala`. The row and its key then come from the same definition.
4. Write and read the attribute in the shared row codec: `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/schema/JournalRowCodec.scala`, `snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/schema/SnapshotRowCodec.scala`, or `state/state-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/state/schema/StateRowCodec.scala`. The v1 and v2 drivers and DAOs delegate to the shared stores, so there is one write path per plugin. Do not add the column to the deprecated classes.
5. Do not edit the generated table JSON or fixture by hand. `TableSpecArtifactSpec` renders these files from the table specs: `tools/dynamodb-setup/journal-table.json`, `tools/dynamodb-setup/journal-table-for-head.json`, `tools/dynamodb-setup/journal-head-table.json`, `tools/dynamodb-setup/snapshot-table.json`, `tools/dynamodb-setup/state-table.json`, and `test/src/main/resources/dynamodb-local-fixture.json`. After changing the schema value or `columns-def`, regenerate them with `TABLE_SPEC_WRITE=1 sbt -batch "schema-check/testOnly *TableSpecArtifactSpec"`. `tools/dynamodb-setup/account-table.json` and the `tools/dynamodb-setup/legacy-*.json` files keep their checked-in shape and are never regenerated. Changing a key in a legacy table is a compatibility decision, so confirm existing deployments still work before you edit that JSON.
6. Add the attribute to `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/head/DynamoDBItemSizeCalculator.scala` when the column is part of the JournalHead write path. The head fails a write that would exceed the item size, so an uncounted column fails too late.
7. Update `docs/src/sphinx/customize.md` for the column name and `docs/src/sphinx/table-format.md` for a key or index change.
8. Run `sbt -batch "schema-check/test"`. `SchemaDriftSpec` compares the column names in each plugin's `reference.conf` with `customize.md`, the index names in `table-format.md` with the table JSON and `reference.conf`, the literal `withAttributeName("...")` attributes found anywhere in `DynamoDBContainerHelper` with the table JSON, the State sort key in `table-format.md` with `state-table.json`, and the removed index and sort-key claims. `TableSpecArtifactSpec` checks that each generated table JSON and the fixture match the rendered table specs. The helper attribute scan currently extracts `persistence-id`, `pkey`, `sequence-nr`, and `tags`. These literals currently occur in the legacy table creation code; the scan itself covers the entire helper file. It does not extract column names from the tables built from the generated fixture; `TableSpecArtifactSpec` checks that fixture against the table specs.
9. Run the tests of the family you changed. Journal, snapshot, and state each have v1 and v2 suites under their module's `src/test`.

To find every code site that handles an existing column, search for `tagsColumnName`. To find the config and docs sites, search for `tags-column-name`.

## Change a write condition

A write condition decides whether DynamoDB accepts a journal write. The v2 driver holds the DynamoDB condition expressions of the JournalHead path. The v1 driver has no condition expression on its put or batch put. The shared validation lives in `JournalWritePlan.plan`, which returns a `WriteRejection` value and never throws for a rejected write. `JournalHeadWriteRequest.of` is the compatibility adapter that maps a rejection to the public `JournalHeadException` family.

The 400 KB journal-row limit applies to each event and its tags. The 400 KB JournalHead item limit, the 99-event limit, and the 4 MB transaction limit apply to all events in one write (`AtomicWrite` or one `persistAll`).

1. Decide where the check belongs. A validation that both client versions must share goes into `JournalWritePlan` in `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/write/JournalWritePlan.scala`, because it must run before either driver sends anything. Both write paths reach it: `JournalWritePlan.plan` runs the validation for a JournalHead write, and `DynamoDBJournal.executeInParallel` runs the shared row checks (such as `JournalWritePlan.findJournalRowTooLarge`) when `head.enabled` is false, before it calls `putMessages`. Add a case to the sealed `WriteRejection` family in `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/write/WriteRejection.scala`, and map it to a `JournalHeadException` there. Both paths fail the outer `Future` of `asyncWriteMessages` with the mapped `JournalHeadException`, after they validate every `AtomicWrite` of the call and before they send the first one. The write queue keeps running and nothing is written. Pekko calls `onPersistFailure` and the actor stops, and after a restart it continues without a gap. Only a serialization failure of Pekko itself stays a `Failure` of that one `AtomicWrite`, which reaches `onPersistRejected`. A DynamoDB condition expression goes into the driver for the client version that needs it. The v1 driver sets no condition expression today.
2. Edit the v2 condition in `createTransactWriteItemsRequest` in `journal/journal-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/dao/v2/V2JournalRowWriteDriver.scala`. That class is a deprecated facade, but the JournalHead transaction still lives there. The journal row condition is `attribute_not_exists(#pkey) AND attribute_not_exists(#skey)`. The head condition is `(attribute_not_exists(#seq) AND :first = :one) OR (#seq < :first)`.
3. Edit the v1 path in `journal/journal-v1/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/dao/v1/V1JournalRowWriteDriver.scala` when the condition applies to v1. `PutItemRequest` accepts a condition expression. `BatchWriteItem` does not, so a condition on the batch path needs a different call.
4. Map a cancelled transaction to the existing family in `classifyTransactionCancellation`. Do not add a bare `RuntimeException`, because callers and operators tell the failures apart by the subclass.
5. Add a case to `journal/journal-v2/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/JournalHeadSpec.scala` that fails before the change and passes after.
6. Run `sbt -batch "journal-v2/testOnly *JournalHeadSpec"`. It starts DynamoDB Local, so start Docker first.
7. Run `sbt -batch lint`.

Do not validate inside a stream stage. A `throw` inside a `Flow` aborts the stream and every later write on that queue fails. Return the rejection as a `WriteRejection` value from `JournalWritePlan.plan`, and let `DynamoDBJournal` fail the whole `asyncWriteMessages` call with it, so that the failure reaches `onPersistFailure`.

## Add a client variant

A client variant is one value of `ClientVersion`. The repository has `v1`, `v1-dax`, `v2`, and `v2-dax`. Adding a variant touches the neutral config, the client module, the three plugin configs, and the three plugin contexts.

1. Add the value to `base/base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/config/client/ClientVersion.scala`.
2. Parse and validate it in `base/base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/config/client/DynamoDBClientConfig.scala`.
3. Add the client factories to `base/base-v1/` or `base/base-v2/`. The factory classes implement `V1AsyncClientFactory`, `V1SyncClientFactory`, `V2AsyncClientFactory`, or `V2SyncClientFactory`, or the DAX variant, and build the SDK client. Register them under `j5ik2o.dynamo-db-defaults` in `base/base/src/main/resources/reference.conf`.
4. Add the dispatcher case to `base/base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/utils/DispatcherUtils.scala`. `getV1DispatcherName` must accept the v1 variants, and `getV2DispatcherName` must accept the v2 variants.
5. Add the `*ClientFactoryClassName` keys and reads to `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/config/JournalPluginConfig.scala`, `snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/config/SnapshotPluginConfig.scala`, and `state/state-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/state/config/StatePluginConfig.scala`. Pass the `clientVersion == ...` predicate to `ClassCheckUtils.requireClassByName` so that a factory class is checked only for the variant that uses it.
6. Add the variant to the factory selection and the `pluginExecutor` match in `JournalPluginContext`, `SnapshotPluginContext`, and `StatePluginContext`. The match on an `Enumeration.Value` is not checked for exhaustiveness by the compiler, so a missing case fails at runtime with a `MatchError`.
7. Add the factory class names to the three `reference.conf` files.
8. Run the three module test suites for the client module you changed and `sbt -batch "schema-check/test"`. `FactoryClassReferenceSpec` fails when a class name in a config does not resolve to a class, object, or trait.

The three `pluginExecutor` matches resolve all four registered variants: `V1`, `V1Dax`, `V2`, and `V2Dax`. A new variant still needs its own case, because the match on an `Enumeration.Value` is not checked for exhaustiveness and a missing case fails at startup with a `MatchError`.

## Change a published dependency

A published module's POM is what a user resolves. An `.exclude(...)` on a dependency of a published module removes the excluded module from every user's classpath, so a class that still needs it fails at runtime with `NoClassDefFoundError`. `tools/check-dependency-exclusion.py` checks such an exclusion in five steps and stops at the first failure.

1. Add the `.exclude(org, name)` to each dependency of the module in `build.sbt` that reaches the excluded module. Find these paths with `sbt -batch "<project>/dependencyTree"` before the change.
2. Run the check. `--project` names the sbt project that declares the exclusion. `--suite` names one project-scoped test command that uses the module, and `--via` names each dependency that must carry the exclusion in the POM. The excluded jar is read from the Coursier cache; pass `--excluded-jar` when it is not there. Start Docker first when the suite extends `DynamoDBContainerHelper`.

   ```bash
   python3 tools/check-dependency-exclusion.py --project test \
     --exclude org.seasar.util:s2util \
     --via com.github.j5ik2o:docker-controller-scala-scalatest \
     --via com.github.j5ik2o:docker-controller-scala-dynamodb-local \
     --suite 'journal-v1/testOnly *DynamoDBJournalV1SyncSpec' --resolve-central
   ```

3. Read the result. Step 1 runs `<project>/update` and `<project>/dependencyTree` and fails when the excluded module is still in the tree or a dependency does not resolve. `dependencyTree` alone drops an unresolvable module and exits 0, so the script runs `update` first. Step 2 scans the suite project's `Test/fullClasspath`, fails when an excluded class is still on it, and lists each class that references an excluded class, with the `javap -c -p` lines of the reference. Step 3 runs `<project>/makePom` and fails when the POM declares the excluded module, has no exclusion of it, or lacks it on a `--via` dependency. Step 4 resolves the POM's compile and runtime dependencies from Maven Central only, in a temporary sbt project. Step 5 runs the suite with `-verbose:class` and fails when it loads an excluded class or a listed caller, reports a missing excluded class, or prints no class loading at all.
4. Pass `--resolve-central` before you push. Step 4 needs the network, so without the option the script prints `SKIPPED 4 Maven Central resolution: offline run` and ends with `(Maven Central resolution skipped)`. Do not report a skipped step as checked.
5. A caller listed in step 2 is code that fails for a user who calls it. If the suite does not load it, write the caller, the excluded module, and the dependency a user must add to keep that feature in the pull request and the release notes.
6. Run `python3 -m unittest discover -s tools/tests -p 'test_check_dependency_exclusion.py'` when you change the script. The tests replace every command, so they need neither sbt nor the network.

The script exits 0 when every step passes and 1 at the first failure. A pass covers only the suite you named. Run it again with another suite when other modules use the excluded code path.

## Build matrix

Read `.github/workflows/ci.yml` for the current matrix. At the time of writing it is:

| Axis | Values |
|:--|:--|
| Scala | `2.12.20`, `2.13.16` (default), `3.6.4` |
| Java, lint job | `11` |
| Java, test matrix | `11`, `17`, `19` |
| sbt | the version in `project/build.properties` |
| CI test jobs | 9, one per Java and Scala pair, after the lint job |

`ThisBuild / crossScalaVersions` in `build.sbt` lists the three Scala versions. `ThisBuild / scalaVersion` defaults to 2.13.16. Tests fork a JVM (`ThisBuild / Test / fork := true`) and run one at a time (`ThisBuild / Test / parallelExecution := false`).

The README still claims Java 1.8+. CI tests Java 11, 17, and 19. Treat CI as the matrix that gates a merge.

## Docker cost

26 concrete test classes extend `DynamoDBContainerHelper`, directly or through `BaseCounterSpec`, and each one starts an `amazon/dynamodb-local` container on a temporary port with an in-memory database. Each name below works as a `testOnly` pattern of its module, such as `sbt -batch "journal-v2/testOnly *JournalHeadPreflightIntegrationSpec"`.

| Module | Suites |
|:--|:--|
| `journal-v1` | `DynamoDBJournalV1SyncSpec`, `DynamoDBJournalV1AsyncSpec` |
| `journal-v2` | `DynamoDBJournalV2SyncSpec`, `DynamoDBJournalV2AsyncSpec`, `JournalHeadSpec`, `JournalHeadInPlaceUpdateSpec`, `JournalHeadMigrationSpec`, `JournalHeadPreflightIntegrationSpec` |
| `snapshot-v1` | `DynamoDBSnapshotStoreV1SyncSpec`, `DynamoDBSnapshotStoreV1AsyncSpec`, `DynamoDBSnapshotStoreV1LegacySyncSpec` |
| `snapshot-v2` | `DynamoDBSnapshotStoreV2SyncSpec`, `DynamoDBSnapshotStoreV2AsyncSpec` |
| `state-v1` | `DynamoDBStateV1SyncSpec`, `DynamoDBStateV1AsyncSpec` |
| `state-v2` | `DynamoDBStateV2SyncSpec`, `DynamoDBStateV2AsyncSpec` |
| `test` | `DynamoDBContainerHelperSpec` |
| `example` | `CounterV1AsyncSpec`, `CounterV1SyncSpec`, `CounterV2AsyncSpec`, `CounterV2SyncSpec` |

The `example` module declares each Counter suite twice, once in the `softdelete` package and once in the `harddelete` package, so `example/testOnly *CounterV1AsyncSpec` runs two classes. The file `CounterSpec.scala` declares no class named `CounterSpec`, so `example/testOnly *CounterSpec` runs no test. `DockerSuiteReferenceSpec` checks this table and every `testOnly` pattern in Verification commands against the test sources.

Tests do not run in parallel, so the full suite starts and stops the 26 containers one after another. Each start waits for a log message and retries a table check up to 10 times at one second, and table creation and deletion add fixed 500 millisecond sleeps. The full `sbt test` therefore spends minutes in Docker even when the code is correct.

The fast gates start no container. Run `schema-check/test`, `lint`, `checkModuleBoundaries`, and the cross-compiles before the Docker-backed suites. Start Docker before any command that names a suite.

`docker-compose.yml` is a separate manual stack for local development. It runs DynamoDB Local on port 8000, a table setup job, and an admin page on port 8003. The test helper does not use it.

## Verification commands

Run these before you push. They are the checks of this change, in the order that fails fastest.

```bash
sbt -batch "schema-check/test"
sbt -batch "++2.12.20 schema-check/Test/compile"
sbt -batch "++3.6.4 schema-check/Test/compile"
sbt -batch lint
sbt -batch checkModuleBoundaries
```

Each of these starts no Docker container:

```bash
sbt -batch "schema-check/testOnly *FactoryClassReferenceSpec"
sbt -batch "schema-check/testOnly *SchemaDriftSpec"
sbt -batch "schema-check/testOnly *RuleTableSpec"
sbt -batch "journal-base/testOnly *DynamoDBItemSizeCalculatorSpec"
```

Build the docs with warnings as errors. Create the virtual environment once.

```bash
python3 -m venv target/docs-venv
target/docs-venv/bin/pip install -q -r docs/requirements.txt
target/docs-venv/bin/sphinx-build -W -b html docs/src/sphinx target/docs-build
```

Run a Docker-backed suite only when your change reaches the journal, snapshot, or state write path.

```bash
sbt -batch "journal-v2/testOnly *JournalHeadSpec"
sbt -batch "journal-v1/testOnly *DynamoDBJournalV1SyncSpec"
sbt -batch "snapshot-v1/testOnly *DynamoDBSnapshotStoreV1SyncSpec"
sbt -batch "state-v1/testOnly *DynamoDBStateV1SyncSpec"
```

`sbt lint` runs `scalafmtCheck`, `test:scalafmtCheck`, `scalafmtSbtCheck`, `scalafixAll --check`, and `checkModuleBoundaries`. `sbt fmt` rewrites the files.

## Before you merge

Before you merge, run `tools/check-review-threads.sh <owner/repo> <pr>` and confirm exit 0. Reply to every review thread with the fix, the reason for dismissal, or a follow-up issue, then resolve the thread. An unresolved thread means a real finding can ship.

Branch protection on `main` requires conversation resolution and the `Check unresolved comments` status, so GitHub blocks the merge while any thread is unresolved. The `review-thread-resolution` workflow publishes that status through the shared workflow in `j5ik2o/ci`. It refreshes the status on review, review comment, and issue comment events, and refreshes every open pull request once a day at 18:17 UTC, so the status can lag behind a thread that was just resolved. Treat the local `tools/check-review-threads.sh` run as the check of the current state.

## Rule table

Each row names a mistake that was made more than once, the rule that prevents it, and the file that enforces the rule. `RuleTableSpec` reads this table and fails when an enforcement path no longer exists. The known problems and their owner issues are in `schema-check/known-problems.allowlist`.

| Repeated mistake | Rule | Enforcement |
|:--|:--|:--|
| A factory class named in `reference.conf` or in a default does not exist, so startup fails on the client version that uses it. | Name every configurable factory by its fully qualified class name, and keep a class, object, or trait at that name. | test `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/FactoryClassReferenceSpec.scala` |
| A column or index name differs between `reference.conf`, the table JSON, and the docs. | Define each name once in the module's `columns-def` config or schema value, and derive the table JSON and the fixture from it. | test `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/SchemaDriftSpec.scala` |
| The table JSON or the DynamoDB Local fixture is edited by hand, so it drifts from the table spec. | Regenerate the checked-in artifacts from the table specs instead of editing them. | test `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/TableSpecArtifactSpec.scala` |
| A new file imports an AWS SDK and couples the core to one client version. | Only `base-v1`, `base-v2`, and allowlisted files import `com.amazonaws`, `software.amazon.awssdk`, or `software.amazon.dax`. The list shrinks by default. A module that already holds SDK imports accepts a new entry with its reason in the entry and in the PR. A core module accepts none. | check `project/ModuleBoundaryCheck.scala` |
| A fixed problem stays in an allowlist and hides the next occurrence. | An allowlist entry must reproduce. Remove the entry when the problem is fixed. | check `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/Allowlist.scala` |
| A rejected JournalHead write is thrown from a stream stage, so the write queue stops. | Return the rejection as a `JournalHeadException` value, and fail the whole write with it, so that `onPersistFailure` handles it. | test `journal/journal-v2/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/JournalHeadSpec.scala` |
| A new rejection is a bare `RuntimeException` that callers cannot tell apart. | Add a subclass of the sealed `JournalHeadException` family. | type `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/head/JournalHeadException.scala` |
| `head.enabled` accepts a client version or a table pair that cannot serve it. | Require `client-version = v2` without DAX, and a `JournalHead` table name that differs from the journal table. | check `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/config/JournalPluginConfig.scala` |
| A GitHub Actions job has no time limit and can retain a runner indefinitely. | Set `timeout-minutes` on every executable job in `.github/workflows/*.yml`; reusable workflow caller jobs are exempt because GitHub Actions does not allow the setting there. | test `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/WorkflowTimeoutSpec.scala` |
| A change passes `sbt test` but fails `lint` on formatting or an unused import. | Run `sbt lint` before every push. | check `build.sbt` |
| A review thread is left unresolved at merge, so a real finding ships. | Resolve every review thread with a disposition before merge. | check `tools/check-review-threads.sh` |
