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

sealed trait CompareOp

object CompareOp {
  case object Eq extends CompareOp
  case object Lt extends CompareOp
  case object Le extends CompareOp
  case object Gt extends CompareOp
  case object Ge extends CompareOp
  case object BeginsWith extends CompareOp
}

sealed trait Operand

object Operand {
  final case class Column(name: AttributeName) extends Operand
  final case class Literal(value: DdbValue) extends Operand
}

sealed trait Condition

object Condition {
  final case class AttributeExists(name: AttributeName) extends Condition
  final case class AttributeNotExists(name: AttributeName) extends Condition
  final case class Compare(left: Operand, op: CompareOp, right: Operand) extends Condition
  final case class And(left: Condition, right: Condition) extends Condition
  final case class Or(left: Condition, right: Condition) extends Condition
  final case class Not(condition: Condition) extends Condition
}

sealed trait SortKeyCondition

object SortKeyCondition {
  final case class Eq(value: DdbValue) extends SortKeyCondition
  final case class Lt(value: DdbValue) extends SortKeyCondition
  final case class Le(value: DdbValue) extends SortKeyCondition
  final case class Gt(value: DdbValue) extends SortKeyCondition
  final case class Ge(value: DdbValue) extends SortKeyCondition
  final case class Between(lower: DdbValue, upper: DdbValue) extends SortKeyCondition
  final case class BeginsWith(value: DdbValue) extends SortKeyCondition
}
