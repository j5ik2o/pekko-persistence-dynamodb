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
  QueryResult,
  WriteRequest
}
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, DdbValue, OperationResult }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

final class V1ResponsesSpec extends AnyFreeSpec with Matchers {

  private def item(name: String, value: String): java.util.Map[String, AttributeValue] =
    Map(name -> new AttributeValue().withS(value)).asJava

  private def write(): WriteRequest = new WriteRequest()

  "an empty GetItem map becomes None" in {
    V1Responses.item(new java.util.HashMap[String, AttributeValue]()) shouldBe OperationResult.Item(None)
  }

  "a present GetItem map becomes Some" in {
    V1Responses.item(item("k", "v")) shouldBe
    OperationResult.Item(Some(AttributeMap.of(AttributeName("k") -> DdbValue.Str("v"))))
  }

  "an empty query last key becomes None" in {
    val response = new QueryResult()
      .withItems(Vector(item("k", "v")).asJava)
      .withLastEvaluatedKey(new java.util.HashMap[String, AttributeValue]())

    V1Responses.items(response) shouldBe
    OperationResult.Items(Vector(AttributeMap.of(AttributeName("k") -> DdbValue.Str("v"))), None)
  }

  "a non-empty query last key is kept" in {
    val response = new QueryResult()
      .withItems(new java.util.ArrayList[java.util.Map[String, AttributeValue]]())
      .withLastEvaluatedKey(item("k", "v"))

    V1Responses.items(response) shouldBe
    OperationResult.Items(Vector.empty, Some(AttributeMap.of(AttributeName("k") -> DdbValue.Str("v"))))
  }

  "an unprocessed batch item fails with a recoverable failure, not an illegal state" in {
    val request  = new BatchWriteItemRequest().withRequestItems(Map("t" -> Vector(write()).asJava).asJava)
    val response = new BatchWriteItemResult().withUnprocessedItems(Map("t" -> Vector(write()).asJava).asJava)

    V1Responses.batch(request, response) match {
      case Left(cause)   => cause should not be an[IllegalStateException]
      case Right(result) => fail(s"expected a batch failure, got $result")
    }
  }

  "a fully processed batch reports the requested count" in {
    val request = new BatchWriteItemRequest().withRequestItems(Map("t" -> Vector(write(), write()).asJava).asJava)
    val response =
      new BatchWriteItemResult().withUnprocessedItems(new java.util.HashMap[String, java.util.List[WriteRequest]]())

    V1Responses.batch(request, response) shouldBe Right(OperationResult.Written(2))
  }
}
