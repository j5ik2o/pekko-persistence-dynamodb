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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeName, DdbValue, EncodeError, Operation }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{
  OperationPlanner,
  RenderedExpression,
  RenderedOperation,
  RenderedTransactItem
}
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  BatchWriteItemRequest,
  DeleteItemRequest,
  DeleteRequest,
  GetItemRequest,
  PutItemRequest,
  PutRequest,
  QueryRequest,
  TransactWriteItem,
  TransactWriteItemsRequest,
  UpdateItemRequest,
  WriteRequest
}
import software.amazon.awssdk.services.dynamodb.model.{ Delete => TransactionDelete }
import software.amazon.awssdk.services.dynamodb.model.{ Put => TransactionPut }
import software.amazon.awssdk.services.dynamodb.model.{ Update => TransactionUpdate }

import scala.jdk.CollectionConverters._

/** Lowers an SDK-neutral operation to an AWS SDK v2 request.
  *
  * The placeholder assignment comes from `OperationPlanner`, so the v1 and v2 renderers produce the same expression
  * strings for the same operation.
  */
object V2OperationRenderer {

  def render(operation: Operation): Either[EncodeError, V2RenderedOperation] =
    OperationPlanner.plan(operation).map(renderPlan)

  private def renderPlan(plan: RenderedOperation): V2RenderedOperation = plan match {
    case RenderedOperation.Put(table, item, condition) =>
      val builder = PutItemRequest.builder().tableName(table.value).item(V2AttributeValues.toItem(item))
      condition.foreach { c =>
        builder.conditionExpression(c.expression)
        if (c.names.nonEmpty) builder.expressionAttributeNames(names(c))
        if (c.values.nonEmpty) builder.expressionAttributeValues(values(c))
      }
      V2RenderedOperation.Put(builder.build())

    case RenderedOperation.Get(table, key, consistentRead) =>
      V2RenderedOperation.Get(
        GetItemRequest
          .builder()
          .tableName(table.value)
          .key(V2AttributeValues.toItem(key))
          .consistentRead(Boolean.box(consistentRead))
          .build()
      )

    case RenderedOperation.Delete(table, key, condition) =>
      val builder = DeleteItemRequest.builder().tableName(table.value).key(V2AttributeValues.toItem(key))
      condition.foreach { c =>
        builder.conditionExpression(c.expression)
        if (c.names.nonEmpty) builder.expressionAttributeNames(names(c))
        if (c.values.nonEmpty) builder.expressionAttributeValues(values(c))
      }
      V2RenderedOperation.Delete(builder.build())

    case RenderedOperation.Update(table, key, update, condition) =>
      val builder = UpdateItemRequest
        .builder()
        .tableName(table.value)
        .key(V2AttributeValues.toItem(key))
        .updateExpression(update.expression)
      val expressions = update +: condition.toVector
      if (expressions.exists(_.names.nonEmpty)) builder.expressionAttributeNames(names(expressions: _*))
      if (expressions.exists(_.values.nonEmpty)) builder.expressionAttributeValues(values(expressions: _*))
      condition.foreach(c => builder.conditionExpression(c.expression))
      V2RenderedOperation.Update(builder.build())

    case RenderedOperation.Query(
          table,
          index,
          keyCondition,
          filter,
          limit,
          consistentRead,
          scanForward,
          exclusiveStartKey
        ) =>
      val builder = QueryRequest
        .builder()
        .tableName(table.value)
        .keyConditionExpression(keyCondition.expression)
        .consistentRead(Boolean.box(consistentRead))
        .scanIndexForward(Boolean.box(scanForward))
      index.foreach(value => builder.indexName(value.value))
      val expressions = keyCondition +: filter.toVector
      if (expressions.exists(_.names.nonEmpty)) builder.expressionAttributeNames(names(expressions: _*))
      if (expressions.exists(_.values.nonEmpty)) builder.expressionAttributeValues(values(expressions: _*))
      filter.foreach(c => builder.filterExpression(c.expression))
      limit.foreach(value => builder.limit(Integer.valueOf(value)))
      exclusiveStartKey.foreach(key => builder.exclusiveStartKey(V2AttributeValues.toItem(key)))
      V2RenderedOperation.Query(builder.build())

    case RenderedOperation.BatchWrite(table, puts, deletes) =>
      val writes = puts.map { item =>
        WriteRequest.builder().putRequest(PutRequest.builder().item(V2AttributeValues.toItem(item)).build()).build()
      } ++ deletes.map { key =>
        WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(V2AttributeValues.toItem(key)).build()).build()
      }
      val requestItems = Map(table.value -> writes.asJava).asJava
      V2RenderedOperation.BatchWrite(BatchWriteItemRequest.builder().requestItems(requestItems).build())

    case RenderedOperation.TransactWrite(items) =>
      V2RenderedOperation.TransactWrite(
        TransactWriteItemsRequest.builder().transactItems(items.map(renderTransactItem).asJava).build()
      )
  }

  private def renderTransactItem(item: RenderedTransactItem): TransactWriteItem = item match {
    case RenderedTransactItem.Put(table, attributes, condition) =>
      val builder = TransactionPut.builder().tableName(table.value).item(V2AttributeValues.toItem(attributes))
      condition.foreach { c =>
        builder.conditionExpression(c.expression)
        if (c.names.nonEmpty) builder.expressionAttributeNames(names(c))
        if (c.values.nonEmpty) builder.expressionAttributeValues(values(c))
      }
      TransactWriteItem.builder().put(builder.build()).build()

    case RenderedTransactItem.Delete(table, key, condition) =>
      val builder = TransactionDelete.builder().tableName(table.value).key(V2AttributeValues.toItem(key))
      condition.foreach { c =>
        builder.conditionExpression(c.expression)
        if (c.names.nonEmpty) builder.expressionAttributeNames(names(c))
        if (c.values.nonEmpty) builder.expressionAttributeValues(values(c))
      }
      TransactWriteItem.builder().delete(builder.build()).build()

    case RenderedTransactItem.Update(table, key, update, condition) =>
      val builder = TransactionUpdate
        .builder()
        .tableName(table.value)
        .key(V2AttributeValues.toItem(key))
        .updateExpression(update.expression)
      val expressions = update +: condition.toVector
      if (expressions.exists(_.names.nonEmpty)) builder.expressionAttributeNames(names(expressions: _*))
      if (expressions.exists(_.values.nonEmpty)) builder.expressionAttributeValues(values(expressions: _*))
      condition.foreach(c => builder.conditionExpression(c.expression))
      TransactWriteItem.builder().update(builder.build()).build()
  }

  private def names(expressions: RenderedExpression*): java.util.Map[String, String] = {
    val merged = scala.collection.mutable.LinkedHashMap.empty[String, AttributeName]
    expressions.foreach(_.names.foreach { case (placeholder, name) => merged.update(placeholder, name) })
    merged.iterator.map { case (placeholder, name) => placeholder -> name.value }.toMap.asJava
  }

  private def values(expressions: RenderedExpression*): java.util.Map[String, AttributeValue] = {
    val merged = scala.collection.mutable.LinkedHashMap.empty[String, DdbValue]
    expressions.foreach(_.values.foreach { case (placeholder, value) => merged.update(placeholder, value) })
    merged.iterator.map { case (placeholder, value) => placeholder -> V2AttributeValues.toSdk(value) }.toMap.asJava
  }
}
