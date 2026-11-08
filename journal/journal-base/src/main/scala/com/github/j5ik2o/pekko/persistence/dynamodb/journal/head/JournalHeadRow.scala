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

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.write.JournalWritePlan
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ JournalRow, PartitionKeyResolver, SortKeyResolver }
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
    *
    * The validation itself lives in `JournalWritePlan`, which returns a `WriteRejection` value. This method is the
    * compatibility adapter that maps that value to the public `JournalHeadException` family.
    */
  def of(
      rows: Seq[JournalRow],
      headConfig: JournalHeadConfig,
      journalColumnsDefConfig: JournalColumnsDefConfig,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Either[JournalHeadException, JournalHeadWriteRequest] =
    JournalWritePlan
      .plan(rows, headConfig, journalColumnsDefConfig, partitionKeyResolver, sortKeyResolver)
      .left
      .map(_.toJournalHeadException)
      .map(plan => JournalHeadWriteRequest(plan.rows, plan.head))

}
