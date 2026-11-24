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
package com.github.j5ik2o.pekko.persistence.dynamodb.model

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class PersistenceIdKeyPartsSpec extends AnyFreeSpec with Matchers {

  private val separators = Seq("-", "|", ".", "::", "$", "+")

  private val ids = for {
    a <- Seq("", "A", "Order")
    b <- Seq("", "x", "-", "|", ".", "::", "$", "+")
    c <- Seq("", "1", "-2", "|2", ".2", "::2", "$2", "+2")
    d <- Seq("", "-", "|", ".")
    id = s"$a$b$c$d"
    if id.nonEmpty
  } yield id

  "split" - {

    "gives two parts that join back to the id, so two different ids never get the same parts" in {
      separators.foreach { separator =>
        val parts = ids.distinct.map(id => id -> PersistenceIdKeyParts.split(PersistenceId(id), separator))
        parts.foreach {
          case (id, Some((prefix, rest))) =>
            withClue(s"$id with $separator") {
              prefix + separator + rest shouldBe id
              prefix should not include separator
            }
          case (id, None) =>
            withClue(s"$id with $separator")(id should not include separator)
        }
        parts.map(_._2).filter(_.isDefined).distinct.size shouldBe parts.count(_._2.isDefined)
      }
    }

    "splits at the first separator, read as plain text" in {
      PersistenceIdKeyParts.split(PersistenceId("shopping-cart-123"), "-") shouldBe Some(("shopping", "cart-123"))
      PersistenceIdKeyParts.split(PersistenceId("UserAccount|01ABC"), "|") shouldBe Some(("UserAccount", "01ABC"))
      PersistenceIdKeyParts.split(PersistenceId("Order.1"), ".") shouldBe Some(("Order", "1"))
      PersistenceIdKeyParts.split(PersistenceId("A-"), "-") shouldBe Some(("A", ""))
      PersistenceIdKeyParts.split(PersistenceId("01HZX6K5VJ"), "-") shouldBe None
      PersistenceIdKeyParts.split(PersistenceId("abc"), "") shouldBe None
    }
  }

  "the parts of releases up to 1.3.0" - {

    "keep only the first two elements of a split that reads the separator as a regular expression" in {
      val id = PersistenceId("shopping-cart-123")
      PersistenceIdKeyParts.legacyPrefix(id, "-") shouldBe Some(Some("shopping"))
      PersistenceIdKeyParts.legacyBody(id, "-") shouldBe Some(Some("cart"))
      PersistenceIdKeyParts.legacyPrefix(PersistenceId("UserAccount|33"), "|") shouldBe Some(Some("U"))
      PersistenceIdKeyParts.legacyBody(PersistenceId("UserAccount|33"), "|") shouldBe Some(Some("s"))
    }

    "are none where those releases failed, and none inside when the id has no separator" in {
      PersistenceIdKeyParts.legacyBody(PersistenceId("A-"), "-") shouldBe None
      PersistenceIdKeyParts.legacyPrefix(PersistenceId("A-"), "-") shouldBe Some(Some("A"))
      PersistenceIdKeyParts.legacyPrefix(PersistenceId("Order.1"), ".") shouldBe None
      PersistenceIdKeyParts.legacyPrefix(PersistenceId("Order+1"), "+") shouldBe None
      PersistenceIdKeyParts.legacyBody(PersistenceId("01HZX6K5VJ"), "-") shouldBe Some(None)
    }
  }

  "sortKey" - {

    "keeps the body up to 1,024 bytes of sort key and hashes it with the marker beyond" in {
      PersistenceIdKeyParts.sortKey("cart-1", SequenceNumber(7)) shouldBe "cart-1-0000000000000000007"
      PersistenceIdKeyParts.sortKey("x" * 1004, SequenceNumber(1)).length shouldBe 1024
      val hashed = PersistenceIdKeyParts.sortKey("x" * 1005, SequenceNumber(1))
      // sha256 of 1,005 "x" characters, computed outside the JVM
      hashed shouldBe "#h1#14be44821ac0d0c31b8cb74d3481e379fb7ece89bb90180d538091e38e2d73b9-0000000000000000001#"
      PersistenceIdKeyParts.sortKey("x" * 1005, SequenceNumber(2)).dropRight(21) shouldBe hashed.dropRight(21)
      PersistenceIdKeyParts.sortKey("y" * 1005, SequenceNumber(1)) should not be hashed
    }
  }

  "the hashed form" - {

    "never meets a key without a hash, even when the text is the marker and the digits of a hash (review of #1022)" in {
      val long    = "x" * 1005
      val hashed  = PersistenceIdKeyParts.sortKey(long, SequenceNumber(1))
      val literal = hashed.stripSuffix("#").dropRight(20) // "#h1#" and the 64 hex digits, as the text of an id
      PersistenceIdKeyParts.sortKey(literal, SequenceNumber(1)) should not be hashed
      PersistenceIdKeyParts.sortKey(literal, SequenceNumber(1)) should endWith("-0000000000000000001")
      hashed should endWith("-0000000000000000001#")

      val longPrefix    = "A" * 2008
      val shard         = "0" * 39 + "7"
      val hashedPrefix  = PersistenceIdKeyParts.partitionKey(Some(longPrefix), shard)
      val literalPrefix = hashedPrefix.stripSuffix("#").dropRight(41)
      PersistenceIdKeyParts.partitionKey(Some(literalPrefix), shard) should not be hashedPrefix
    }

    "bounds the partition key to 2,048 bytes and keeps a shorter one as it was" in {
      val shard = "0" * 39 + "7"
      PersistenceIdKeyParts.partitionKey(None, shard) shouldBe shard
      PersistenceIdKeyParts.partitionKey(Some("A" * 2007), shard) shouldBe ("A" * 2007) + "-" + shard // 2,048 bytes
      val hashed = PersistenceIdKeyParts.partitionKey(Some("A" * 2008), shard)
      hashed should fullyMatch regex s"#h1#[0-9a-f]{64}-$shard#"
      hashed.length should be < 2048
      PersistenceIdKeyParts.partitionKey(Some("é" * 1004), shard) should startWith("#h1#") // 2,008 bytes
    }
  }

  "with a format locale whose digits take two bytes in UTF-8 (review of #1022)" - {

    "keeps the sort key and the partition key within the DynamoDB limits" in {
      val saved = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT)
      java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.forLanguageTag("ar-EG"))
      try {
        val sortKey = PersistenceIdKeyParts.sortKey("x" * 990, SequenceNumber(1))
        "%019d".format(1L).getBytes("UTF-8").length shouldBe 38 // the digits are localized, as in 1.3.0
        sortKey.getBytes("UTF-8").length should be <= PersistenceIdKeyParts.MaxSortKeyBytes
        sortKey should startWith("#h1#")
        PersistenceIdKeyParts.sortKey("x" * 980, SequenceNumber(1)) should startWith("x")

        val shard        = "\u0660" * 39 + "\u0667" // 40 digits, 80 bytes
        val partitionKey = PersistenceIdKeyParts.partitionKey(Some("A" * 1980), shard)
        partitionKey.getBytes("UTF-8").length should be <= PersistenceIdKeyParts.MaxPartitionKeyBytes
        partitionKey should startWith("#h1#")
      } finally java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, saved)
    }
  }

  "fitsDynamoDB" - {

    "accepts keys up to the DynamoDB limits in UTF-8 bytes" in {
      PersistenceIdKeyParts.fitsDynamoDB("p" * 2048, "s" * 1024) shouldBe true
      PersistenceIdKeyParts.fitsDynamoDB("p" * 2049, "s") shouldBe false
      PersistenceIdKeyParts.fitsDynamoDB("p", "é" * 513) shouldBe false
    }
  }
}
