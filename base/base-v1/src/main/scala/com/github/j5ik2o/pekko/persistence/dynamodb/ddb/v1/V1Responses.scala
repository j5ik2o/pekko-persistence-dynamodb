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
  BatchWriteItemResult,
  QueryResult
}
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.OperationResult

import scala.jdk.CollectionConverters._

private[v1] object V1Responses {

  def item(values: java.util.Map[String, AttributeValue]): OperationResult =
    OperationResult.Item(Option(values).filter(_.asScala.nonEmpty).map(V1AttributeValues.fromItem))

  def items(response: QueryResult): OperationResult = {
    val values = Option(response.getItems)
      .map(_.asScala.toVector.map(V1AttributeValues.fromItem))
      .getOrElse(Vector.empty)
    val lastEvaluatedKey =
      Option(response.getLastEvaluatedKey).filter(_.asScala.nonEmpty).map(V1AttributeValues.fromItem)
    OperationResult.Items(values, lastEvaluatedKey)
  }

  def batch(
      request: BatchWriteItemRequest,
      response: BatchWriteItemResult
  ): Either[Throwable, OperationResult] = {
    val unprocessed = Option(response.getUnprocessedItems).map(_.asScala.values.map(_.size).sum).getOrElse(0)
    if (unprocessed > 0) {
      Left(new IllegalStateException(s"BatchWriteItem left $unprocessed unprocessed items"))
    } else {
      val count = Option(request.getRequestItems).map(_.asScala.values.map(_.size).sum).getOrElse(0)
      Right(OperationResult.Written(count))
    }
  }
}
