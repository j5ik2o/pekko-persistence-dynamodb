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

  "an unprocessed batch item fails" in {
    val request = BatchWriteItemRequest
      .builder()
      .requestItems(Map("t" -> Vector(write()).asJava).asJava)
      .build()
    val response = BatchWriteItemResponse
      .builder()
      .unprocessedItems(Map("t" -> Vector(write()).asJava).asJava)
      .build()

    V2Responses.batch(request, response).isLeft shouldBe true
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
