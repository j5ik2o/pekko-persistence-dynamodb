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

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.JournalRow
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadColumnsDefConfig
}

import java.nio.charset.StandardCharsets

/** Estimates the DynamoDB item size of the rows written by the JournalHead path.
  *
  * The estimate follows the documented DynamoDB rule (attribute name length plus attribute value length, 3 bytes of
  * overhead for list and map values) and rounds numbers up, so it never under-estimates. Rounding up means a write can
  * be rejected a few bytes before DynamoDB itself would reject it; that is safe, because the write is rejected before
  * anything is sent and a write that DynamoDB would refuse is never attempted.
  */
object DynamoDBItemSizeCalculator {

  private val ListOverheadBytes: Int = 3
  private val MapOverheadBytes: Int  = 3
  private val BooleanSizeBytes: Int  = 1

  def stringSize(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length

  /** Numbers take about (significant digits / 2) + 1 bytes; round up by one byte. */
  def numberSize(value: Long): Int = java.lang.Long.toString(value).length / 2 + 2

  def binarySize(value: Array[Byte]): Int = value.length

  def journalItemSize(
      row: JournalRow,
      columnsDefConfig: JournalColumnsDefConfig,
      partitionKey: String,
      sortKey: String
  ): Int = {
    val fixed =
      attributeSize(columnsDefConfig.partitionKeyColumnName, stringSize(partitionKey)) +
      attributeSize(columnsDefConfig.sortKeyColumnName, stringSize(sortKey)) +
      attributeSize(columnsDefConfig.persistenceIdColumnName, stringSize(row.persistenceId.asString)) +
      attributeSize(columnsDefConfig.sequenceNrColumnName, numberSize(row.sequenceNumber.value)) +
      attributeSize(columnsDefConfig.orderingColumnName, numberSize(row.ordering)) +
      attributeSize(columnsDefConfig.deletedColumnName, BooleanSizeBytes) +
      attributeSize(columnsDefConfig.messageColumnName, binarySize(row.message))
    fixed + row.tags.fold(0)(tags => attributeSize(columnsDefConfig.tagsColumnName, stringSize(tags)))
  }

  def journalHeadItemSize(head: JournalHeadRow, columnsDefConfig: JournalHeadColumnsDefConfig): Int = {
    val eventsSize = head.events.map { event =>
      val eventMapSize =
        attributeSize(columnsDefConfig.eventSequenceNrColumnName, numberSize(event.sequenceNumber.value)) +
        attributeSize(columnsDefConfig.eventMessageColumnName, binarySize(event.message))
      eventMapSize + MapOverheadBytes
    }.sum
    attributeSize(columnsDefConfig.persistenceIdColumnName, stringSize(head.persistenceId.asString)) +
    attributeSize(columnsDefConfig.sequenceNrColumnName, numberSize(head.sequenceNumber.value)) +
    attributeSize(columnsDefConfig.eventsColumnName, eventsSize + ListOverheadBytes)
  }

  private def attributeSize(name: String, valueSize: Int): Int = stringSize(name) + valueSize

}
