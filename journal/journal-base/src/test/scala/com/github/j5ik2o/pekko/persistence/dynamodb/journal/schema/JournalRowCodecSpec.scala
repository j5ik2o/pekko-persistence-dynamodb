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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeKind, DdbValue, DecodeError }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class JournalRowCodecSpec extends AnyFreeSpec with Matchers {

  import JournalTestSupport._

  private val codec          = new JournalRowCodec(schema)
  private val keyCodec       = new JournalKeyCodec(schema)
  private val persistenceId  = PersistenceId("account-1")
  private val sequenceNumber = SequenceNumber(3)
  private val resolver       = new ShardedPartitionKeyResolver(4)
  private val rowKey         = keyCodec.keyOf(persistenceId, sequenceNumber, resolver, sortKeyResolver).toOption.get

  "a row with tags encoded by the codec decodes to the same row" in {
    val row = JournalTestSupport.row(
      persistenceId,
      sequenceNumber.value,
      "payload",
      deleted = false,
      ordering = 42L,
      tags = Some("tag-a")
    )

    codec.decode(codec.encode(row, rowKey)) match {
      case Right(decoded) =>
        decoded.persistenceId shouldBe row.persistenceId
        decoded.sequenceNumber shouldBe row.sequenceNumber
        decoded.deleted shouldBe row.deleted
        decoded.message.toSeq shouldBe row.message.toSeq
        decoded.ordering shouldBe row.ordering
        decoded.tags shouldBe row.tags
      case Left(error) =>
        fail(s"the row did not decode: $error")
    }
  }

  "a row without tags encoded by the codec decodes to a row without tags" in {
    val row = JournalTestSupport.row(persistenceId, sequenceNumber.value, "payload")

    codec.decode(codec.encode(row, rowKey)) match {
      case Right(decoded) => decoded.tags shouldBe None
      case Left(error)    => fail(s"the row did not decode: $error")
    }
  }

  "the codec reports a missing column" in {
    val encoded = codec.encode(JournalTestSupport.row(persistenceId, sequenceNumber.value, "payload"), rowKey)

    codec.decode(encoded.removed(schema.message)) shouldBe Left(DecodeError.Missing(schema.message))
  }

  "the codec reports a column of the wrong type" in {
    val encoded = codec.encode(JournalTestSupport.row(persistenceId, sequenceNumber.value, "payload"), rowKey)

    codec.decode(encoded.updated(schema.deleted, DdbValue.Str("true"))) shouldBe
    Left(DecodeError.WrongType(schema.deleted, AttributeKind.B))
  }

  "the codec reports a sequence number that is not a whole number" in {
    val encoded = codec.encode(JournalTestSupport.row(persistenceId, sequenceNumber.value, "payload"), rowKey)

    codec.decode(encoded.updated(schema.sequenceNr, DdbValue.Num(BigDecimal("1.5")))) shouldBe
    Left(DecodeError.InvalidValue(schema.sequenceNr, "1.5 is not a whole number"))
  }
}
