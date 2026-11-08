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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.store

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._

import scala.collection.mutable

final class InMemoryDdbAdapter(keyColumns: Map[TableName, Vector[AttributeName]]) extends DdbClientAdapter {

  override val capabilities: CapabilityProfile = CapabilityProfile.V2

  private val tables: mutable.Map[TableName, mutable.LinkedHashMap[String, AttributeMap]] = mutable.Map.empty

  def seed(table: TableName, item: AttributeMap): Unit = {
    tableItems(table).update(itemId(table, item), item)
    ()
  }

  override def run(operation: Operation): Source[OperationResult, NotUsed] = Source.single(apply(operation))

  override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = Source(select(query).values)

  override def close(): Unit = ()

  private def apply(operation: Operation): OperationResult = operation match {
    case Operation.Put(table, item, _) =>
      tableItems(table).update(itemId(table, item), item)
      OperationResult.Written(1)
    case Operation.Get(table, key, _) =>
      OperationResult.Item(tables.get(table).flatMap(_.get(id(key.values.entries))))
    case Operation.Delete(table, key, _) =>
      tables.get(table).foreach(_.remove(id(key.values.entries)))
      OperationResult.Written(1)
    case Operation.Update(table, key, updates, _) =>
      val current = tables.get(table).flatMap(_.get(id(key.values.entries))).getOrElse(key.values)
      val updated = updates.foldLeft(current) {
        case (item, AttributeUpdate.Set(name, value)) => item.updated(name, value)
        case (item, AttributeUpdate.Remove(name))     => item.removed(name)
      }
      tableItems(table).update(id(key.values.entries), updated)
      OperationResult.Written(1)
    case query: Operation.Query =>
      select(query)
    case Operation.BatchWrite(table, puts, deletes) =>
      puts.foreach(item => tableItems(table).update(itemId(table, item), item))
      deletes.foreach(key => tables.get(table).foreach(_.remove(id(key.values.entries))))
      OperationResult.Written(puts.size + deletes.size)
    case Operation.TransactWrite(items) =>
      OperationResult.TransactionCommitted(items.size)
  }

  private def select(query: Operation.Query): OperationResult.Items = {
    val candidates = tableItems(query.table).values.toVector
      .filter(item => item.get(query.hashKey._1).contains(query.hashKey._2))
      .filter(item =>
        query.rangeKey.forall { case (name, condition) =>
          item.get(name).exists(value => matchesSortKey(value, condition))
        }
      )
      .filter(item => query.filter.forall(condition => matchesCondition(item, condition)))
    val ordered = query.rangeKey match {
      case Some((name, _)) => candidates.sortBy(item => numberAt(item, name))
      case None            => candidates
    }
    val directed = if (query.scanForward) ordered else ordered.reverse
    val after = query.exclusiveStartKey match {
      case None =>
        directed
      case Some(startKey) =>
        val startId = itemId(query.table, startKey)
        val index   = directed.indexWhere(item => itemId(query.table, item) == startId)
        if (index >= 0) directed.drop(index + 1) else directed
    }
    val page = query.limit.fold(after)(limit => after.take(limit))
    val lastEvaluatedKey = query.limit match {
      case Some(limit) if after.size > limit => page.lastOption.map(item => keysOf(query.table, item))
      case _                                 => None
    }
    OperationResult.Items(page, lastEvaluatedKey)
  }

  private def tableItems(table: TableName): mutable.LinkedHashMap[String, AttributeMap] =
    tables.getOrElseUpdate(table, mutable.LinkedHashMap.empty[String, AttributeMap])

  private def columnsOf(table: TableName): Vector[AttributeName] = keyColumns.getOrElse(table, Vector.empty)

  private def keyEntries(table: TableName, item: AttributeMap): Vector[(AttributeName, DdbValue)] =
    columnsOf(table).flatMap(name => item.get(name).map(value => name -> value))

  private def itemId(table: TableName, item: AttributeMap): String = id(keyEntries(table, item))

  private def keysOf(table: TableName, item: AttributeMap): AttributeMap = AttributeMap(keyEntries(table, item))

  private def id(entries: Vector[(AttributeName, DdbValue)]): String =
    entries.map { case (name, value) => s"${name.value}=$value" }.mkString("&")

  private def numberAt(item: AttributeMap, name: AttributeName): BigDecimal =
    item.get(name).collect { case DdbValue.Num(value) => value }.getOrElse(BigDecimal(0))

  private def matchesSortKey(value: DdbValue, condition: SortKeyCondition): Boolean = condition match {
    case SortKeyCondition.Eq(expected)          => value == expected
    case SortKeyCondition.Lt(expected)          => compare(value, expected) < 0
    case SortKeyCondition.Le(expected)          => compare(value, expected) <= 0
    case SortKeyCondition.Gt(expected)          => compare(value, expected) > 0
    case SortKeyCondition.Ge(expected)          => compare(value, expected) >= 0
    case SortKeyCondition.Between(lower, upper) => compare(lower, value) <= 0 && compare(value, upper) <= 0
    case SortKeyCondition.BeginsWith(expected) =>
      (value, expected) match {
        case (DdbValue.Str(actual), DdbValue.Str(prefix)) => actual.startsWith(prefix)
        case _                                            => false
      }
  }

  private def matchesCondition(item: AttributeMap, condition: Condition): Boolean = condition match {
    case Condition.AttributeExists(name)    => item.contains(name)
    case Condition.AttributeNotExists(name) => !item.contains(name)
    case Condition.Compare(left, operator, right) =>
      compareOperation(valueOf(item, left), operator, valueOf(item, right))
    case Condition.And(left, right) => matchesCondition(item, left) && matchesCondition(item, right)
    case Condition.Or(left, right)  => matchesCondition(item, left) || matchesCondition(item, right)
    case Condition.Not(inner)       => !matchesCondition(item, inner)
  }

  private def valueOf(item: AttributeMap, operand: Operand): DdbValue = operand match {
    case Operand.Column(name)   => item.get(name).getOrElse(DdbValue.Null)
    case Operand.Literal(value) => value
  }

  private def compareOperation(left: DdbValue, operator: CompareOp, right: DdbValue): Boolean = operator match {
    case CompareOp.Eq         => left == right
    case CompareOp.Lt         => compare(left, right) < 0
    case CompareOp.Le         => compare(left, right) <= 0
    case CompareOp.Gt         => compare(left, right) > 0
    case CompareOp.Ge         => compare(left, right) >= 0
    case CompareOp.BeginsWith => matchesSortKey(left, SortKeyCondition.BeginsWith(right))
  }

  private def compare(left: DdbValue, right: DdbValue): Int = (left, right) match {
    case (DdbValue.Num(actual), DdbValue.Num(expected)) => actual.compare(expected)
    case (DdbValue.Str(actual), DdbValue.Str(expected)) => actual.compareTo(expected)
    case _                                              => 0
  }
}
