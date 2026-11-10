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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.store

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.scaladsl.{ RestartSource, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadColumnsDefConfig,
  JournalHeadConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  DynamoDBItemSizeCalculator,
  JournalHeadAsyncUpdateEventUnsupportedException,
  JournalHeadConcurrentUpdateException,
  JournalHeadEvent,
  JournalHeadLimits,
  JournalHeadMissingException,
  JournalHeadRow,
  JournalHeadRowUpdateRejectedException,
  JournalRowUpdateRejection
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema.{ JournalKeyCodec, JournalRowCodec, JournalSchema }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.write.{ JournalWritePlan, WriteRejection }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalPluginContext,
  JournalRow,
  PartitionKeyResolver,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.typesafe.config.ConfigFactory

import java.util.concurrent.{ ThreadLocalRandom, TimeUnit }
import scala.concurrent.duration.FiniteDuration

final class JournalRowStore(
    val schema: JournalSchema,
    partitionKeyResolver: PartitionKeyResolver,
    sortKeyResolver: SortKeyResolver,
    adapter: DdbClientAdapter,
    queryBatchSize: Int,
    consistentRead: Boolean,
    batchWriteLimit: Int,
    readBackoffConfig: BackoffConfig,
    writeBackoffConfig: BackoffConfig,
    headConfig: Option[JournalHeadConfig]
) {

  private val UpdateAttempts: Int = 3

  private val journalColumns: JournalColumnsDefConfig = JournalColumnsDefConfig(
    sourceConfig = ConfigFactory.empty(),
    partitionKeyColumnName = schema.partitionKey.value,
    sortKeyColumnName = schema.sortKey.value,
    persistenceIdColumnName = schema.persistenceId.value,
    sequenceNrColumnName = schema.sequenceNr.value,
    deletedColumnName = schema.deleted.value,
    messageColumnName = schema.message.value,
    orderingColumnName = schema.ordering.value,
    tagsColumnName = schema.tags.value
  )

  val rowCodec: JournalRowCodec = new JournalRowCodec(schema)
  val keyCodec: JournalKeyCodec = new JournalKeyCodec(schema)

  private val headTable: Option[HeadTable] = headConfig.filter(_.enabled).map { head =>
    val columns  = head.columnsDefConfig
    val idColumn = AttributeName(columns.persistenceIdColumnName)
    new HeadTable(
      TableSpec(
        table = TableName(head.tableName),
        columns = Vector(Column(idColumn, AttributeKind.S, Presence.Required)),
        primaryKey = PrimaryKey(idColumn, None),
        indexes = Vector.empty,
        stream = Some(StreamView.NewImage)
      ),
      idColumn,
      AttributeName(columns.sequenceNrColumnName),
      AttributeName(columns.eventsColumnName),
      AttributeName(JournalHeadColumnsDefConfig.revisionColumnName),
      columns.eventSequenceNrColumnName,
      columns.eventMessageColumnName,
      columns
    )
  }

  def read(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber],
      toSequenceNr: SequenceNumber,
      max: Option[Long],
      deleted: Option[Boolean]
  ): Source[JournalRow, NotUsed] = {
    if (max.contains(0L) || fromSequenceNr.exists(_.value > toSequenceNr.value)) {
      Source.empty
    } else {
      queryPages(readQuery(persistenceId, fromSequenceNr, toSequenceNr, deleted), max).map(decodeOrThrow)
    }
  }

  def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber],
      deleted: Option[Boolean]
  ): Source[Option[Long], NotUsed] = {
    val rangeKey = fromSequenceNr.map { from =>
      schema.sequenceNr -> (SortKeyCondition.Ge(DdbValue.Num(BigDecimal(from.value))): SortKeyCondition)
    }
    val query = Operation.Query(
      table = schema.table,
      index = Some(schema.journalRowsIndex),
      hashKey = schema.persistenceId -> DdbValue.Str(persistenceId.asString),
      rangeKey = rangeKey,
      filter = deletedFilter(deleted),
      limit = Some(if (deleted.isDefined) Int.MaxValue else 1),
      consistentRead = consistentRead,
      scanForward = false
    )
    run(query, readBackoffConfig).map {
      case OperationResult.Items(values, _) =>
        values.headOption
          .flatMap(_.get(schema.sequenceNr))
          .collect { case DdbValue.Num(value) => value.toLong }
      case other =>
        throw new IllegalStateException(s"unexpected result for a query: $other")
    }
  }

  def write(rows: Seq[JournalRow]): Source[Long, NotUsed] = {
    if (rows.isEmpty) {
      Source.single(0L)
    } else {
      val entries = rows.map(row => row -> keyOf(row.persistenceId, row.sequenceNumber))
      if (entries.map { case (_, key) => key }.distinct.size != entries.size) {
        // Two rows that resolve to the same key would silently collapse in one batch. Report it as a rejected write
        // instead of throwing: a throw here runs inside a stream stage and aborts every later write on the queue.
        val persistenceId = rows.head.persistenceId.asString
        val keys          = entries.map { case (_, key) => key.toString }
        Source.failed(WriteRejection.DuplicateJournalKeys(persistenceId, keys).toJournalHeadException)
      } else if (entries.size == 1) {
        val (row, key) = entries.head
        run(Operation.Put(schema.table, rowCodec.encode(row, key)), writeBackoffConfig).map(expectWritten)
      } else {
        Source(entries.grouped(batchWriteLimit).toVector)
          .flatMapConcat { batch =>
            runBatch(
              Operation.BatchWrite(
                schema.table,
                batch.map { case (row, key) => rowCodec.encode(row, key) }.toVector,
                Vector.empty
              )
            )
          }
          .map(expectWritten)
          .fold(0L)(_ + _)
      }
    }
  }

  def delete(keys: Seq[PersistenceIdWithSeqNr]): Source[Long, NotUsed] = {
    if (keys.isEmpty) {
      Source.single(0L)
    } else if (keys.size == 1) {
      val key = keys.head
      run(Operation.Delete(schema.table, keyOf(key.persistenceId, key.sequenceNumber)), writeBackoffConfig)
        .map(expectWritten)
    } else {
      Source(keys.grouped(batchWriteLimit).toVector)
        .flatMapConcat { batch =>
          val deletes = batch.map(key => keyOf(key.persistenceId, key.sequenceNumber)).toVector
          runBatch(Operation.BatchWrite(schema.table, Vector.empty, deletes))
        }
        .map(expectWritten)
        .fold(0L)(_ + _)
    }
  }

  /** Rewrites one journal row. With `head.enabled`, the JournalHead event of the same sequence number is rewritten in
    * the same transaction, so the journal and the head stream cannot disagree about the event.
    */
  def update(row: JournalRow): Source[Unit, NotUsed] =
    headTable match {
      case None       => updateRow(row)
      case Some(head) => updateRowAndHead(head, row)
    }

  private def updateRow(row: JournalRow): Source[Unit, NotUsed] =
    run(
      Operation.Update(
        schema.table,
        keyOf(row.persistenceId, row.sequenceNumber),
        rowContentUpdates(row) :+ AttributeUpdate.Set(schema.deleted, DdbValue.Bool(row.deleted))
      ),
      writeBackoffConfig
    ).map { result =>
      expectWritten(result)
      ()
    }

  private def rowContentUpdates(row: JournalRow): Vector[AttributeUpdate] =
    Vector(
      AttributeUpdate.Set(schema.message, DdbValue.Bin(row.message.toVector)),
      AttributeUpdate.Set(schema.ordering, DdbValue.Num(BigDecimal(row.ordering)))
    ) ++ row.tags.map(tag => AttributeUpdate.Set(schema.tags, DdbValue.Str(tag))).toVector

  /** The head keeps only the events of its latest write, so an event outside that write cannot be changed there. The
    * update is refused for such an event, because the head stream has already delivered it.
    *
    * The head is read, replaced with the new event and a revision one higher, and written back under the condition that
    * its `sequence-nr` and `revision` are still the ones that were read. A concurrent write or update fails that
    * condition, and the whole read and write is tried again against the new head. A transaction conflict or throttling
    * is tried again after a backoff. When the attempts are used up the failure is
    * `JournalHeadConcurrentUpdateException`, which the caller can retry. The journal row keeps its `deleted` flag and
    * is not updated when it is missing or deleted, so an update cannot bring a soft deleted event back.
    */
  private def updateRowAndHead(head: HeadTable, row: JournalRow): Source[Unit, NotUsed] =
    JournalWritePlan.findJournalRowTooLarge(
      Seq(row),
      journalColumns,
      partitionKeyResolver,
      sortKeyResolver
    ) match {
      case Some(tooLarge) => Source.failed(tooLarge.toJournalHeadException)
      case None           => updateAttempt(head, row, 1, None)
    }

  private def updateAttempt(
      head: HeadTable,
      row: JournalRow,
      attempt: Int,
      previous: Option[ObservedHead]
  ): Source[Unit, NotUsed] = {
    val persistenceId = row.persistenceId.asString
    val sequenceNr    = row.sequenceNumber.value
    def illegalState(detail: String): Source[Unit, NotUsed] =
      Source.failed(
        new IllegalStateException(
          s"persistenceId=[$persistenceId]: cannot update the event at sequence number $sequenceNr because $detail"
        )
      )
    readHead(head, row.persistenceId).flatMapConcat {
      case None =>
        Source.failed(new JournalHeadMissingException(persistenceId, sequenceNr))
      case Some(item) =>
        ObservedHead.parse(item, head.names) match {
          case Left(detail) =>
            illegalState(s"the JournalHead item is malformed: $detail")
          case Right(observed) if previous.exists(observed.isBehind) =>
            illegalState(
              s"the JournalHead item went backwards from (${previous.get.sequenceNr}, ${previous.get.revisionOrZero}) to (${observed.sequenceNr}, ${observed.revisionOrZero})"
            )
          case Right(observed) =>
            replaceHeadEvent(head, observed, row) match {
              case Left(failure) =>
                Source.failed(failure)
              case Right((headCondition, newHeadItem)) =>
                def again(reason: String, delayed: Boolean): Source[Unit, NotUsed] =
                  if (attempt < UpdateAttempts) {
                    val next = updateAttempt(head, row, attempt + 1, Some(observed))
                    if (delayed && writeBackoffConfig.enabled)
                      Source.single(()).initialDelay(backoffDelay(attempt)).flatMapConcat(_ => next)
                    else next
                  } else {
                    Source.failed(
                      new JournalHeadConcurrentUpdateException(persistenceId, sequenceNr, UpdateAttempts, reason)
                    )
                  }
                run(updateTransaction(head, row, newHeadItem, headCondition), writeBackoffConfig).flatMapConcat {
                  case OperationResult.TransactionCommitted(_) =>
                    Source.single(())
                  case OperationResult.TransactionRejected(reasons) =>
                    UpdateCancellation.classify(reasons) match {
                      case UpdateCancellation.HeadChanged =>
                        again("the JournalHead item was changed by another write or update", delayed = false)
                      case UpdateCancellation.Contended(code) =>
                        again(s"DynamoDB reported $code", delayed = true)
                      case UpdateCancellation.RowConditionFailed =>
                        readRowState(row).flatMapConcat {
                          case None =>
                            Source.failed(
                              new JournalHeadRowUpdateRejectedException(
                                persistenceId,
                                sequenceNr,
                                JournalRowUpdateRejection.RowDoesNotExist
                              )
                            )
                          case Some(true) =>
                            Source.failed(
                              new JournalHeadRowUpdateRejectedException(
                                persistenceId,
                                sequenceNr,
                                JournalRowUpdateRejection.RowDeleted
                              )
                            )
                          case Some(false) =>
                            again("the journal row changed while it was checked", delayed = false)
                        }
                      case UpdateCancellation.Unknown(unknown) =>
                        illegalState(
                          s"DynamoDB cancelled the transaction for a reason that is not handled: ${unknown.mkString(", ")}"
                        )
                    }
                  case other =>
                    illegalState(s"the transactional write returned an unexpected result: $other")
                }
            }
        }
    }
  }

  private def updateTransaction(
      head: HeadTable,
      row: JournalRow,
      newHeadItem: AttributeMap,
      headCondition: Condition
  ): Operation.TransactWrite =
    Operation.TransactWrite(
      Vector(
        TransactItem.Update(
          schema.table,
          keyOf(row.persistenceId, row.sequenceNumber),
          rowContentUpdates(row),
          Some(
            Condition.And(
              Condition.AttributeExists(schema.partitionKey),
              Condition.Not(
                Condition.Compare(
                  Operand.Column(schema.deleted),
                  CompareOp.Eq,
                  Operand.Literal(DdbValue.Bool(true))
                )
              )
            )
          )
        ),
        TransactItem.Put(head.spec.table, newHeadItem, Some(headCondition))
      )
    )

  /** The `deleted` flag of the journal row, or none when the row does not exist. */
  private def readRowState(row: JournalRow): Source[Option[Boolean], NotUsed] =
    run(
      Operation.Get(schema.table, keyOf(row.persistenceId, row.sequenceNumber), consistentRead = true),
      readBackoffConfig
    ).map {
      case OperationResult.Item(item) => item.map(_.get(schema.deleted).contains(DdbValue.Bool(true)))
      case other => throw new IllegalStateException(s"unexpected result for a journal row read: $other")
    }

  private def backoffDelay(attempt: Int): FiniteDuration = {
    val base = math.min(writeBackoffConfig.maxBackoff.toMillis, writeBackoffConfig.minBackoff.toMillis << (attempt - 1))
    val jitter = 1.0 + ThreadLocalRandom.current().nextDouble() * writeBackoffConfig.randomFactor
    FiniteDuration(math.min(writeBackoffConfig.maxBackoff.toMillis, (base * jitter).toLong), TimeUnit.MILLISECONDS)
  }

  /** The condition that the head is still the one that was read, and the head item with the event of the row replaced
    * and its revision raised by one.
    */
  private def replaceHeadEvent(
      head: HeadTable,
      observed: ObservedHead,
      row: JournalRow
  ): Either[Throwable, (Condition, AttributeMap)] = {
    val persistenceId = row.persistenceId.asString
    val target        = BigDecimal(row.sequenceNumber.value)
    if (!observed.events.exists(_.sequenceNr == target)) {
      Left(new JournalHeadAsyncUpdateEventUnsupportedException(persistenceId, row.sequenceNumber.value))
    } else {
      val replaced = observed.events.map(event =>
        if (event.sequenceNr == target) event.withMessage(head.names.eventMessage, row.message.toVector) else event
      )
      val newRevision = observed.revisionOrZero + 1
      val headRow = JournalHeadRow(
        row.persistenceId,
        SequenceNumber(observed.sequenceNr.toLong),
        replaced.map(event => JournalHeadEvent(SequenceNumber(event.sequenceNr.toLong), event.message.toArray))
      )
      val headSize = DynamoDBItemSizeCalculator.journalHeadItemSize(headRow, head.columns) +
        DynamoDBItemSizeCalculator.stringSize(head.revision.value) +
        DynamoDBItemSizeCalculator.numberSize(newRevision.toLong)
      if (headSize > JournalHeadLimits.MaxItemSizeBytes) {
        Left(
          WriteRejection
            .HeadItemTooLarge(persistenceId, headSize, JournalHeadLimits.MaxItemSizeBytes).toJournalHeadException
        )
      } else {
        val unchanged = Condition.And(
          Condition.Compare(
            Operand.Column(head.sequenceNr),
            CompareOp.Eq,
            Operand.Literal(DdbValue.Num(observed.sequenceNr))
          ),
          observed.revision.fold[Condition](Condition.AttributeNotExists(head.revision))(revision =>
            Condition.Compare(Operand.Column(head.revision), CompareOp.Eq, Operand.Literal(DdbValue.Num(revision)))
          )
        )
        Right(
          (
            unchanged,
            observed.item
              .updated(head.events, DdbValue.L(replaced.map(_.toValue)))
              .updated(head.revision, DdbValue.Num(newRevision))
          )
        )
      }
    }
  }

  private def readHead(head: HeadTable, persistenceId: PersistenceId): Source[Option[AttributeMap], NotUsed] =
    head.spec.keyOf(
      AttributeMap.of(head.persistenceId -> DdbValue.Str(persistenceId.asString))
    ) match {
      case Left(error) =>
        Source.failed(new IllegalArgumentException(s"cannot build the JournalHead row key: $error"))
      case Right(key) =>
        run(Operation.Get(head.spec.table, key, consistentRead = true), readBackoffConfig).map {
          case OperationResult.Item(item) => item
          case other => throw new IllegalStateException(s"unexpected result for a head read: $other")
        }
    }

  def headSequenceNr(persistenceId: PersistenceId): Source[Option[Long], NotUsed] =
    headTable match {
      case None =>
        Source.single(None)
      case Some(head) =>
        readHead(head, persistenceId).map(
          _.flatMap(_.get(head.sequenceNr).collect { case DdbValue.Num(value) => value.toLong })
        )
    }

  private def readQuery(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber],
      toSequenceNr: SequenceNumber,
      deleted: Option[Boolean]
  ): Operation.Query = {
    val upperBound = DdbValue.Num(BigDecimal(toSequenceNr.value))
    val rangeKey = fromSequenceNr match {
      case Some(from) =>
        schema.sequenceNr -> (SortKeyCondition.Between(
          DdbValue.Num(BigDecimal(from.value)),
          upperBound
        ): SortKeyCondition)
      case None =>
        schema.sequenceNr -> (SortKeyCondition.Le(upperBound): SortKeyCondition)
    }
    Operation.Query(
      table = schema.table,
      index = Some(schema.journalRowsIndex),
      hashKey = schema.persistenceId -> DdbValue.Str(persistenceId.asString),
      rangeKey = Some(rangeKey),
      filter = deletedFilter(deleted),
      limit = Some(queryBatchSize),
      consistentRead = consistentRead
    )
  }

  private def deletedFilter(deleted: Option[Boolean]): Option[Condition] =
    deleted.map { flag =>
      Condition.Compare(
        Operand.Column(schema.deleted),
        CompareOp.Eq,
        Operand.Literal(DdbValue.Bool(flag))
      )
    }

  private def queryPages(query: Operation.Query, max: Option[Long]): Source[AttributeMap, NotUsed] = {
    def loop(next: Option[AttributeMap], remaining: Option[Long]): Source[AttributeMap, NotUsed] = {
      val request = next.fold(query)(key => query.copy(exclusiveStartKey = Some(key)))
      run(request, readBackoffConfig).flatMapConcat {
        case OperationResult.Items(values, lastEvaluatedKey) =>
          val page = remaining match {
            case None        => values
            case Some(limit) => values.take(math.min(limit, Int.MaxValue.toLong).toInt)
          }
          val continue      = lastEvaluatedKey.nonEmpty && remaining.forall(_ > values.size)
          val nextRemaining = remaining.map(_ - page.size)
          Source(page).concat(if (continue) loop(lastEvaluatedKey, nextRemaining) else Source.empty)
        case other =>
          Source.failed(new IllegalStateException(s"unexpected result for a query: $other"))
      }
    }
    loop(None, max)
  }

  private def keyOf(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): RowKey =
    keyCodec.keyOf(persistenceId, sequenceNumber, partitionKeyResolver, sortKeyResolver) match {
      case Right(key)  => key
      case Left(error) => throw new IllegalArgumentException(s"cannot build the journal row key: $error")
    }

  private def decodeOrThrow(values: AttributeMap): JournalRow =
    rowCodec.decode(values) match {
      case Right(row)  => row
      case Left(error) => throw new IllegalArgumentException(s"cannot decode a journal row: $error")
    }

  private def expectWritten(result: OperationResult): Long =
    result match {
      case OperationResult.Written(count) => count.toLong
      case other                          => throw new IllegalStateException(s"unexpected result for a write: $other")
    }

  /** DynamoDB can leave a batch write partly unprocessed, which is a transient condition that the adapter reports as
    * `BatchWriteUnprocessedItemsException`. Retry only that signal, because retrying every failure would also retry a
    * bug or a permanent rejection. The write is idempotent, so the bound and timings of `write-backoff` apply even when
    * its `enabled` flag is false. When the attempts are used up the failure is
    * `JournalRowStoreException.BatchWriteRetriesExhausted`, which the caller can retry.
    */
  private def runBatch(operation: Operation.BatchWrite): Source[OperationResult, NotUsed] = {
    val maxAttempts = math.max(0, writeBackoffConfig.maxRestarts) + 1

    def attempt(attemptNo: Int): Source[OperationResult, NotUsed] =
      adapter
        .run(operation).recoverWithRetries(
          1,
          {
            case _: BatchWriteUnprocessedItemsException if attemptNo < maxAttempts =>
              Source.single(()).delay(backoffDelay(attemptNo)).flatMapConcat(_ => attempt(attemptNo + 1))
            case cause: BatchWriteUnprocessedItemsException =>
              Source.failed(
                new JournalRowStoreException.BatchWriteRetriesExhausted(operation.table, maxAttempts, cause)
              )
          }
        )

    attempt(1)
  }

  private def run(operation: Operation, backoffConfig: BackoffConfig): Source[OperationResult, NotUsed] = {
    def source: Source[OperationResult, NotUsed] = adapter.run(operation)
    if (backoffConfig.enabled) {
      RestartSource.onFailuresWithBackoff(
        RestartSettings(
          backoffConfig.minBackoff,
          backoffConfig.maxBackoff,
          backoffConfig.randomFactor
        ).withMaxRestarts(backoffConfig.maxRestarts, backoffConfig.minBackoff)
      )(() => source)
    } else {
      source
    }
  }

  private final class HeadTable(
      val spec: TableSpec,
      val persistenceId: AttributeName,
      val sequenceNr: AttributeName,
      val events: AttributeName,
      val revision: AttributeName,
      val eventSequenceNr: String,
      val eventMessage: String,
      val columns: JournalHeadColumnsDefConfig
  ) {
    val names: ObservedHead.Names = ObservedHead.Names(sequenceNr, events, revision, eventSequenceNr, eventMessage)
  }
}

object JournalRowStore {

  def fromPluginContext(pluginContext: JournalPluginContext, adapter: DdbClientAdapter): JournalRowStore = {
    val config = pluginContext.pluginConfig
    new JournalRowStore(
      schema = JournalSchema.fromPluginConfig(config),
      partitionKeyResolver = pluginContext.partitionKeyResolver,
      sortKeyResolver = pluginContext.sortKeyResolver,
      adapter = adapter,
      queryBatchSize = config.queryBatchSize,
      consistentRead = config.consistentRead,
      batchWriteLimit = config.clientConfig.batchWriteItemLimit,
      readBackoffConfig = config.readBackoffConfig,
      writeBackoffConfig = config.writeBackoffConfig,
      headConfig = Some(config.headConfig)
    )
  }
}
