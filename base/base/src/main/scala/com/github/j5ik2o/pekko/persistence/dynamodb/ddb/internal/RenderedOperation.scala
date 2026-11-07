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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, DdbValue, IndexName, TableName }

final case class RenderedExpression(
    expression: String,
    names: Vector[(String, AttributeName)],
    values: Vector[(String, DdbValue)]
) {

  /** Sorts the placeholders by their index, so two expressions compare equal independently of map iteration order. */
  def normalized: RenderedExpression =
    RenderedExpression(
      expression,
      names.sortBy { case (placeholder, _) => placeholderIndex(placeholder) },
      values.sortBy { case (placeholder, _) => placeholderIndex(placeholder) }
    )

  private def placeholderIndex(placeholder: String): Int = placeholder.drop(2).toInt
}

/** An operation with every expression lowered to a condition or update string.
  *
  * Both SDK renderers consume this shape, so the placeholder assignment is decided once and stays identical across SDK
  * versions.
  */
sealed trait RenderedOperation {
  def normalized: RenderedOperation
}

object RenderedOperation {
  final case class Put(table: TableName, item: AttributeMap, condition: Option[RenderedExpression])
      extends RenderedOperation {
    override def normalized: RenderedOperation = Put(table, item.sorted, condition.map(_.normalized))
  }

  final case class Get(table: TableName, key: AttributeMap, consistentRead: Boolean) extends RenderedOperation {
    override def normalized: RenderedOperation = Get(table, key.sorted, consistentRead)
  }

  final case class Delete(table: TableName, key: AttributeMap, condition: Option[RenderedExpression])
      extends RenderedOperation {
    override def normalized: RenderedOperation = Delete(table, key.sorted, condition.map(_.normalized))
  }

  final case class Update(
      table: TableName,
      key: AttributeMap,
      update: RenderedExpression,
      condition: Option[RenderedExpression]
  ) extends RenderedOperation {
    override def normalized: RenderedOperation =
      Update(table, key.sorted, update.normalized, condition.map(_.normalized))
  }

  final case class Query(
      table: TableName,
      index: Option[IndexName],
      keyCondition: RenderedExpression,
      filter: Option[RenderedExpression],
      limit: Option[Int],
      consistentRead: Boolean,
      scanForward: Boolean,
      exclusiveStartKey: Option[AttributeMap]
  ) extends RenderedOperation {
    override def normalized: RenderedOperation =
      Query(
        table,
        index,
        keyCondition.normalized,
        filter.map(_.normalized),
        limit,
        consistentRead,
        scanForward,
        exclusiveStartKey.map(_.sorted)
      )
  }

  final case class BatchWrite(table: TableName, puts: Vector[AttributeMap], deletes: Vector[AttributeMap])
      extends RenderedOperation {
    override def normalized: RenderedOperation =
      BatchWrite(table, puts.map(_.sorted), deletes.map(_.sorted))
  }

  final case class TransactWrite(items: Vector[RenderedTransactItem]) extends RenderedOperation {
    override def normalized: RenderedOperation = TransactWrite(items.map(_.normalized))
  }
}

sealed trait RenderedTransactItem {
  def normalized: RenderedTransactItem
}

object RenderedTransactItem {
  final case class Put(table: TableName, item: AttributeMap, condition: Option[RenderedExpression])
      extends RenderedTransactItem {
    override def normalized: RenderedTransactItem = Put(table, item.sorted, condition.map(_.normalized))
  }

  final case class Delete(table: TableName, key: AttributeMap, condition: Option[RenderedExpression])
      extends RenderedTransactItem {
    override def normalized: RenderedTransactItem = Delete(table, key.sorted, condition.map(_.normalized))
  }

  final case class Update(
      table: TableName,
      key: AttributeMap,
      update: RenderedExpression,
      condition: Option[RenderedExpression]
  ) extends RenderedTransactItem {
    override def normalized: RenderedTransactItem =
      Update(table, key.sorted, update.normalized, condition.map(_.normalized))
  }
}
