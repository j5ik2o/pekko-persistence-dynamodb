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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class SortKeyResolverSpec extends AnyFreeSpec with Matchers {

  "PersistenceIdWithSeqNr" - {
    "keeps the existing body-based key when persistence-id contains the separator" in {
      SortKeyResolver.persistenceIdWithSeqNrSKey(
        PersistenceId("counter-875e6ce0425e4d2b8203f3b44b9b531a"),
        SequenceNumber(1),
        PersistenceId.Separator
      ) shouldBe "875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000001"
    }

    "includes the full persistence-id when persistence-id does not contain the separator" in {
      val sequenceNumber = SequenceNumber(1)

      SortKeyResolver.persistenceIdWithSeqNrSKey(
        PersistenceId("01HZX6K5VJAS2Y0P7FNKX5A2A1"),
        sequenceNumber,
        PersistenceId.Separator
      ) shouldBe "01HZX6K5VJAS2Y0P7FNKX5A2A1-0000000000000000001"

      SortKeyResolver.persistenceIdWithSeqNrSKey(
        PersistenceId("01HZX6K5VJAS2Y0P7FNKX5A2A2"),
        sequenceNumber,
        PersistenceId.Separator
      ) shouldBe "01HZX6K5VJAS2Y0P7FNKX5A2A2-0000000000000000001"
    }

    // issue #1009
    "keeps everything after the first separator, so ids that differ after the second separator get different keys" in {
      def key(id: String) = SortKeyResolver.persistenceIdWithSeqNrSKey(PersistenceId(id), SequenceNumber(1), "-")
      key("Coll-X-57") shouldBe "X-57-0000000000000000001"
      key("Coll-X-76") shouldBe "X-76-0000000000000000001"
      key("shopping-cart-123") shouldBe "cart-123-0000000000000000001"
      key("A--B") shouldBe "-B-0000000000000000001"
      key("A-B-") shouldBe "B--0000000000000000001"
    }

    "builds a key for an id that ends with the separator" in {
      SortKeyResolver.persistenceIdWithSeqNrSKey(PersistenceId("A-"), SequenceNumber(1), "-") shouldBe
      "-0000000000000000001"
    }

    "matches the separator as plain text, not as a regular expression" in {
      def key(id: String, separator: String) =
        SortKeyResolver.persistenceIdWithSeqNrSKey(PersistenceId(id), SequenceNumber(1), separator)
      key("UserAccount|33", "|") shouldBe "33-0000000000000000001"
      key("UserAccount|37", "|") shouldBe "37-0000000000000000001"
      key("Order.1", ".") shouldBe "1-0000000000000000001"
      key("Order::a::b", "::") shouldBe "a::b-0000000000000000001"
    }

    "gives the sort key of releases up to 1.2.0 for the rows they wrote" in {
      def before(id: String, separator: String) =
        SortKeyResolver.persistenceIdWithSeqNrSKeyBeforeFix(PersistenceId(id), SequenceNumber(1), separator)
      before("Coll-X-57", "-") shouldBe Some("X-0000000000000000001")
      before("counter-875e", "-") shouldBe Some("875e-0000000000000000001")
      before("01HZX6K5VJ", "-") shouldBe Some("01HZX6K5VJ-0000000000000000001")
      before("UserAccount|33", "|") shouldBe Some("s-0000000000000000001")
      before("A-", "-") shouldBe None
      before("Order.1", ".") shouldBe None
    }

    "changes the key only of the ids whose key was ambiguous or could not be built" in {
      val ids = for {
        head <- Seq("", "A", "Order", "-")
        mid <- Seq("", "-", "--", "x", "-x", "x-", "x-y", "-x-y-")
      } yield s"$head$mid"
      ids.filter(_.nonEmpty).foreach { id =>
        val pid        = PersistenceId(id)
        val separators = id.sliding(1).count(_ == "-")
        val now        = SortKeyResolver.persistenceIdWithSeqNrSKey(pid, SequenceNumber(1), "-")
        val before     = SortKeyResolver.persistenceIdWithSeqNrSKeyBeforeFix(pid, SequenceNumber(1), "-")
        val unchanged  = separators == 0 || (separators == 1 && !id.endsWith("-"))
        withClue(id) {
          if (unchanged) before shouldBe Some(now) else before should not be Some(now)
        }
      }
    }
  }

}
