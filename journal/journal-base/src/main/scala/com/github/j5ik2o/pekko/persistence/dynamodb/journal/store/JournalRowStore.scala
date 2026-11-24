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
import org.apache.pekko.stream.scaladsl.{ RestartSource, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.client.Backoff
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
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.write.WriteRejection
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalPluginContext,
  JournalRow,
  PartitionKeyResolver,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

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

  /** Writes the rows, each with its own conditional PutItem, at most `batchWriteLimit` at a time.
    *
    * A row is written only when its key holds no row or a row of the same persistence id. Two persistence ids can
    * resolve to the same key (issue #1009), and an unconditional write would replace the event of the other id without
    * a trace. Such a write fails with `JournalRowStoreException.KeyConflict` and writes nothing under that key; the
    * other new events of the same write are deleted again (see `undoWrites`). A rewrite of an event of the same
    * persistence id, such as a soft delete, is accepted. BatchWriteItem is not used, because it cannot carry a
    * condition.
    *
    * A row with `deleted = true` is the rewrite of an existing event by a soft delete. Releases up to 1.3.0 may have
    * stored that event under another key (see `legacyKeyOf`). Such an event is marked deleted where it is stored, under
    * the condition that the row there is the event and is not deleted yet, and nothing is written under the current
    * key, which can hold a row of another persistence id. Only an event that is not stored under that key is written
    * under the current key.
    */
  def write(rows: Seq[JournalRow]): Source[Long, NotUsed] = {
    val (underLegacyKey, others) =
      rows.partition(row => row.deleted && legacyKeyOf(row.persistenceId, row.sequenceNumber).isDefined)
    if (underLegacyKey.isEmpty) {
      writeRows(rows)
    } else {
      Source(underLegacyKey.toVector)
        .flatMapMerge(batchWriteLimit, row => softDeleteUnderLegacyKey(row).map(row -> _))
        .fold(Vector.empty[(JournalRow, Boolean)])(_ :+ _)
        .flatMapConcat { results =>
          val notThere = results.collect { case (row, false) => row }
          writeRows(others ++ notThere).map(_ + results.count(_._2))
        }
    }
  }

  /** Marks the event of `row` deleted under its key of a release up to 1.3.0. The result is false when that key holds
    * no such event that is not deleted yet: no row, a row of another persistence id or sequence number, or a row that
    * is already deleted.
    */
  private def softDeleteUnderLegacyKey(row: JournalRow): Source[Boolean, NotUsed] =
    legacyKeyOf(row.persistenceId, row.sequenceNumber) match {
      case None => Source.single(false)
      case Some(legacyKey) =>
        run(
          Operation.Update(
            schema.table,
            legacyKey,
            Vector(AttributeUpdate.Set(schema.deleted, DdbValue.Bool(true))),
            Some(updatableRowCondition(row, legacyKey))
          ),
          writeBackoffConfig
        ).map {
          case OperationResult.Written(_)           => true
          case OperationResult.ConditionRejected(_) => false
          case other => throw new IllegalStateException(s"unexpected result for a soft delete: $other")
        }
    }

  private def writeRows(rows: Seq[JournalRow]): Source[Long, NotUsed] = {
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
      } else {
        Source(entries.toVector)
          .flatMapMerge(
            batchWriteLimit,
            { case (row, key) =>
              run(
                Operation.Put(schema.table, rowCodec.encode(row, key), Some(sameRowOrNone(row.persistenceId))),
                writeBackoffConfig
              ).map {
                case OperationResult.ConditionRejected(_) => Left(row -> key)
                case other                                => expectWritten(other); Right(row -> key)
              }
            }
          )
          .fold(Vector.empty[Either[(JournalRow, RowKey), (JournalRow, RowKey)]])(_ :+ _)
          .flatMapConcat { results =>
            results.collectFirst { case Left(rejected) => rejected } match {
              case None => Source.single(results.size.toLong)
              case Some((row, key)) =>
                val written = results.collect { case Right(entry) => entry }
                undoWrites(written)
                  .flatMapConcat(_ => conflict(key)).flatMapConcat(other =>
                    Source.failed(
                      new JournalRowStoreException.KeyConflict(
                        row.persistenceId.asString,
                        row.sequenceNumber.value,
                        other,
                        key.values.entries.map { case (name, value) => s"${name.value}=$value" }.mkString(", ")
                      )
                    )
                  )
            }
          }
      }
    }
  }

  /** Deletes the new events of a write that failed with a key conflict, so the failed write leaves no part of itself: a
    * restarted actor must not replay the later events of a `persistAll` without the event that was refused. A row is
    * deleted only while it still holds the message that this write put there. A rewrite of an existing event, such as a
    * soft delete (`deleted = true`), is not undone, because deleting it would delete the event.
    */
  private def undoWrites(written: Vector[(JournalRow, RowKey)]): Source[Unit, NotUsed] =
    Source(written.filterNot { case (row, _) => row.deleted })
      .flatMapMerge(
        batchWriteLimit,
        { case (row, key) =>
          val sameWrite = Condition.And(
            Condition.And(samePersistenceId(row.persistenceId), sameSequenceNr(row.sequenceNumber)),
            Condition.Compare(
              Operand.Column(schema.message),
              CompareOp.Eq,
              Operand.Literal(DdbValue.Bin(row.message.toVector))
            )
          )
          run(Operation.Delete(schema.table, key, Some(sameWrite)), writeBackoffConfig).map(_ => ())
        }
      )
      .fold(())((_, _) => ())

  private def sameSequenceNr(sequenceNumber: SequenceNumber): Condition =
    Condition.Compare(
      Operand.Column(schema.sequenceNr),
      CompareOp.Eq,
      Operand.Literal(DdbValue.Num(BigDecimal(sequenceNumber.value)))
    )

  /** Deletes the rows, each with its own conditional DeleteItem, at most `batchWriteLimit` at a time. A key that holds
    * the row of another persistence id is left as it is, because that row is not the event to delete. The copies that
    * releases up to 1.3.0 stored under another key are deleted too (see `deleteLegacyRows`).
    */
  def delete(keys: Seq[PersistenceIdWithSeqNr]): Source[Long, NotUsed] =
    deleteRows(keys).flatMapConcat(count => deleteLegacyRows(keys).map(_ => count))

  private def deleteRows(keys: Seq[PersistenceIdWithSeqNr]): Source[Long, NotUsed] = {
    if (keys.isEmpty) {
      Source.single(0L)
    } else {
      Source(keys.toVector)
        .flatMapMerge(
          batchWriteLimit,
          key =>
            run(
              Operation.Delete(
                schema.table,
                keyOf(key.persistenceId, key.sequenceNumber),
                Some(sameRowOrNone(key.persistenceId))
              ),
              writeBackoffConfig
            ).map {
              case OperationResult.ConditionRejected(_) => 0L
              case other                                => expectWritten(other)
            }
        )
        .fold(0L)(_ + _)
    }
  }

  /** Deletes the rows that releases up to 1.3.0 stored under a key that differs from the current one (issue #1009).
    * Such a key can hold the row of another persistence id that shared it, so a row is deleted only while it is the
    * event of the same persistence id and sequence number. A key that holds no such row is left as it is. An id whose
    * key did not change sends no request here.
    */
  private def deleteLegacyRows(keys: Seq[PersistenceIdWithSeqNr]): Source[Unit, NotUsed] = {
    val legacyKeys = keys.flatMap(key => legacyKeyOf(key.persistenceId, key.sequenceNumber).map(key -> _)).toVector
    if (legacyKeys.isEmpty) {
      Source.single(())
    } else {
      Source(legacyKeys)
        .flatMapMerge(
          batchWriteLimit,
          { case (key, legacyKey) =>
            run(
              Operation.Delete(schema.table, legacyKey, Some(sameEvent(key.persistenceId, key.sequenceNumber))),
              writeBackoffConfig
            ).map {
              case OperationResult.Written(_) | OperationResult.ConditionRejected(_) => ()
              case other => throw new IllegalStateException(s"unexpected result for a delete: $other")
            }
          }
        )
        .fold(())((_, _) => ())
    }
  }

  /** The row is the event `sequenceNumber` of `persistenceId`. */
  private def sameEvent(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): Condition =
    Condition.And(samePersistenceId(persistenceId), sameSequenceNr(sequenceNumber))

  /** The key holds no row, or a row of `persistenceId`. */
  private def sameRowOrNone(persistenceId: PersistenceId): Condition =
    Condition.Or(Condition.AttributeNotExists(schema.partitionKey), samePersistenceId(persistenceId))

  private def samePersistenceId(persistenceId: PersistenceId): Condition =
    Condition.Compare(
      Operand.Column(schema.persistenceId),
      CompareOp.Eq,
      Operand.Literal(DdbValue.Str(persistenceId.asString))
    )

  /** The persistence id of the row under `key` that refused a write, or none when it cannot be read. */
  private def conflict(key: RowKey): Source[Option[String], NotUsed] =
    run(Operation.Get(schema.table, key, consistentRead = true), readBackoffConfig)
      .map {
        case OperationResult.Item(item) =>
          item.flatMap(_.get(schema.persistenceId)).collect { case DdbValue.Str(value) => value }
        case _ => None
      }
      .recover { case _ => None }

  /** Rewrites one journal row. With `head.enabled`, the JournalHead event of the same sequence number is rewritten in
    * the same transaction, so the journal and the head stream cannot disagree about the event.
    */
  def update(row: JournalRow): Source[Unit, NotUsed] =
    headTable match {
      case None       => updateRow(row)
      case Some(head) => updateRowAndHead(head, row)
    }

  /** Rewrites the journal row under the condition that it exists and is not deleted, so an update cannot create a
    * missing row or bring a soft deleted one back. A row that is missing or deleted is refused with
    * `JournalHeadRowUpdateRejectedException`, the same failure that `head.enabled` gives. When the condition failed but
    * the row is there and not deleted, a concurrent writer changed it while it was checked, and the update is sent
    * again. After `UpdateAttempts` attempts the failure is `JournalHeadConcurrentUpdateException`.
    *
    * The update goes to the current key first. When that key holds no row of the event, and releases up to 1.3.0 stored
    * the event under another key (see `legacyKeyOf`), the update goes to that key, so an id whose rows are under the
    * current key sends no extra request.
    */
  private def updateRow(row: JournalRow): Source[Unit, NotUsed] =
    updateRowAttempt(row, keyOf(row.persistenceId, row.sequenceNumber), 1)

  private def updateRowAttempt(row: JournalRow, key: RowKey, attempt: Int): Source[Unit, NotUsed] = {
    val persistenceId = row.persistenceId.asString
    val sequenceNr    = row.sequenceNumber.value
    run(
      Operation.Update(
        schema.table,
        key,
        rowContentUpdates(row) :+ AttributeUpdate.Set(schema.deleted, DdbValue.Bool(row.deleted)),
        Some(updatableRowCondition(row, key))
      ),
      writeBackoffConfig
    ).flatMapConcat {
      case OperationResult.Written(_) =>
        Source.single(())
      case OperationResult.ConditionRejected(_) =>
        rowStateAt(row, key).flatMapConcat {
          case (storedKey, Some(false)) if storedKey != key =>
            updateRowAttempt(row, storedKey, attempt)
          case (_, None) =>
            Source.failed(
              new JournalHeadRowUpdateRejectedException(
                persistenceId,
                sequenceNr,
                JournalRowUpdateRejection.RowDoesNotExist
              )
            )
          case (_, Some(true)) =>
            Source.failed(
              new JournalHeadRowUpdateRejectedException(persistenceId, sequenceNr, JournalRowUpdateRejection.RowDeleted)
            )
          case (_, Some(false)) if attempt < UpdateAttempts =>
            updateRowAttempt(row, key, attempt + 1)
          case (_, Some(false)) =>
            Source.failed(
              new JournalHeadConcurrentUpdateException(
                persistenceId,
                sequenceNr,
                UpdateAttempts,
                "the journal row changed while it was checked"
              )
            )
        }
      case other =>
        Source.failed(
          new IllegalStateException(s"persistenceId=[$persistenceId]: unexpected result for a row update: $other")
        )
    }
  }

  /** The journal row under `key` exists, is a row of the persistence id of `row`, and is not soft deleted. Another
    * persistence id can resolve to the same key (issue #1009), and its row must not be changed. Under a key of a
    * release up to 1.3.0 the row must also be the event of `row`.
    */
  private def updatableRowCondition(row: JournalRow, key: RowKey): Condition = {
    val updatable = Condition.And(
      Condition.And(Condition.AttributeExists(schema.partitionKey), samePersistenceId(row.persistenceId)),
      Condition.Not(
        Condition.Compare(Operand.Column(schema.deleted), CompareOp.Eq, Operand.Literal(DdbValue.Bool(true)))
      )
    )
    if (key == keyOf(row.persistenceId, row.sequenceNumber)) updatable
    else Condition.And(updatable, sameSequenceNr(row.sequenceNumber))
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
  private def updateRowAndHead(head: HeadTable, row: JournalRow): Source[Unit, NotUsed] = {
    val key = keyOf(row.persistenceId, row.sequenceNumber)
    legacyKeyOf(row.persistenceId, row.sequenceNumber) match {
      // The size of the row depends on its key. When the current key and the key of a release up to 1.3.0 disagree
      // about the limit, the row is located first, and the size is checked with the key it is stored under.
      case Some(legacyKey) if rowTooLarge(row, key).isDefined != rowTooLarge(row, legacyKey).isDefined =>
        rowStateAt(row, key).flatMapConcat { case (storedKey, _) => checkedUpdate(head, row, storedKey) }
      case _ => checkedUpdate(head, row, key)
    }
  }

  private def checkedUpdate(head: HeadTable, row: JournalRow, key: RowKey): Source[Unit, NotUsed] =
    rowTooLarge(row, key) match {
      case Some(tooLarge) => Source.failed(tooLarge.toJournalHeadException)
      case None           => updateAttempt(head, row, key, 1, None)
    }

  /** The rejection when the journal row of `row` under `key` exceeds the DynamoDB item limit. */
  private def rowTooLarge(row: JournalRow, key: RowKey): Option[WriteRejection.JournalRowTooLarge] = {
    def keyPart(name: AttributeName): String =
      key.values.get(name).collect { case DdbValue.Str(value) => value }.getOrElse("")
    val size = DynamoDBItemSizeCalculator.journalItemSize(
      row,
      journalColumns,
      keyPart(schema.partitionKey),
      keyPart(schema.sortKey)
    )
    if (size > JournalHeadLimits.MaxItemSizeBytes)
      Some(
        WriteRejection.JournalRowTooLarge(
          row.persistenceId.asString,
          row.sequenceNumber.value,
          size,
          JournalHeadLimits.MaxItemSizeBytes
        )
      )
    else None
  }

  private def updateAttempt(
      head: HeadTable,
      row: JournalRow,
      key: RowKey,
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
                    val next = updateAttempt(head, row, key, attempt + 1, Some(observed))
                    if (delayed && writeBackoffConfig.enabled)
                      Source.single(()).initialDelay(backoffDelay(attempt)).flatMapConcat(_ => next)
                    else next
                  } else {
                    Source.failed(
                      new JournalHeadConcurrentUpdateException(persistenceId, sequenceNr, UpdateAttempts, reason)
                    )
                  }
                run(updateTransaction(head, row, key, newHeadItem, headCondition), writeBackoffConfig).flatMapConcat {
                  case OperationResult.TransactionCommitted(_) =>
                    Source.single(())
                  case OperationResult.TransactionRejected(reasons) =>
                    UpdateCancellation.classify(reasons) match {
                      case UpdateCancellation.HeadChanged =>
                        again("the JournalHead item was changed by another write or update", delayed = false)
                      case UpdateCancellation.Contended(code) =>
                        again(s"DynamoDB reported $code", delayed = true)
                      case UpdateCancellation.RowConditionFailed =>
                        rowStateAt(row, key).flatMapConcat {
                          case (storedKey, Some(false)) if storedKey != key =>
                            updateAttempt(head, row, storedKey, attempt, Some(observed))
                          case (_, None) =>
                            Source.failed(
                              new JournalHeadRowUpdateRejectedException(
                                persistenceId,
                                sequenceNr,
                                JournalRowUpdateRejection.RowDoesNotExist
                              )
                            )
                          case (_, Some(true)) =>
                            Source.failed(
                              new JournalHeadRowUpdateRejectedException(
                                persistenceId,
                                sequenceNr,
                                JournalRowUpdateRejection.RowDeleted
                              )
                            )
                          case (_, Some(false)) =>
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
      key: RowKey,
      newHeadItem: AttributeMap,
      headCondition: Condition
  ): Operation.TransactWrite =
    Operation.TransactWrite(
      Vector(
        TransactItem.Update(
          schema.table,
          key,
          rowContentUpdates(row),
          Some(updatableRowCondition(row, key))
        ),
        TransactItem.Put(head.spec.table, newHeadItem, Some(headCondition))
      )
    )

  /** The key that holds the event of `row` and the `deleted` flag of that row, after an update under `key` was refused.
    * When `key` holds no row of the event, the key of a release up to 1.3.0 is read (see `legacyKeyOf`). The flag is
    * none when neither key holds the event.
    */
  private def rowStateAt(row: JournalRow, key: RowKey): Source[(RowKey, Option[Boolean]), NotUsed] =
    readRowState(key, row).flatMapConcat {
      case None if key == keyOf(row.persistenceId, row.sequenceNumber) =>
        legacyKeyOf(row.persistenceId, row.sequenceNumber) match {
          case None => Source.single(key -> None)
          case Some(legacyKey) =>
            readRowState(legacyKey, row).map(state => if (state.isDefined) legacyKey -> state else key -> None)
        }
      case state => Source.single(key -> state)
    }

  /** The `deleted` flag of the row under `key`, or none when `key` holds no row of the event of `row`: no row, or a row
    * of another persistence id or sequence number.
    */
  private def readRowState(key: RowKey, row: JournalRow): Source[Option[Boolean], NotUsed] =
    run(Operation.Get(schema.table, key, consistentRead = true), readBackoffConfig).map {
      case OperationResult.Item(item) =>
        item
          .filter(values =>
            values.get(schema.persistenceId).contains(DdbValue.Str(row.persistenceId.asString)) &&
            values.get(schema.sequenceNr).contains(DdbValue.Num(BigDecimal(row.sequenceNumber.value)))
          )
          .map(_.get(schema.deleted).contains(DdbValue.Bool(true)))
      case other => throw new IllegalStateException(s"unexpected result for a journal row read: $other")
    }

  private def backoffDelay(attempt: Int): FiniteDuration = Backoff.backoffDelay(writeBackoffConfig, attempt)

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

  private def legacyKeyOf(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): Option[RowKey] =
    keyCodec.legacyKeyOf(persistenceId, sequenceNumber, partitionKeyResolver, sortKeyResolver)

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

  /** With `write-backoff` enabled the operation restarts after any failure, at most `max-restarts` times in total
    * however long each attempt takes.
    */
  private def run(operation: Operation, backoffConfig: BackoffConfig): Source[OperationResult, NotUsed] = {
    def source: Source[OperationResult, NotUsed] = adapter.run(operation)
    if (backoffConfig.enabled) {
      RestartSource.onFailuresWithBackoff(Backoff.restartSettings(backoffConfig))(() => source)
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
