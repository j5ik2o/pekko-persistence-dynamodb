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
import org.apache.pekko.actor.ActorSystem
import software.amazon.awssdk.services.dynamodb.model.{ AttributeValue, PutItemRequest, ScanRequest }
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => JavaDynamoDbSyncClient }

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Creates the JournalHead items of the aggregates that were written before `head.enabled` was turned on.
  *
  * The tool scans the journal table and, for every persistence id, writes a JournalHead item that holds the last
  * sequence number of that persistence id and an empty event list. An empty event list is correct for the migration:
  * the read model is assumed to be up to date at the moment head mode is enabled, and every later write carries its own
  * events. The journal table remains the source for rebuilding a read model from scratch.
  *
  * The writes are conditional (`attribute_not_exists`), so the tool can be run more than once: the JournalHead items
  * that already exist are left untouched.
  */
object JournalHeadMigration {

  final case class Result(scannedPersistenceIds: Int, createdJournalHeads: Int, existingJournalHeads: Int)

  def run(
      syncClient: JavaDynamoDbSyncClient,
      journalTableName: String,
      journalColumnsDefConfig: JournalColumnsDefConfig,
      headConfig: JournalHeadConfig,
      dryRun: Boolean
  ): Result = {
    val highestSequenceNrs = scanHighestSequenceNrs(syncClient, journalTableName, journalColumnsDefConfig)
    var created            = 0
    var existing           = 0
    highestSequenceNrs.foreach { case (persistenceId, lastSequenceNr) =>
      if (dryRun) {
        created += 1
      } else if (putJournalHeadIfAbsent(syncClient, headConfig, persistenceId, lastSequenceNr)) {
        created += 1
      } else {
        existing += 1
      }
    }
    Result(highestSequenceNrs.size, created, existing)
  }

  private def scanHighestSequenceNrs(
      syncClient: JavaDynamoDbSyncClient,
      journalTableName: String,
      journalColumnsDefConfig: JournalColumnsDefConfig
  ): Map[String, Long] = {
    val result             = mutable.Map.empty[String, Long]
    var exclusiveStartKey  = Option.empty[java.util.Map[String, AttributeValue]]
    var hasMoreItemsToRead = true
    while (hasMoreItemsToRead) {
      val requestBuilder = ScanRequest
        .builder()
        .tableName(journalTableName)
        .projectionExpression("#persistenceId, #sequenceNr")
        .expressionAttributeNames(
          Map(
            "#persistenceId" -> journalColumnsDefConfig.persistenceIdColumnName,
            "#sequenceNr"    -> journalColumnsDefConfig.sequenceNrColumnName
          ).asJava
        )
      exclusiveStartKey.foreach(key => requestBuilder.exclusiveStartKey(key))
      val response = syncClient.scan(requestBuilder.build())
      response.items().asScala.foreach { item =>
        val persistenceId  = item.get(journalColumnsDefConfig.persistenceIdColumnName).s()
        val sequenceNr     = item.get(journalColumnsDefConfig.sequenceNrColumnName).n().toLong
        val currentHighest = result.getOrElse(persistenceId, 0L)
        if (sequenceNr > currentHighest) result.update(persistenceId, sequenceNr)
      }
      exclusiveStartKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
      hasMoreItemsToRead = exclusiveStartKey.nonEmpty
    }
    result.toMap
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

  def main(args: Array[String]): Unit = {
    val dryRun = args.contains("--dry-run")
    val system = ActorSystem("journal-head-migration")
    try {
      val pluginConfig =
        JournalPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
      require(
        pluginConfig.clientConfig.clientVersion == ClientVersion.V2,
        "the JournalHead migration tool requires client-version = \"v2\""
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
          dryRun
        )
        val prefix = if (dryRun) "[dry-run] " else ""
        println(
          s"${prefix}JournalHead migration finished: scanned ${result.scannedPersistenceIds} persistence ids," +
          s" created ${result.createdJournalHeads} JournalHead items," +
          s" skipped ${result.existingJournalHeads} existing JournalHead items"
        )
      } finally {
        client.close()
      }
    } finally {
      system.terminate()
    }
  }

}
