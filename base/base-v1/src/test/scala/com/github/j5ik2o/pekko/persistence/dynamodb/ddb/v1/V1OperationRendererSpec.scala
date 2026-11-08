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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ Operation, TableName }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.OperationConformanceCases
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

final class V1OperationRendererSpec extends AnyFreeSpec with Matchers {

  "the renderer produces one request kind for every operation case" in {
    val kinds = OperationConformanceCases.operations.map { case (name, operation) =>
      withClue(name) {
        V1OperationRenderer.render(operation) match {
          case Right(_: V1RenderedOperation.Put)           => "put"
          case Right(_: V1RenderedOperation.Get)           => "get"
          case Right(_: V1RenderedOperation.Delete)        => "delete"
          case Right(_: V1RenderedOperation.Update)        => "update"
          case Right(_: V1RenderedOperation.Query)         => "query"
          case Right(_: V1RenderedOperation.BatchWrite)    => "batch-write"
          case Right(_: V1RenderedOperation.TransactWrite) => "transact-write"
          case Left(error)                                 => fail(s"did not render: $error")
        }
      }
    }

    kinds.toSet shouldBe Set("put", "get", "delete", "update", "query", "batch-write", "transact-write")
  }

  "a query carries the index, limit, and paging key" in {
    val query = OperationConformanceCases.operations
      .collectFirst { case ("query with index and paging", value: Operation.Query) =>
        value
      }.getOrElse(fail("the conformance query is missing"))

    val request = V1OperationRenderer.render(query) match {
      case Right(V1RenderedOperation.Query(request)) => request
      case other                                     => fail(s"expected a query request, got $other")
    }

    request.getTableName shouldBe "Conformance"
    request.getIndexName shouldBe "ConformanceIndex"
    request.getLimit.intValue() shouldBe 25
    request.getKeyConditionExpression shouldBe "#n0 = :v0 AND #n1 BETWEEN :v1 AND :v2"
    request.getFilterExpression shouldBe "begins_with(#n2, :v3)"
    request.getExpressionAttributeNames.keySet.asScala should contain allOf ("#n0", "#n1", "#n2")
    request.getExpressionAttributeValues.keySet.asScala should contain allOf (":v0", ":v1", ":v2", ":v3")
    request.getExclusiveStartKey should not be null
  }

  "an update request carries the update and condition placeholders" in {
    val update = OperationConformanceCases.operations
      .collectFirst { case ("update with set and remove", value: Operation.Update) =>
        value
      }.getOrElse(fail("the conformance update is missing"))

    val request = V1OperationRenderer.render(update) match {
      case Right(V1RenderedOperation.Update(request)) => request
      case other                                      => fail(s"expected an update request, got $other")
    }

    request.getUpdateExpression shouldBe "SET #n0 = :v0 REMOVE #n1"
    request.getConditionExpression shouldBe "attribute_not_exists(#n2)"
    request.getExpressionAttributeNames.keySet.asScala should contain allOf ("#n0", "#n1", "#n2")
    request.getExpressionAttributeValues.keySet.asScala should contain(":v0")
  }

  "a batch write carries one write per put and delete" in {
    val batch = OperationConformanceCases.operations
      .collectFirst { case ("batch write", value: Operation.BatchWrite) =>
        value
      }.getOrElse(fail("the conformance batch write is missing"))

    val request = V1OperationRenderer.render(batch) match {
      case Right(V1RenderedOperation.BatchWrite(request)) => request
      case other                                          => fail(s"expected a batch write request, got $other")
    }

    request.getRequestItems.get("Conformance") should have size 2
  }

  "a transaction carries one item per transact item" in {
    val transact = OperationConformanceCases.operations
      .collectFirst { case ("transact write", value: Operation.TransactWrite) =>
        value
      }.getOrElse(fail("the conformance transaction is missing"))

    val request = V1OperationRenderer.render(transact) match {
      case Right(V1RenderedOperation.TransactWrite(request)) => request
      case other                                             => fail(s"expected a transaction request, got $other")
    }

    request.getTransactItems should have size 3
  }

  "the renderer rejects a key from another table" in {
    val otherSpec = OperationConformanceCases.spec.copy(table = TableName("Other"))
    val otherKey  = otherSpec.keyOf(OperationConformanceCases.rowKey.values).toOption.get

    V1OperationRenderer.render(Operation.Get(OperationConformanceCases.table, otherKey)).isLeft shouldBe true
  }

  "the renderer rejects an empty update, batch write, and transaction" in {
    val key = OperationConformanceCases.rowKey

    V1OperationRenderer.render(Operation.Update(TableName("t"), key, Vector.empty)).isLeft shouldBe true
    V1OperationRenderer.render(Operation.BatchWrite(TableName("t"), Vector.empty, Vector.empty)).isLeft shouldBe true
    V1OperationRenderer.render(Operation.TransactWrite(Vector.empty)).isLeft shouldBe true
  }
}
