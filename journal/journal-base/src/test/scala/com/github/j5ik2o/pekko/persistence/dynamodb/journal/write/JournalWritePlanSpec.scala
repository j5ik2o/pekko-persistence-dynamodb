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

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadColumnsDefConfig,
  JournalHeadConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  DynamoDBItemSizeCalculator,
  JournalHeadEvent,
  JournalHeadLimits,
  JournalHeadRow
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalRow,
  PartitionKey,
  PartitionKeyResolver,
  SortKey,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class JournalWritePlanSpec extends AnyWordSpec with Matchers {

  private val headConfig = JournalHeadConfig(
    enabled = true,
    tableName = "JournalHead",
    columnsDefConfig = JournalHeadColumnsDefConfig(
      persistenceIdColumnName = "persistence-id",
      sequenceNrColumnName = "sequence-nr",
      eventsColumnName = "events",
      eventSequenceNrColumnName = "sequence-nr",
      eventMessageColumnName = "message"
    )
  )

  private val journalColumns = JournalColumnsDefConfig(
    sourceConfig = ConfigFactory.empty(),
    partitionKeyColumnName = "pkey",
    sortKeyColumnName = "skey",
    persistenceIdColumnName = "persistence-id",
    sequenceNrColumnName = "sequence-nr",
    deletedColumnName = "deleted",
    messageColumnName = "message",
    orderingColumnName = "ordering",
    tagsColumnName = "tags"
  )

  private val partitionKeyResolver: PartitionKeyResolver = new PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(s"${persistenceId.asString}-${sequenceNumber.value}")
  }

  private val sortKeyResolver: SortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey("%019d".format(sequenceNumber.value))
  }

  private def journalRow(
      persistenceId: PersistenceId,
      sequenceNumber: Long,
      messageLength: Int = 1,
      tags: Option[String] = None
  ): JournalRow =
    JournalRow(
      persistenceId = persistenceId,
      sequenceNumber = SequenceNumber(sequenceNumber),
      deleted = false,
      message = Array.fill(messageLength)(0x41.toByte),
      ordering = sequenceNumber,
      tags = tags
    )

  private def headItemSize(persistenceId: PersistenceId, messageLengths: Int*): Int =
    DynamoDBItemSizeCalculator.journalHeadItemSize(
      JournalHeadRow(
        persistenceId,
        SequenceNumber(messageLengths.size.toLong),
        messageLengths.zipWithIndex.map { case (messageLength, index) =>
          JournalHeadEvent(SequenceNumber(index + 1L), Array.fill(messageLength)(0x41.toByte))
        }
      ),
      headConfig.columnsDefConfig
    )

  private def journalRowSize(persistenceId: PersistenceId, row: JournalRow): Int =
    DynamoDBItemSizeCalculator.journalItemSize(
      row,
      journalColumns,
      partitionKeyResolver.resolve(persistenceId, row.sequenceNumber).asString,
      sortKeyResolver.resolve(persistenceId, row.sequenceNumber).asString
    )

  private def plan(
      rows: Seq[JournalRow],
      pkResolver: PartitionKeyResolver = partitionKeyResolver,
      skResolver: SortKeyResolver = sortKeyResolver
  ): Either[WriteRejection, JournalWritePlan] =
    JournalWritePlan.plan(rows, headConfig, journalColumns, pkResolver, skResolver)

  "JournalWritePlan" should {

    "accept a contiguous write of one persistence id and build its head" in {
      val persistenceId = PersistenceId("plan-accepted-1")
      val rows          = (1L to 3L).map(sequenceNumber => journalRow(persistenceId, sequenceNumber))

      plan(rows) match {
        case Right(built) =>
          built.rows.map(_.sequenceNumber.value) shouldBe Seq(1L, 2L, 3L)
          built.head.persistenceId shouldBe persistenceId
          built.head.sequenceNumber shouldBe SequenceNumber(3L)
          built.head.events.map(_.sequenceNumber.value) shouldBe Seq(1L, 2L, 3L)
        case Left(rejection) =>
          fail(s"expected an accepted write but got $rejection")
      }
    }

    "reject an empty write" in {
      plan(Seq.empty) shouldBe Left(WriteRejection.NoEvents)
    }

    "reject a write of more than one persistence id" in {
      val rows = Seq(
        journalRow(PersistenceId("plan-mixed-a"), 1L),
        journalRow(PersistenceId("plan-mixed-b"), 1L)
      )

      plan(rows) match {
        case Left(WriteRejection.MixedPersistenceIds(persistenceIds)) =>
          persistenceIds shouldBe Seq("plan-mixed-a", "plan-mixed-b")
        case other =>
          fail(s"expected MixedPersistenceIds but got $other")
      }
    }

    "reject non-contiguous sequence numbers" in {
      val persistenceId = PersistenceId("plan-gap-1")
      val rows          = Seq(journalRow(persistenceId, 1L), journalRow(persistenceId, 3L))

      plan(rows) match {
        case Left(WriteRejection.NonContiguousSequenceNumbers(rejectedId, sequenceNumbers)) =>
          rejectedId shouldBe persistenceId.asString
          sequenceNumbers shouldBe Seq(1L, 3L)
        case other =>
          fail(s"expected NonContiguousSequenceNumbers but got $other")
      }
    }

    "reject two rows that resolve to the same journal key" in {
      val persistenceId = PersistenceId("plan-duplicate-key-1")
      val constantPartitionKeyResolver: PartitionKeyResolver = new PartitionKeyResolver {
        override def resolve(pid: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
          PartitionKey("same")
      }
      val constantSortKeyResolver: SortKeyResolver = new SortKeyResolver {
        override def resolve(pid: PersistenceId, sequenceNumber: SequenceNumber): SortKey = SortKey("same")
      }

      plan(
        Seq(journalRow(persistenceId, 1L), journalRow(persistenceId, 2L)),
        constantPartitionKeyResolver,
        constantSortKeyResolver
      ) match {
        case Left(WriteRejection.DuplicateJournalKeys(rejectedId, keys)) =>
          rejectedId shouldBe persistenceId.asString
          keys shouldBe Seq("same/same", "same/same")
        case other =>
          fail(s"expected DuplicateJournalKeys but got $other")
      }
    }

    "reject more events than a single transaction can carry" in {
      val persistenceId = PersistenceId("plan-too-many-events-1")
      val rows = (1L to (JournalHeadLimits.MaxEventsPerTransaction + 1).toLong)
        .map(sequenceNumber => journalRow(persistenceId, sequenceNumber))

      plan(rows) match {
        case Left(WriteRejection.TooManyEvents(rejectedId, eventCount, limit)) =>
          rejectedId shouldBe persistenceId.asString
          eventCount shouldBe JournalHeadLimits.MaxEventsPerTransaction + 1
          limit shouldBe JournalHeadLimits.MaxEventsPerTransaction
        case other =>
          fail(s"expected TooManyEvents but got $other")
      }
    }

    "accept a JournalHead item of exactly 409600 bytes and reject 409601 bytes" in {
      // Two events keep each journal row under 400 KB while the head item reaches the exact item size limit. One event
      // at the head limit would put the journal row itself over the limit, and the row check would reject it first.
      val persistenceId     = PersistenceId("plan-size-boundary-1")
      val available         = JournalHeadLimits.MaxItemSizeBytes - headItemSize(persistenceId, 0, 0)
      val firstMessageSize  = available / 2
      val secondMessageSize = available - firstMessageSize

      headItemSize(persistenceId, firstMessageSize, secondMessageSize) shouldBe JournalHeadLimits.MaxItemSizeBytes

      plan(
        Seq(
          journalRow(persistenceId, 1L, firstMessageSize),
          journalRow(persistenceId, 2L, secondMessageSize)
        )
      ) match {
        case Right(_) => ()
        case Left(rejection) =>
          fail(s"expected an accepted write but got $rejection")
      }

      headItemSize(persistenceId, firstMessageSize, secondMessageSize + 1) shouldBe
      JournalHeadLimits.MaxItemSizeBytes + 1
      plan(
        Seq(
          journalRow(persistenceId, 1L, firstMessageSize),
          journalRow(persistenceId, 2L, secondMessageSize + 1)
        )
      ) match {
        case Left(WriteRejection.HeadItemTooLarge(rejectedId, itemSizeBytes, limitBytes)) =>
          rejectedId shouldBe persistenceId.asString
          itemSizeBytes shouldBe JournalHeadLimits.MaxItemSizeBytes + 1
          limitBytes shouldBe JournalHeadLimits.MaxItemSizeBytes
        case other =>
          fail(s"expected HeadItemTooLarge but got $other")
      }
    }

    "reject a journal row of more than 400 KB even when the JournalHead item is small" in {
      // The row carries its tags and the JournalHead item does not, so a large tag makes the row larger than the head.
      val persistenceId  = PersistenceId("plan-row-size-boundary-1")
      val rowWithoutTags = journalRow(persistenceId, 1L, messageLength = 1)
      val tagsAtLimit =
        "t" * (JournalHeadLimits.MaxItemSizeBytes - journalRowSize(persistenceId, rowWithoutTags) -
        DynamoDBItemSizeCalculator.stringSize(journalColumns.tagsColumnName))
      val rowAtLimit   = journalRow(persistenceId, 1L, messageLength = 1, tags = Some(tagsAtLimit))
      val rowOverLimit = journalRow(persistenceId, 1L, messageLength = 1, tags = Some(tagsAtLimit + "t"))

      journalRowSize(persistenceId, rowAtLimit) shouldBe JournalHeadLimits.MaxItemSizeBytes
      journalRowSize(persistenceId, rowOverLimit) shouldBe JournalHeadLimits.MaxItemSizeBytes + 1

      plan(Seq(rowAtLimit)) match {
        case Right(_) => ()
        case Left(rejection) =>
          fail(s"expected an accepted write but got $rejection")
      }

      plan(Seq(rowOverLimit)) match {
        case Left(WriteRejection.JournalRowTooLarge(rejectedId, sequenceNr, itemSizeBytes, limitBytes)) =>
          rejectedId shouldBe persistenceId.asString
          sequenceNr shouldBe 1L
          itemSizeBytes shouldBe JournalHeadLimits.MaxItemSizeBytes + 1
          limitBytes shouldBe JournalHeadLimits.MaxItemSizeBytes
        case Left(rejection) =>
          fail(s"expected JournalRowTooLarge but got $rejection")
        case Right(_) =>
          fail("expected JournalRowTooLarge but got an accepted write")
      }
    }

    "reject a transaction larger than 4 MB even when the head item is small" in {
      val persistenceId = PersistenceId("plan-transaction-too-large-1")
      val tag           = "t" * 43000
      val rows = (1L to JournalHeadLimits.MaxEventsPerTransaction.toLong)
        .map(sequenceNumber => journalRow(persistenceId, sequenceNumber, tags = Some(tag)))

      plan(rows) match {
        case Left(WriteRejection.TransactionTooLarge(rejectedId, transactionSizeBytes, limitBytes)) =>
          rejectedId shouldBe persistenceId.asString
          transactionSizeBytes should be > limitBytes
          limitBytes shouldBe JournalHeadLimits.MaxTransactionSizeBytes
        case other =>
          fail(s"expected TransactionTooLarge but got $other")
      }
    }

  }

}
