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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1

import com.amazonaws.services.dynamodbv2.model.{
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
import com.amazonaws.services.dynamodbv2.model.{ Delete => TransactionDelete }
import com.amazonaws.services.dynamodbv2.model.{ Put => TransactionPut }
import com.amazonaws.services.dynamodbv2.model.{ Update => TransactionUpdate }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ EncodeError, Operation }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{
  OperationPlanner,
  RenderedExpression,
  RenderedOperation,
  RenderedTransactItem
}

import scala.jdk.CollectionConverters._

/** Lowers an SDK-neutral operation to an AWS SDK v1 request.
  *
  * The placeholder assignment comes from `OperationPlanner`, so the v1 and v2 renderers produce the same expression
  * strings for the same operation.
  */
object V1OperationRenderer {

  def render(operation: Operation): Either[EncodeError, V1RenderedOperation] =
    OperationPlanner.plan(operation).map(renderPlan)

  private def renderPlan(plan: RenderedOperation): V1RenderedOperation = plan match {
    case RenderedOperation.Put(table, item, condition) =>
      val request = new PutItemRequest().withTableName(table.value).withItem(V1AttributeValues.toItem(item))
      condition.foreach { c =>
        request.setConditionExpression(c.expression)
        if (c.names.nonEmpty) request.setExpressionAttributeNames(names(c))
        if (c.values.nonEmpty) request.setExpressionAttributeValues(values(c))
      }
      V1RenderedOperation.Put(request)

    case RenderedOperation.Get(table, key, consistentRead) =>
      V1RenderedOperation.Get(
        new GetItemRequest()
          .withTableName(table.value)
          .withKey(V1AttributeValues.toItem(key))
          .withConsistentRead(Boolean.box(consistentRead))
      )

    case RenderedOperation.Delete(table, key, condition) =>
      val request = new DeleteItemRequest().withTableName(table.value).withKey(V1AttributeValues.toItem(key))
      condition.foreach { c =>
        request.setConditionExpression(c.expression)
        if (c.names.nonEmpty) request.setExpressionAttributeNames(names(c))
        if (c.values.nonEmpty) request.setExpressionAttributeValues(values(c))
      }
      V1RenderedOperation.Delete(request)

    case RenderedOperation.Update(table, key, update, condition) =>
      val request = new UpdateItemRequest()
        .withTableName(table.value)
        .withKey(V1AttributeValues.toItem(key))
        .withUpdateExpression(update.expression)
      if (update.names.nonEmpty) request.setExpressionAttributeNames(names(update))
      if (update.values.nonEmpty) request.setExpressionAttributeValues(values(update))
      condition.foreach(c => request.setConditionExpression(c.expression))
      V1RenderedOperation.Update(request)

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
      val request = new QueryRequest()
        .withTableName(table.value)
        .withIndexName(index.map(_.value).orNull)
        .withKeyConditionExpression(keyCondition.expression)
        .withConsistentRead(Boolean.box(consistentRead))
        .withScanIndexForward(Boolean.box(scanForward))
      if (keyCondition.names.nonEmpty) request.setExpressionAttributeNames(names(keyCondition))
      if (keyCondition.values.nonEmpty) request.setExpressionAttributeValues(values(keyCondition))
      filter.foreach(c => request.setFilterExpression(c.expression))
      limit.foreach(value => request.setLimit(Integer.valueOf(value)))
      exclusiveStartKey.foreach(key => request.setExclusiveStartKey(V1AttributeValues.toItem(key)))
      V1RenderedOperation.Query(request)

    case RenderedOperation.BatchWrite(table, puts, deletes) =>
      val writes = puts.map(item =>
        new WriteRequest().withPutRequest(new PutRequest().withItem(V1AttributeValues.toItem(item)))
      ) ++
        deletes.map(key =>
          new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(V1AttributeValues.toItem(key)))
        )
      val requestItems = Map(table.value -> writes.asJava).asJava
      V1RenderedOperation.BatchWrite(new BatchWriteItemRequest().withRequestItems(requestItems))

    case RenderedOperation.TransactWrite(items) =>
      V1RenderedOperation.TransactWrite(
        new TransactWriteItemsRequest().withTransactItems(items.map(renderTransactItem).asJava)
      )
  }

  private def renderTransactItem(item: RenderedTransactItem): TransactWriteItem = item match {
    case RenderedTransactItem.Put(table, attributes, condition) =>
      val put = new TransactionPut().withTableName(table.value).withItem(V1AttributeValues.toItem(attributes))
      condition.foreach { c =>
        put.setConditionExpression(c.expression)
        if (c.names.nonEmpty) put.setExpressionAttributeNames(names(c))
        if (c.values.nonEmpty) put.setExpressionAttributeValues(values(c))
      }
      new TransactWriteItem().withPut(put)

    case RenderedTransactItem.Delete(table, key, condition) =>
      val delete = new TransactionDelete().withTableName(table.value).withKey(V1AttributeValues.toItem(key))
      condition.foreach { c =>
        delete.setConditionExpression(c.expression)
        if (c.names.nonEmpty) delete.setExpressionAttributeNames(names(c))
        if (c.values.nonEmpty) delete.setExpressionAttributeValues(values(c))
      }
      new TransactWriteItem().withDelete(delete)

    case RenderedTransactItem.Update(table, key, update, condition) =>
      val request = new TransactionUpdate()
        .withTableName(table.value)
        .withKey(V1AttributeValues.toItem(key))
        .withUpdateExpression(update.expression)
      if (update.names.nonEmpty) request.setExpressionAttributeNames(names(update))
      if (update.values.nonEmpty) request.setExpressionAttributeValues(values(update))
      condition.foreach(c => request.setConditionExpression(c.expression))
      new TransactWriteItem().withUpdate(request)
  }

  private def names(expression: RenderedExpression): java.util.Map[String, String] =
    expression.names.map { case (placeholder, name) => placeholder -> name.value }.toMap.asJava

  private def values(expression: RenderedExpression): java.util.Map[String, AttributeValue] =
    expression.values.map { case (placeholder, value) => placeholder -> V1AttributeValues.toSdk(value) }.toMap.asJava
}
