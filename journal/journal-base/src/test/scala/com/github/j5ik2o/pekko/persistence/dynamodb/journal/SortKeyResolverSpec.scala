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

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, PersistenceIdKeyParts, SequenceNumber }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

final class SortKeyResolverSpec extends AnyFreeSpec with Matchers {

  private def sortKeyOf(id: String, separator: String = "-", sequenceNr: Long = 1): String =
    SortKeyResolver.persistenceIdWithSeqNrSKey(PersistenceId(id), SequenceNumber(sequenceNr), separator)

  /** The partition key and the sort key of the default resolvers, with 64 shards. */
  private def keyPair(id: String, separator: String): (String, String) = {
    val pid    = PersistenceId(id)
    val prefix = PersistenceIdKeyParts.split(pid, separator).map(_._1)
    (PartitionKeyResolver.persistenceIdBased(pid, prefix, 64).asString, sortKeyOf(id, separator))
  }

  /** The keys of the legacy resolvers, which are the default resolvers of 1.3.0, or none where they throw. */
  private def legacyKeyPair(id: String, separator: String): Option[(String, String)] = {
    val pid = PersistenceId(id)
    Try(
      (
        PartitionKeyResolver.persistenceIdBased(pid, new PersistenceIdOps(pid, separator).prefix, 64).asString,
        SortKeyResolver.legacyPersistenceIdWithSeqNrSKey(pid, SequenceNumber(1), separator)
      )
    ).toOption
  }

  "PersistenceIdWithSeqNr" - {
    "keeps the existing body-based key when persistence-id contains the separator" in {
      sortKeyOf(
        "counter-875e6ce0425e4d2b8203f3b44b9b531a"
      ) shouldBe "875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000001"
    }

    "includes the full persistence-id when persistence-id does not contain the separator" in {
      sortKeyOf("01HZX6K5VJAS2Y0P7FNKX5A2A1") shouldBe "01HZX6K5VJAS2Y0P7FNKX5A2A1-0000000000000000001"
      sortKeyOf("01HZX6K5VJAS2Y0P7FNKX5A2A2") shouldBe "01HZX6K5VJAS2Y0P7FNKX5A2A2-0000000000000000001"
    }

    // issue #1009
    "keeps everything after the first separator, so ids that differ after the second separator get different keys" in {
      sortKeyOf("Coll-X-57") shouldBe "X-57-0000000000000000001"
      sortKeyOf("Coll-X-76") shouldBe "X-76-0000000000000000001"
      sortKeyOf("shopping-cart-123") shouldBe "cart-123-0000000000000000001"
      sortKeyOf("A--B") shouldBe "-B-0000000000000000001"
      sortKeyOf("A-B-") shouldBe "B--0000000000000000001"
      sortKeyOf("A-") shouldBe "-0000000000000000001"
    }

    "matches the separator as plain text, not as a regular expression" in {
      sortKeyOf("UserAccount|33", "|") shouldBe "33-0000000000000000001"
      sortKeyOf("UserAccount|37", "|") shouldBe "37-0000000000000000001"
      sortKeyOf("Order.1", ".") shouldBe "1-0000000000000000001"
      sortKeyOf("Order::a::b", "::") shouldBe "a::b-0000000000000000001"
    }

    "hashes the text after the first separator only when the sort key would exceed 1,024 bytes" in {
      val fits = "x" * 1004 // 1,004 bytes + "-" + 19 digits = 1,024 bytes
      sortKeyOf(s"Order-$fits") shouldBe s"$fits-0000000000000000001"
      sortKeyOf(s"Order-$fits").getBytes("UTF-8").length shouldBe 1024

      val hashed = sortKeyOf(s"Order-${fits}x")
      hashed should fullyMatch regex "#h1#[0-9a-f]{64}-0000000000000000001"
      sortKeyOf(s"Order-${"é" * 503}") should startWith("#h1#") // 1,006 bytes in UTF-8, 503 characters
      sortKeyOf(s"Order-${"é" * 502}") should startWith("éé")   // 1,004 bytes
      sortKeyOf("x" * 1005) should startWith("#h1#")            // an id without a separator
    }

    "gives every event of a long id a hashed key of the same form, and different long ids different keys" in {
      val id   = s"Order-item-${"x" * 1000}"
      val keys = Seq(1L, 2L, Long.MaxValue).map(n => sortKeyOf(id, sequenceNr = n))
      keys.map(_.dropRight(20)).distinct should have size 1
      keys.foreach(_.length shouldBe 4 + 64 + 20)
      keys.last should endWith("-9223372036854775807")
      sortKeyOf(s"Order-item-${"x" * 999}y") should not be keys.head
      sortKeyOf(
        s"Other-item-${"x" * 1000}"
      ) shouldBe keys.head // the same text after the separator: the pkey tells them apart
      keyPair(s"Other-item-${"x" * 1000}", "-") should not be keyPair(id, "-")
    }

    "gives the sort key of 1.3.0 with the legacy function, which throws where 1.3.0 threw" in {
      def legacy(id: String, separator: String) =
        Try(SortKeyResolver.legacyPersistenceIdWithSeqNrSKey(PersistenceId(id), SequenceNumber(1), separator)).toOption
      legacy("Coll-X-57", "-") shouldBe Some("X-0000000000000000001")
      legacy("counter-875e", "-") shouldBe Some("875e-0000000000000000001")
      legacy("01HZX6K5VJ", "-") shouldBe Some("01HZX6K5VJ-0000000000000000001")
      legacy("UserAccount|33", "|") shouldBe Some("s-0000000000000000001")
      legacy("A-", "-") shouldBe None
      legacy("Order.1", ".") shouldBe None
    }

    "changes the key only of the ids whose key was ambiguous or could not be built" in {
      // Every id of up to four characters from a small alphabet and the characters of the separator, for each
      // separator. A separator that is not a regular expression metacharacter keeps the key of an id with exactly one
      // separator that is not at its end. `|` changes the key of every id that contains it except
      // `<one character>||`, and the other metacharacters had no key for such ids.
      val nonRegex   = Seq("-", "::", "_")
      val separators = nonRegex ++ Seq("|", ".", "$", "^", "?", "*", "+", "(", "[", "{", "\\")
      separators.foreach { separator =>
        val alphabet = (Seq("a", "b", "é", "-", "|") ++ separator.map(_.toString)).distinct
        val ids =
          (1 to 4).flatMap(n => Seq.fill(n)(alphabet).foldLeft(Seq(""))((acc, cs) => acc.flatMap(a => cs.map(a + _))))
        ids.size shouldBe (1 to 4).map(n => math.pow(alphabet.size.toDouble, n.toDouble).toInt).sum
        ids.foreach { id =>
          val unchanged = PersistenceIdKeyParts.split(PersistenceId(id), separator) match {
            case None => true
            // 1.3.0 split at every character with `|`: only `<one character>||` kept the same two parts
            case Some((prefix, rest)) if separator == "|" => prefix.length == 1 && rest == "|"
            case Some(_) if !nonRegex.contains(separator) => false
            case Some((_, rest))                          => rest.nonEmpty && !rest.contains(separator)
          }
          withClue(s"id=[$id] separator=[$separator]: ") {
            if (unchanged) legacyKeyPair(id, separator) shouldBe Some(keyPair(id, separator))
            else legacyKeyPair(id, separator) should not be Some(keyPair(id, separator))
          }
        }
        withClue(s"separator=[$separator]: ") {
          ids.map(keyPair(_, separator)).distinct.size shouldBe ids.size
        }
      }
    }

    "keeps the key byte for byte for ids without a separator or with one separator in the middle" in {
      Seq(
        "counter-875e6ce0425e4d2b8203f3b44b9b531a",
        "01HZX6K5VJAS2Y0P7FNKX5A2A1",
        "-leading",
        "UserAccount-01HZX6K5VJAS2Y0P7FNKX5A2A1",
        s"Order-${"x" * 1004}"
      ).foreach { id =>
        Seq(1L, 42L, Long.MaxValue).foreach { n =>
          val pid = PersistenceId(id)
          SortKeyResolver.persistenceIdWithSeqNrSKey(pid, SequenceNumber(n), "-") shouldBe
          SortKeyResolver.legacyPersistenceIdWithSeqNrSKey(pid, SequenceNumber(n), "-")
        }
        legacyKeyPair(id, "-") shouldBe Some(keyPair(id, "-"))
      }
    }
  }

}
