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
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalHeadColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  DynamoDBItemSizeCalculator,
  JournalHeadAsyncUpdateEventUnsupportedException,
  JournalHeadEvent,
  JournalHeadLimits,
  JournalHeadMissingException,
  JournalHeadRow
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema.{ JournalKeyCodec, JournalRowCodec, JournalSchema }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.write.WriteRejection
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalPluginContext,
  JournalRow,
  PartitionKeyResolver,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

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

  private val UpdateAttempts: Int    = 3
  private val JournalRowIndex: Int   = 0
  private val HeadIndex: Int         = 1
  private val ConditionalCheckFailed = "ConditionalCheckFailed"

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
      case Some(head) => updateRowAndHead(head, row, UpdateAttempts)
    }

  private def updateRow(row: JournalRow): Source[Unit, NotUsed] =
    run(
      Operation.Update(schema.table, keyOf(row.persistenceId, row.sequenceNumber), rowUpdates(row)),
      writeBackoffConfig
    ).map { result =>
      expectWritten(result)
      ()
    }

  private def rowUpdates(row: JournalRow): Vector[AttributeUpdate] =
    Vector(
      AttributeUpdate.Set(schema.message, DdbValue.Bin(row.message.toVector)),
      AttributeUpdate.Set(schema.ordering, DdbValue.Num(BigDecimal(row.ordering))),
      AttributeUpdate.Set(schema.deleted, DdbValue.Bool(row.deleted))
    ) ++ row.tags.map(tag => AttributeUpdate.Set(schema.tags, DdbValue.Str(tag))).toVector

  /** The head keeps only the events of its latest write, so an event outside that write cannot be changed there. The
    * update is refused for such an event, because the head stream has already delivered it.
    *
    * The head is read, replaced with the new event, and written back under the condition that its events are still the
    * ones that were read. A concurrent write or update of another event fails that condition, and the whole read and
    * write is tried again against the new head.
    */
  private def updateRowAndHead(head: HeadTable, row: JournalRow, attemptsLeft: Int): Source[Unit, NotUsed] = {
    val persistenceId = row.persistenceId.asString
    readHead(head, row.persistenceId).flatMapConcat {
      case None =>
        Source.failed(new JournalHeadMissingException(persistenceId, row.sequenceNumber.value))
      case Some(headItem) =>
        replaceHeadEvent(head, headItem, row) match {
          case Left(failure) =>
            Source.failed(failure)
          case Right((observedEvents, newHeadItem)) =>
            val transaction = Operation.TransactWrite(
              Vector(
                TransactItem.Update(
                  schema.table,
                  keyOf(row.persistenceId, row.sequenceNumber),
                  rowUpdates(row),
                  Some(Condition.AttributeExists(schema.partitionKey))
                ),
                TransactItem.Put(
                  head.spec.table,
                  newHeadItem,
                  Some(Condition.Compare(Operand.Column(head.events), CompareOp.Eq, Operand.Literal(observedEvents)))
                )
              )
            )
            run(transaction, writeBackoffConfig).flatMapConcat {
              case OperationResult.TransactionCommitted(_) =>
                Source.single(())
              case OperationResult.TransactionRejected(reasons) =>
                val conditionFailed = reasons.collect { case ConditionFailure(index, ConditionalCheckFailed) => index }
                def cannotUpdate(why: String): Source[Unit, NotUsed] =
                  Source.failed(
                    new IllegalStateException(
                      s"persistenceId=[$persistenceId]: cannot update the event at sequence number ${row.sequenceNumber.value} because $why"
                    )
                  )
                if (conditionFailed.contains(JournalRowIndex)) {
                  cannotUpdate("its journal row does not exist, although the JournalHead item holds the event")
                } else if (conditionFailed.contains(HeadIndex)) {
                  // Only a failed condition on the head means the head changed after it was read. Any other
                  // cancellation reason (a validation error, throttling, a transaction conflict) is not cured by
                  // reading the head again at once, so it is reported as it is.
                  if (attemptsLeft > 1) updateRowAndHead(head, row, attemptsLeft - 1)
                  else cannotUpdate(s"the JournalHead item kept changing during $UpdateAttempts attempts")
                } else {
                  cannotUpdate(
                    s"DynamoDB cancelled the transaction: ${reasons.filter(_.code != "None").mkString(", ")}"
                  )
                }
              case other =>
                Source.failed(new IllegalStateException(s"unexpected result for a transactional write: $other"))
            }
        }
    }
  }

  /** The events as they were read, and the head item with the event of the row replaced. */
  private def replaceHeadEvent(
      head: HeadTable,
      headItem: AttributeMap,
      row: JournalRow
  ): Either[Throwable, (DdbValue, AttributeMap)] = {
    val persistenceId = row.persistenceId.asString
    val target        = BigDecimal(row.sequenceNumber.value)
    def sequenceNrOf(event: DdbValue): Option[BigDecimal] = event match {
      case DdbValue.M(entries) =>
        entries.collectFirst { case (name, DdbValue.Num(value)) if name == head.eventSequenceNr => value }
      case _ => None
    }
    headItem.get(head.events) match {
      case Some(observed @ DdbValue.L(events)) if events.exists(event => sequenceNrOf(event).contains(target)) =>
        val replaced = events.map {
          case event @ DdbValue.M(entries) if sequenceNrOf(event).contains(target) =>
            DdbValue.M(entries.map {
              case (name, _) if name == head.eventMessage => name -> DdbValue.Bin(row.message.toVector)
              case entry                                  => entry
            })
          case event => event
        }
        val headSequenceNr = headItem.get(head.sequenceNr).collect { case DdbValue.Num(value) => value.toLong }
        val headRow = JournalHeadRow(
          row.persistenceId,
          SequenceNumber(headSequenceNr.getOrElse(row.sequenceNumber.value)),
          replaced.collect { case event @ DdbValue.M(entries) =>
            JournalHeadEvent(
              SequenceNumber(sequenceNrOf(event).get.toLong),
              entries
                .collectFirst { case (name, DdbValue.Bin(bytes)) if name == head.eventMessage => bytes.toArray }
                .getOrElse(Array.emptyByteArray)
            )
          }
        )
        val headSize = DynamoDBItemSizeCalculator.journalHeadItemSize(headRow, head.columns)
        if (headSize > JournalHeadLimits.MaxItemSizeBytes) {
          Left(
            WriteRejection
              .HeadItemTooLarge(persistenceId, headSize, JournalHeadLimits.MaxItemSizeBytes).toJournalHeadException
          )
        } else {
          Right((observed, headItem.updated(head.events, DdbValue.L(replaced))))
        }
      case _ =>
        Left(new JournalHeadAsyncUpdateEventUnsupportedException(persistenceId, row.sequenceNumber.value))
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

  /** DynamoDB can leave a batch write partly unprocessed. The adapter reports that as a failed stream, so retry the
    * whole batch, which is idempotent. The retry runs until DynamoDB accepts the batch, as the previous batch flow did;
    * a batch has transient unprocessed items rather than a permanent rejection.
    */
  private def runBatch(operation: Operation.BatchWrite): Source[OperationResult, NotUsed] = {
    def attempt: Source[OperationResult, NotUsed] =
      run(operation, writeBackoffConfig).recoverWithRetries(
        1,
        { case _: IllegalStateException =>
          attempt
        }
      )
    attempt
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
      val eventSequenceNr: String,
      val eventMessage: String,
      val columns: JournalHeadColumnsDefConfig
  )
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
