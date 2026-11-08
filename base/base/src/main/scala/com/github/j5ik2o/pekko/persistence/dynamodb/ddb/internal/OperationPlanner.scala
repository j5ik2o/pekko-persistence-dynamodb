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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.AttributeUpdate.{ Remove, Set => SetUpdate }

import scala.collection.mutable

private[dynamodb] object OperationPlanner {

  def plan(operation: Operation): Either[EncodeError, RenderedOperation] =
    OperationKeys.validate(operation).flatMap(_ => planValidated(operation))

  private def planValidated(operation: Operation): Either[EncodeError, RenderedOperation] = operation match {
    case Operation.Put(table, item, condition) =>
      Right(RenderedOperation.Put(table, item, condition.map(renderCondition)))

    case Operation.Get(table, key, consistentRead) =>
      Right(RenderedOperation.Get(table, key.values, consistentRead))

    case Operation.Delete(table, key, condition) =>
      Right(RenderedOperation.Delete(table, key.values, condition.map(renderCondition)))

    case Operation.Update(table, key, updates, condition) =>
      if (updates.isEmpty) {
        Left(EncodeError("an update needs at least one attribute update"))
      } else {
        val builder             = new ExpressionBuilder
        val updateExpression    = builder.updates(updates)
        val conditionExpression = condition.map(builder.condition)
        val update              = builder.result(updateExpression)
        val renderedCondition   = conditionExpression.map(expression => update.copy(expression = expression))
        Right(RenderedOperation.Update(table, key.values, update, renderedCondition))
      }

    case Operation.Query(
          table,
          index,
          hashKey,
          rangeKey,
          filter,
          limit,
          consistentRead,
          scanForward,
          exclusiveStartKey
        ) =>
      val builder          = new ExpressionBuilder
      val keyExpression    = builder.keyCondition(hashKey, rangeKey)
      val filterExpression = filter.map(builder.condition)
      val keyCondition     = builder.result(keyExpression)
      val renderedFilter   = filterExpression.map(expression => keyCondition.copy(expression = expression))
      Right(
        RenderedOperation.Query(
          table,
          index,
          keyCondition,
          renderedFilter,
          limit,
          consistentRead,
          scanForward,
          exclusiveStartKey
        )
      )

    case Operation.BatchWrite(table, puts, deletes) =>
      if (puts.isEmpty && deletes.isEmpty) {
        Left(EncodeError("a batch write needs at least one put or delete"))
      } else {
        Right(RenderedOperation.BatchWrite(table, puts, deletes.map(_.values)))
      }

    case Operation.TransactWrite(items) =>
      if (items.isEmpty) {
        Left(EncodeError("a transaction needs at least one item"))
      } else {
        sequence(items.map(renderTransactItem)).map(rendered => RenderedOperation.TransactWrite(rendered))
      }
  }

  def renderCondition(condition: Condition): RenderedExpression = {
    val builder    = new ExpressionBuilder
    val expression = builder.condition(condition)
    builder.result(expression)
  }

  private def sequence(
      items: Vector[Either[EncodeError, RenderedTransactItem]]
  ): Either[EncodeError, Vector[RenderedTransactItem]] =
    items.foldLeft[Either[EncodeError, Vector[RenderedTransactItem]]](Right(Vector.empty)) {
      case (Right(rendered), Right(item)) => Right(rendered :+ item)
      case (left @ Left(_), _)            => left
      case (_, Left(error))               => Left(error)
    }

  private def renderTransactItem(item: TransactItem): Either[EncodeError, RenderedTransactItem] = item match {
    case TransactItem.Put(table, attributes, condition) =>
      Right(RenderedTransactItem.Put(table, attributes, condition.map(renderCondition)))
    case TransactItem.Delete(table, key, condition) =>
      Right(RenderedTransactItem.Delete(table, key.values, condition.map(renderCondition)))
    case TransactItem.Update(table, key, updates, condition) =>
      if (updates.isEmpty) {
        Left(EncodeError("an update needs at least one attribute update"))
      } else {
        val builder             = new ExpressionBuilder
        val updateExpression    = builder.updates(updates)
        val conditionExpression = condition.map(builder.condition)
        val update              = builder.result(updateExpression)
        val renderedCondition   = conditionExpression.map(expression => update.copy(expression = expression))
        Right(RenderedTransactItem.Update(table, key.values, update, renderedCondition))
      }
  }

  private final class ExpressionBuilder {
    private val names          = mutable.LinkedHashMap.empty[AttributeName, String]
    private val values         = mutable.LinkedHashMap.empty[DdbValue, String]
    private var nextNameIndex  = 0
    private var nextValueIndex = 0

    def name(value: AttributeName): String =
      names.getOrElseUpdate(
        value, {
          val placeholder = s"#n$nextNameIndex"
          nextNameIndex += 1
          placeholder
        }
      )

    def value(value: DdbValue): String =
      values.getOrElseUpdate(
        value, {
          val placeholder = s":v$nextValueIndex"
          nextValueIndex += 1
          placeholder
        }
      )

    def result(expression: String): RenderedExpression =
      RenderedExpression(
        expression,
        names.toVector.map { case (attribute, placeholder) => placeholder -> attribute },
        values.toVector.map { case (attributeValue, placeholder) => placeholder -> attributeValue }
      )

    def condition(input: Condition): String = input match {
      case Condition.AttributeExists(name)    => s"attribute_exists(${this.name(name)})"
      case Condition.AttributeNotExists(name) => s"attribute_not_exists(${this.name(name)})"
      case Condition.Compare(left, CompareOp.BeginsWith, right) =>
        s"begins_with(${operand(left)}, ${operand(right)})"
      case Condition.Compare(left, operator, right) =>
        s"${operand(left)} ${operatorText(operator)} ${operand(right)}"
      case Condition.And(left, right) => s"(${condition(left)}) AND (${condition(right)})"
      case Condition.Or(left, right)  => s"(${condition(left)}) OR (${condition(right)})"
      case Condition.Not(inner)       => s"NOT (${condition(inner)})"
    }

    def keyCondition(
        hashKey: (AttributeName, DdbValue),
        rangeKey: Option[(AttributeName, SortKeyCondition)]
    ): String = {
      val hash = s"${name(hashKey._1)} = ${value(hashKey._2)}"
      rangeKey match {
        case None                                       => hash
        case Some((column, SortKeyCondition.Eq(value))) => s"$hash AND ${name(column)} = ${this.value(value)}"
        case Some((column, SortKeyCondition.Lt(value))) => s"$hash AND ${name(column)} < ${this.value(value)}"
        case Some((column, SortKeyCondition.Le(value))) => s"$hash AND ${name(column)} <= ${this.value(value)}"
        case Some((column, SortKeyCondition.Gt(value))) => s"$hash AND ${name(column)} > ${this.value(value)}"
        case Some((column, SortKeyCondition.Ge(value))) => s"$hash AND ${name(column)} >= ${this.value(value)}"
        case Some((column, SortKeyCondition.Between(lower, upper))) =>
          s"$hash AND ${name(column)} BETWEEN ${value(lower)} AND ${value(upper)}"
        case Some((column, SortKeyCondition.BeginsWith(value))) =>
          s"$hash AND begins_with(${name(column)}, ${this.value(value)})"
      }
    }

    def updates(updates: Vector[AttributeUpdate]): String = {
      val sets = updates.collect { case SetUpdate(name, value) =>
        s"${this.name(name)} = ${this.value(value)}"
      }
      val removes = updates.collect { case Remove(name) => this.name(name) }
      val clauses = Vector(
        if (sets.nonEmpty) Some("SET " + sets.mkString(", ")) else None,
        if (removes.nonEmpty) Some("REMOVE " + removes.mkString(", ")) else None
      ).flatten
      clauses.mkString(" ")
    }

    private def operand(operand: Operand): String = operand match {
      case Operand.Column(name)   => this.name(name)
      case Operand.Literal(value) => this.value(value)
    }

    private def operatorText(operator: CompareOp): String = operator match {
      case CompareOp.Eq         => "="
      case CompareOp.Lt         => "<"
      case CompareOp.Le         => "<="
      case CompareOp.Gt         => ">"
      case CompareOp.Ge         => ">="
      case CompareOp.BeginsWith => "begins_with"
    }
  }
}
