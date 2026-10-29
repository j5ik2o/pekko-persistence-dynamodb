/*
 * Copyright 2020 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.utils

import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.model._
import com.amazonaws.services.dynamodbv2.{ AmazonDynamoDB, AmazonDynamoDBClientBuilder }
import com.github.j5ik2o.dockerController.RandomPortUtil.temporaryServerPort
import com.github.j5ik2o.dockerController.WaitPredicates.WaitPredicate
import com.github.j5ik2o.dockerController.dynamodbLocal.DynamoDBLocalController
import com.github.j5ik2o.dockerController.{
  DockerController,
  DockerControllerHelper,
  DockerControllerSpecSupport,
  WaitPredicates
}
import org.scalatest.TestSuite

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

trait DockerControllerHelperUtil extends DockerControllerHelper {

  val testTimeFactor: Int = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toInt
  logger.debug(s"testTimeFactor = $testTimeFactor")

  lazy val dynamoDBPort: Int              = temporaryServerPort()
  val controller: DynamoDBLocalController = new DynamoDBLocalController(dockerClient, imageTag = None)(dynamoDBPort)

  val waitPredicate: WaitPredicate = WaitPredicates.forLogMessageByRegex(
    DynamoDBLocalController.RegexOfWaitPredicate,
    Some((1 * testTimeFactor).seconds)
  )

  val waitPredicateSetting: WaitPredicateSetting = WaitPredicateSetting(Duration.Inf, waitPredicate)

  val tableName = "test"

  override protected val dockerControllers: Vector[DockerController] = Vector(controller)

  override protected val waitPredicatesSettings: Map[DockerController, WaitPredicateSetting] =
    Map(
      controller -> waitPredicateSetting
    )

  val dynamoDBEndpoint: String        = s"http://$dockerHost:$dynamoDBPort"
  val dynamoDBRegion: Regions         = Regions.AP_NORTHEAST_1
  val dynamoDBAccessKeyId: String     = "x"
  val dynamoDBSecretAccessKey: String = "x"

  protected lazy val dynamoDBClient: AmazonDynamoDB = {
    AmazonDynamoDBClientBuilder
      .standard().withCredentials(
        new AWSStaticCredentialsProvider(
          new BasicAWSCredentials(dynamoDBAccessKeyId, dynamoDBSecretAccessKey)
        )
      )
      .withEndpointConfiguration(
        new EndpointConfiguration(dynamoDBEndpoint, dynamoDBRegion.getName)
      ).build()
  }

  val journalTableName     = "Journal"
  val journalHeadTableName = "JournalHead"
  val snapshotTableName    = "Snapshot"
  val stateTableName       = "State"

  protected val waitIntervalForDynamoDBLocal: FiniteDuration = 500.milliseconds

  protected val MaxCount = 10

  def startContainer(): Unit = {
    createDockerContainer(controller, None)
    startDockerContainer(controller, None)
  }

  def stopContainer(): Unit = {
    stopDockerContainer(controller, None)
    removeDockerContainer(controller, None)
  }

  protected def waitDynamoDBLocal(tableNames: Seq[String]): Unit = {
    var isWaken: Boolean = false
    var counter          = 0
    while (counter < MaxCount && !isWaken) {
      counter += 1
      try {
        val listTablesResult = dynamoDBClient.listTables(100)
        if (tableNames.forall(s => listTablesResult.getTableNames.asScala.contains(s))) {
          isWaken = true
        } else {
          Thread.sleep(1000)
        }
      } catch {
        case _: ResourceNotFoundException =>
          Thread.sleep(waitIntervalForDynamoDBLocal.toMillis)
      }
    }
    require(isWaken, s"the tables [${tableNames.mkString(", ")}] did not become available")
  }

  def deleteTable(): Unit = synchronized {
    Thread.sleep(500)
    deleteJournalTable()
    deleteJournalHeadTable()
    deleteSnapshotTable()
    deleteStateTable()
    Thread.sleep(500)
  }

  val legacyJournalTable  = false
  val legacySnapshotTable = false

  def createTable(): Unit = synchronized {
    Thread.sleep(500)
    if (legacyJournalTable)
      createLegacyJournalTable()
    else
      createJournalTable()
    createJournalHeadTable()
    if (legacySnapshotTable)
      createLegacySnapshotTable()
    else
      createSnapshotTable()
    createStateTable()
    waitDynamoDBLocal(Seq(journalTableName, journalHeadTableName, snapshotTableName))
  }

  private def deleteJournalTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (listTablesResult.getTableNames.asScala.exists(_ == journalTableName))
      dynamoDBClient.deleteTable(journalTableName)
    val result = dynamoDBClient.listTables(100)
    require(!result.getTableNames.asScala.exists(_ == journalTableName))
  }

  private def deleteJournalHeadTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (listTablesResult.getTableNames.asScala.exists(_ == journalHeadTableName))
      dynamoDBClient.deleteTable(journalHeadTableName)
    val result = dynamoDBClient.listTables(100)
    require(!result.getTableNames.asScala.exists(_ == journalHeadTableName))
  }

  private def deleteSnapshotTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (listTablesResult.getTableNames.asScala.exists(_ == snapshotTableName))
      dynamoDBClient.deleteTable(snapshotTableName)
    val result = dynamoDBClient.listTables(100)
    require(!result.getTableNames.asScala.exists(_ == snapshotTableName))
  }

  private def deleteStateTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (listTablesResult.getTableNames.asScala.exists(_ == stateTableName))
      dynamoDBClient.deleteTable(stateTableName)
    val result = dynamoDBClient.listTables(100)
    require(!result.getTableNames.asScala.exists(_ == stateTableName))
  }

  def createLegacySnapshotTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (!listTablesResult.getTableNames.asScala.exists(_ == snapshotTableName)) {
      val createRequest = new CreateTableRequest()
        .withTableName(snapshotTableName).withAttributeDefinitions(
          Seq(
            new AttributeDefinition().withAttributeName("persistence-id").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("sequence-nr").withAttributeType(ScalarAttributeType.N)
          ).asJava
        ).withKeySchema(
          Seq(
            new KeySchemaElement().withAttributeName("persistence-id").withKeyType(KeyType.HASH),
            new KeySchemaElement().withAttributeName("sequence-nr").withKeyType(KeyType.RANGE)
          ).asJava
        ).withProvisionedThroughput(
          new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
        )
      val createResponse = dynamoDBClient.createTable(createRequest)
      require(createResponse.getSdkHttpMetadata.getHttpStatusCode == 200)
    }
  }

  def createSnapshotTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (!listTablesResult.getTableNames.asScala.exists(_ == snapshotTableName)) {
      val createRequest = new CreateTableRequest()
        .withTableName(snapshotTableName).withAttributeDefinitions(
          Seq(
            new AttributeDefinition().withAttributeName("pkey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("skey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("persistence-id").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("sequence-nr").withAttributeType(ScalarAttributeType.N)
          ).asJava
        ).withKeySchema(
          Seq(
            new KeySchemaElement().withAttributeName("pkey").withKeyType(KeyType.HASH),
            new KeySchemaElement().withAttributeName("skey").withKeyType(KeyType.RANGE)
          ).asJava
        )
        .withGlobalSecondaryIndexes(
          Seq(
            new GlobalSecondaryIndex()
              .withIndexName("GetSnapshotRowsIndex").withKeySchema(
                Seq(
                  new KeySchemaElement().withAttributeName("persistence-id").withKeyType(KeyType.HASH),
                  new KeySchemaElement().withAttributeName("sequence-nr").withKeyType(KeyType.RANGE)
                ).asJava
              ).withProjection(new Projection().withProjectionType(ProjectionType.ALL))
              .withProvisionedThroughput(
                new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
              )
          ).asJava
        )
        .withProvisionedThroughput(
          new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
        )
      val createResponse = dynamoDBClient.createTable(createRequest)
      require(createResponse.getSdkHttpMetadata.getHttpStatusCode == 200)
    }
  }

  private def createLegacyJournalTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (!listTablesResult.getTableNames.asScala.exists(_ == journalTableName)) {
      val createRequest = new CreateTableRequest()
        .withTableName(journalTableName)
        .withAttributeDefinitions(
          Seq(
            new AttributeDefinition().withAttributeName("pkey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("persistence-id").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("sequence-nr").withAttributeType(ScalarAttributeType.N),
            new AttributeDefinition().withAttributeName("tags").withAttributeType(ScalarAttributeType.S)
          ).asJava
        ).withKeySchema(
          Seq(
            new KeySchemaElement().withAttributeName("pkey").withKeyType(KeyType.HASH),
            new KeySchemaElement().withAttributeName("sequence-nr").withKeyType(KeyType.RANGE)
          ).asJava
        ).withProvisionedThroughput(
          new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
        ).withGlobalSecondaryIndexes(
          Seq(
            new GlobalSecondaryIndex()
              .withIndexName("GetJournalRowsIndex").withKeySchema(
                Seq(
                  new KeySchemaElement().withAttributeName("persistence-id").withKeyType(KeyType.HASH),
                  new KeySchemaElement().withAttributeName("sequence-nr").withKeyType(KeyType.RANGE)
                ).asJava
              ).withProjection(new Projection().withProjectionType(ProjectionType.ALL))
              .withProvisionedThroughput(
                new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
              )
          ).asJava
        ).withStreamSpecification(
          new StreamSpecification().withStreamEnabled(true).withStreamViewType(StreamViewType.NEW_IMAGE)
        )
      val createResponse = dynamoDBClient.createTable(createRequest)
      require(createResponse.getSdkHttpMetadata.getHttpStatusCode == 200)
    }
  }

  val GetJournalRowsIndexName = "GetJournalRowsIndex"

  protected def createJournalTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (!listTablesResult.getTableNames.asScala.exists(_ == journalTableName)) {
      val createRequest = new CreateTableRequest()
        .withTableName(journalTableName)
        .withAttributeDefinitions(
          Seq(
            new AttributeDefinition().withAttributeName("pkey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("skey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("persistence-id").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("sequence-nr").withAttributeType(ScalarAttributeType.N),
            new AttributeDefinition().withAttributeName("tags").withAttributeType(ScalarAttributeType.S)
          ).asJava
        ).withKeySchema(
          Seq(
            new KeySchemaElement().withAttributeName("pkey").withKeyType(KeyType.HASH),
            new KeySchemaElement().withAttributeName("skey").withKeyType(KeyType.RANGE)
          ).asJava
        ).withProvisionedThroughput(
          new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
        ).withGlobalSecondaryIndexes(
          Seq(
            new GlobalSecondaryIndex()
              .withIndexName("TagsIndex").withKeySchema(
                Seq(
                  new KeySchemaElement().withAttributeName("tags").withKeyType(KeyType.HASH)
                ).asJava
              ).withProjection(new Projection().withProjectionType(ProjectionType.ALL))
              .withProvisionedThroughput(
                new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
              ),
            new GlobalSecondaryIndex()
              .withIndexName("GetJournalRowsIndex").withKeySchema(
                Seq(
                  new KeySchemaElement().withAttributeName("persistence-id").withKeyType(KeyType.HASH),
                  new KeySchemaElement().withAttributeName("sequence-nr").withKeyType(KeyType.RANGE)
                ).asJava
              ).withProjection(new Projection().withProjectionType(ProjectionType.ALL))
              .withProvisionedThroughput(
                new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
              )
          ).asJava
        ).withStreamSpecification(
          new StreamSpecification().withStreamEnabled(true).withStreamViewType(StreamViewType.NEW_IMAGE)
        )
      val createResponse = dynamoDBClient.createTable(createRequest)
      require(createResponse.getSdkHttpMetadata.getHttpStatusCode == 200)
    }
  }

  def createJournalHeadTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (!listTablesResult.getTableNames.asScala.exists(_ == journalHeadTableName)) {
      val createRequest = new CreateTableRequest()
        .withTableName(journalHeadTableName)
        .withAttributeDefinitions(
          Seq(
            new AttributeDefinition().withAttributeName("persistence-id").withAttributeType(ScalarAttributeType.S)
          ).asJava
        ).withKeySchema(
          Seq(
            new KeySchemaElement().withAttributeName("persistence-id").withKeyType(KeyType.HASH)
          ).asJava
        ).withProvisionedThroughput(
          new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
        ).withStreamSpecification(
          new StreamSpecification().withStreamEnabled(true).withStreamViewType(StreamViewType.NEW_IMAGE)
        )
      val createResponse = dynamoDBClient.createTable(createRequest)
      require(createResponse.getSdkHttpMetadata.getHttpStatusCode == 200)
    }
  }

  def createStateTable(): Unit = {
    val listTablesResult = dynamoDBClient.listTables(100)
    if (!listTablesResult.getTableNames.asScala.exists(_ == stateTableName)) {
      val createRequest = new CreateTableRequest()
        .withTableName(stateTableName).withAttributeDefinitions(
          Seq(
            new AttributeDefinition().withAttributeName("pkey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("skey").withAttributeType(ScalarAttributeType.S),
            new AttributeDefinition().withAttributeName("persistence-id").withAttributeType(ScalarAttributeType.S)
          ).asJava
        ).withKeySchema(
          Seq(
            new KeySchemaElement().withAttributeName("pkey").withKeyType(KeyType.HASH),
            new KeySchemaElement().withAttributeName("skey").withKeyType(KeyType.RANGE)
          ).asJava
        ).withGlobalSecondaryIndexes(
          Seq(
            new GlobalSecondaryIndex()
              .withIndexName("PersistenceIdIndex").withKeySchema(
                Seq(
                  new KeySchemaElement().withAttributeName("persistence-id").withKeyType(KeyType.HASH)
                ).asJava
              ).withProjection(new Projection().withProjectionType(ProjectionType.ALL))
              .withProvisionedThroughput(
                new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
              )
          ).asJava
        )
        .withProvisionedThroughput(
          new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L)
        )
      val createResponse = dynamoDBClient.createTable(createRequest)
      require(createResponse.getSdkHttpMetadata.getHttpStatusCode == 200)
    }
  }

}

trait DynamoDBContainerHelper extends DockerControllerSpecSupport with DockerControllerHelperUtil { this: TestSuite => }
