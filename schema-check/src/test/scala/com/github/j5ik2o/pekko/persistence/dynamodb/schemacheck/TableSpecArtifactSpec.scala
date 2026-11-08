package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeKind,
  AttributeName,
  Column,
  IndexName,
  Presence,
  PrimaryKey,
  StreamView,
  TableName,
  TableSpec
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema.{ JournalSchema, JournalTableSpec }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotColumnsDefConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.schema.{ SnapshotSchema, SnapshotTableSpec }
import com.github.j5ik2o.pekko.persistence.dynamodb.state.config.StateColumnsDefConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.state.schema.{ StateSchema, StateTableSpec }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The table JSON files and the DynamoDB Local fixture are rendered from the table specs. A change to a key or an index
  * in `reference.conf` or in a schema value must reach these checked-in files.
  */
private object TableSpecArtifacts {

  private val config: Config = ConfigFactory.load()

  private val journalConfig: Config = config.getConfig("j5ik2o.dynamo-db-journal")
  private val journalSchema: JournalSchema = JournalSchema(
    journalConfig.getString("table-name"),
    journalConfig.getString("get-journal-rows-index-name"),
    JournalColumnsDefConfig.fromConfig(journalConfig.getConfig("columns-def"))
  )
  private val journalSpec: TableSpec = JournalTableSpec.of(journalSchema)

  private val journalHeadConfig: JournalHeadConfig = JournalHeadConfig.fromConfig(journalConfig.getConfig("head"))
  private val journalHeadId: AttributeName =
    AttributeName(journalHeadConfig.columnsDefConfig.persistenceIdColumnName)
  private val journalHeadSpec: TableSpec = TableSpec(
    table = TableName(journalHeadConfig.tableName),
    columns = Vector(Column(journalHeadId, AttributeKind.S, Presence.Required)),
    primaryKey = PrimaryKey(journalHeadId, None),
    indexes = Vector.empty,
    stream = Some(StreamView.NewImage)
  )

  private val snapshotConfig: Config = config.getConfig("j5ik2o.dynamo-db-snapshot")
  private val snapshotSchema: SnapshotSchema =
    SnapshotSchema.fromColumns(SnapshotColumnsDefConfig.fromConfig(snapshotConfig.getConfig("columns-def")))
  private val snapshotSpec: TableSpec = SnapshotTableSpec
    .sharded(
      snapshotSchema,
      TableName(snapshotConfig.getString("table-name")),
      IndexName(snapshotConfig.getString("get-snapshot-rows-index-name")),
      SnapshotResolvers.PartitionKey,
      SnapshotResolvers.SortKey
    )
    .table

  private val stateConfig: Config = config.getConfig("j5ik2o.dynamo-db-state")
  private val stateSchema: StateSchema =
    StateSchema.fromColumns(StateColumnsDefConfig.fromConfig(stateConfig.getConfig("columns-def")))
  private val stateSpec: TableSpec = StateTableSpec
    .create(
      stateSchema,
      TableName(stateConfig.getString("table-name")),
      StateResolvers.TableName,
      StateResolvers.PartitionKey,
      StateResolvers.SortKey
    )
    .table

  val artifacts: Vector[(String, String)] = Vector(
    "tools/dynamodb-setup/journal-table.json"          -> TableSpecJson.render(journalSpec),
    "tools/dynamodb-setup/journal-table-for-head.json" -> TableSpecJson.render(journalSpec.copy(stream = None)),
    "tools/dynamodb-setup/journal-head-table.json"     -> TableSpecJson.render(journalHeadSpec),
    "tools/dynamodb-setup/snapshot-table.json"         -> TableSpecJson.render(snapshotSpec),
    "tools/dynamodb-setup/state-table.json"            -> TableSpecJson.render(stateSpec),
    "test/src/main/resources/dynamodb-local-fixture.json" -> TableSpecJson.renderFixture(
      Seq(
        TableSpecJson.FixtureTable(journalSpec),
        TableSpecJson.FixtureTable(journalHeadSpec),
        TableSpecJson.FixtureTable(snapshotSpec),
        TableSpecJson.FixtureTable(stateSpec)
      )
    )
  )
}

/** Placeholders for values that the table specs carry but the rendered JSON does not read. */
private object SnapshotResolvers {
  val PartitionKey: com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver =
    new com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKeyResolver {
      override def resolve(
          persistenceId: PersistenceId,
          sequenceNumber: SequenceNumber
      ): com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKey =
        com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.PartitionKey("pkey")
    }

  val SortKey: com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver =
    new com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKeyResolver {
      override def resolve(
          persistenceId: PersistenceId,
          sequenceNumber: SequenceNumber
      ): com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKey =
        com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SortKey("skey")
    }
}

private object StateResolvers {
  val TableName: com.github.j5ik2o.pekko.persistence.dynamodb.state.TableNameResolver =
    new com.github.j5ik2o.pekko.persistence.dynamodb.state.TableNameResolver {
      override def resolve(
          persistenceId: PersistenceId
      ): com.github.j5ik2o.pekko.persistence.dynamodb.state.TableName =
        com.github.j5ik2o.pekko.persistence.dynamodb.state.TableName("State")
    }

  val PartitionKey: com.github.j5ik2o.pekko.persistence.dynamodb.state.PartitionKeyResolver =
    new com.github.j5ik2o.pekko.persistence.dynamodb.state.PartitionKeyResolver {
      override def resolve(
          persistenceId: PersistenceId
      ): com.github.j5ik2o.pekko.persistence.dynamodb.state.PartitionKey =
        com.github.j5ik2o.pekko.persistence.dynamodb.state.PartitionKey("pkey")
    }

  val SortKey: com.github.j5ik2o.pekko.persistence.dynamodb.state.SortKeyResolver =
    new com.github.j5ik2o.pekko.persistence.dynamodb.state.SortKeyResolver {
      override def resolve(
          persistenceId: PersistenceId
      ): com.github.j5ik2o.pekko.persistence.dynamodb.state.SortKey =
        com.github.j5ik2o.pekko.persistence.dynamodb.state.SortKey("skey")
    }
}

final class TableSpecArtifactSpec extends AnyFreeSpec with Matchers {

  "the checked-in table JSON and DynamoDB Local fixture match the rendered table specs" in {
    val repo = Repo.locate()

    if (sys.env.get("TABLE_SPEC_WRITE").contains("1")) {
      TableSpecArtifacts.artifacts.foreach { case (relative, content) =>
        val path = repo.root.resolve(relative)
        Files.createDirectories(path.getParent)
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
        info(s"wrote $relative")
      }
    } else {
      TableSpecArtifacts.artifacts.foreach { case (relative, expected) =>
        assertArtifact(repo, relative, expected)
      }
    }
  }

  "the legacy table JSON files stay checked in and keep the legacy shape" in {
    val repo = Repo.locate()

    repo.read(repo.root.resolve("tools/dynamodb-setup/legacy-journal-table.json")) should include("TagsIndex")
    repo.read(repo.root.resolve("tools/dynamodb-setup/legacy-snapshot-table.json")) should include(
      "\"TableName\": \"Snapshot\""
    )
  }

  private def assertArtifact(repo: Repo, relative: String, expected: String): Unit = {
    val actual = repo.read(repo.root.resolve(relative))
    if (actual != expected) {
      val actualLines   = actual.linesIterator.toVector
      val expectedLines = expected.linesIterator.toVector
      val first         = actualLines.zipAll(expectedLines, "", "").indexWhere { case (left, right) => left != right }
      val detail = (first until (first + 3))
        .flatMap { index =>
          if (index >= 0 && index < math.max(actualLines.size, expectedLines.size)) {
            Some(
              s"  line ${index + 1}:\n    checked in: ${actualLines.lift(index).getOrElse("<missing>")}\n    rendered:   ${expectedLines.lift(index).getOrElse("<missing>")}"
            )
          } else {
            None
          }
        }.mkString("\n")
      fail(
        relative + " differs from the rendered table spec. Run TABLE_SPEC_WRITE=1 sbt -batch " +
        "\"schema-check/testOnly *TableSpecArtifactSpec\" to regenerate it.\n" + detail
      )
    }
  }
}
