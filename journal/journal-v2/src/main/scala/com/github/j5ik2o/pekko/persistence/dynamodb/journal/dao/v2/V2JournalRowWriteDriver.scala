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
import com.github.j5ik2o.pekko.persistence.dynamodb.client.v2.StreamWriteClient
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v2.{ V2ClientAdapter, V2OperationRenderer, V2RenderedOperation }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.{ JournalRowWriteDriver, PersistenceIdWithSeqNr }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.store.JournalRowStore
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.write.WriteRejection
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import software.amazon.awssdk.services.dynamodb.model.{ TransactWriteItemsRequest, TransactionCanceledException }
import software.amazon.awssdk.services.dynamodb.{
  DynamoDbAsyncClient => JavaDynamoDbAsyncClient,
  DynamoDbClient => JavaDynamoDbSyncClient
}

import scala.compat.java8.OptionConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

import java.io.IOException

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

  private val streamClient =
    new StreamWriteClient(pluginContext, asyncClient, syncClient, pluginConfig.writeBackoffConfig)

  private def clientVariant: ClientVariant =
    ClientVariant
      .fromClientVersion(pluginConfig.clientConfig.clientVersion)
      .getOrElse(
        throw new IllegalArgumentException(
          s"unsupported client version: ${pluginConfig.clientConfig.clientVersion}"
        )
      )

  private val store = JournalRowStore.fromPluginContext(
    pluginContext,
    new V2ClientAdapter(clientVariant, asyncClient, syncClient, pluginExecutor)
  )

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
    Flow[PersistenceIdWithSeqNr]
      .flatMapConcat(persistenceIdWithSeqNr => store.delete(Seq(persistenceIdWithSeqNr)))
      .withAttributes(logLevels)

  override def updateMessage(journalRow: JournalRow): Source[Unit, NotUsed] =
    store.update(journalRow).withAttributes(logLevels)

  override def putJournalRowsWithHeadFlow: Flow[JournalHeadWriteRequest, Long, NotUsed] =
    Flow[JournalHeadWriteRequest]
      .flatMapConcat { request =>
        // A failure to build the transaction is a rejected write, not a thrown exception: a throw here runs inside a
        // stream stage and aborts every later write on the queue.
        createTransactWriteItemsRequest(request) match {
          case Left(rejection) =>
            Source.failed(rejection.toJournalHeadException)
          case Right(transactWriteItemsRequest) =>
            Source
              .single(transactWriteItemsRequest)
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
        }
      }.withAttributes(logLevels)

  /** The async client fails the future with the SDK exception, sometimes wrapped in another exception. */
  @scala.annotation.tailrec
  private def findTransactionCanceledException(ex: Throwable): Option[TransactionCanceledException] =
    ex match {
      case e: TransactionCanceledException              => Some(e)
      case e if e.getCause != null && (e.getCause ne e) => findTransactionCanceledException(e.getCause)
      case _                                            => None
    }

  private def createTransactWriteItemsRequest(
      request: JournalHeadWriteRequest
  ): Either[WriteRejection, TransactWriteItemsRequest] = {
    val headConfig    = pluginConfig.headConfig
    val headColumns   = headConfig.columnsDefConfig
    val persistenceId = request.head.persistenceId.asString
    val keyedRows: Either[WriteRejection, Vector[(JournalRow, RowKey)]] =
      request.rows.foldLeft[Either[WriteRejection, Vector[(JournalRow, RowKey)]]](Right(Vector.empty)) {
        case (Right(acc), row) =>
          keyOf(row).map(key => acc :+ (row -> key))
        case (left @ Left(_), _) =>
          left
      }
    keyedRows.flatMap { rows =>
      val journalWrites = rows.map { case (row, key) =>
        TransactItem.Put(
          store.schema.table,
          store.rowCodec.encode(row, key),
          Some(
            Condition.And(
              Condition.AttributeNotExists(store.schema.partitionKey),
              Condition.AttributeNotExists(store.schema.sortKey)
            )
          )
        )
      }
      val headItem = AttributeMap.of(
        AttributeName(headColumns.persistenceIdColumnName) -> DdbValue.Str(request.head.persistenceId.asString),
        AttributeName(headColumns.sequenceNrColumnName) -> DdbValue.Num(BigDecimal(request.head.sequenceNumber.value)),
        AttributeName(headColumns.eventsColumnName) -> DdbValue.L(
          request.head.events.map { event =>
            DdbValue.M(
              Vector(
                headColumns.eventSequenceNrColumnName -> DdbValue.Num(BigDecimal(event.sequenceNumber.value)),
                headColumns.eventMessageColumnName    -> DdbValue.Bin(event.message.toVector)
              )
            )
          }.toVector
        )
      )
      // The head is written only if it is behind the first sequence number of this write, or if it does not exist
      // and this write starts at sequence number 1. A head that is ahead (a rewind, or another writer that already
      // wrote these sequence numbers) fails. The gap is allowed on purpose: Pekko does not rewind the sequence
      // number of a rejected write, so the write after a rejection starts above the head by more than one.
      //
      // This is not optimistic locking. It checks that the assumption "there is only one writer per aggregate"
      // (kept by Cluster Sharding and Split Brain Resolver) still holds.
      val firstSequenceNr = request.rows.head.sequenceNumber.value
      val first           = DdbValue.Num(BigDecimal(firstSequenceNr))
      val sequenceNr      = AttributeName(headColumns.sequenceNrColumnName)
      val headWrite = TransactItem.Put(
        TableName(headConfig.tableName),
        headItem,
        Some(
          Condition.Or(
            Condition.And(
              Condition.AttributeNotExists(sequenceNr),
              Condition.Compare(
                Operand.Literal(first),
                CompareOp.Eq,
                Operand.Literal(DdbValue.Num(BigDecimal(1)))
              )
            ),
            Condition.Compare(Operand.Column(sequenceNr), CompareOp.Lt, Operand.Literal(first))
          )
        )
      )
      V2OperationRenderer.render(Operation.TransactWrite(journalWrites.toVector :+ headWrite)) match {
        case Right(V2RenderedOperation.TransactWrite(transactWriteItemsRequest)) =>
          Right(transactWriteItemsRequest)
        case Right(other) =>
          Left(WriteRejection.UnrenderableWrite(persistenceId, s"unexpected rendered operation: $other"))
        case Left(error) =>
          Left(WriteRejection.UnrenderableWrite(persistenceId, s"cannot render the JournalHead write: ${error.detail}"))
      }
    }
  }

  private def keyOf(row: JournalRow): Either[WriteRejection, RowKey] =
    store.keyCodec
      .keyOf(row.persistenceId, row.sequenceNumber, partitionKeyResolver, sortKeyResolver)
      .left
      .map(error =>
        WriteRejection.UnkeyableRow(
          row.persistenceId.asString,
          s"cannot build the journal row key: $error"
        )
      )

  /** Maps a cancelled transaction to the failure reason the actor should see. The JournalHead table is read back so
    * that "the head has not been migrated yet" can be told apart from "the head did not continue from this write".
    */
  private def classifyTransactionCancellation(
      request: JournalHeadWriteRequest,
      ex: TransactionCanceledException
  ): Future[Option[JournalHeadException]] = {
    implicit val ec: ExecutionContext = pluginExecutor
    val reasons                       = ex.cancellationReasons.asScala.toIndexedSeq
    val rowCount                      = request.rows.size
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
      store.headSequenceNr(request.head.persistenceId).runWith(Sink.head).map {
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
    store.headSequenceNr(persistenceId)

  override def singlePutJournalRowFlow: Flow[JournalRow, Long, NotUsed] =
    Flow[JournalRow]
      .flatMapConcat(journalRow => store.write(Seq(journalRow)))
      .withAttributes(logLevels)

  override def multiDeleteJournalRowsFlow: Flow[Seq[PersistenceIdWithSeqNr], Long, NotUsed] =
    Flow[Seq[PersistenceIdWithSeqNr]]
      .flatMapConcat(persistenceIdWithSeqNrs => store.delete(persistenceIdWithSeqNrs))
      .withAttributes(logLevels)

  override def multiPutJournalRowsFlow: Flow[Seq[JournalRow], Long, NotUsed] =
    Flow[Seq[JournalRow]]
      .flatMapConcat(journalRows => store.write(journalRows))
      .withAttributes(logLevels)
}
