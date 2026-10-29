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

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalRow,
  PartitionKeyResolver,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

/** One event of the write that produced a JournalHead item.
  *
  * Only the sequence number and the serialized event are kept here. The JournalHead table exists to feed the read model
  * updates in write order; it is not a second copy of the aggregate state.
  */
final case class JournalHeadEvent(sequenceNumber: SequenceNumber, message: Array[Byte])

/** The content of a JournalHead item: the last sequence number of a persistence id and the events of the write that
  * produced that head. No aggregate state is stored.
  */
final case class JournalHeadRow(
    persistenceId: PersistenceId,
    sequenceNumber: SequenceNumber,
    events: Seq[JournalHeadEvent]
)

/** A single write of one persistence id: the journal rows and the JournalHead item to write with them. */
final case class JournalHeadWriteRequest(rows: Seq[JournalRow], head: JournalHeadRow)

object JournalHeadWriteRequest {

  /** Builds the request for one AtomicWrite and rejects writes that cannot be carried by a single TransactWriteItems
    * call. Validation happens before anything is sent to DynamoDB, so a rejected write leaves both tables untouched.
    */
  def of(
      rows: Seq[JournalRow],
      headConfig: JournalHeadConfig,
      journalColumnsDefConfig: JournalColumnsDefConfig,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Either[JournalHeadException, JournalHeadWriteRequest] = {
    require(rows.nonEmpty, "rows must not be empty")
    val persistenceIds = rows.map(_.persistenceId).distinct
    require(
      persistenceIds.size == 1,
      s"a single transaction must contain the rows of exactly one persistence id, but got [${persistenceIds.map(_.asString).mkString(", ")}]"
    )
    val sortedRows = rows.sortBy(_.sequenceNumber.value)
    val firstSeqNr = sortedRows.head.sequenceNumber.value
    require(
      sortedRows.map(_.sequenceNumber.value) == (firstSeqNr until firstSeqNr + sortedRows.size).toList,
      s"the sequence numbers of a single write must be contiguous, but got [${sortedRows.map(_.sequenceNumber.value).mkString(", ")}]"
    )
    val persistenceId = sortedRows.head.persistenceId

    if (sortedRows.size > JournalHeadLimits.MaxEventsPerTransaction) {
      Left(
        new JournalHeadTooManyEventsException(
          persistenceId.asString,
          sortedRows.size,
          JournalHeadLimits.MaxEventsPerTransaction
        )
      )
    } else {
      val head = JournalHeadRow(
        persistenceId,
        sortedRows.last.sequenceNumber,
        sortedRows.map(row => JournalHeadEvent(row.sequenceNumber, row.message))
      )
      val headSize = DynamoDBItemSizeCalculator.journalHeadItemSize(head, headConfig.columnsDefConfig)
      val transactionSize = headSize.toLong + sortedRows.map { row =>
        val pkey = partitionKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
        val skey = sortKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
        DynamoDBItemSizeCalculator.journalItemSize(row, journalColumnsDefConfig, pkey, skey).toLong
      }.sum
      if (headSize > JournalHeadLimits.MaxItemSizeBytes) {
        Left(
          new JournalHeadItemTooLargeException(
            persistenceId.asString,
            headSize,
            JournalHeadLimits.MaxItemSizeBytes
          )
        )
      } else if (transactionSize > JournalHeadLimits.MaxTransactionSizeBytes) {
        Left(
          new JournalHeadTransactionTooLargeException(
            persistenceId.asString,
            transactionSize,
            JournalHeadLimits.MaxTransactionSizeBytes
          )
        )
      } else {
        Right(JournalHeadWriteRequest(sortedRows, head))
      }
    }
  }

}
