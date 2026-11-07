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

/** A DynamoDB attribute value, independent of the AWS SDK version.
  *
  * Binary values use `Vector[Byte]` rather than `Array[Byte]`, so case class equality compares the bytes.
  */
sealed trait DdbValue

object DdbValue {
  final case class Str(value: String) extends DdbValue
  final case class Num(value: BigDecimal) extends DdbValue
  final case class Bin(value: Vector[Byte]) extends DdbValue
  final case class Bool(value: Boolean) extends DdbValue
  final case class StrSet(value: Set[String]) extends DdbValue
  final case class NumSet(value: Set[BigDecimal]) extends DdbValue
  final case class BinSet(value: Set[Vector[Byte]]) extends DdbValue
  final case class L(value: Vector[DdbValue]) extends DdbValue
  final case class M(value: Vector[(String, DdbValue)]) extends DdbValue
  case object Null extends DdbValue
}
