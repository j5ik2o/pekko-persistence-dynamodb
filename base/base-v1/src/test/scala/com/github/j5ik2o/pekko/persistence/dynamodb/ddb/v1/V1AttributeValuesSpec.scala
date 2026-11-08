package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.DdbValue
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class V1AttributeValuesSpec extends AnyFreeSpec with Matchers {

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

  "every value round-trips through the AWS SDK v1 attribute value" in {
    values.foreach { case (name, value) =>
      withClue(name) {
        V1AttributeValues.fromSdk(V1AttributeValues.toSdk(value)) shouldBe value
      }
    }
  }
}
