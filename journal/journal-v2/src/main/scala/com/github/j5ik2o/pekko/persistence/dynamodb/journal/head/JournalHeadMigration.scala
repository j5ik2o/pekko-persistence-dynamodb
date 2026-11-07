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
  JournalHeadConfig,
  JournalPluginConfig
}
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
    val journalHighestSequenceNrs = scanHighestSequenceNrs(
      syncClient,
      journalTableName,
      journalColumnsDefConfig.persistenceIdColumnName,
      journalColumnsDefConfig.sequenceNrColumnName
    )
    val snapshotHighestSequenceNrs = snapshotTable.flatMap(table => scanSnapshotHighestSequenceNrs(syncClient, table))
    val highestSequenceNrs =
      mergeHighestSequenceNrs(journalHighestSequenceNrs, snapshotHighestSequenceNrs.getOrElse(Map.empty))
    var created  = 0
    var updated  = 0
    var existing = 0
    val raised   = Vector.newBuilder[String]
    highestSequenceNrs.foreach { case (persistenceId, lastSequenceNr) =>
      if (!execute) {
        readHeadSequenceNr(syncClient, headConfig, persistenceId) match {
          case None => created += 1
          case Some(current) if current < lastSequenceNr =>
            updated += 1
            raised += persistenceId
          case Some(_) => existing += 1
        }
      } else if (putJournalHeadIfAbsent(syncClient, headConfig, persistenceId, lastSequenceNr)) {
        created += 1
      } else if (raiseJournalHeadIfBehind(syncClient, headConfig, persistenceId, lastSequenceNr)) {
        updated += 1
        raised += persistenceId
      } else {
        existing += 1
      }
    }
    Result(
      highestSequenceNrs.size,
      created,
      updated,
      existing,
      raised.result().sorted,
      snapshotTableScanned = snapshotTable.isDefined && snapshotHighestSequenceNrs.isDefined
    )
  }

  private def mergeHighestSequenceNrs(left: Map[String, Long], right: Map[String, Long]): Map[String, Long] =
    right.foldLeft(left) { case (acc, (persistenceId, sequenceNr)) =>
      acc.updated(persistenceId, Math.max(acc.getOrElse(persistenceId, 0L), sequenceNr))
    }

  /** Reads the highest sequence number of every persistence id in a table. A missing snapshot table is not an error:
    * the snapshot plugin is optional, and a deployment that does not use it has no snapshot table to scan.
    */
  private def scanSnapshotHighestSequenceNrs(
      syncClient: JavaDynamoDbSyncClient,
      snapshotTable: SnapshotTable
  ): Option[Map[String, Long]] =
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
  ): Map[String, Long] = {
    val result             = mutable.Map.empty[String, Long]
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
        val persistenceId  = item.get(persistenceIdColumnName).s()
        val sequenceNr     = item.get(sequenceNrColumnName).n().toLong
        val currentHighest = result.getOrElse(persistenceId, 0L)
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
      persistenceId: String
  ): Option[Long] = {
    val headColumnsDefConfig = headConfig.columnsDefConfig
    val request = GetItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .key(
        Map(headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId).build()).asJava
      )
      .consistentRead(true)
      .build()
    Option(syncClient.getItem(request).item())
      .flatMap(item => Option(item.get(headColumnsDefConfig.sequenceNrColumnName)))
      .map(_.n().toLong)
  }

  /** Raises an existing JournalHead item to the last sequence number of the journal, if the head is behind. The event
    * list is cleared: after the raise the head no longer describes a single write, and the events up to the raise are
    * in the journal for a rebuild.
    */
  private def raiseJournalHeadIfBehind(
      syncClient: JavaDynamoDbSyncClient,
      headConfig: JournalHeadConfig,
      persistenceId: String,
      lastSequenceNr: Long
  ): Boolean = {
    val headColumnsDefConfig = headConfig.columnsDefConfig
    val request = UpdateItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .key(
        Map(headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId).build()).asJava
      )
      .updateExpression("SET #sequenceNr = :latest, #events = :emptyEvents")
      .conditionExpression("#sequenceNr < :latest")
      .expressionAttributeNames(
        Map(
          "#sequenceNr" -> headColumnsDefConfig.sequenceNrColumnName,
          "#events"     -> headColumnsDefConfig.eventsColumnName
        ).asJava
      )
      .expressionAttributeValues(
        Map(
          ":latest"      -> AttributeValue.builder().n(lastSequenceNr.toString).build(),
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
      persistenceId: String,
      lastSequenceNr: Long
  ): Boolean = {
    val headColumnsDefConfig = headConfig.columnsDefConfig
    val request = PutItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .item(
        Map(
          headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId).build(),
          headColumnsDefConfig.sequenceNrColumnName    -> AttributeValue.builder().n(lastSequenceNr.toString).build(),
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

  /** Reads the snapshot table from the `j5ik2o.dynamo-db-snapshot` config when it is present, and applies the command
    * line overrides. `--without-snapshots` skips the snapshot table entirely.
    */
  private def resolveSnapshotTable(args: Array[String], config: Config): Option[SnapshotTable] =
    if (args.contains("--without-snapshots")) {
      None
    } else {
      val configured = Try(config.getConfig("j5ik2o.dynamo-db-snapshot")).toOption
        .map(SnapshotTable.fromConfig)
        .getOrElse(SnapshotTable.default)
      Some(
        configured.copy(
          tableName = optionValue(args, "--snapshot-table").getOrElse(configured.tableName),
          persistenceIdColumnName =
            optionValue(args, "--snapshot-persistence-id-column").getOrElse(configured.persistenceIdColumnName),
          sequenceNrColumnName =
            optionValue(args, "--snapshot-sequence-nr-column").getOrElse(configured.sequenceNrColumnName)
        )
      )
    }

  private def optionValue(args: Array[String], name: String): Option[String] = {
    val index = args.indexOf(name)
    if (index >= 0 && index + 1 < args.length) Some(args(index + 1)) else None
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
            s"${prefix}the snapshot table was not scanned (not configured, not found, or --without-snapshots);" +
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
