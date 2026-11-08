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

  "an unprocessed batch item fails" in {
    val request  = new BatchWriteItemRequest().withRequestItems(Map("t" -> Vector(write()).asJava).asJava)
    val response = new BatchWriteItemResult().withUnprocessedItems(Map("t" -> Vector(write()).asJava).asJava)

    V1Responses.batch(request, response).isLeft shouldBe true
  }

  "a fully processed batch reports the requested count" in {
    val request = new BatchWriteItemRequest().withRequestItems(Map("t" -> Vector(write(), write()).asJava).asJava)
    val response =
      new BatchWriteItemResult().withUnprocessedItems(new java.util.HashMap[String, java.util.List[WriteRequest]]())

    V1Responses.batch(request, response) shouldBe Right(OperationResult.Written(2))
  }
}
