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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.OperationResult
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  BatchWriteItemRequest,
  BatchWriteItemResponse,
  QueryResponse
}

import scala.jdk.CollectionConverters._

private[v2] object V2Responses {

  def item(values: java.util.Map[String, AttributeValue]): OperationResult =
    OperationResult.Item(Option(values).filter(_.asScala.nonEmpty).map(V2AttributeValues.fromItem))

  def items(response: QueryResponse): OperationResult = {
    val values = Option(response.items())
      .map(_.asScala.toVector.map(V2AttributeValues.fromItem))
      .getOrElse(Vector.empty)
    val lastEvaluatedKey =
      Option(response.lastEvaluatedKey()).filter(_.asScala.nonEmpty).map(V2AttributeValues.fromItem)
    OperationResult.Items(values, lastEvaluatedKey)
  }

  def batch(
      request: BatchWriteItemRequest,
      response: BatchWriteItemResponse
  ): Either[Throwable, OperationResult] = {
    val unprocessed = Option(response.unprocessedItems()).map(_.asScala.values.map(_.size).sum).getOrElse(0)
    if (unprocessed > 0) {
      Left(new IllegalStateException(s"BatchWriteItem left $unprocessed unprocessed items"))
    } else {
      val count = Option(request.requestItems()).map(_.asScala.values.map(_.size).sum).getOrElse(0)
      Right(OperationResult.Written(count))
    }
  }
}
