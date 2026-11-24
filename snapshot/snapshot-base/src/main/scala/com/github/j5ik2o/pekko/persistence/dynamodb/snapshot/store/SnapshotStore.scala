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
import org.apache.pekko.stream.scaladsl.{ RestartSource, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.client.Backoff
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

  /** Writes the snapshot when its key holds no row or a row of the same persistence id, so a snapshot saved again at
    * the same sequence number replaces the earlier one. Two persistence ids can resolve to the same key (issue #1009);
    * the snapshot of the other id is not replaced, and the save fails with `SnapshotStoreException.KeyConflict`.
    */
  def save(row: SnapshotRow): Source[Unit, NotUsed] = {
    val key = codec.key(row)
    adapter.run(Operation.Put(key.table, codec.encode(row), Some(sameRowOrNone(row.persistenceId)))).flatMapConcat {
      case OperationResult.Written(_) => Source.single(())
      case OperationResult.ConditionRejected(_) =>
        conflict(key).flatMapConcat(other =>
          Source.failed(
            new SnapshotStoreException.KeyConflict(
              row.persistenceId.asString,
              row.sequenceNumber.value,
              other,
              key.values.entries.map { case (name, value) => s"${name.value}=$value" }.mkString(", ")
            )
          )
        )
      case other => Source.failed(new IllegalStateException(s"unexpected write result: $other"))
    }
  }

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
        deleteRow(persistenceId, tableSpec.keyOf(persistenceId, sequenceNumber))
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

  /** Deletes the rows, each with its own conditional DeleteItem, at most `batchWriteLimit` at a time. */
  private def deleteRows(rows: Source[AttributeMap, NotUsed]): Source[Unit, NotUsed] =
    rows
      .map(codec.decode)
      .flatMapConcat {
        case Right(row)  => Source.single(row.persistenceId -> codec.key(row))
        case Left(error) => Source.failed(new IllegalArgumentException(s"cannot decode the snapshot row: $error"))
      }
      .flatMapMerge(batchWriteLimit, { case (persistenceId, key) => deleteRow(persistenceId, key) })

  /** Deletes the row under `key` when it is a row of `persistenceId`. A key that holds the snapshot of another
    * persistence id (issue #1009) is left as it is, because that row is not the snapshot to delete.
    */
  private def deleteRow(persistenceId: PersistenceId, key: RowKey): Source[Unit, NotUsed] = {
    def delete: Source[OperationResult, NotUsed] =
      adapter.run(Operation.Delete(key.table, key, Some(sameRowOrNone(persistenceId))))
    // The delete is idempotent, so with write-backoff enabled it is sent again after a failure, as the batch was.
    val attempts =
      if (writeBackoffConfig.enabled)
        RestartSource.onFailuresWithBackoff(Backoff.restartSettings(writeBackoffConfig))(() => delete)
      else delete
    attempts.flatMapConcat {
      case OperationResult.Written(_) | OperationResult.ConditionRejected(_) => Source.single(())
      case other => Source.failed(new IllegalStateException(s"unexpected write result: $other"))
    }
  }

  /** The key holds no row, or a row of `persistenceId`. */
  private def sameRowOrNone(persistenceId: PersistenceId): Condition =
    Condition.Or(
      Condition.AttributeNotExists(tableSpec.table.primaryKey.hash),
      Condition.Compare(
        Operand.Column(schema.persistenceId),
        CompareOp.Eq,
        Operand.Literal(DdbValue.Str(persistenceId.asString))
      )
    )

  /** The persistence id of the row under `key` that refused a write, or none when it cannot be read. */
  private def conflict(key: RowKey): Source[Option[String], NotUsed] =
    adapter
      .run(Operation.Get(key.table, key, consistentRead = true))
      .map {
        case OperationResult.Item(item) =>
          item.flatMap(_.get(schema.persistenceId)).collect { case DdbValue.Str(value) => value }
        case _ => None
      }
      .recover { case _ => None }

  private def number(value: Long): DdbValue = DdbValue.Num(BigDecimal(value))
}
