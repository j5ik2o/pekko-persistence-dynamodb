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

/** A DynamoDB request, independent of the AWS SDK version.
  *
  * The set is closed, so adding a case makes every renderer fail to compile until it handles the new case.
  */
sealed trait Operation

object Operation {
  final case class Put(table: TableName, item: AttributeMap, condition: Option[Condition] = None) extends Operation

  final case class Get(table: TableName, key: RowKey, consistentRead: Boolean = false) extends Operation

  final case class Delete(table: TableName, key: RowKey, condition: Option[Condition] = None) extends Operation

  final case class Update(
      table: TableName,
      key: RowKey,
      updates: Vector[AttributeUpdate],
      condition: Option[Condition] = None
  ) extends Operation

  final case class Query(
      table: TableName,
      index: Option[IndexName],
      hashKey: (AttributeName, DdbValue),
      rangeKey: Option[(AttributeName, SortKeyCondition)] = None,
      filter: Option[Condition] = None,
      limit: Option[Int] = None,
      consistentRead: Boolean = false,
      scanForward: Boolean = true,
      exclusiveStartKey: Option[AttributeMap] = None
  ) extends Operation

  final case class BatchWrite(table: TableName, puts: Vector[AttributeMap], deletes: Vector[RowKey]) extends Operation

  final case class TransactWrite(items: Vector[TransactItem]) extends Operation
}

sealed trait TransactItem

object TransactItem {
  final case class Put(table: TableName, item: AttributeMap, condition: Option[Condition] = None) extends TransactItem

  final case class Delete(table: TableName, key: RowKey, condition: Option[Condition] = None) extends TransactItem

  final case class Update(
      table: TableName,
      key: RowKey,
      updates: Vector[AttributeUpdate],
      condition: Option[Condition] = None
  ) extends TransactItem
}
