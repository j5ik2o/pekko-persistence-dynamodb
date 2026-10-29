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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.persistence.{ PersistentActor, PersistentRepr }
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.testkit.TestProbe
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadColumnsDefConfig,
  JournalHeadConfig,
  JournalPluginConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.WriteJournalDaoImpl
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.serialization.ByteArrayJournalSerializer
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DynamoDBContainerHelper, RandomPortUtil }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.model.{
  DescribeStreamRequest,
  DescribeTableRequest,
  GetRecordsRequest,
  GetShardIteratorRequest,
  ShardIteratorType
}
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => V2DynamoDbClient }

import java.net.URI
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.jdk.CollectionConverters._

object JournalHeadSpec {
  val dynamoDBHost: String = "localhost"
  val dynamoDBPort: Int    = RandomPortUtil.temporaryServerPort()

  final case class Persist(payload: String)
  final case class PersistAllPayloads(payloads: immutable.Seq[String])
  final case class Persisted(payload: String)
  final case class PersistedAll(size: Int)
  final case class PersistFailed(cause: Throwable)

  class TestPersistentActor(val persistenceId: String, failureProbe: ActorRef) extends PersistentActor {

    override def receiveRecover: Receive = { case _ => () }

    override def receiveCommand: Receive = {
      case Persist(payload) =>
        persist(payload) { p => sender() ! Persisted(p) }
      case PersistAllPayloads(payloads) =>
        // The handler of persistAll is called once per event. Reply when every event of the write has
        // been confirmed, because the callbacks run in write order.
        var remaining = payloads.size
        persistAll(payloads) { _ =>
          remaining -= 1
          if (remaining == 0) sender() ! PersistedAll(payloads.size)
        }
    }

    override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
      failureProbe ! PersistFailed(cause)
      super.onPersistFailure(cause, event, seqNr)
    }
  }
}

final class JournalHeadSpec extends AnyWordSpec with Matchers with ScalaFutures with DynamoDBContainerHelper {

  import JournalHeadSpec._

  override lazy val dynamoDBPort: Int = JournalHeadSpec.dynamoDBPort

  override val legacyJournalTable: Boolean = false

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(120, Seconds), interval = Span(200, Millis))

  private val journalColumnsDefConfig = JournalColumnsDefConfig(
    sourceConfig = ConfigFactory.empty(),
    partitionKeyColumnName = "pkey",
    sortKeyColumnName = "skey",
    persistenceIdColumnName = "persistence-id",
    sequenceNrColumnName = "sequence-nr",
    deletedColumnName = "deleted",
    messageColumnName = "message",
    orderingColumnName = "ordering",
    tagsColumnName = "tags"
  )

  private val headConfig = JournalHeadConfig(
    enabled = true,
    tableName = "JournalHead",
    columnsDefConfig = JournalHeadColumnsDefConfig(
      persistenceIdColumnName = "persistence-id",
      sequenceNrColumnName = "sequence-nr",
      eventsColumnName = "events",
      eventSequenceNrColumnName = "sequence-nr",
      eventMessageColumnName = "message"
    )
  )

  private var headDisabledSystem: ActorSystem = _
  private var headEnabledSystem: ActorSystem  = _

  private def config(headEnabled: Boolean) = {
    ConfigHelper.config(
      Some("journal-reference"),
      legacyConfigFormat = false,
      legacyJournalMode = false,
      dynamoDBHost = JournalHeadSpec.dynamoDBHost,
      dynamoDBPort = dynamoDBPort,
      clientVersion = ClientVersion.V2.toString,
      clientType = ClientType.Sync.toString,
      headEnabled = headEnabled
    )
  }

  override def afterStartContainers(): Unit = {
    super.afterStartContainers()
    createTable()
    headDisabledSystem = ActorSystem("journal-head-disabled", config(headEnabled = false))
    headEnabledSystem = ActorSystem("journal-head-enabled", config(headEnabled = true))
  }

  override def beforeStopContainers(): Unit = {
    if (headDisabledSystem != null) {
      Await.result(headDisabledSystem.terminate(), 60.seconds)
      headDisabledSystem = null
    }
    if (headEnabledSystem != null) {
      Await.result(headEnabledSystem.terminate(), 60.seconds)
      headEnabledSystem = null
    }
    deleteTable()
    super.beforeStopContainers()
  }

  private def newTestProbe(system: ActorSystem): TestProbe = TestProbe()(system)

  private def newActor(system: ActorSystem, persistenceId: String, failureProbe: ActorRef): ActorRef =
    system.actorOf(
      Props(classOf[TestPersistentActor], persistenceId, failureProbe),
      s"actor-${persistenceId.hashCode}-${System.nanoTime()}"
    )

  private def persistOk(system: ActorSystem, persistenceId: String, payload: String): Persisted = {
    val probe = newTestProbe(system)
    val actor = newActor(system, persistenceId, probe.ref)
    actor.tell(Persist(payload), probe.ref)
    probe.expectMsg(60.seconds, Persisted(payload))
  }

  private def persistAllOk(
      system: ActorSystem,
      persistenceId: String,
      payloads: immutable.Seq[String]
  ): PersistedAll = {
    val probe = newTestProbe(system)
    val actor = newActor(system, persistenceId, probe.ref)
    actor.tell(PersistAllPayloads(payloads), probe.ref)
    probe.expectMsgType[PersistedAll](60.seconds)
  }

  private def persistFails(system: ActorSystem, persistenceId: String, payloads: immutable.Seq[String]): Throwable = {
    val probe = newTestProbe(system)
    val actor = newActor(system, persistenceId, probe.ref)
    actor.tell(PersistAllPayloads(payloads), probe.ref)
    probe.expectMsgType[PersistFailed](60.seconds).cause
  }

  private def headItem(persistenceId: String): Option[Map[String, AttributeValue]] = {
    val getItemRequest = new com.amazonaws.services.dynamodbv2.model.GetItemRequest()
      .withTableName(journalHeadTableName)
      .withKey(Map("persistence-id" -> new AttributeValue().withS(persistenceId)).asJava)
      .withConsistentRead(true)
    Option(dynamoDBClient.getItem(getItemRequest).getItem).map(_.asScala.toMap)
  }

  private def journalRows(persistenceId: String): Seq[Map[String, AttributeValue]] = {
    val scanRequest = new ScanRequest()
      .withTableName(journalTableName)
      .withConsistentRead(true)
      .withFilterExpression("#persistenceId = :pid")
      .withExpressionAttributeNames(Map("#persistenceId" -> "persistence-id").asJava)
      .withExpressionAttributeValues(Map(":pid" -> new AttributeValue().withS(persistenceId)).asJava)
    dynamoDBClient
      .scan(scanRequest).getItems.asScala.toSeq
      .map(_.asScala.toMap)
      .sortBy(_("sequence-nr").getN.toLong)
  }

  private def journalSequenceNrs(persistenceId: String): Seq[Long] =
    journalRows(persistenceId).map(_("sequence-nr").getN.toLong)

  private def headSequenceNr(persistenceId: String): Option[Long] =
    headItem(persistenceId).map(_("sequence-nr").getN.toLong)

  private def newWriteJournalDao(system: ActorSystem): WriteJournalDaoImpl = {
    val pluginConfig  = JournalPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
    val pluginContext = JournalPluginContext(system, pluginConfig)
    val serializer =
      new ByteArrayJournalSerializer(SerializationExtension(system), pluginConfig.tagSeparator, None, None)
    implicit val ec: ExecutionContext = pluginContext.pluginExecutor
    new WriteJournalDaoImpl(pluginContext, pluginContext.journalRowWriteDriver, serializer)(ec, system)
  }

  private def journalRow(system: ActorSystem, persistenceId: String, sequenceNr: Long, payload: String): JournalRow = {
    val serializer                    = new ByteArrayJournalSerializer(SerializationExtension(system), ",", None, None)
    implicit val ec: ExecutionContext = system.dispatcher
    Await.result(serializer.serialize(PersistentRepr(payload, sequenceNr, persistenceId)), 30.seconds)
  }

  private def failedWith(dao: WriteJournalDaoImpl, rows: Seq[JournalRow]): Throwable = {
    implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
    Await.result(dao.putMessages(rows).runWith(Sink.head).failed, 60.seconds)
  }

  private def newV2Client(): V2DynamoDbClient =
    V2DynamoDbClient
      .builder()
      .endpointOverride(URI.create(s"http://${JournalHeadSpec.dynamoDBHost}:$dynamoDBPort"))
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
      .build()

  private def newV2StreamsClient(): DynamoDbStreamsClient =
    DynamoDbStreamsClient
      .builder()
      .endpointOverride(URI.create(s"http://${JournalHeadSpec.dynamoDBHost}:$dynamoDBPort"))
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
      .build()

  "JournalHead" should {

    "not write any JournalHead item when head.enabled is false" in {
      val pid = "head-disabled-1"
      persistOk(headDisabledSystem, pid, "e1")
      persistAllOk(headDisabledSystem, pid, immutable.Seq("e2", "e3"))

      headItem(pid) shouldBe None
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
    }

    "write the journal row and the JournalHead item for persist" in {
      val pid = "head-enabled-persist-1"
      persistOk(headEnabledSystem, pid, "e1")

      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(1L)
    }

    "write the journal rows and the JournalHead item for persistAll" in {
      val pid = "head-enabled-persist-all-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
      headSequenceNr(pid) shouldBe Some(3L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(1L, 2L, 3L)
    }

    "store only the last sequence number and the events of the write in the JournalHead item" in {
      val pid = "head-enabled-attributes-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2"))

      val item = headItem(pid).get
      item.keySet shouldBe Set("persistence-id", "sequence-nr", "events")
      item("events").getL.asScala.foreach { event =>
        event.getM.keySet.asScala shouldBe Set("sequence-nr", "message")
      }
      val headMessages = item("events").getL.asScala
        .map(e => (e.getM.get("sequence-nr").getN.toLong, e.getM.get("message").getB.array().toSeq)).toMap
      val journalMessages = journalRows(pid)
        .map(row => (row("sequence-nr").getN.toLong, row("message").getB.array().toSeq)).toMap
      headMessages shouldBe journalMessages
    }

    "reject a write whose sequence numbers have a gap and write nothing" in {
      val pid = "head-gap-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))
      headSequenceNr(pid) shouldBe Some(3L)

      val dao       = newWriteJournalDao(headEnabledSystem)
      val exception = failedWith(dao, Seq(journalRow(headEnabledSystem, pid, 5L, "e5")))

      exception shouldBe a[JournalHeadSequenceMismatchException]
      exception.asInstanceOf[JournalHeadSequenceMismatchException].expectedPreviousSequenceNr shouldBe 4L
      exception.asInstanceOf[JournalHeadSequenceMismatchException].actualSequenceNr shouldBe 3L
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
      headSequenceNr(pid) shouldBe Some(3L)
    }

    "reject a duplicate write and write nothing" in {
      val pid = "head-duplicate-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2"))

      val dao = newWriteJournalDao(headEnabledSystem)
      val exception = failedWith(
        dao,
        Seq(journalRow(headEnabledSystem, pid, 1L, "e1"), journalRow(headEnabledSystem, pid, 2L, "e2"))
      )

      exception shouldBe a[DuplicateJournalEventsException]
      exception.asInstanceOf[DuplicateJournalEventsException].sequenceNrs shouldBe Seq(1L, 2L)
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
    }

    "accept 99 events in one persistAll and reject 100 events" in {
      val acceptedPid = "head-99-events-1"
      persistAllOk(headEnabledSystem, acceptedPid, (1 to 99).map(i => s"e$i").toVector) shouldBe PersistedAll(99)
      journalSequenceNrs(acceptedPid) shouldBe (1L to 99L).toSeq
      headSequenceNr(acceptedPid) shouldBe Some(99L)

      val rejectedPid = "head-100-events-1"
      val failure     = persistFails(headEnabledSystem, rejectedPid, (1 to 100).map(i => s"e$i").toVector)
      failure shouldBe a[JournalHeadTooManyEventsException]
      failure.asInstanceOf[JournalHeadTooManyEventsException].eventCount shouldBe 100
      journalSequenceNrs(rejectedPid) shouldBe Seq.empty
      headItem(rejectedPid) shouldBe None
    }

    "accept an event that keeps the JournalHead item under the size limit and reject one that exceeds it" in {
      val acceptedPid = "head-size-under-1"
      persistOk(headEnabledSystem, acceptedPid, "x" * 406000)
      journalSequenceNrs(acceptedPid) shouldBe Seq(1L)
      headSequenceNr(acceptedPid) shouldBe Some(1L)

      val rejectedPid = "head-size-over-1"
      val failure     = persistFails(headEnabledSystem, rejectedPid, immutable.Seq("x" * 410000))
      failure shouldBe a[JournalHeadItemTooLargeException]
      journalSequenceNrs(rejectedPid) shouldBe Seq.empty
      headItem(rejectedPid) shouldBe None
    }

    "require the migration for an existing aggregate and accept new aggregates before it" in {
      val existingPid = "head-migration-existing-1"
      persistAllOk(headDisabledSystem, existingPid, immutable.Seq("e1", "e2", "e3"))

      // before the migration: an existing aggregate is rejected with a distinguishable error
      val missingFailure = persistFails(headEnabledSystem, existingPid, immutable.Seq("e4"))
      missingFailure shouldBe a[JournalHeadMissingException]
      missingFailure.asInstanceOf[JournalHeadMissingException].firstSequenceNr shouldBe 4L
      journalSequenceNrs(existingPid) shouldBe Seq(1L, 2L, 3L)
      headItem(existingPid) shouldBe None

      // before the migration: a new aggregate starts at sequence number 1 and is accepted
      val newPid = "head-migration-new-1"
      persistOk(headEnabledSystem, newPid, "e1")
      headSequenceNr(newPid) shouldBe Some(1L)

      // run the migration tool
      val client = newV2Client()
      try {
        val result = JournalHeadMigration.run(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          dryRun = false
        )
        result.createdJournalHeads should be >= 1
        result.existingJournalHeads should be >= 1 // the new aggregate already has a head
      } finally client.close()

      headSequenceNr(existingPid) shouldBe Some(3L)
      headItem(existingPid).get("events").getL.isEmpty shouldBe true

      // after the migration: the existing aggregate continues at sequence number 4
      persistOk(headEnabledSystem, existingPid, "e4")
      journalSequenceNrs(existingPid) shouldBe Seq(1L, 2L, 3L, 4L)
      headSequenceNr(existingPid) shouldBe Some(4L)
      headItem(existingPid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(4L)
    }

    "deliver the JournalHead stream records in sequence number order" in {
      val pid = "head-stream-1"
      persistOk(headEnabledSystem, pid, "e1")
      persistOk(headEnabledSystem, pid, "e2")
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e3", "e4"))

      val v2Client      = newV2Client()
      val streamsClient = newV2StreamsClient()
      try {
        val streamArn = v2Client
          .describeTable(DescribeTableRequest.builder().tableName(journalHeadTableName).build())
          .table()
          .latestStreamArn()

        val shardIds = waitForStreamShards(streamsClient, streamArn)
        val records  = readStreamRecords(streamsClient, streamArn, shardIds, pid)

        val headSequenceNrs = records
          .filter(r => r.dynamodb().newImage().get("persistence-id").s() == pid)
          .map(r => r.dynamodb().newImage().get("sequence-nr").n().toLong)
        // three writes: seq 1, seq 2, and the write of seq 3 and 4 (the head jumps to 4)
        headSequenceNrs shouldBe Seq(1L, 2L, 4L)

        val streamEvents = records
          .filter(r => r.dynamodb().newImage().get("persistence-id").s() == pid)
          .flatMap(_.dynamodb().newImage().get("events").l().asScala)
          .map(e => (e.m().get("sequence-nr").n().toLong, e.m().get("message").b().asByteArray.toSeq))
        streamEvents.map(_._1) shouldBe Seq(1L, 2L, 3L, 4L)

        val journalMessages = journalRows(pid)
          .map(row => (row("sequence-nr").getN.toLong, row("message").getB.array().toSeq))
        streamEvents shouldBe journalMessages
      } finally {
        v2Client.close()
        streamsClient.close()
      }
    }

  }

  private def waitForStreamShards(
      streamsClient: DynamoDbStreamsClient,
      streamArn: String
  ): Seq[String] = {
    val deadline = System.nanoTime() + 60.seconds.toNanos
    var shardIds = Seq.empty[String]
    while (shardIds.isEmpty && System.nanoTime() < deadline) {
      shardIds =
        try {
          streamsClient
            .describeStream(DescribeStreamRequest.builder().streamArn(streamArn).build())
            .streamDescription()
            .shards()
            .asScala
            .map(_.shardId())
            .toSeq
        } catch {
          case _: software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException => Seq.empty
        }
      if (shardIds.isEmpty) Thread.sleep(200)
    }
    withClue(s"the stream $streamArn did not become available") {
      shardIds should not be empty
    }
    shardIds
  }

  private def readStreamRecords(
      streamsClient: DynamoDbStreamsClient,
      streamArn: String,
      shardIds: Seq[String],
      persistenceId: String
  ): Seq[software.amazon.awssdk.services.dynamodb.model.Record] = {
    val deadline  = System.nanoTime() + 60.seconds.toNanos
    var collected = Vector.empty[software.amazon.awssdk.services.dynamodb.model.Record]
    var iterators = shardIds.flatMap { shardId =>
      Option(
        streamsClient
          .getShardIterator(
            GetShardIteratorRequest
              .builder()
              .streamArn(streamArn)
              .shardId(shardId)
              .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
              .build()
          ).shardIterator()
      )
    }
    def recordsOfPersistenceId: Vector[software.amazon.awssdk.services.dynamodb.model.Record] =
      collected.filter(record =>
        Option(record.dynamodb().newImage())
          .flatMap(image => Option(image.get("persistence-id")))
          .exists(_.s() == persistenceId)
      )
    while (iterators.nonEmpty && recordsOfPersistenceId.size < 3 && System.nanoTime() < deadline) {
      iterators = iterators.flatMap { iterator =>
        val response = streamsClient.getRecords(GetRecordsRequest.builder().shardIterator(iterator).build())
        collected ++= response.records().asScala
        Option(response.nextShardIterator())
      }
      if (recordsOfPersistenceId.size < 3) Thread.sleep(200)
    }
    collected.toSeq
  }

}
