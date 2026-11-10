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

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  JournalHeadException,
  JournalHeadInvalidWriteException,
  JournalHeadItemTooLargeException,
  JournalHeadJournalRowTooLargeException,
  JournalHeadTooManyEventsException,
  JournalHeadTransactionTooLargeException
}

/** A write the journal plugin refuses before DynamoDB is called.
  *
  * The rejection is a value so that the write queue keeps running. `toJournalHeadException` maps every case to the
  * existing `JournalHeadException` family. `DynamoDBJournal` fails the whole write with that exception, which is what
  * the actor reports through `onPersistFailure` before it stops and what an operator matches on.
  */
sealed trait WriteRejection extends Product with Serializable {

  def persistenceId: String

  def toJournalHeadException: JournalHeadException

}

object WriteRejection {

  /** A transaction must carry at least one event. */
  final case object NoEvents extends WriteRejection {

    override val persistenceId: String = ""

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadInvalidWriteException(persistenceId, "a single transaction must contain at least one event")

  }

  /** The rows of one write belong to more than one persistence id. */
  final case class MixedPersistenceIds(persistenceIds: Seq[String]) extends WriteRejection {

    override val persistenceId: String = persistenceIds.mkString(", ")

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadInvalidWriteException(
        persistenceId,
        s"a single transaction must contain the rows of exactly one persistence id, but got [$persistenceId]"
      )

  }

  /** The sequence numbers of one write are not contiguous. */
  final case class NonContiguousSequenceNumbers(persistenceId: String, sequenceNumbers: Seq[Long])
      extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadInvalidWriteException(
        persistenceId,
        s"the sequence numbers of a single write must be contiguous, but got [${sequenceNumbers.mkString(", ")}]"
      )

  }

  /** Two rows of one write resolve to the same journal key, so a batch write would silently lose one of them. */
  final case class DuplicateJournalKeys(persistenceId: String, keys: Seq[String]) extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadInvalidWriteException(
        persistenceId,
        s"the journal rows of a single write must have distinct keys, but got [${keys.mkString(", ")}]"
      )

  }

  /** The write carries more events than a single transaction can hold. */
  final case class TooManyEvents(persistenceId: String, eventCount: Int, limit: Int) extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadTooManyEventsException(persistenceId, eventCount, limit)

  }

  /** The JournalHead item of the write is larger than the DynamoDB item size limit. */
  final case class HeadItemTooLarge(persistenceId: String, itemSizeBytes: Int, limitBytes: Int) extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadItemTooLargeException(persistenceId, itemSizeBytes, limitBytes)

  }

  /** One journal row of the write is larger than the DynamoDB item size limit.
    *
    * A journal row carries the encoded event and its tags, so it can exceed the limit while the JournalHead item of the
    * same write stays below it.
    */
  final case class JournalRowTooLarge(
      persistenceId: String,
      sequenceNr: Long,
      itemSizeBytes: Int,
      limitBytes: Int
  ) extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadJournalRowTooLargeException(persistenceId, sequenceNr, itemSizeBytes, limitBytes)

  }

  /** The transaction as a whole is larger than the DynamoDB transaction size limit. */
  final case class TransactionTooLarge(persistenceId: String, transactionSizeBytes: Long, limitBytes: Long)
      extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadTransactionTooLargeException(persistenceId, transactionSizeBytes, limitBytes)

  }

  /** The SDK-neutral algebra could not render the transaction of a write that passed the plan. */
  final case class UnrenderableWrite(persistenceId: String, detail: String) extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadInvalidWriteException(persistenceId, detail)

  }

  /** A journal row of the write cannot be turned into a key from the configured column definitions. */
  final case class UnkeyableRow(persistenceId: String, detail: String) extends WriteRejection {

    override def toJournalHeadException: JournalHeadException =
      new JournalHeadInvalidWriteException(persistenceId, detail)

  }

}
