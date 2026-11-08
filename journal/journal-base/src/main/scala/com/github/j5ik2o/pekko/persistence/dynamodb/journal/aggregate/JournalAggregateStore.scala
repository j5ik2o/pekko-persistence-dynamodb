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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.aggregate

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.WriteJournalDao
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

/** Reads the sequence number an aggregate has reached.
  *
  * The journal and the JournalHead item are both lower bounds. The journal knows every event that is still stored. The
  * JournalHead item keeps the last sequence number after every event of the aggregate was hard deleted, so it is the
  * source when the journal is empty. The store takes the higher of the two.
  */
final class JournalAggregateStore(
    journalHighestSequenceNr: (PersistenceId, SequenceNumber) => Source[Option[Long], NotUsed],
    headHighestSequenceNr: PersistenceId => Source[Option[Long], NotUsed],
    headEnabled: Boolean
) {

  def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber
  ): Source[AggregateVersion, NotUsed] = {
    val journalVersion =
      journalHighestSequenceNr(persistenceId, fromSequenceNr).map(AggregateVersion.fromHighestSequenceNr)
    if (headEnabled) {
      journalVersion
        .zip(headHighestSequenceNr(persistenceId).map(AggregateVersion.fromHighestSequenceNr))
        .map { case (journal, head) => AggregateVersion.highestOf(journal, head) }
    } else {
      journalVersion
    }
  }

}

object JournalAggregateStore {

  def fromJournalDao(
      journalDao: WriteJournalDao,
      headSequenceNr: PersistenceId => Source[Option[Long], NotUsed],
      headEnabled: Boolean
  ): JournalAggregateStore =
    new JournalAggregateStore(
      (persistenceId, fromSequenceNr) => journalDao.highestSequenceNr(persistenceId, fromSequenceNr),
      headSequenceNr,
      headEnabled
    )

}
