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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.write

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  DynamoDBItemSizeCalculator,
  JournalHeadEvent,
  JournalHeadLimits,
  JournalHeadRow
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ JournalRow, PartitionKeyResolver, SortKeyResolver }

/** The journal rows and the JournalHead item of one write, after every local check has passed.
  *
  * A plan is a value. Building it never touches DynamoDB and never throws for a rejected write, so a rejection reaches
  * the actor as a `Failure` and leaves the write queue working.
  */
final case class JournalWritePlan(rows: Seq[JournalRow], head: JournalHeadRow)

object JournalWritePlan {

  /** Validates one write and builds the rows and the head to send.
    *
    * The order of the checks is the order an operator reads a rejection in: the shape of the write first, then the
    * sequence numbers, the keys, the item count, and the two DynamoDB size limits.
    */
  def plan(
      rows: Seq[JournalRow],
      headConfig: JournalHeadConfig,
      journalColumnsDefConfig: JournalColumnsDefConfig,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Either[WriteRejection, JournalWritePlan] = {
    if (rows.isEmpty) {
      Left(WriteRejection.NoEvents)
    } else {
      val persistenceIds = rows.map(_.persistenceId).distinct
      if (persistenceIds.size != 1) {
        Left(WriteRejection.MixedPersistenceIds(persistenceIds.map(_.asString)))
      } else {
        val sortedRows    = rows.sortBy(_.sequenceNumber.value)
        val persistenceId = sortedRows.head.persistenceId
        val sequenceNrs   = sortedRows.map(_.sequenceNumber.value)
        val firstSeqNr    = sequenceNrs.head
        if (sequenceNrs != (firstSeqNr until firstSeqNr + sortedRows.size).toList) {
          Left(WriteRejection.NonContiguousSequenceNumbers(persistenceId.asString, sequenceNrs))
        } else {
          val keys = sortedRows.map { row =>
            val partitionKey = partitionKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
            val sortKey      = sortKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
            s"$partitionKey/$sortKey"
          }
          if (keys.distinct.size != keys.size) {
            Left(WriteRejection.DuplicateJournalKeys(persistenceId.asString, keys))
          } else if (sortedRows.size > JournalHeadLimits.MaxEventsPerTransaction) {
            Left(
              WriteRejection.TooManyEvents(
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
              val partitionKey = partitionKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
              val sortKey      = sortKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
              DynamoDBItemSizeCalculator.journalItemSize(row, journalColumnsDefConfig, partitionKey, sortKey).toLong
            }.sum
            if (headSize > JournalHeadLimits.MaxItemSizeBytes) {
              Left(
                WriteRejection.HeadItemTooLarge(
                  persistenceId.asString,
                  headSize,
                  JournalHeadLimits.MaxItemSizeBytes
                )
              )
            } else if (transactionSize > JournalHeadLimits.MaxTransactionSizeBytes) {
              Left(
                WriteRejection.TransactionTooLarge(
                  persistenceId.asString,
                  transactionSize,
                  JournalHeadLimits.MaxTransactionSizeBytes
                )
              )
            } else {
              Right(JournalWritePlan(sortedRows, head))
            }
          }
        }
      }
    }
  }

}
