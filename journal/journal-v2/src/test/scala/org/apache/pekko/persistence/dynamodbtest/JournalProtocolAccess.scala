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
package org.apache.pekko.persistence.dynamodbtest

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.persistence.{ AtomicWrite, DeleteMessagesSuccess, JournalProtocol, PersistentRepr }

/** `JournalProtocol` is private to `org.apache.pekko.persistence`, so a test of the journal plugin reaches it here. */
object JournalProtocolAccess {

  def write(persistenceId: String, sequenceNr: Long, payload: Any, replyTo: ActorRef): Any =
    JournalProtocol.WriteMessages(
      Vector(AtomicWrite(PersistentRepr(payload, sequenceNr, persistenceId, writerUuid = "headless-writer"))),
      replyTo,
      1
    )

  def writeSuccessful: Any = JournalProtocol.WriteMessagesSuccessful

  def replay(persistenceId: String, to: ActorRef): Any =
    JournalProtocol.ReplayMessages(1L, Long.MaxValue, Long.MaxValue, persistenceId, to)

  def deleteTo(persistenceId: String, toSequenceNr: Long, to: ActorRef): Any =
    JournalProtocol.DeleteMessagesTo(persistenceId, toSequenceNr, to)

  def deleteSuccess(toSequenceNr: Long): Any = DeleteMessagesSuccess(toSequenceNr)

  def recoverySuccess(highestSequenceNr: Long): Any = JournalProtocol.RecoverySuccess(highestSequenceNr)

  def replayedPayload(message: Any): Option[Any] = message match {
    case JournalProtocol.ReplayedMessage(repr) => Some(repr.payload)
    case _                                     => None
  }
}
