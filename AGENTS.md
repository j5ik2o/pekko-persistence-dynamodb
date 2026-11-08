# Working on pekko-persistence-dynamodb

This file is for an agent that changes this repository. It names the module that owns each part of the code, the files to edit for the three common changes, the build matrix, the Docker cost, the verification commands, and the rules that the build enforces.

Read this file before you edit. If a rule names an enforcement path, run that check after your change.

## Module map

Every module is an sbt project in `build.sbt`. The `Depends on` column lists the compile dependencies, so a module can use the types of the modules it depends on. A `(test only)` entry is used by tests and is not part of the published dependency.

| Module | Directory | It owns | Depends on |
|:--|:--|:--|:--|
| `test` | `test/` | The DynamoDB Local controller, table creation, and `ConfigHelper` for tests | none |
| `schema-check` | `schema-check/` | Repository checks that read files instead of running the product code | none |
| `base` | `base/base/` | Configuration parsing, the model wrappers, metrics and trace interfaces, and the client-neutral `PluginContext` | `test` (test only) |
| `base-v1` | `base/base-v1/` | AWS SDK v1 client builders and client factories, including DAX | `base` |
| `base-v2` | `base/base-v2/` | AWS SDK v2 client builders and client factories, including DAX | `base` |
| `journal-base` | `journal/journal-base/` | The journal plugin, its config, DAO, serializer, and the JournalHead types and validation | `base` |
| `journal-v1` | `journal/journal-v1/` | The v1 journal row read and write drivers and their factories | `journal-base`, `base-v1`, `snapshot-base` (test only) |
| `journal-v2` | `journal/journal-v2/` | The v2 journal row read and write drivers, their factories, and the JournalHead migration tool | `journal-base`, `base-v2`, `snapshot-base` and `snapshot-v2` (test only) |
| `snapshot-base` | `snapshot/snapshot-base/` | The snapshot store, its config, and its key resolvers | `base` |
| `snapshot-v1` | `snapshot/snapshot-v1/` | The v1 snapshot DAO and factory | `snapshot-base`, `base-v1` |
| `snapshot-v2` | `snapshot/snapshot-v2/` | The v2 snapshot DAO and factory | `snapshot-base`, `base-v2` |
| `state-base` | `state/state-base/` | The durable state store, its config, and its key resolvers | `base` |
| `state-v1` | `state/state-v1/` | The v1 durable state store and factory | `state-base`, `base-v1` |
| `state-v2` | `state/state-v2/` | The v2 durable state store and factory | `state-base`, `base-v2` |
| `benchmark` | `benchmark/` | JMH benchmarks; not published | `test`, the journal and snapshot modules |
| `example` | `example/` | Usage examples; not published | `test`, the journal, snapshot, and state modules |
| `root` | `.` | Aggregates every module so that `sbt test` covers all of them | all modules |

The AWS SDK is confined to `base-v1`, `base-v2`, and the allowlisted files in `project/module-boundaries.allowlist`. The core modules use the neutral types in `base`, `journal-base`, `snapshot-base`, and `state-base`.

## Add a column

The column names of each plugin live in one config value. Add the column to that value first, then thread it through the paths below.

1. Edit the `columns-def` value of the plugin. Journal uses `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/config/JournalColumnsDefConfig.scala`. Snapshot uses `snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/config/SnapshotColumnsDefConfig.scala`. State uses `state/state-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/state/config/StateColumnsDefConfig.scala`. Add the key constant, the case class field, and the `fromConfig` read.
2. Add the key with its default value to the `columns-def` block of the module's `src/main/resources/reference.conf`.
3. Write the attribute in both client versions. Journal writes in `journal/journal-v1/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/dao/v1/V1JournalRowWriteDriver.scala` and `journal/journal-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/dao/v2/V2JournalRowWriteDriver.scala`. Snapshot writes in `snapshot/snapshot-v1/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/dao/V1LegacySnapshotDaoImpl.scala`, `V1NewSnapshotDaoImpl.scala`, and the v2 equivalents. State writes in `state/state-v1/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/state/scaladsl/DynamoDBDurableStateStoreV1.scala` and the v2 equivalent.
4. Read the attribute in the same two client versions, or in the shared row type when the read path is neutral.
5. Add the attribute definition and key schema to `tools/dynamodb-setup/journal-table.json`, `snapshot-table.json`, or `state-table.json`. Update `tools/dynamodb-setup/legacy-journal-table.json` or `legacy-snapshot-table.json` only when the legacy table keeps the column.
6. Add the attribute to `test/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/utils/DynamoDBContainerHelper.scala`. The test tables are built by hand, so a missing column fails at runtime, not at compile time.
7. Add the attribute to `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/head/DynamoDBItemSizeCalculator.scala` when the column is part of the JournalHead write path. The head rejects a write that would exceed the item size, so an uncounted column rejects too late.
8. Update `docs/src/sphinx/customize.md` for the column name and `docs/src/sphinx/table-format.md` for a key or index change.
9. Run `sbt -batch "schema-check/test"`. `SchemaDriftSpec` compares `reference.conf`, the table JSON, the docs, and `DynamoDBContainerHelper`, and fails on a name that appears in one place only.
10. Run the tests of the family you changed. Journal, snapshot, and state each have v1 and v2 suites under their module's `src/test`.

To find every code site that handles an existing column, search for `tagsColumnName`. To find the config and docs sites, search for `tags-column-name`.

## Change a write condition

A write condition decides whether DynamoDB accepts a journal write. The v2 driver holds the conditions of the JournalHead path. The v1 driver has no condition expression on its put or batch put. The shared validation lives in `JournalHeadWriteRequest.of`, which returns `Either[JournalHeadException, JournalHeadWriteRequest]` and never throws for a rejected write.

1. Decide where the check belongs. A validation that both client versions must share goes into `JournalHeadWriteRequest.of` in `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/head/JournalHeadRow.scala`, because it runs before either driver sends anything. A DynamoDB condition expression goes into the driver for the client version that needs it. The v1 driver sets no condition expression today.
2. Edit the v2 condition in `createTransactWriteItemsRequest` in `journal/journal-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/dao/v2/V2JournalRowWriteDriver.scala`. The journal row condition is `attribute_not_exists(#pkey) AND attribute_not_exists(#skey)`. The head condition is `(attribute_not_exists(#seq) AND :first = :one) OR (#seq < :first)`.
3. Edit the v1 path in `journal/journal-v1/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/dao/v1/V1JournalRowWriteDriver.scala` when the condition applies to v1. `PutItemRequest` accepts a condition expression. `BatchWriteItem` does not, so a condition on the batch path needs a different call.
4. Map a cancelled transaction to the existing family in `classifyTransactionCancellation`. Do not add a bare `RuntimeException`, because callers and operators tell the failures apart by the subclass.
5. Add a case to `journal/journal-v2/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/JournalHeadSpec.scala` that fails before the change and passes after.
6. Run `sbt -batch "journal-v2/testOnly *JournalHeadSpec"`. It starts DynamoDB Local, so start Docker first.
7. Run `sbt -batch lint`.

Do not validate inside a stream stage. A `throw` inside a `Flow` aborts the stream and every later write on that queue fails. Return the rejection from `JournalHeadWriteRequest.of`, and let `WriteJournalDaoImpl` and `DynamoDBJournal` turn it into a `Failure` that reaches `onPersistRejected`.

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

The three `pluginExecutor` matches currently accept only `V1` and `V2`. A DAX variant reaches one of them at startup. The DAX cases are a known gap that the DAX startup work fixes; do not copy the gap into a new variant.

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

14 test classes extend `DynamoDBContainerHelper`, and each one starts an `amazon/dynamodb-local` container on a temporary port with an in-memory database. The classes are the two v1 and two v2 journal sync and async suites, `JournalHeadSpec`, the two v1 and two v2 snapshot suites, the two v1 and two v2 state suites, and the example `CounterSpec`.

Tests do not run in parallel, so the full suite starts and stops the 14 containers one after another. Each start waits for a log message and retries a table check up to 10 times at one second, and table creation and deletion add fixed 500 millisecond sleeps. The full `sbt test` therefore spends minutes in Docker even when the code is correct.

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

## Rule table

Each row names a mistake that was made more than once, the rule that prevents it, and the file that enforces the rule. `RuleTableSpec` reads this table and fails when an enforcement path no longer exists. The known problems and their owner issues are in `schema-check/known-problems.allowlist`.

| Repeated mistake | Rule | Enforcement |
|:--|:--|:--|
| A factory class named in `reference.conf` or in a default does not exist, so startup fails on the client version that uses it. | Name every configurable factory by its fully qualified class name, and keep a class, object, or trait at that name. | test `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/FactoryClassReferenceSpec.scala` |
| A column or index name differs between `reference.conf`, the table JSON, the docs, and the test fixture. | Define each name once in the module's `columns-def` config, and use the same spelling in all four places. | test `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/SchemaDriftSpec.scala` |
| A new file imports an AWS SDK and couples the core to one client version. | Only allowlisted files import `com.amazonaws`, `software.amazon.awssdk`, or `software.amazon.dax`. The list only shrinks. | check `project/ModuleBoundaryPlugin.scala` |
| A fixed problem stays in an allowlist and hides the next occurrence. | An allowlist entry must reproduce. Remove the entry when the problem is fixed. | check `schema-check/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/schemacheck/Allowlist.scala` |
| A rejected JournalHead write is thrown from a stream stage, so the write queue stops. | Return the rejection as a `JournalHeadException` value, and let `onPersistRejected` handle it. | test `journal/journal-v2/src/test/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/JournalHeadSpec.scala` |
| A new rejection is a bare `RuntimeException` that callers cannot tell apart. | Add a subclass of the sealed `JournalHeadException` family. | type `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/head/JournalHeadException.scala` |
| `head.enabled` accepts a client version or a table pair that cannot serve it. | Require `client-version = v2` without DAX, and a `JournalHead` table name that differs from the journal table. | check `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/config/JournalPluginConfig.scala` |
| A change passes `sbt test` but fails `lint` on formatting or an unused import. | Run `sbt lint` before every push. | check `build.sbt` |
