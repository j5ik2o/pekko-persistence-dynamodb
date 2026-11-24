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

import scala.util.Try

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
    *
    * Releases up to 1.3.0 may have stored a snapshot of the same sequence number under another key (see
    * `SnapshotTableSpec.legacyKeyOf`). That copy is deleted after the write. When the delete does not happen, a load
    * still returns the snapshot under the current key (see `first`).
    */
  def save(row: SnapshotRow): Source[Unit, NotUsed] = {
    val key = codec.key(row)
    adapter.run(Operation.Put(key.table, codec.encode(row), Some(sameRowOrNone(row.persistenceId)))).flatMapConcat {
      case OperationResult.Written(_) =>
        tableSpec.legacyKeyOf(row.persistenceId, row.sequenceNumber) match {
          case None            => Source.single(())
          case Some(legacyKey) => deleteRow(legacyKey, sameSnapshot(row.persistenceId, row.sequenceNumber))
        }
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

  /** Reads one row more for an id whose key changed, because its latest snapshot can be stored under both keys. */
  def latest(persistenceId: PersistenceId): Source[Option[SnapshotRow], NotUsed] = {
    val limit = if (hasLegacyKey(persistenceId)) 2 else 1
    first(
      query(persistenceId, SequenceNumber.MinValue, SequenceNumber.MaxValue, None, Some(limit), scanForward = false)
    )
  }

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
        deleteRow(tableSpec.keyOf(persistenceId, sequenceNumber), sameRowOrNone(persistenceId))
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

  /** The first of the rows, which come newest first. When the first two rows have the same sequence number, a save of a
    * snapshot under the current key stopped before it deleted the copy under the key of a release up to 1.3.0, and the
    * row under the current key is the newer one.
    */
  private def first(rows: Source[AttributeMap, NotUsed]): Source[Option[SnapshotRow], NotUsed] =
    rows
      .take(2)
      .fold(Vector.empty[AttributeMap])(_ :+ _)
      .flatMapConcat { values =>
        val chosen = values.headOption.map { head =>
          val sameSequenceNr = values.filter(_.get(schema.sequenceNumber) == head.get(schema.sequenceNumber))
          // The key is compared only when two rows have the same sequence number, so a resolver that cannot build a
          // key for the id does not fail a load that returns one row.
          if (sameSequenceNr.size < 2) head else sameSequenceNr.find(isUnderCurrentKey).getOrElse(head)
        }
        chosen.map(codec.decode) match {
          case None             => Source.single(None)
          case Some(Right(row)) => Source.single(Some(row))
          case Some(Left(error)) =>
            Source.failed(new IllegalArgumentException(s"cannot decode the snapshot row: $error"))
        }
      }

  private def isUnderCurrentKey(values: AttributeMap): Boolean =
    codec
      .decode(values).exists(row =>
        Try(codec.key(row)).toOption.exists(tableSpec.table.keyOf(values).toOption.contains)
      )

  private def hasLegacyKey(persistenceId: PersistenceId): Boolean =
    tableSpec.legacyKeyOf(persistenceId, SequenceNumber(1)).isDefined

  /** Deletes the rows, each with its own conditional DeleteItem, at most `batchWriteLimit` at a time.
    *
    * A row that a release up to 1.3.0 stored under another key than the current one (see
    * `SnapshotTableSpec.legacyKeyOf`) is deleted under that key, while it is still the snapshot that was read. Any
    * other row is deleted under its current key.
    */
  private def deleteRows(rows: Source[AttributeMap, NotUsed]): Source[Unit, NotUsed] =
    rows
      .map(values => codec.decode(values).map(row => row -> tableSpec.table.keyOf(values).toOption))
      .flatMapConcat {
        case Right((row, storedKey))
            if storedKey.isDefined && tableSpec.legacyKeyOf(row.persistenceId, row.sequenceNumber) == storedKey =>
          Source.single(storedKey.get -> sameSnapshot(row.persistenceId, row.sequenceNumber))
        case Right((row, _)) => Source.single(codec.key(row) -> sameRowOrNone(row.persistenceId))
        case Left(error)     => Source.failed(new IllegalArgumentException(s"cannot decode the snapshot row: $error"))
      }
      .flatMapMerge(batchWriteLimit, { case (key, condition) => deleteRow(key, condition) })

  /** Deletes the row under `key` when `condition` holds. A key that holds the snapshot of another persistence id (issue
    * #1009) is left as it is, because that row is not the snapshot to delete.
    */
  private def deleteRow(key: RowKey, condition: Condition): Source[Unit, NotUsed] = {
    def delete: Source[OperationResult, NotUsed] =
      adapter.run(Operation.Delete(key.table, key, Some(condition)))
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

  /** The row is the snapshot `sequenceNumber` of `persistenceId`. */
  private def sameSnapshot(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): Condition =
    Condition.And(
      Condition.Compare(
        Operand.Column(schema.persistenceId),
        CompareOp.Eq,
        Operand.Literal(DdbValue.Str(persistenceId.asString))
      ),
      Condition.Compare(
        Operand.Column(schema.sequenceNumber),
        CompareOp.Eq,
        Operand.Literal(number(sequenceNumber.value))
      )
    )

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
