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
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalHeadConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema.{ JournalKeyCodec, JournalRowCodec, JournalSchema }
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

  val rowCodec: JournalRowCodec = new JournalRowCodec(schema)
  val keyCodec: JournalKeyCodec = new JournalKeyCodec(schema)

  private val maxBatchAttempts: Int = 3

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
      AttributeName(columns.sequenceNrColumnName)
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
      require(
        entries.map { case (_, key) => key }.toSet.size == entries.size,
        "journal rows contain duplicate keys"
      )
      if (entries.size == 1) {
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

  def update(row: JournalRow): Source[Unit, NotUsed] = {
    val updates = Vector(
      AttributeUpdate.Set(schema.message, DdbValue.Bin(row.message.toVector)),
      AttributeUpdate.Set(schema.ordering, DdbValue.Num(BigDecimal(row.ordering))),
      AttributeUpdate.Set(schema.deleted, DdbValue.Bool(row.deleted))
    ) ++ row.tags.map(tag => AttributeUpdate.Set(schema.tags, DdbValue.Str(tag))).toVector
    run(
      Operation.Update(schema.table, keyOf(row.persistenceId, row.sequenceNumber), updates),
      writeBackoffConfig
    ).map { result =>
      expectWritten(result)
      ()
    }
  }

  def headSequenceNr(persistenceId: PersistenceId): Source[Option[Long], NotUsed] =
    headTable match {
      case None =>
        Source.single(None)
      case Some(head) =>
        head.spec.keyOf(
          AttributeMap.of(head.persistenceId -> DdbValue.Str(persistenceId.asString))
        ) match {
          case Left(error) =>
            Source.failed(new IllegalArgumentException(s"cannot build the JournalHead row key: $error"))
          case Right(key) =>
            run(Operation.Get(head.spec.table, key, consistentRead = true), readBackoffConfig).map {
              case OperationResult.Item(Some(values)) =>
                values.get(head.sequenceNr).collect { case DdbValue.Num(value) => value.toLong }
              case OperationResult.Item(None) =>
                None
              case other =>
                throw new IllegalStateException(s"unexpected result for a head read: $other")
            }
        }
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
    * whole batch, which is idempotent. When the write backoff is enabled it already retries; this immediate retry keeps
    * the unprocessed-items behavior with the default configuration.
    */
  private def runBatch(operation: Operation.BatchWrite): Source[OperationResult, NotUsed] = {
    if (writeBackoffConfig.enabled) {
      run(operation, writeBackoffConfig)
    } else {
      def attempt(remaining: Int): Source[OperationResult, NotUsed] =
        run(operation, writeBackoffConfig).recoverWithRetries(
          if (remaining > 0) 1 else 0,
          {
            case _: IllegalStateException if remaining > 0 => attempt(remaining - 1)
          }
        )
      attempt(maxBatchAttempts)
    }
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
      val sequenceNr: AttributeName
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
