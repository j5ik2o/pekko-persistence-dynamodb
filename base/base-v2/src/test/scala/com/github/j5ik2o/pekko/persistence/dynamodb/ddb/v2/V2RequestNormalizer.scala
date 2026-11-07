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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{
  RenderedExpression,
  RenderedOperation,
  RenderedTransactItem
}
import software.amazon.awssdk.services.dynamodb.model.{ AttributeValue, TransactWriteItem }

import scala.jdk.CollectionConverters._

private[v2] object V2RequestNormalizer {

  def normalize(rendered: V2RenderedOperation): RenderedOperation = rendered match {
    case V2RenderedOperation.Put(request) =>
      RenderedOperation.Put(
        TableName(request.tableName()),
        V2AttributeValues.fromItem(request.item()),
        expression(
          request.conditionExpression(),
          request.expressionAttributeNames(),
          request.expressionAttributeValues()
        )
      )

    case V2RenderedOperation.Get(request) =>
      RenderedOperation.Get(
        TableName(request.tableName()),
        V2AttributeValues.fromItem(request.key()),
        Option(request.consistentRead()).exists(_.booleanValue())
      )

    case V2RenderedOperation.Delete(request) =>
      RenderedOperation.Delete(
        TableName(request.tableName()),
        V2AttributeValues.fromItem(request.key()),
        expression(
          request.conditionExpression(),
          request.expressionAttributeNames(),
          request.expressionAttributeValues()
        )
      )

    case V2RenderedOperation.Update(request) =>
      RenderedOperation.Update(
        TableName(request.tableName()),
        V2AttributeValues.fromItem(request.key()),
        RenderedExpression(
          request.updateExpression(),
          toNames(request.expressionAttributeNames()),
          toValues(request.expressionAttributeValues())
        ).normalized,
        expression(
          request.conditionExpression(),
          request.expressionAttributeNames(),
          request.expressionAttributeValues()
        )
      )

    case V2RenderedOperation.Query(request) =>
      RenderedOperation.Query(
        TableName(request.tableName()),
        Option(request.indexName()).map(IndexName.apply),
        RenderedExpression(
          request.keyConditionExpression(),
          toNames(request.expressionAttributeNames()),
          toValues(request.expressionAttributeValues())
        ).normalized,
        expression(
          request.filterExpression(),
          request.expressionAttributeNames(),
          request.expressionAttributeValues()
        ),
        Option(request.limit()).map(_.intValue()),
        Option(request.consistentRead()).exists(_.booleanValue()),
        Option(request.scanIndexForward()).exists(_.booleanValue()),
        Option(request.exclusiveStartKey()).map(V2AttributeValues.fromItem)
      )

    case V2RenderedOperation.BatchWrite(request) =>
      val entries = Option(request.requestItems()).map(_.asScala.toVector).getOrElse(Vector.empty)
      val (table, writes) = entries.headOption
        .map { case (name, list) => TableName(name) -> list.asScala.toVector }
        .getOrElse(TableName("") -> Vector.empty)
      RenderedOperation.BatchWrite(
        table,
        writes.flatMap(write => Option(write.putRequest()).map(put => V2AttributeValues.fromItem(put.item()))),
        writes.flatMap(write => Option(write.deleteRequest()).map(delete => V2AttributeValues.fromItem(delete.key())))
      )

    case V2RenderedOperation.TransactWrite(request) =>
      RenderedOperation.TransactWrite(
        Option(request.transactItems()).map(_.asScala.toVector.map(normalizeTransactItem)).getOrElse(Vector.empty)
      )
  }

  private def normalizeTransactItem(item: TransactWriteItem): RenderedTransactItem = {
    Option(item.put()) match {
      case Some(put) =>
        RenderedTransactItem.Put(
          TableName(put.tableName()),
          V2AttributeValues.fromItem(put.item()),
          expression(put.conditionExpression(), put.expressionAttributeNames(), put.expressionAttributeValues())
        )
      case None =>
        Option(item.delete()) match {
          case Some(delete) =>
            RenderedTransactItem.Delete(
              TableName(delete.tableName()),
              V2AttributeValues.fromItem(delete.key()),
              expression(
                delete.conditionExpression(),
                delete.expressionAttributeNames(),
                delete.expressionAttributeValues()
              )
            )
          case None =>
            Option(item.update()) match {
              case Some(update) =>
                RenderedTransactItem.Update(
                  TableName(update.tableName()),
                  V2AttributeValues.fromItem(update.key()),
                  RenderedExpression(
                    update.updateExpression(),
                    toNames(update.expressionAttributeNames()),
                    toValues(update.expressionAttributeValues())
                  ).normalized,
                  expression(
                    update.conditionExpression(),
                    update.expressionAttributeNames(),
                    update.expressionAttributeValues()
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
      .map(_.asScala.toVector.map { case (placeholder, value) => placeholder -> V2AttributeValues.fromSdk(value) })
      .getOrElse(Vector.empty)
}
