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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, DdbValue, OperationResult }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  BatchWriteItemRequest,
  BatchWriteItemResponse,
  QueryResponse,
  WriteRequest
}

import scala.jdk.CollectionConverters._

final class V2ResponsesSpec extends AnyFreeSpec with Matchers {

  private def item(name: String, value: String): java.util.Map[String, AttributeValue] =
    Map(name -> AttributeValue.builder().s(value).build()).asJava

  private def write(): WriteRequest = WriteRequest.builder().build()

  "an empty GetItem map becomes None" in {
    V2Responses.item(new java.util.HashMap[String, AttributeValue]()) shouldBe OperationResult.Item(None)
  }

  "a present GetItem map becomes Some" in {
    V2Responses.item(item("k", "v")) shouldBe
    OperationResult.Item(Some(AttributeMap.of(AttributeName("k") -> DdbValue.Str("v"))))
  }

  "an empty query last key becomes None" in {
    val response = QueryResponse
      .builder()
      .items(Vector(item("k", "v")).asJava)
      .lastEvaluatedKey(new java.util.HashMap[String, AttributeValue]())
      .build()

    V2Responses.items(response) shouldBe
    OperationResult.Items(Vector(AttributeMap.of(AttributeName("k") -> DdbValue.Str("v"))), None)
  }

  "a non-empty query last key is kept" in {
    val response = QueryResponse
      .builder()
      .items(new java.util.ArrayList[java.util.Map[String, AttributeValue]]())
      .lastEvaluatedKey(item("k", "v"))
      .build()

    V2Responses.items(response) shouldBe
    OperationResult.Items(Vector.empty, Some(AttributeMap.of(AttributeName("k") -> DdbValue.Str("v"))))
  }

  "an unprocessed batch item fails with the signal that SnapshotStore retries" in {
    val request = BatchWriteItemRequest
      .builder()
      .requestItems(Map("t" -> Vector(write()).asJava).asJava)
      .build()
    val response = BatchWriteItemResponse
      .builder()
      .unprocessedItems(Map("t" -> Vector(write()).asJava).asJava)
      .build()

    // SnapshotStore retries this type. Issue #887 replaces it, so update both places together.
    V2Responses.batch(request, response) match {
      case Left(cause)   => cause shouldBe a[IllegalStateException]
      case Right(result) => fail(s"expected a batch failure, got $result")
    }
  }

  "a fully processed batch reports the requested count" in {
    val request = BatchWriteItemRequest
      .builder()
      .requestItems(Map("t" -> Vector(write(), write()).asJava).asJava)
      .build()
    val response = BatchWriteItemResponse
      .builder()
      .unprocessedItems(new java.util.HashMap[String, java.util.List[WriteRequest]]())
      .build()

    V2Responses.batch(request, response) shouldBe Right(OperationResult.Written(2))
  }
}
