/*
 * Copyright 2026 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.head

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadColumnsDefConfig,
  JournalHeadConfig,
  JournalPluginConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2SyncClientFactory
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.actor.ActorSystem
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  GetItemRequest,
  PutItemRequest,
  ResourceNotFoundException,
  ScanRequest,
  UpdateItemRequest
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => JavaDynamoDbSyncClient }

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Creates the JournalHead items of the aggregates that were written before `head.enabled` was turned on.
  *
  * The tool scans the journal table and the snapshot table and, for every persistence id, writes a JournalHead item
  * that holds the last sequence number of that persistence id and an empty event list. The snapshot table is scanned as
  * well because an aggregate whose journal events were all hard deleted has no journal row left: its snapshot is the
  * only record of its last sequence number, and without a JournalHead item the next write of that aggregate would be
  * rejected. An empty event list is correct for the migration: the read model is assumed to be up to date at the moment
  * head mode is enabled, and every later write carries its own events. The journal table remains the source for
  * rebuilding a read model from scratch.
  *
  * An existing JournalHead item is raised to the last sequence number the journal or the snapshot holds, but only
  * forwards: a write that happens after the migration (for example from a node that still runs with
  * `head.enabled = false`) leaves the head behind the journal, and running the tool again after every node runs with
  * head mode enabled raises it again. The event list of such an item is cleared, because the head no longer describes a
  * single write.
  *
  * The writes are conditional (`attribute_not_exists` for the creation and `sequence-nr < :latest` for the raise), so
  * the tool can be run as often as needed. Nothing is written unless `execute` is true.
  */
object JournalHeadMigration {

  /** The snapshot table scanned by the migration, for aggregates whose journal events were all hard deleted. */
  final case class SnapshotTable(
      tableName: String,
      persistenceIdColumnName: String,
      sequenceNrColumnName: String
  )

  object SnapshotTable {

    val default: SnapshotTable = SnapshotTable("Snapshot", "persistence-id", "sequence-nr")

    def fromConfig(config: Config): SnapshotTable = {
      val columnsDef = if (config.hasPath("columns-def")) config.getConfig("columns-def") else ConfigFactory.empty()
      SnapshotTable(
        tableName = stringOr(config, "table-name", default.tableName),
        persistenceIdColumnName = stringOr(columnsDef, "persistence-id-column-name", default.persistenceIdColumnName),
        sequenceNrColumnName = stringOr(columnsDef, "sequence-nr-column-name", default.sequenceNrColumnName)
      )
    }

    private def stringOr(config: Config, path: String, default: String): String =
      if (config.hasPath(path)) config.getString(path) else default

  }

  final case class Result(
      scannedPersistenceIds: Int,
      createdJournalHeads: Int,
      updatedJournalHeads: Int,
      existingJournalHeads: Int,
      raisedPersistenceIds: Seq[String],
      snapshotTableScanned: Boolean
  )

  def run(
      syncClient: JavaDynamoDbSyncClient,
      journalTableName: String,
      journalColumnsDefConfig: JournalColumnsDefConfig,
      headConfig: JournalHeadConfig,
      execute: Boolean,
      snapshotTable: Option[SnapshotTable] = Some(SnapshotTable.default)
  ): Result = {
    val journalVersions = scanHighestSequenceNrs(
      syncClient,
      journalTableName,
      journalColumnsDefConfig.persistenceIdColumnName,
      journalColumnsDefConfig.sequenceNrColumnName
    )
    val snapshotVersions = snapshotTable.flatMap(table => scanSnapshotHighestSequenceNrs(syncClient, table))
    val persistenceIds =
      journalVersions.keySet ++ snapshotVersions.getOrElse(Map.empty[PersistenceId, SequenceNumber]).keySet
    val currentHeads = persistenceIds.iterator
      .flatMap(persistenceId => readHeadSequenceNr(syncClient, headConfig, persistenceId).map(persistenceId -> _))
      .toMap
    val plan = MigrationPlanner.plan(journalVersions, snapshotVersions, currentHeads)
    if (execute) {
      val result = MigrationExecutor.execute(plan, headWriter(syncClient, headConfig))
      Result(
        plan.scannedPersistenceIds,
        result.createdJournalHeads,
        result.updatedJournalHeads,
        result.existingJournalHeads,
        result.raisedPersistenceIds.map(_.asString),
        plan.snapshotTableScanned
      )
    } else {
      Result(
        plan.scannedPersistenceIds,
        plan.createdJournalHeads,
        plan.updatedJournalHeads,
        plan.existingJournalHeads,
        plan.raisedPersistenceIds.map(_.asString),
        plan.snapshotTableScanned
      )
    }
  }

  private def headWriter(
      syncClient: JavaDynamoDbSyncClient,
      headConfig: JournalHeadConfig
  ): MigrationExecutor.HeadWriter =
    new MigrationExecutor.HeadWriter {
      override def putIfAbsent(persistenceId: PersistenceId, target: SequenceNumber): Boolean =
        putJournalHeadIfAbsent(syncClient, headConfig, persistenceId, target)
      override def raiseIfBehind(persistenceId: PersistenceId, target: SequenceNumber): Boolean =
        raiseJournalHeadIfBehind(syncClient, headConfig, persistenceId, target)
    }

  /** Reads the highest sequence number of every persistence id in a table. A missing snapshot table is not an error:
    * the snapshot plugin is optional, and a deployment that does not use it has no snapshot table to scan.
    */
  private def scanSnapshotHighestSequenceNrs(
      syncClient: JavaDynamoDbSyncClient,
      snapshotTable: SnapshotTable
  ): Option[Map[PersistenceId, SequenceNumber]] =
    try {
      Some(
        scanHighestSequenceNrs(
          syncClient,
          snapshotTable.tableName,
          snapshotTable.persistenceIdColumnName,
          snapshotTable.sequenceNrColumnName
        )
      )
    } catch {
      case _: ResourceNotFoundException => None
    }

  private def scanHighestSequenceNrs(
      syncClient: JavaDynamoDbSyncClient,
      tableName: String,
      persistenceIdColumnName: String,
      sequenceNrColumnName: String
  ): Map[PersistenceId, SequenceNumber] = {
    val result             = mutable.Map.empty[PersistenceId, SequenceNumber]
    var exclusiveStartKey  = Option.empty[java.util.Map[String, AttributeValue]]
    var hasMoreItemsToRead = true
    while (hasMoreItemsToRead) {
      val requestBuilder = ScanRequest
        .builder()
        .tableName(tableName)
        .consistentRead(true)
        .projectionExpression("#persistenceId, #sequenceNr")
        .expressionAttributeNames(
          Map(
            "#persistenceId" -> persistenceIdColumnName,
            "#sequenceNr"    -> sequenceNrColumnName
          ).asJava
        )
      exclusiveStartKey.foreach(key => requestBuilder.exclusiveStartKey(key))
      val response = syncClient.scan(requestBuilder.build())
      response.items().asScala.foreach { item =>
        val persistenceId  = PersistenceId(item.get(persistenceIdColumnName).s())
        val sequenceNr     = SequenceNumber(item.get(sequenceNrColumnName).n().toLong)
        val currentHighest = result.getOrElse(persistenceId, SequenceNumber.MinValue)
        if (sequenceNr > currentHighest) result.update(persistenceId, sequenceNr)
      }
      exclusiveStartKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
      hasMoreItemsToRead = exclusiveStartKey.nonEmpty
    }
    result.toMap
  }

  private def readHeadSequenceNr(
      syncClient: JavaDynamoDbSyncClient,
      headConfig: JournalHeadConfig,
      persistenceId: PersistenceId
  ): Option[SequenceNumber] = {
    val headColumnsDefConfig = headConfig.columnsDefConfig
    val request = GetItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .key(
        Map(
          headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId.asString).build()
        ).asJava
      )
      .consistentRead(true)
      .build()
    Option(syncClient.getItem(request).item())
      .flatMap(item => Option(item.get(headColumnsDefConfig.sequenceNrColumnName)))
      .map(attributeValue => SequenceNumber(attributeValue.n().toLong))
  }

  /** Raises an existing JournalHead item to the last sequence number of the journal, if the head is behind. The event
    * list is cleared and the revision is removed: after the raise the head no longer describes a single write, and the
    * events up to the raise are in the journal for a rebuild.
    */
  private def raiseJournalHeadIfBehind(
      syncClient: JavaDynamoDbSyncClient,
      headConfig: JournalHeadConfig,
      persistenceId: PersistenceId,
      target: SequenceNumber
  ): Boolean = {
    val headColumnsDefConfig = headConfig.columnsDefConfig
    val request = UpdateItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .key(
        Map(
          headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId.asString).build()
        ).asJava
      )
      .updateExpression("SET #sequenceNr = :latest, #events = :emptyEvents REMOVE #revision")
      .conditionExpression("#sequenceNr < :latest")
      .expressionAttributeNames(
        Map(
          "#sequenceNr" -> headColumnsDefConfig.sequenceNrColumnName,
          "#events"     -> headColumnsDefConfig.eventsColumnName,
          "#revision"   -> JournalHeadColumnsDefConfig.revisionColumnName
        ).asJava
      )
      .expressionAttributeValues(
        Map(
          ":latest"      -> AttributeValue.builder().n(target.value.toString).build(),
          ":emptyEvents" -> AttributeValue.builder().l(List.empty[AttributeValue].asJava).build()
        ).asJava
      )
      .build()
    try {
      syncClient.updateItem(request)
      true
    } catch {
      case _: software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException => false
    }
  }

  private def putJournalHeadIfAbsent(
      syncClient: JavaDynamoDbSyncClient,
      headConfig: JournalHeadConfig,
      persistenceId: PersistenceId,
      target: SequenceNumber
  ): Boolean = {
    val headColumnsDefConfig = headConfig.columnsDefConfig
    val request = PutItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .item(
        Map(
          headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId.asString).build(),
          headColumnsDefConfig.sequenceNrColumnName    -> AttributeValue.builder().n(target.value.toString).build(),
          headColumnsDefConfig.eventsColumnName -> AttributeValue.builder().l(List.empty[AttributeValue].asJava).build()
        ).asJava
      )
      .conditionExpression("attribute_not_exists(#persistenceId)")
      .expressionAttributeNames(Map("#persistenceId" -> headColumnsDefConfig.persistenceIdColumnName).asJava)
      .build()
    try {
      syncClient.putItem(request)
      true
    } catch {
      case _: software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException => false
    }
  }

  /** Reads the snapshot table from the `j5ik2o.dynamo-db-snapshot` config or from `--snapshot-table`, and applies the
    * command line overrides. `--without-snapshots` skips the snapshot table entirely.
    *
    * With neither the config nor `--snapshot-table` the tool refuses to guess a table. Falling back to `Snapshot` hid a
    * missing snapshot source and scanned a table that a custom deployment does not have.
    */
  private[journal] def resolveSnapshotTable(args: Array[String], config: Config): Option[SnapshotTable] =
    if (args.contains("--without-snapshots")) {
      None
    } else {
      val configured = Try(config.getConfig("j5ik2o.dynamo-db-snapshot")).toOption.map(SnapshotTable.fromConfig)
      val tableName  = optionValue(args, "--snapshot-table")
      if (configured.isEmpty && tableName.isEmpty) {
        throw new IllegalArgumentException(
          "no snapshot source is configured: add j5ik2o.dynamo-db-snapshot to the config, pass --snapshot-table, or pass --without-snapshots"
        )
      }
      val base = configured.getOrElse(SnapshotTable.default)
      Some(
        base.copy(
          tableName = tableName.getOrElse(base.tableName),
          persistenceIdColumnName =
            optionValue(args, "--snapshot-persistence-id-column").getOrElse(base.persistenceIdColumnName),
          sequenceNrColumnName = optionValue(args, "--snapshot-sequence-nr-column").getOrElse(base.sequenceNrColumnName)
        )
      )
    }

  private def optionValue(args: Array[String], name: String): Option[String] = {
    val index = args.indexOf(name)
    if (index < 0) {
      None
    } else {
      // A following flag is not the value of this option. Reading it as one silently skipped the option.
      args.lift(index + 1).filterNot(_.startsWith("--")) match {
        case Some(value) => Some(value)
        case None        => throw new IllegalArgumentException(s"$name requires a value")
      }
    }
  }

  def main(args: Array[String]): Unit = {
    // Nothing is written unless --execute is given.
    val execute = args.contains("--execute")
    val system  = ActorSystem("journal-head-migration")
    try {
      val config = system.settings.config
      val pluginConfig =
        JournalPluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-journal"))
      require(
        pluginConfig.clientConfig.clientVersion == ClientVersion.V2,
        "the JournalHead migration tool requires client-version v2"
      )
      val pluginContext = com.github.j5ik2o.pekko.persistence.dynamodb.journal.JournalPluginContext(
        system,
        pluginConfig
      )
      val client = pluginContext
        .newDynamicAccessor[V2SyncClientFactory]()
        .createThrow(pluginConfig.v2SyncClientFactoryClassName)
        .create
      try {
        val result = run(
          client,
          pluginConfig.tableName,
          pluginConfig.columnsDefConfig,
          pluginConfig.headConfig,
          execute,
          resolveSnapshotTable(args, config)
        )
        val prefix = if (execute) "" else "[dry-run] "
        println(
          s"${prefix}JournalHead migration finished: scanned ${result.scannedPersistenceIds} persistence ids," +
          s" created ${result.createdJournalHeads} JournalHead items," +
          s" raised ${result.updatedJournalHeads} JournalHead items," +
          s" skipped ${result.existingJournalHeads} existing JournalHead items"
        )
        if (!result.snapshotTableScanned) {
          println(
            s"${prefix}the snapshot table was not scanned (not found, or --without-snapshots);" +
            " aggregates whose journal events were all hard deleted were not migrated"
          )
        }
        if (result.raisedPersistenceIds.nonEmpty) {
          println(
            s"${prefix}raised JournalHead items (rebuild the read model of these aggregates from the journal):" +
            result.raisedPersistenceIds.map(persistenceId => s"\n  - $persistenceId").mkString
          )
        }
      } finally {
        client.close()
      }
    } finally {
      system.terminate()
    }
  }

}
