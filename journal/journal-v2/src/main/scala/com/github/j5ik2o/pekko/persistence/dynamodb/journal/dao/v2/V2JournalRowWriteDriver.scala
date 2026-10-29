/*
 * Copyright 2019 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v2

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Flow, Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.client.v2.{ StreamReadClient, StreamWriteClient }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.{ JournalRowWriteDriver, PersistenceIdWithSeqNr }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model._
import software.amazon.awssdk.services.dynamodb.{
  DynamoDbAsyncClient => JavaDynamoDbAsyncClient,
  DynamoDbClient => JavaDynamoDbSyncClient
}

import java.io.IOException
import scala.compat.java8.OptionConverters._
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success }

final class V2JournalRowWriteDriver(
    val pluginContext: JournalPluginContext,
    val asyncClient: Option[JavaDynamoDbAsyncClient],
    val syncClient: Option[JavaDynamoDbSyncClient]
) extends JournalRowWriteDriver {
  override def system: ActorSystem = pluginContext.system

  private implicit val mat: Materializer = SystemMaterializer(system).materializer

  import pluginContext._
  (asyncClient, syncClient) match {
    case (None, None) =>
      throw new IllegalArgumentException("aws clients is both None")
    case _ =>
  }

  private val logger = LoggerFactory.getLogger(getClass)

  private val streamClient =
    new StreamWriteClient(pluginContext, asyncClient, syncClient, pluginConfig.writeBackoffConfig)

  private val streamReadClient =
    new StreamReadClient(pluginContext, asyncClient, syncClient, pluginConfig.readBackoffConfig)

  private val readDriver: V2JournalRowReadDriver = new V2JournalRowReadDriver(
    pluginContext,
    asyncClient,
    syncClient
  )

  override def dispose(): Unit = {
    (asyncClient, syncClient) match {
      case (Some(a), _) => a.close()
      case (_, Some(s)) => s.close()
      case _            =>
    }
  }

  override def getJournalRows(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber,
      toSequenceNr: SequenceNumber,
      max: Long,
      deleted: Option[Boolean] = Some(false)
  ): Source[JournalRow, NotUsed] = {
    readDriver.getJournalRows(persistenceId, fromSequenceNr, toSequenceNr, max, deleted)
  }

  override def getJournalRows(
      persistenceId: PersistenceId,
      toSequenceNr: SequenceNumber,
      deleted: Boolean
  ): Source[Seq[JournalRow], NotUsed] =
    readDriver.getJournalRows(persistenceId, toSequenceNr, deleted)

  override def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber],
      deleted: Option[Boolean]
  ): Source[Option[Long], NotUsed] =
    readDriver.highestSequenceNr(persistenceId, fromSequenceNr, deleted)

  override def singleDeleteJournalRowFlow: Flow[PersistenceIdWithSeqNr, Long, NotUsed] =
    Flow[PersistenceIdWithSeqNr].flatMapConcat { persistenceIdWithSeqNr =>
      val pkey =
        partitionKeyResolver
          .resolve(persistenceIdWithSeqNr.persistenceId, persistenceIdWithSeqNr.sequenceNumber).asString
      val skey =
        sortKeyResolver.resolve(persistenceIdWithSeqNr.persistenceId, persistenceIdWithSeqNr.sequenceNumber).asString
      val deleteRequest = DeleteItemRequest
        .builder().key(
          Map(
            pluginConfig.columnsDefConfig.partitionKeyColumnName -> AttributeValue.builder().s(pkey).build(),
            pluginConfig.columnsDefConfig.sortKeyColumnName      -> AttributeValue.builder().s(skey).build()
          ).asJava
        ).build()
      Source.single(deleteRequest).via(streamClient.deleteItemFlow).flatMapConcat { response =>
        if (response.sdkHttpResponse().isSuccessful) {
          Source.single(1L)
        } else {
          val statusCode = response.sdkHttpResponse().statusCode()
          val statusText = response.sdkHttpResponse().statusText()
          Source.failed(new IOException(s"statusCode: $statusCode" + statusText.asScala.fold("")(s => s", $s")))
        }
      }
    }

  override def updateMessage(journalRow: JournalRow): Source[Unit, NotUsed] = {
    val pkey = journalRow.partitionKey(partitionKeyResolver).asString
    val skey = journalRow.sortKey(sortKeyResolver).asString
    val updateRequest = UpdateItemRequest
      .builder()
      .tableName(pluginConfig.tableName).key(
        Map(
          pluginConfig.columnsDefConfig.partitionKeyColumnName -> AttributeValue
            .builder()
            .s(pkey).build(),
          pluginConfig.columnsDefConfig.sortKeyColumnName -> AttributeValue
            .builder()
            .s(skey).build()
        ).asJava
      ).attributeUpdates(
        (Map(
          pluginConfig.columnsDefConfig.messageColumnName -> AttributeValueUpdate
            .builder()
            .action(AttributeAction.PUT).value(
              AttributeValue.builder().b(SdkBytes.fromByteArray(journalRow.message)).build()
            ).build(),
          pluginConfig.columnsDefConfig.orderingColumnName ->
          AttributeValueUpdate
            .builder()
            .action(AttributeAction.PUT).value(
              AttributeValue.builder().n(journalRow.ordering.toString).build()
            ).build(),
          pluginConfig.columnsDefConfig.deletedColumnName -> AttributeValueUpdate
            .builder()
            .action(AttributeAction.PUT).value(
              AttributeValue.builder().bool(journalRow.deleted).build()
            ).build()
        ) ++ journalRow.tags
          .map { tag =>
            Map(
              pluginConfig.columnsDefConfig.tagsColumnName -> AttributeValueUpdate
                .builder()
                .action(AttributeAction.PUT).value(AttributeValue.builder().s(tag).build()).build()
            )
          }.getOrElse(Map.empty)).asJava
      ).build()
    Source
      .single(updateRequest)
      .via(streamClient.updateItemFlow).flatMapConcat { response =>
        if (response.sdkHttpResponse().isSuccessful) {
          Source.single(())
        } else {
          val statusCode = response.sdkHttpResponse().statusCode()
          val statusText = response.sdkHttpResponse().statusText()
          Source.failed(new IOException(s"statusCode: $statusCode" + statusText.asScala.fold("")(s => s", $s")))
        }
      }
  }.withAttributes(logLevels)

  override def putJournalRowsWithHeadFlow: Flow[JournalHeadWriteRequest, Long, NotUsed] =
    Flow[JournalHeadWriteRequest]
      .flatMapConcat { request =>
        Source
          .single(createTransactWriteItemsRequest(request))
          .via(streamClient.transactWriteItemsFlow)
          .flatMapConcat { response =>
            if (response.sdkHttpResponse().isSuccessful) {
              Source.single(request.rows.size.toLong)
            } else {
              val statusCode = response.sdkHttpResponse().statusCode()
              val statusText = response.sdkHttpResponse().statusText()
              Source.failed(new IOException(s"statusCode: $statusCode" + statusText.asScala.fold("")(s => s", $s")))
            }
          }.recoverWithRetries(
            1,
            {
              case ex if findTransactionCanceledException(ex).isDefined =>
                val canceled = findTransactionCanceledException(ex).get
                Source.future(classifyTransactionCancellation(request, canceled)).flatMapConcat {
                  case Some(journalHeadException) => Source.failed(journalHeadException)
                  case None                       => Source.failed(ex)
                }
            }
          )
      }.withAttributes(logLevels)

  /** The async client fails the future with the SDK exception, sometimes wrapped in another exception. */
  @scala.annotation.tailrec
  private def findTransactionCanceledException(ex: Throwable): Option[TransactionCanceledException] =
    ex match {
      case e: TransactionCanceledException              => Some(e)
      case e if e.getCause != null && (e.getCause ne e) => findTransactionCanceledException(e.getCause)
      case _                                            => None
    }

  private def createTransactWriteItemsRequest(request: JournalHeadWriteRequest): TransactWriteItemsRequest = {
    val journalColumnsDefConfig = pluginConfig.columnsDefConfig
    val headConfig              = pluginConfig.headConfig
    val headColumnsDefConfig    = headConfig.columnsDefConfig

    val journalWrites = request.rows.map { row =>
      val pkey = partitionKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
      val skey = sortKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
      TransactWriteItem
        .builder().put(
          Put
            .builder()
            .tableName(pluginConfig.tableName)
            .item(journalItemAttributes(row, pkey, skey).asJava)
            // A journal row is written only if the key (persistenceId, seqNr) does not exist yet.
            .conditionExpression("attribute_not_exists(#pkey) AND attribute_not_exists(#skey)")
            .expressionAttributeNames(
              Map(
                "#pkey" -> journalColumnsDefConfig.partitionKeyColumnName,
                "#skey" -> journalColumnsDefConfig.sortKeyColumnName
              ).asJava
            )
            .build()
        ).build()
    }

    val head = request.head
    val headItem = Map(
      headColumnsDefConfig.persistenceIdColumnName -> AttributeValue.builder().s(head.persistenceId.asString).build(),
      headColumnsDefConfig.sequenceNrColumnName    -> AttributeValue.builder().n(head.sequenceNumber.asString).build(),
      headColumnsDefConfig.eventsColumnName -> AttributeValue
        .builder()
        .l(
          head.events.map { event =>
            AttributeValue
              .builder()
              .m(
                Map(
                  headColumnsDefConfig.eventSequenceNrColumnName -> AttributeValue
                    .builder()
                    .n(event.sequenceNumber.asString).build(),
                  headColumnsDefConfig.eventMessageColumnName -> AttributeValue
                    .builder()
                    .b(SdkBytes.fromByteArray(event.message)).build()
                ).asJava
              ).build()
          }.asJava
        ).build()
    )
    val firstSequenceNr = request.rows.head.sequenceNumber.value
    val headWrite = TransactWriteItem
      .builder().put(
        Put
          .builder()
          .tableName(headConfig.tableName)
          .item(headItem.asJava)
          // The head is written only if it is behind the first sequence number of this write, or if it
          // does not exist yet and this write starts at sequence number 1. A head that is ahead (a
          // rewind, or another writer that already wrote these sequence numbers) fails.
          //
          // The gap is allowed on purpose: Pekko does not rewind the sequence number of a rejected
          // write, so the write after a rejection starts above the head by more than one. A gap in the
          // journal is not checked when an aggregate recovers.
          //
          // This is not optimistic locking: it checks that the assumption "there is only one writer per
          // aggregate" (kept by Cluster Sharding and Split Brain Resolver) still holds. A second writer
          // that got ahead is rejected by this condition and by the journal condition.
          .conditionExpression("(attribute_not_exists(#seq) AND :first = :one) OR (#seq < :first)")
          .expressionAttributeNames(Map("#seq" -> headColumnsDefConfig.sequenceNrColumnName).asJava)
          .expressionAttributeValues(
            Map(
              ":first" -> AttributeValue.builder().n(firstSequenceNr.toString).build(),
              ":one"   -> AttributeValue.builder().n("1").build()
            ).asJava
          )
          .build()
      ).build()

    TransactWriteItemsRequest.builder().transactItems((journalWrites :+ headWrite).asJava).build()
  }

  private def journalItemAttributes(
      journalRow: JournalRow,
      pkey: String,
      skey: String
  ): Map[String, AttributeValue] = {
    val columnsDefConfig = pluginConfig.columnsDefConfig
    Map(
      columnsDefConfig.partitionKeyColumnName -> AttributeValue.builder().s(pkey).build(),
      columnsDefConfig.sortKeyColumnName      -> AttributeValue.builder().s(skey).build(),
      columnsDefConfig.persistenceIdColumnName -> AttributeValue
        .builder()
        .s(journalRow.persistenceId.asString).build(),
      columnsDefConfig.sequenceNrColumnName -> AttributeValue
        .builder()
        .n(journalRow.sequenceNumber.asString).build(),
      columnsDefConfig.orderingColumnName -> AttributeValue.builder().n(journalRow.ordering.toString).build(),
      columnsDefConfig.deletedColumnName  -> AttributeValue.builder().bool(journalRow.deleted).build(),
      columnsDefConfig.messageColumnName -> AttributeValue
        .builder()
        .b(SdkBytes.fromByteArray(journalRow.message)).build()
    ) ++ journalRow.tags
      .map { tag =>
        Map(columnsDefConfig.tagsColumnName -> AttributeValue.builder().s(tag).build())
      }.getOrElse(Map.empty)
  }

  /** Maps a cancelled transaction to the failure reason the actor should see. The JournalHead table is read back so
    * that "the head has not been migrated yet" can be told apart from "the head did not continue from this write".
    */
  private def classifyTransactionCancellation(
      request: JournalHeadWriteRequest,
      ex: TransactionCanceledException
  ): Future[Option[JournalHeadException]] = {
    implicit val ec: scala.concurrent.ExecutionContext = pluginExecutor
    val reasons                                        = ex.cancellationReasons.asScala.toIndexedSeq
    val rowCount                                       = request.rows.size
    def failedAt(index: Int): Boolean =
      reasons.lift(index).exists(reason => reason != null && "ConditionalCheckFailed" == reason.code)
    val failedJournalRowIndexes = request.rows.indices.filter(failedAt).toVector
    if (failedJournalRowIndexes.nonEmpty) {
      val sequenceNrs = failedJournalRowIndexes.map(index => request.rows(index).sequenceNumber.value)
      Future.successful(
        Some(new DuplicateJournalEventsException(request.head.persistenceId.asString, sequenceNrs))
      )
    } else if (failedAt(rowCount)) {
      val firstSequenceNr = request.rows.head.sequenceNumber.value
      readHeadSequenceNr(request.head.persistenceId).map {
        case None =>
          Some(new JournalHeadMissingException(request.head.persistenceId.asString, firstSequenceNr))
        case Some(actualSequenceNr) if actualSequenceNr >= firstSequenceNr =>
          Some(
            new JournalHeadSequenceMismatchException(
              request.head.persistenceId.asString,
              firstSequenceNr - 1,
              actualSequenceNr
            )
          )
        case Some(_) =>
          None
      }
    } else {
      Future.successful(None)
    }
  }

  override def headSequenceNr(persistenceId: PersistenceId): Source[Option[Long], NotUsed] =
    Source.future(readHeadSequenceNr(persistenceId))

  private def readHeadSequenceNr(
      persistenceId: PersistenceId
  ): Future[Option[Long]] = {
    implicit val ec: scala.concurrent.ExecutionContext = pluginExecutor
    val headConfig                                     = pluginConfig.headConfig
    val headColumns                                    = headConfig.columnsDefConfig
    val getItemRequest = GetItemRequest
      .builder()
      .tableName(headConfig.tableName)
      .key(
        Map(headColumns.persistenceIdColumnName -> AttributeValue.builder().s(persistenceId.asString).build()).asJava
      )
      .consistentRead(true)
      .build()
    Source
      .single(getItemRequest)
      .via(streamReadClient.getFlow)
      .runWith(Sink.head)
      .map { response =>
        Option(response.item)
          .flatMap(item => Option(item.get(headColumns.sequenceNrColumnName)))
          .map(_.n.toLong)
      }
  }

  override def singlePutJournalRowFlow: Flow[JournalRow, Long, NotUsed] = Flow[JournalRow].flatMapConcat { journalRow =>
    val pkey = partitionKeyResolver.resolve(journalRow.persistenceId, journalRow.sequenceNumber).asString
    val skey = sortKeyResolver.resolve(journalRow.persistenceId, journalRow.sequenceNumber).asString
    val request = PutItemRequest
      .builder().tableName(pluginConfig.tableName)
      .item(journalItemAttributes(journalRow, pkey, skey).asJava)
      .build()
    Source.single(request).via(streamClient.putItemFlow).flatMapConcat { response =>
      if (response.sdkHttpResponse().isSuccessful) {
        Source.single(1L)
      } else {
        val statusCode = response.sdkHttpResponse().statusCode()
        val statusText = response.sdkHttpResponse().statusText()
        Source.failed(new IOException(s"statusCode: $statusCode" + statusText.asScala.fold("")(s => s", $s")))
      }
    }
  }

  override def multiDeleteJournalRowsFlow: Flow[Seq[PersistenceIdWithSeqNr], Long, NotUsed] =
    Flow[Seq[PersistenceIdWithSeqNr]]
      .flatMapConcat { persistenceIdWithSeqNrs =>
        persistenceIdWithSeqNrs
          .map { case PersistenceIdWithSeqNr(pid, seqNr) => s"pid = $pid, seqNr = $seqNr" }.foreach(logger.debug)
        if (persistenceIdWithSeqNrs.isEmpty)
          Source.single(0L)
        else
          Source
            .single(persistenceIdWithSeqNrs.map { persistenceIdWithSeqNr =>
              val pkey = partitionKeyResolver
                .resolve(persistenceIdWithSeqNr.persistenceId, persistenceIdWithSeqNr.sequenceNumber).asString
              val skey = sortKeyResolver
                .resolve(persistenceIdWithSeqNr.persistenceId, persistenceIdWithSeqNr.sequenceNumber).asString
              WriteRequest
                .builder().deleteRequest(
                  DeleteRequest
                    .builder().key(
                      Map(
                        pluginConfig.columnsDefConfig.partitionKeyColumnName -> AttributeValue
                          .builder().s(pkey).build(),
                        pluginConfig.columnsDefConfig.sortKeyColumnName -> AttributeValue.builder().s(skey).build()
                      ).asJava
                    ).build()
                ).build()
            }).flatMapConcat { requestItems =>
              Source
                .single(
                  BatchWriteItemRequest
                    .builder().requestItems(Map(pluginConfig.tableName -> requestItems.asJava).asJava).build()
                )
                .via(streamClient.recursiveBatchWriteItemFlow).map { _ =>
                  requestItems.size.toLong
                }
            }
      }.withAttributes(logLevels)

  override def multiPutJournalRowsFlow: Flow[Seq[JournalRow], Long, NotUsed] = Flow[Seq[JournalRow]].flatMapConcat {
    journalRows =>
      if (journalRows.isEmpty)
        Source.single(0L)
      else {
        require(journalRows.size == journalRows.toSet.size, "journalRows: keys contains duplicates")
        val journalRowWithPKeyWithSKeys = journalRows.map { journalRow =>
          val pkey = partitionKeyResolver
            .resolve(journalRow.persistenceId, journalRow.sequenceNumber).asString
          val skey = sortKeyResolver.resolve(journalRow.persistenceId, journalRow.sequenceNumber).asString
          (journalRow, pkey, skey)
        }
        logger.debug(
          s"multiPutJournalRowsFlow: journalRowWithPKeyWithSKeys = ${journalRowWithPKeyWithSKeys.mkString("\n", ",\n", "\n")}"
        )
        require(
          journalRowWithPKeyWithSKeys.map { case (_, p, s) => (p, s) }.toSet.size == journalRows.size,
          "journalRowWithPKeyWithSKeys: keys contains duplicates"
        )

        Source
          .single(journalRowWithPKeyWithSKeys.map { case (journalRow, pkey, skey) =>
            val pid      = journalRow.persistenceId.asString
            val seqNr    = journalRow.sequenceNumber.asString
            val ordering = journalRow.ordering.toString
            val deleted  = journalRow.deleted
            val message  = SdkBytes.fromByteArray(journalRow.message)
            val tagsOpt  = journalRow.tags
            WriteRequest
              .builder().putRequest(
                PutRequest
                  .builder()
                  .item(
                    (Map(
                      pluginConfig.columnsDefConfig.partitionKeyColumnName -> AttributeValue
                        .builder()
                        .s(pkey).build(),
                      pluginConfig.columnsDefConfig.sortKeyColumnName -> AttributeValue
                        .builder()
                        .s(skey).build(),
                      pluginConfig.columnsDefConfig.persistenceIdColumnName -> AttributeValue
                        .builder()
                        .s(pid).build(),
                      pluginConfig.columnsDefConfig.sequenceNrColumnName -> AttributeValue
                        .builder()
                        .n(seqNr).build(),
                      pluginConfig.columnsDefConfig.orderingColumnName -> AttributeValue
                        .builder()
                        .n(ordering).build(),
                      pluginConfig.columnsDefConfig.deletedColumnName -> AttributeValue
                        .builder().bool(deleted).build(),
                      pluginConfig.columnsDefConfig.messageColumnName -> AttributeValue
                        .builder().b(message).build()
                    ) ++ tagsOpt
                      .map { tags =>
                        Map(
                          pluginConfig.columnsDefConfig.tagsColumnName -> AttributeValue
                            .builder().s(tags).build()
                        )
                      }.getOrElse(Map.empty)).asJava
                  ).build()
              ).build()
          }).flatMapConcat { requestItems =>
            Source
              .single(
                BatchWriteItemRequest
                  .builder().requestItems(Map(pluginConfig.tableName -> requestItems.asJava).asJava).build()
              )
              .via(streamClient.recursiveBatchWriteItemFlow).map { _ =>
                requestItems.size.toLong
              }
          }.withAttributes(logLevels)
      }

  }

}
