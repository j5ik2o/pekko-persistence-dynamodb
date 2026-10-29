/*
 * Copyright 2019 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.scaladsl.{ Flow, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.JournalRow
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadWriteRequest
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

trait JournalRowDriver {

  def system: ActorSystem

  protected val logLevels: Attributes = Attributes.logLevels(
    onElement = Attributes.LogLevels.Debug,
    onFailure = Attributes.LogLevels.Error,
    onFinish = Attributes.LogLevels.Debug
  )

  def dispose(): Unit

}

trait JournalRowReadDriver extends JournalRowDriver {

  def getJournalRows(
      persistenceId: PersistenceId,
      toSequenceNr: SequenceNumber,
      deleted: Boolean
  ): Source[Seq[JournalRow], NotUsed]

  def getJournalRows(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber,
      toSequenceNr: SequenceNumber,
      max: Long,
      deleted: Option[Boolean] = Some(false)
  ): Source[JournalRow, NotUsed]

  def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber] = None,
      deleted: Option[Boolean] = None
  ): Source[Option[Long], NotUsed]
}

trait JournalRowWriteDriver extends JournalRowReadDriver {

  def singlePutJournalRowFlow: Flow[JournalRow, Long, NotUsed]
  def multiPutJournalRowsFlow: Flow[Seq[JournalRow], Long, NotUsed]

  /** Writes the journal rows and the JournalHead item of a single write in one TransactWriteItems call. This flow is
    * used only when `head.enabled` is true; the PutItem and BatchWriteItem flows above are not used on that path.
    *
    * The default implementation refuses the write. Drivers that cannot use TransactWriteItems (the AWS SDK v1 drivers
    * and the DAX drivers) therefore fail loudly instead of writing the journal rows without the head, which would
    * silently break the ordering guarantee.
    */
  def putJournalRowsWithHeadFlow: Flow[JournalHeadWriteRequest, Long, NotUsed] =
    Flow[JournalHeadWriteRequest].map { request =>
      throw new UnsupportedOperationException(
        s"persistenceId=[${request.head.persistenceId.asString}]: this journal row write driver does not support head.enabled"
      )
    }

  def updateMessage(journalRow: JournalRow): Source[Unit, NotUsed]

  /** The last sequence number of the JournalHead item, when `head.enabled` is true and the driver supports it.
    *
    * It is used as a lower bound of the highest sequence number of an aggregate: when every event of an aggregate was
    * hard deleted from the journal, the journal no longer knows the highest sequence number, but the head still does.
    */
  def headSequenceNr(persistenceId: PersistenceId): Source[Option[Long], NotUsed] = Source.single(None)

  def singleDeleteJournalRowFlow: Flow[PersistenceIdWithSeqNr, Long, NotUsed]
  def multiDeleteJournalRowsFlow: Flow[Seq[PersistenceIdWithSeqNr], Long, NotUsed]

}
