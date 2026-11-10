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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.store

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.scaladsl.{ RestartSource, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.dao.SnapshotRow
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.schema.{
  SnapshotKeyDerivation,
  SnapshotRowCodec,
  SnapshotSchema,
  SnapshotTableSpec
}

final class SnapshotStore(
    adapter: DdbClientAdapter,
    private val codec: SnapshotRowCodec,
    consistentRead: Boolean,
    batchWriteLimit: Int,
    writeBackoffConfig: BackoffConfig
) {

  private val schema: SnapshotSchema       = codec.schema
  private val tableSpec: SnapshotTableSpec = codec.tableSpec

  def save(row: SnapshotRow): Source[Unit, NotUsed] =
    written(Operation.Put(codec.key(row).table, codec.encode(row)))

  def latest(persistenceId: PersistenceId): Source[Option[SnapshotRow], NotUsed] =
    first(query(persistenceId, SequenceNumber.MinValue, SequenceNumber.MaxValue, None, Some(1), scanForward = false))

  def forMaxSequenceNr(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber
  ): Source[Option[SnapshotRow], NotUsed] =
    first(query(persistenceId, SequenceNumber.MinValue, maxSequenceNr, None, None, scanForward = false))

  def forMaxTimestamp(persistenceId: PersistenceId, maxTimestamp: Long): Source[Option[SnapshotRow], NotUsed] =
    first(
      query(
        persistenceId,
        SequenceNumber.MinValue,
        SequenceNumber.MaxValue,
        Some(maxTimestamp),
        None,
        scanForward = false
      )
    )

  def forMaxSequenceNrAndMaxTimestamp(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber,
      maxTimestamp: Long
  ): Source[Option[SnapshotRow], NotUsed] =
    first(query(persistenceId, SequenceNumber.MinValue, maxSequenceNr, Some(maxTimestamp), None, scanForward = false))

  def delete(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): Source[Unit, NotUsed] =
    tableSpec.keyDerivation match {
      case SnapshotKeyDerivation.Identity =>
        written(Operation.Delete(tableSpec.table.table, tableSpec.keyOf(persistenceId, sequenceNumber)))
      case SnapshotKeyDerivation.Sharded(_, _) =>
        deleteRows(query(persistenceId, sequenceNumber, sequenceNumber, None))
    }

  def deleteAll(persistenceId: PersistenceId): Source[Unit, NotUsed] =
    deleteRows(query(persistenceId, SequenceNumber.MinValue, SequenceNumber.MaxValue, None))

  def deleteUpToMaxSequenceNr(persistenceId: PersistenceId, maxSequenceNr: SequenceNumber): Source[Unit, NotUsed] =
    deleteRows(query(persistenceId, SequenceNumber.MinValue, maxSequenceNr, None))

  def deleteUpToMaxTimestamp(persistenceId: PersistenceId, maxTimestamp: Long): Source[Unit, NotUsed] =
    deleteRows(query(persistenceId, SequenceNumber.MinValue, SequenceNumber.MaxValue, Some(maxTimestamp)))

  def deleteUpToMaxSequenceNrAndMaxTimestamp(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber,
      maxTimestamp: Long
  ): Source[Unit, NotUsed] =
    deleteRows(query(persistenceId, SequenceNumber.MinValue, maxSequenceNr, Some(maxTimestamp)))

  private def query(
      persistenceId: PersistenceId,
      minSequenceNumber: SequenceNumber,
      maxSequenceNumber: SequenceNumber,
      maxTimestamp: Option[Long],
      limit: Option[Int] = None,
      scanForward: Boolean = true
  ): Source[AttributeMap, NotUsed] = {
    val operation = Operation.Query(
      table = tableSpec.table.table,
      index = tableSpec.readIndex,
      hashKey = (schema.persistenceId, DdbValue.Str(persistenceId.asString)),
      rangeKey = Some(
        (
          schema.sequenceNumber,
          SortKeyCondition.Between(number(minSequenceNumber.value), number(maxSequenceNumber.value))
        )
      ),
      filter = maxTimestamp.map(timestamp =>
        Condition.Compare(Operand.Column(schema.created), CompareOp.Le, Operand.Literal(number(timestamp)))
      ),
      limit = limit,
      consistentRead = consistentRead,
      scanForward = scanForward,
      exclusiveStartKey = None
    )
    adapter.run(operation).flatMapConcat {
      case OperationResult.Items(values, _) => Source(values)
      case other => Source.failed(new IllegalStateException(s"unexpected query result: $other"))
    }
  }

  private def first(rows: Source[AttributeMap, NotUsed]): Source[Option[SnapshotRow], NotUsed] =
    rows
      .take(1)
      .map(codec.decode)
      .flatMapConcat {
        case Right(row)  => Source.single(Some(row))
        case Left(error) => Source.failed(new IllegalArgumentException(s"cannot decode the snapshot row: $error"))
      }
      .fold(Option.empty[SnapshotRow]) { (_, row) => row }

  private def deleteRows(rows: Source[AttributeMap, NotUsed]): Source[Unit, NotUsed] =
    rows
      .map(codec.decode)
      .flatMapConcat {
        case Right(row)  => Source.single(codec.key(row))
        case Left(error) => Source.failed(new IllegalArgumentException(s"cannot decode the snapshot row: $error"))
      }
      .grouped(batchWriteLimit)
      .flatMapConcat(keys => deleteBatch(keys.toVector))

  /** DynamoDB can leave a batch write partly unprocessed. The adapter fails the stream for that transient condition, so
    * retry the idempotent batch within the configured bound. The write path has no other retry, so the bound and
    * timings from write-backoff apply even when its `enabled` flag is false.
    */
  private def deleteBatch(keys: Vector[RowKey]): Source[Unit, NotUsed] = {
    val operation   = Operation.BatchWrite(tableSpec.table.table, Vector.empty, keys)
    val maxRestarts = math.max(0, writeBackoffConfig.maxRestarts)
    // A restart window shorter than one backoff lets the source reset its count and restart without bound.
    val restartWindow = writeBackoffConfig.maxBackoff * (maxRestarts + 2)
    val settings = RestartSettings(
      writeBackoffConfig.minBackoff,
      writeBackoffConfig.maxBackoff,
      writeBackoffConfig.randomFactor
    ).withMaxRestarts(maxRestarts, restartWindow)
    RestartSource
      .onFailuresWithBackoff(settings)(() => adapter.run(operation))
      .recoverWithRetries(
        1,
        { case cause: Exception =>
          Source.failed(
            new SnapshotStoreException.BatchWriteRetriesExhausted(
              tableSpec.table.table,
              maxRestarts + 1,
              cause
            )
          )
        }
      )
      .flatMapConcat {
        case OperationResult.Written(_) => Source.single(())
        case other                      => Source.failed(new IllegalStateException(s"unexpected write result: $other"))
      }
  }

  private def written(operation: Operation): Source[Unit, NotUsed] =
    adapter.run(operation).flatMapConcat {
      case OperationResult.Written(_) => Source.single(())
      case other                      => Source.failed(new IllegalStateException(s"unexpected write result: $other"))
    }

  private def number(value: Long): DdbValue = DdbValue.Num(BigDecimal(value))
}
