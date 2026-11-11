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

  private[journal] sealed trait Source
  private[journal] case object Journal extends Source
  private[journal] case object Snapshot extends Source
  private[journal] sealed trait Progress
  private[journal] final case class SourceStarted(source: Source) extends Progress
  private[journal] final case class ScanPage(
      source: Source,
      pages: Long,
      rowsObserved: Long,
      distinctIdsObserved: Long,
      sourceExhausted: Boolean
  ) extends Progress
  private[journal] case object SnapshotUnavailable extends Progress
  private[journal] case object SnapshotSkipped extends Progress
  private[journal] final case class PhaseStarted(phase: String) extends Progress

  private[journal] def renderProgress(progress: Progress, execute: Boolean): String = {
    def sourceName(source: Source): String = source match {
      case Journal  => "journal"
      case Snapshot => "snapshot"
    }
    val detail = progress match {
      case SourceStarted(source) => s"phase=scan source=${sourceName(source)} started"
      case ScanPage(source, pages, rows, ids, exhausted) =>
        s"phase=scan source=${sourceName(source)} pages=$pages rows-observed=$rows ids-observed=$ids source-exhausted=$exhausted"
      case SnapshotUnavailable => "phase=scan source=snapshot unavailable"
      case SnapshotSkipped     => "phase=scan source=snapshot skipped"
      case PhaseStarted(phase) => s"phase=$phase started"
    }
    val prefix = if (execute) "" else "[dry-run] "
    s"${prefix}JournalHead migration progress $detail"
  }

  private[journal] def notifyProgress(emit: Progress => Unit, progress: Progress): Unit =
    try emit(progress)
    catch {
      // Progress output is advisory; an unavailable sink must not interrupt a migration.
      case _: IllegalStateException => ()
    }

  private[journal] def progressSink(args: Array[String], execute: Boolean): Progress => Unit =
    if (args.contains("--progress")) progress => Console.err.println(renderProgress(progress, execute))
    else _ => ()

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
  ): Result = runObserved(
    syncClient,
    journalTableName,
    journalColumnsDefConfig,
    headConfig,
    execute,
    snapshotTable,
    _ => (),
    None,
    (_, _) => ()
  )

  private[journal] def resolvePersistenceIds(args: Array[String]): Option[Set[PersistenceId]] = {
    val ids    = Set.newBuilder[PersistenceId]
    val flags  = Set("--execute", "--without-snapshots", "--progress")
    val values = Set("--snapshot-table", "--snapshot-persistence-id-column", "--snapshot-sequence-nr-column")
    var index  = 0
    while (index < args.length) {
      val name = args(index)
      if (flags(name)) index += 1
      else if (name == "--persistence-id" || values(name)) {
        val value = args
          .lift(index + 1).filterNot(_.startsWith("--"))
          .getOrElse(throw new IllegalArgumentException(s"$name requires a value"))
        if (name == "--persistence-id") ids += PersistenceId(value)
        index += 2
      } else throw new IllegalArgumentException(s"unknown migration argument: $name")
    }
    val result = ids.result()
    if (result.isEmpty) None else Some(result)
  }

  private[journal] def runObserved(
      syncClient: JavaDynamoDbSyncClient,
      journalTableName: String,
      journalColumnsDefConfig: JournalColumnsDefConfig,
      headConfig: JournalHeadConfig,
      execute: Boolean,
      snapshotTable: Option[SnapshotTable],
      emit: Progress => Unit,
      selectedIds: Option[Set[PersistenceId]],
      reportSelection: (Int, Int) => Unit
  ): Result = {
    val allJournalVersions = scanHighestSequenceNrs(
      syncClient,
      journalTableName,
      journalColumnsDefConfig.persistenceIdColumnName,
      journalColumnsDefConfig.sequenceNrColumnName,
      Journal,
      emit
    )
    val allSnapshotVersions = snapshotTable match {
      case Some(table) => scanSnapshotHighestSequenceNrs(syncClient, table, emit)
      case None =>
        notifyProgress(emit, SnapshotSkipped)
        None
    }
    val journalVersions =
      selectedIds.fold(allJournalVersions)(ids => allJournalVersions.filter { case (id, _) => ids(id) })
    val snapshotVersions = allSnapshotVersions.map(versions =>
      selectedIds.fold(versions)(ids => versions.filter { case (id, _) => ids(id) })
    )
    val persistenceIds =
      journalVersions.keySet ++ snapshotVersions.getOrElse(Map.empty[PersistenceId, SequenceNumber]).keySet
    selectedIds.foreach(ids => reportSelection(ids.size, persistenceIds.size))
    notifyProgress(emit, PhaseStarted("read-heads"))
    val currentHeads = persistenceIds.iterator
      .flatMap(persistenceId => readHeadSequenceNr(syncClient, headConfig, persistenceId).map(persistenceId -> _))
      .toMap
    notifyProgress(emit, PhaseStarted("plan"))
    val plan = MigrationPlanner.plan(journalVersions, snapshotVersions, currentHeads)
    if (execute) {
      notifyProgress(emit, PhaseStarted("execute"))
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
      snapshotTable: SnapshotTable,
      emit: Progress => Unit
  ): Option[Map[PersistenceId, SequenceNumber]] =
    try {
      Some(
        scanHighestSequenceNrs(
          syncClient,
          snapshotTable.tableName,
          snapshotTable.persistenceIdColumnName,
          snapshotTable.sequenceNrColumnName,
          Snapshot,
          emit
        )
      )
    } catch {
      case _: ResourceNotFoundException =>
        notifyProgress(emit, SnapshotUnavailable)
        None
    }

  private def scanHighestSequenceNrs(
      syncClient: JavaDynamoDbSyncClient,
      tableName: String,
      persistenceIdColumnName: String,
      sequenceNrColumnName: String,
      source: Source,
      emit: Progress => Unit
  ): Map[PersistenceId, SequenceNumber] = {
    notifyProgress(emit, SourceStarted(source))
    var pages              = 0L
    var rows               = 0L
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
      pages += 1L
      rows += response.items().size().toLong
      notifyProgress(emit, ScanPage(source, pages, rows, result.size.toLong, !hasMoreItemsToRead))
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
    val selectedIds = resolvePersistenceIds(args)
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
        val result = runObserved(
          client,
          pluginConfig.tableName,
          pluginConfig.columnsDefConfig,
          pluginConfig.headConfig,
          execute,
          resolveSnapshotTable(args, config),
          progressSink(args, execute),
          selectedIds,
          (requested, observed) => {
            val prefix = if (execute) "" else "[dry-run] "
            Console.err.println(
              s"${prefix}JournalHead migration selection requested=$requested observed-in-sources=$observed" +
              s" not-observed-in-sources=${requested - observed}"
            )
          }
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
