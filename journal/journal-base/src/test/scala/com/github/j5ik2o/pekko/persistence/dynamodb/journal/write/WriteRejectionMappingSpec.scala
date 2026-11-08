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
  JournalHeadTooManyEventsException,
  JournalHeadTransactionTooLargeException
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class WriteRejectionMappingSpec extends AnyWordSpec with Matchers {

  private final case class Mapping(rejection: WriteRejection, expected: Class[_ <: JournalHeadException])

  private val mappings: Seq[Mapping] = Seq(
    Mapping(WriteRejection.NoEvents, classOf[JournalHeadInvalidWriteException]),
    Mapping(WriteRejection.MixedPersistenceIds(Seq("a", "b")), classOf[JournalHeadInvalidWriteException]),
    Mapping(
      WriteRejection.NonContiguousSequenceNumbers("pid-1", Seq(1L, 3L)),
      classOf[JournalHeadInvalidWriteException]
    ),
    Mapping(
      WriteRejection.DuplicateJournalKeys("pid-1", Seq("pkey/skey")),
      classOf[JournalHeadInvalidWriteException]
    ),
    Mapping(WriteRejection.TooManyEvents("pid-1", 100, 99), classOf[JournalHeadTooManyEventsException]),
    Mapping(WriteRejection.HeadItemTooLarge("pid-1", 409601, 409600), classOf[JournalHeadItemTooLargeException]),
    Mapping(
      WriteRejection.TransactionTooLarge("pid-1", 4194305L, 4194304L),
      classOf[JournalHeadTransactionTooLargeException]
    ),
    Mapping(
      WriteRejection.UnrenderableWrite("pid-1", "cannot render the JournalHead write: boom"),
      classOf[JournalHeadInvalidWriteException]
    ),
    Mapping(
      WriteRejection.UnkeyableRow("pid-1", "cannot build the journal row key: boom"),
      classOf[JournalHeadInvalidWriteException]
    )
  )

  "WriteRejection" should {

    "map every rejection to the existing JournalHeadException family" in {
      mappings.foreach { mapping =>
        val exception = mapping.rejection.toJournalHeadException
        withClue(s"${mapping.rejection} mapped to ${exception.getClass.getName}: ") {
          mapping.expected.isInstance(exception) shouldBe true
        }
      }
    }

    "keep the persistence id of every rejection" in {
      mappings.foreach { mapping =>
        withClue(s"${mapping.rejection}: ") {
          mapping.rejection.toJournalHeadException.persistenceId shouldBe mapping.rejection.persistenceId
        }
      }
    }

    "map a local validation rejection to an invalid-write exception with the same detail" in {
      val exception = WriteRejection.NonContiguousSequenceNumbers("pid-1", Seq(1L, 3L)).toJournalHeadException
      exception shouldBe a[JournalHeadInvalidWriteException]
      exception.asInstanceOf[JournalHeadInvalidWriteException].persistenceId shouldBe "pid-1"
      exception.asInstanceOf[JournalHeadInvalidWriteException].detail shouldBe
      "the sequence numbers of a single write must be contiguous, but got [1, 3]"
    }

    "map a size rejection to the exception that carries both sizes" in {
      val exception = WriteRejection.HeadItemTooLarge("pid-1", 409601, 409600).toJournalHeadException
      exception shouldBe a[JournalHeadItemTooLargeException]
      exception.asInstanceOf[JournalHeadItemTooLargeException].itemSizeBytes shouldBe 409601
      exception.asInstanceOf[JournalHeadItemTooLargeException].limitBytes shouldBe 409600
    }

    "map an event-count rejection to the exception that carries the count and the limit" in {
      val exception = WriteRejection.TooManyEvents("pid-1", 100, 99).toJournalHeadException
      exception shouldBe a[JournalHeadTooManyEventsException]
      exception.asInstanceOf[JournalHeadTooManyEventsException].eventCount shouldBe 100
      exception.asInstanceOf[JournalHeadTooManyEventsException].limit shouldBe 99
    }

  }

}
