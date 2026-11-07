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
  JournalHeadColumnsDefConfig,
  JournalHeadConfig
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

final class DynamoDBItemSizeCalculatorSpec extends AnyWordSpec with Matchers {

  private val headColumns = JournalHeadColumnsDefConfig(
    persistenceIdColumnName = "persistence-id",
    sequenceNrColumnName = "sequence-nr",
    eventsColumnName = "events",
    eventSequenceNrColumnName = "sequence-nr",
    eventMessageColumnName = "message"
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

  private val headConfig = JournalHeadConfig(
    enabled = true,
    tableName = "JournalHead",
    columnsDefConfig = headColumns
  )

  private val partitionKeyResolver = new PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(s"${persistenceId.asString}-${sequenceNumber.value}")
  }

  private val sortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey(s"${persistenceId.asString}-${sequenceNumber.value}")
  }

  private val persistenceId = PersistenceId("head-size-1")

  private def event(sequenceNr: Long, messageLength: Int): JournalHeadEvent =
    JournalHeadEvent(SequenceNumber(sequenceNr), Array.fill(messageLength)(0x41.toByte))

  private def journalRow(messageLength: Int): JournalRow =
    JournalRow(
      persistenceId = persistenceId,
      sequenceNumber = SequenceNumber(1),
      deleted = false,
      message = Array.fill(messageLength)(0x41.toByte),
      ordering = 1L
    )

  private def headItemSize(messageLength: Int): Int =
    DynamoDBItemSizeCalculator.journalHeadItemSize(
      JournalHeadRow(persistenceId, SequenceNumber(1), Seq(event(1, messageLength))),
      headColumns
    )

  private def attribute(name: String, valueSize: Int): Int =
    DynamoDBItemSizeCalculator.stringSize(name) + valueSize

  private def stringSize(value: String): Int = DynamoDBItemSizeCalculator.stringSize(value)

  private def numberSize(value: Long): Int = DynamoDBItemSizeCalculator.numberSize(value)

  "DynamoDBItemSizeCalculator" should {

    "count the list element and map entry overhead of every event" in {
      val head = JournalHeadRow(
        persistenceId,
        SequenceNumber(2),
        Seq(event(1, 10), event(2, 20))
      )

      val expected =
        attribute("persistence-id", stringSize(persistenceId.asString)) +
        attribute("sequence-nr", numberSize(2)) +
        // 3 bytes for the list itself and 1 byte for each of its two elements
        attribute("events", 3 + 2) +
        (attribute("sequence-nr", numberSize(1)) +
        attribute("message", 10) +
        // 3 bytes for the map itself and 1 byte for each of its two key-value pairs
        3 + 2) +
        (attribute("sequence-nr", numberSize(2)) +
        attribute("message", 20) +
        3 + 2)

      DynamoDBItemSizeCalculator.journalHeadItemSize(head, headColumns) shouldBe expected
    }

    "accept a write exactly at the item size limit and reject one byte more" in {
      val messageLengthAtLimit = JournalHeadLimits.MaxItemSizeBytes - headItemSize(0)
      headItemSize(messageLengthAtLimit) shouldBe JournalHeadLimits.MaxItemSizeBytes
      headItemSize(messageLengthAtLimit + 1) shouldBe JournalHeadLimits.MaxItemSizeBytes + 1

      val accepted = JournalHeadWriteRequest.of(
        Seq(journalRow(messageLengthAtLimit)),
        headConfig,
        journalColumns,
        partitionKeyResolver,
        sortKeyResolver
      )
      accepted.isRight shouldBe true

      val rejected = JournalHeadWriteRequest.of(
        Seq(journalRow(messageLengthAtLimit + 1)),
        headConfig,
        journalColumns,
        partitionKeyResolver,
        sortKeyResolver
      )
      rejected match {
        case Left(exception: JournalHeadItemTooLargeException) =>
          exception.itemSizeBytes shouldBe JournalHeadLimits.MaxItemSizeBytes + 1
          exception.limitBytes shouldBe JournalHeadLimits.MaxItemSizeBytes
        case other =>
          fail(s"expected Left(JournalHeadItemTooLargeException) but got $other")
      }
    }
  }

}
