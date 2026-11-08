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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v2

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.DdbValue
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class V2AttributeValuesSpec extends AnyFreeSpec with Matchers {

  private val values: Vector[(String, DdbValue)] = Vector(
    "string"     -> DdbValue.Str("x"),
    "number"     -> DdbValue.Num(BigDecimal("1.5")),
    "binary"     -> DdbValue.Bin(Vector[Byte](1, 2)),
    "boolean"    -> DdbValue.Bool(true),
    "string set" -> DdbValue.StrSet(Set("a", "b")),
    "number set" -> DdbValue.NumSet(Set(BigDecimal(1), BigDecimal(2))),
    "binary set" -> DdbValue.BinSet(Set(Vector[Byte](1), Vector[Byte](2))),
    "list"       -> DdbValue.L(Vector(DdbValue.Str("a"), DdbValue.Num(1))),
    "empty list" -> DdbValue.L(Vector.empty),
    "map"        -> DdbValue.M(Vector("k" -> DdbValue.Str("v"))),
    "empty map"  -> DdbValue.M(Vector.empty),
    "null"       -> DdbValue.Null
  )

  "every value round-trips through the AWS SDK v2 attribute value" in {
    values.foreach { case (name, value) =>
      withClue(name) {
        V2AttributeValues.fromSdk(V2AttributeValues.toSdk(value)) shouldBe value
      }
    }
  }

  "map entries come back in key order" in {
    val value = DdbValue.M(Vector("b" -> DdbValue.Str("2"), "a" -> DdbValue.Str("1")))

    V2AttributeValues.fromSdk(V2AttributeValues.toSdk(value)) shouldBe
    DdbValue.M(Vector("a" -> DdbValue.Str("1"), "b" -> DdbValue.Str("2")))
  }
}
