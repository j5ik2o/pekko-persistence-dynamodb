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
    * sequence numbers, the keys, the item count, the JournalHead item size, the size of each journal row, and the
    * transaction size.
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
            val rowSizes = journalRowSizes(sortedRows, journalColumnsDefConfig, partitionKeyResolver, sortKeyResolver)
            val transactionSize = headSize.toLong + rowSizes.map(_._2.toLong).sum
            if (headSize > JournalHeadLimits.MaxItemSizeBytes) {
              Left(
                WriteRejection.HeadItemTooLarge(
                  persistenceId.asString,
                  headSize,
                  JournalHeadLimits.MaxItemSizeBytes
                )
              )
            } else {
              journalRowTooLarge(rowSizes) match {
                case Some(rejection) =>
                  Left(rejection)
                case None if transactionSize > JournalHeadLimits.MaxTransactionSizeBytes =>
                  Left(
                    WriteRejection.TransactionTooLarge(
                      persistenceId.asString,
                      transactionSize,
                      JournalHeadLimits.MaxTransactionSizeBytes
                    )
                  )
                case None =>
                  Right(JournalWritePlan(sortedRows, head))
              }
            }
          }
        }
      }
    }
  }

  /** The first journal row of the write that exceeds the DynamoDB item size limit, if any.
    *
    * `plan` runs this check as part of the JournalHead validation. The head-less write path has no JournalHead item to
    * measure, but DynamoDB still refuses a journal row over 400 KB, so the caller of `DynamoDBJournal` runs this same
    * check before it sends the rows and returns the rejection as a `Failure`. Both paths therefore refuse the same row
    * locally, instead of letting DynamoDB decide depending on whether `head.enabled` is set.
    */
  def findJournalRowTooLarge(
      rows: Seq[JournalRow],
      journalColumnsDefConfig: JournalColumnsDefConfig,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Option[WriteRejection.JournalRowTooLarge] =
    journalRowTooLarge(journalRowSizes(rows, journalColumnsDefConfig, partitionKeyResolver, sortKeyResolver))

  private def journalRowSizes(
      rows: Seq[JournalRow],
      journalColumnsDefConfig: JournalColumnsDefConfig,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Seq[(JournalRow, Int)] =
    rows.map { row =>
      val partitionKey = partitionKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
      val sortKey      = sortKeyResolver.resolve(row.persistenceId, row.sequenceNumber).asString
      row -> DynamoDBItemSizeCalculator.journalItemSize(row, journalColumnsDefConfig, partitionKey, sortKey)
    }

  private def journalRowTooLarge(rowSizes: Seq[(JournalRow, Int)]): Option[WriteRejection.JournalRowTooLarge] =
    rowSizes.collectFirst {
      case (row, itemSize) if itemSize > JournalHeadLimits.MaxItemSizeBytes =>
        WriteRejection.JournalRowTooLarge(
          row.persistenceId.asString,
          row.sequenceNumber.value,
          itemSize,
          JournalHeadLimits.MaxItemSizeBytes
        )
    }

}
