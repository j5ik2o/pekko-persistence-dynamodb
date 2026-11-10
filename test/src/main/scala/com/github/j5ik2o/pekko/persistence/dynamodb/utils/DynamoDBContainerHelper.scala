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
import com.github.dockerjava.api.model.ExposedPort
import com.github.j5ik2o.dockerController.WaitPredicates.WaitPredicate
import com.github.j5ik2o.dockerController.dynamodbLocal.DynamoDBLocalController
import com.github.j5ik2o.dockerController.{
  CmdConfigures,
  DockerController,
  DockerControllerHelper,
  DockerControllerSpecSupport,
  WaitPredicates
}
import com.typesafe.config.Config
import org.scalatest.{ Args, Status, TestSuite }

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

trait DockerControllerHelperUtil extends DockerControllerHelper {

  val testTimeFactor: Int = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toInt
  logger.debug(s"testTimeFactor = $testTimeFactor")

  lazy val dynamoDBPort: Int = RandomPortUtil.temporaryServerPort()
  lazy val controller: DynamoDBLocalController =
    new DynamoDBLocalController(dockerClient, isDockerClientAutoClose = true, imageTag = None)(dynamoDBPort)

  val waitPredicate: WaitPredicate = WaitPredicates.forLogMessageByRegex(
    DynamoDBLocalController.RegexOfWaitPredicate,
    Some((1 * testTimeFactor).seconds)
  )

  val waitPredicateSetting: WaitPredicateSetting = WaitPredicateSetting(Duration.Inf, waitPredicate)

  val tableName = "test"

  override protected lazy val dockerControllers: Vector[DockerController] = Vector(controller)

  override protected lazy val waitPredicatesSettings: Map[DockerController, WaitPredicateSetting] =
    Map(
      controller -> waitPredicateSetting
    )

  lazy val dynamoDBEndpoint: String   = s"http://$dockerHost:$dynamoDBPort"
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

  def startContainer(): Unit = synchronized {
    if (Option(controller.containerId).flatten.isEmpty) {
      super.createDockerContainer(controller, None)
      try super.startDockerContainer(controller, None)
      catch {
        case scala.util.control.NonFatal(error) =>
          controller.removeContainer(_.withForce(true))
          dockerClient.close()
          throw error
      }
    }
  }

  def stopContainer(): Unit = {
    super.stopDockerContainer(controller, None)
    super.removeDockerContainer(controller, None)
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
    createTableIfMissing(snapshotTableName)
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
    createTableIfMissing(journalTableName)
  }

  def createJournalHeadTable(): Unit = {
    createTableIfMissing(journalHeadTableName)
  }

  def createStateTable(): Unit = {
    createTableIfMissing(stateTableName)
  }

  private def createTableIfMissing(tableName: String): Unit = {
    val tableNames = dynamoDBClient.listTables(100).getTableNames.asScala
    if (!tableNames.exists(_ == tableName)) {
      val response = dynamoDBClient.createTable(createTableRequest(DynamoDBLocalFixture.table(tableName)))
      require(
        response.getSdkHttpMetadata.getHttpStatusCode == 200,
        s"failed to create the $tableName table: ${response.getSdkHttpMetadata.getHttpStatusCode}"
      )
    }
  }

  private def createTableRequest(config: Config): CreateTableRequest = {
    val request = new CreateTableRequest()
      .withTableName(config.getString("TableName"))
      .withAttributeDefinitions(
        config
          .getConfigList("AttributeDefinitions").asScala.map { attribute =>
            new AttributeDefinition()
              .withAttributeName(attribute.getString("AttributeName"))
              .withAttributeType(ScalarAttributeType.valueOf(attribute.getString("AttributeType")))
          }.asJava
      )
      .withKeySchema(keySchema(config.getConfigList("KeySchema").asScala))
      .withProvisionedThroughput(provisionedThroughput(config.getConfig("ProvisionedThroughput")))
    if (config.hasPath("GlobalSecondaryIndexes")) {
      request.withGlobalSecondaryIndexes(
        config.getConfigList("GlobalSecondaryIndexes").asScala.map(toIndex).asJava
      )
    }
    if (config.hasPath("StreamSpecification")) {
      val stream = config.getConfig("StreamSpecification")
      request.withStreamSpecification(
        new StreamSpecification()
          .withStreamEnabled(stream.getBoolean("StreamEnabled"))
          .withStreamViewType(StreamViewType.valueOf(stream.getString("StreamViewType")))
      )
    }
    request
  }

  private def toIndex(config: Config): GlobalSecondaryIndex =
    new GlobalSecondaryIndex()
      .withIndexName(config.getString("IndexName"))
      .withKeySchema(keySchema(config.getConfigList("KeySchema").asScala))
      .withProjection(projection(config.getConfig("Projection")))
      .withProvisionedThroughput(provisionedThroughput(config.getConfig("ProvisionedThroughput")))

  private def keySchema(entries: Iterable[Config]): java.util.List[KeySchemaElement] =
    entries
      .map { entry =>
        new KeySchemaElement()
          .withAttributeName(entry.getString("AttributeName"))
          .withKeyType(KeyType.valueOf(entry.getString("KeyType")))
      }
      .toSeq
      .asJava

  private def projection(config: Config): Projection = {
    val projection = new Projection().withProjectionType(ProjectionType.valueOf(config.getString("ProjectionType")))
    if (config.hasPath("NonKeyAttributes")) {
      projection.withNonKeyAttributes(config.getStringList("NonKeyAttributes"))
    }
    projection
  }

  private def provisionedThroughput(config: Config): ProvisionedThroughput =
    new ProvisionedThroughput()
      .withReadCapacityUnits(config.getLong("ReadCapacityUnits"))
      .withWriteCapacityUnits(config.getLong("WriteCapacityUnits"))

}

private[dynamodb] trait DockerAssignedPort extends DockerControllerHelperUtil {
  override lazy val controller: DynamoDBLocalController = {
    val result = new DynamoDBLocalController(dockerClient, isDockerClientAutoClose = true, imageTag = None)(0)
    result.configureCmds(CmdConfigures(removeContainerCmdConfigure = _.withForce(true)))
    result
  }

  override lazy val dynamoDBPort: Int = {
    startContainer()
    controller
      .inspectContainer().getNetworkSettings.getPorts
      .getBindings.get(ExposedPort.tcp(DynamoDBLocalController.DefaultContainerPort)).head.getHostPortSpec.toInt
  }
}

trait DynamoDBContainerHelper extends DockerControllerSpecSupport with DockerAssignedPort { this: TestSuite =>

  abstract override def run(testName: Option[String], args: Args): Status =
    try super.run(testName, args)
    finally {
      if (expectedTestCount(args.filter) == 0 && Option(controller.containerId).flatten.isDefined)
        disposeResources()
    }

  // Keep Docker's port binding alive while the suite's ActorSystem uses its endpoint.
  override protected def createDockerContainer(controller: DockerController, testName: Option[String]): Unit =
    startContainer()

  override protected def startDockerContainer(controller: DockerController, testName: Option[String]): Unit = ()

  override protected def stopDockerContainer(controller: DockerController, testName: Option[String]): Unit = ()

  override protected def removeDockerContainer(controller: DockerController, testName: Option[String]): Unit = ()
}
