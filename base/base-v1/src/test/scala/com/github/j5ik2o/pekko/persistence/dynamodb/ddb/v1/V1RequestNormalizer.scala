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

import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, TransactWriteItem }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{
  RenderedExpression,
  RenderedOperation,
  RenderedTransactItem
}

import scala.jdk.CollectionConverters._

private[v1] object V1RequestNormalizer {

  def normalize(rendered: V1RenderedOperation): RenderedOperation = rendered match {
    case V1RenderedOperation.Put(request) =>
      RenderedOperation.Put(
        TableName(request.getTableName),
        V1AttributeValues.fromItem(request.getItem),
        expression(
          request.getConditionExpression,
          request.getExpressionAttributeNames,
          request.getExpressionAttributeValues
        )
      )

    case V1RenderedOperation.Get(request) =>
      RenderedOperation.Get(
        TableName(request.getTableName),
        V1AttributeValues.fromItem(request.getKey),
        Option(request.getConsistentRead).exists(_.booleanValue())
      )

    case V1RenderedOperation.Delete(request) =>
      RenderedOperation.Delete(
        TableName(request.getTableName),
        V1AttributeValues.fromItem(request.getKey),
        expression(
          request.getConditionExpression,
          request.getExpressionAttributeNames,
          request.getExpressionAttributeValues
        )
      )

    case V1RenderedOperation.Update(request) =>
      RenderedOperation.Update(
        TableName(request.getTableName),
        V1AttributeValues.fromItem(request.getKey),
        RenderedExpression(
          request.getUpdateExpression,
          toNames(request.getExpressionAttributeNames),
          toValues(request.getExpressionAttributeValues)
        ).normalized,
        expression(
          request.getConditionExpression,
          request.getExpressionAttributeNames,
          request.getExpressionAttributeValues
        )
      )

    case V1RenderedOperation.Query(request) =>
      RenderedOperation.Query(
        TableName(request.getTableName),
        Option(request.getIndexName).map(IndexName.apply),
        RenderedExpression(
          request.getKeyConditionExpression,
          toNames(request.getExpressionAttributeNames),
          toValues(request.getExpressionAttributeValues)
        ).normalized,
        expression(
          request.getFilterExpression,
          request.getExpressionAttributeNames,
          request.getExpressionAttributeValues
        ),
        Option(request.getLimit).map(_.intValue()),
        Option(request.getConsistentRead).exists(_.booleanValue()),
        Option(request.getScanIndexForward).exists(_.booleanValue()),
        Option(request.getExclusiveStartKey).map(V1AttributeValues.fromItem)
      )

    case V1RenderedOperation.BatchWrite(request) =>
      val entries = Option(request.getRequestItems).map(_.asScala.toVector).getOrElse(Vector.empty)
      val (table, writes) = entries.headOption
        .map { case (name, list) => TableName(name) -> list.asScala.toVector }
        .getOrElse(TableName("") -> Vector.empty)
      RenderedOperation.BatchWrite(
        table,
        writes.flatMap(write => Option(write.getPutRequest).map(put => V1AttributeValues.fromItem(put.getItem))),
        writes.flatMap(write => Option(write.getDeleteRequest).map(delete => V1AttributeValues.fromItem(delete.getKey)))
      )

    case V1RenderedOperation.TransactWrite(request) =>
      RenderedOperation.TransactWrite(
        Option(request.getTransactItems).map(_.asScala.toVector.map(normalizeTransactItem)).getOrElse(Vector.empty)
      )
  }

  private def normalizeTransactItem(item: TransactWriteItem): RenderedTransactItem = {
    Option(item.getPut) match {
      case Some(put) =>
        RenderedTransactItem.Put(
          TableName(put.getTableName),
          V1AttributeValues.fromItem(put.getItem),
          expression(
            put.getConditionExpression,
            put.getExpressionAttributeNames,
            put.getExpressionAttributeValues
          )
        )
      case None =>
        Option(item.getDelete) match {
          case Some(delete) =>
            RenderedTransactItem.Delete(
              TableName(delete.getTableName),
              V1AttributeValues.fromItem(delete.getKey),
              expression(
                delete.getConditionExpression,
                delete.getExpressionAttributeNames,
                delete.getExpressionAttributeValues
              )
            )
          case None =>
            Option(item.getUpdate) match {
              case Some(update) =>
                RenderedTransactItem.Update(
                  TableName(update.getTableName),
                  V1AttributeValues.fromItem(update.getKey),
                  RenderedExpression(
                    update.getUpdateExpression,
                    toNames(update.getExpressionAttributeNames),
                    toValues(update.getExpressionAttributeValues)
                  ).normalized,
                  expression(
                    update.getConditionExpression,
                    update.getExpressionAttributeNames,
                    update.getExpressionAttributeValues
                  )
                )
              case None =>
                sys.error("a transaction item must contain a put, a delete, or an update")
            }
        }
    }
  }

  private def expression(
      value: String,
      attributeNames: java.util.Map[String, String],
      attributeValues: java.util.Map[String, AttributeValue]
  ): Option[RenderedExpression] =
    Option(value).map { text =>
      RenderedExpression(text, toNames(attributeNames), toValues(attributeValues)).normalized
    }

  private def toNames(entries: java.util.Map[String, String]): Vector[(String, AttributeName)] =
    Option(entries)
      .map(_.asScala.toVector.map { case (placeholder, name) => placeholder -> AttributeName(name) }).getOrElse(
        Vector.empty
      )

  private def toValues(entries: java.util.Map[String, AttributeValue]): Vector[(String, DdbValue)] =
    Option(entries)
      .map(_.asScala.toVector.map { case (placeholder, value) => placeholder -> V1AttributeValues.fromSdk(value) })
      .getOrElse(Vector.empty)
}
