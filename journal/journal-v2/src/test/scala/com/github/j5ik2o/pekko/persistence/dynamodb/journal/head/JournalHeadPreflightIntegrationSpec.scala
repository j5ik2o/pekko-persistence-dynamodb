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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.head

import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ DockerAssignedPort, DynamoDBContainerHelper }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeDefinition,
  BillingMode,
  CreateTableRequest,
  DeleteTableRequest,
  DescribeTableRequest,
  KeySchemaElement,
  KeyType,
  ResourceNotFoundException,
  ScalarAttributeType,
  StreamSpecification,
  StreamViewType,
  TableStatus
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => JavaDynamoDbSyncClient }

import java.net.URI

object JournalHeadPreflightIntegrationSpec extends DockerAssignedPort {}

/** Runs the preflight against DynamoDB Local, so the `DescribeTable` call and both stream settings are exercised
  * without a real AWS account.
  */
final class JournalHeadPreflightIntegrationSpec extends AnyFreeSpec with Matchers with DynamoDBContainerHelper {

  import JournalHeadPreflight._

  override lazy val dynamoDBPort: Int = JournalHeadPreflightIntegrationSpec.dynamoDBPort

  override lazy val controller = JournalHeadPreflightIntegrationSpec.controller

  private val headWithStream       = "PreflightHeadWithStream"
  private val headWithoutStream    = "PreflightHeadWithoutStream"
  private val journalWithoutStream = "PreflightJournalWithoutStream"

  private var client: JavaDynamoDbSyncClient = _

  private def newClient(): JavaDynamoDbSyncClient =
    JavaDynamoDbSyncClient
      .builder()
      .endpointOverride(URI.create(dynamoDBEndpoint))
      .region(Region.of(dynamoDBRegion.getName))
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(dynamoDBAccessKeyId, dynamoDBSecretAccessKey))
      )
      .build()

  override def afterStartContainers(): Unit = {
    super.afterStartContainers()
    client = newClient()
    createTable()
    createPreflightTable(headWithStream, streamEnabled = true)
    createPreflightTable(headWithoutStream, streamEnabled = false)
    createPreflightTable(journalWithoutStream, streamEnabled = false)
  }

  override def beforeStopContainers(): Unit = {
    Seq(headWithStream, headWithoutStream, journalWithoutStream).foreach(deletePreflightTable)
    deleteTable()
    if (client != null) {
      client.close()
      client = null
    }
    super.beforeStopContainers()
  }

  "passes when the head stream carries the new image and the journal has no stream" in {
    check(client, headWithStream, journalWithoutStream) shouldBe empty
  }

  "reports the journal stream that the test fixture enables" in {
    check(client, journalHeadTableName, journalTableName) shouldBe
    Vector(Problem.JournalStreamEnabled(journalTableName))
  }

  "reports a head table without a stream" in {
    check(client, headWithoutStream, journalWithoutStream) shouldBe
    Vector(Problem.HeadStreamDisabled(headWithoutStream))
  }

  private def createPreflightTable(tableName: String, streamEnabled: Boolean): Unit = {
    val builder = CreateTableRequest
      .builder()
      .tableName(tableName)
      .attributeDefinitions(
        AttributeDefinition.builder().attributeName("pkey").attributeType(ScalarAttributeType.S).build()
      )
      .keySchema(KeySchemaElement.builder().attributeName("pkey").keyType(KeyType.HASH).build())
      .billingMode(BillingMode.PAY_PER_REQUEST)
    if (streamEnabled) {
      builder.streamSpecification(
        StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build()
      )
    }
    client.createTable(builder.build())
    waitUntilActive(tableName)
  }

  private def waitUntilActive(tableName: String): Unit = {
    var attempts = 0
    var active   = false
    while (!active && attempts < 30) {
      attempts += 1
      val status = client
        .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
        .table()
        .tableStatus()
      active = status == TableStatus.ACTIVE
      if (!active) Thread.sleep(500)
    }
    require(active, s"the $tableName table did not become ACTIVE")
  }

  private def deletePreflightTable(tableName: String): Unit = {
    try client.deleteTable(DeleteTableRequest.builder().tableName(tableName).build())
    catch {
      case _: ResourceNotFoundException => ()
    }
  }

}
