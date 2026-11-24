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

  "the parts of releases up to 1.2.0" - {

    "keep only the first two elements of a split that reads the separator as a regular expression" in {
      val id = PersistenceId("shopping-cart-123")
      PersistenceIdKeyParts.prefixBeforeFix(id, "-") shouldBe Some(Some("shopping"))
      PersistenceIdKeyParts.bodyBeforeFix(id, "-") shouldBe Some(Some("cart"))
      PersistenceIdKeyParts.prefixBeforeFix(PersistenceId("UserAccount|33"), "|") shouldBe Some(Some("U"))
      PersistenceIdKeyParts.bodyBeforeFix(PersistenceId("UserAccount|33"), "|") shouldBe Some(Some("s"))
    }

    "are none where those releases failed, and none inside when the id has no separator" in {
      PersistenceIdKeyParts.bodyBeforeFix(PersistenceId("A-"), "-") shouldBe None
      PersistenceIdKeyParts.prefixBeforeFix(PersistenceId("A-"), "-") shouldBe Some(Some("A"))
      PersistenceIdKeyParts.prefixBeforeFix(PersistenceId("Order.1"), ".") shouldBe None
      PersistenceIdKeyParts.prefixBeforeFix(PersistenceId("Order+1"), "+") shouldBe None
      PersistenceIdKeyParts.bodyBeforeFix(PersistenceId("01HZX6K5VJ"), "-") shouldBe Some(None)
    }
  }
}
