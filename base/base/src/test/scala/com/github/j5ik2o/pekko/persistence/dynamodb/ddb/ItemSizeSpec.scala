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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class ItemSizeSpec extends AnyFreeSpec with Matchers {

  private val name = AttributeName("k")

  private def valueSize(value: DdbValue): Int = ItemSize.of(AttributeMap.of(name -> value)) - 1

  "ItemSize counts an attribute name and a string value in UTF-8 bytes" in {
    ItemSize.of(AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("abc"))) shouldBe 7
  }

  "ItemSize counts a number with one byte per two significant digits" in {
    valueSize(DdbValue.Num(BigDecimal(12345))) shouldBe 4
    valueSize(DdbValue.Num(BigDecimal(1))) shouldBe 2
  }

  "ItemSize counts a binary value by its bytes" in {
    valueSize(DdbValue.Bin(Vector[Byte](1, 2, 3))) shouldBe 3
  }

  "ItemSize counts a boolean or null as one byte" in {
    valueSize(DdbValue.Bool(true)) shouldBe 1
    valueSize(DdbValue.Null) shouldBe 1
  }

  "ItemSize sums the elements of a set" in {
    valueSize(DdbValue.StrSet(Set("a", "bb"))) shouldBe 3
    valueSize(DdbValue.NumSet(Set(BigDecimal(1), BigDecimal(20)))) shouldBe 5
    valueSize(DdbValue.BinSet(Set(Vector[Byte](1), Vector[Byte](2, 3)))) shouldBe 3
  }

  "ItemSize adds the list overhead and one byte per element" in {
    valueSize(DdbValue.L(Vector(DdbValue.Str("ab"), DdbValue.Num(BigDecimal(5))))) shouldBe 9
  }

  "ItemSize adds the map overhead and one byte per pair" in {
    valueSize(DdbValue.M(Vector("k" -> DdbValue.Str("v")))) shouldBe 6
  }
}
