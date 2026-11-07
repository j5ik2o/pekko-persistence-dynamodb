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

final case class ConditionFailure(index: Int, code: String)

/** The outcome of one operation.
  *
  * A transport or service failure fails the stream. A condition that did not hold is a value, so a caller can tell a
  * rejected write from a failed one.
  */
sealed trait OperationResult

object OperationResult {
  final case class Item(value: Option[AttributeMap]) extends OperationResult
  final case class Items(values: Vector[AttributeMap], lastEvaluatedKey: Option[AttributeMap]) extends OperationResult
  final case class Written(count: Int) extends OperationResult
  final case class ConditionRejected(reasons: Vector[ConditionFailure]) extends OperationResult
  final case class TransactionCommitted(count: Int) extends OperationResult
  final case class TransactionRejected(reasons: Vector[ConditionFailure]) extends OperationResult
}
