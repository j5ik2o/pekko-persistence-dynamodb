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
import org.apache.pekko.persistence.journal.Tagged
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
  final case class PersistTwice(first: String, second: String)
  final case class PersistRejectedThenPersist(overLimitCount: Int, after: String)
  final case class PersistThenOverLimit(first: String, overLimitCount: Int)
  final case class PersistTaggedAll(count: Int, tagLength: Int)
  final case class DeleteTo(sequenceNr: Long)
  final case class Persisted(payload: String)
  final case class PersistedAll(size: Int)
  final case class PersistedSecond(payload: String)
  final case class Deleted()
  final case class PersistFailed(cause: Throwable)
  final case class PersistRejected(cause: Throwable)

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
      case PersistTwice(first, second) =>
        // two persist calls in one command handler create two writes in one asyncWriteMessages call
        persist(first) { _ => () }
        persist(second) { p => sender() ! PersistedSecond(p) }
      case PersistRejectedThenPersist(overLimitCount, after) =>
        // the rejected write is the first of two writes in one asyncWriteMessages call
        persistAll((1 to overLimitCount).map(index => s"over$index")) { _ => () }
        persist(after) { p => sender() ! Persisted(p) }
      case PersistThenOverLimit(first, overLimitCount) =>
        // the first write is confirmed and the second one is rejected: a failure in the middle of a batch
        persist(first) { _ => () }
        persistAll((1 to overLimitCount).map(index => s"over$index")) { _ => () }
      case PersistTaggedAll(count, tagLength) =>
        val tag = "t" * tagLength
        persistAll((1 to count).map(index => Tagged(s"e$index", Set(tag)))) { _ => () }
      case DeleteTo(sequenceNr) =>
        deleteMessages(sequenceNr)
      case org.apache.pekko.persistence.DeleteMessagesSuccess(_) =>
        failureProbe ! Deleted()
      case org.apache.pekko.persistence.DeleteMessagesFailure(cause, _) =>
        failureProbe ! PersistFailed(cause)
    }

    override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
      failureProbe ! PersistFailed(cause)
      super.onPersistFailure(cause, event, seqNr)
    }

    override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
      failureProbe ! PersistRejected(cause)
      super.onPersistRejected(cause, event, seqNr)
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

  private var headDisabledSystem: ActorSystem          = _
  private var headEnabledSystem: ActorSystem           = _
  private var headEnabledHardDeleteSystem: ActorSystem = _

  private def config(headEnabled: Boolean, softDelete: Boolean = true) = {
    ConfigHelper.config(
      Some("journal-reference"),
      legacyConfigFormat = false,
      legacyJournalMode = false,
      dynamoDBHost = JournalHeadSpec.dynamoDBHost,
      dynamoDBPort = dynamoDBPort,
      clientVersion = ClientVersion.V2.toString,
      clientType = ClientType.Sync.toString,
      softDelete = softDelete,
      headEnabled = headEnabled
    )
  }

  override def afterStartContainers(): Unit = {
    super.afterStartContainers()
    createTable()
    headDisabledSystem = ActorSystem("journal-head-disabled", config(headEnabled = false))
    headEnabledSystem = ActorSystem("journal-head-enabled", config(headEnabled = true))
    headEnabledHardDeleteSystem =
      ActorSystem("journal-head-enabled-hard-delete", config(headEnabled = true, softDelete = false))
  }

  override def beforeStopContainers(): Unit = {
    Seq(headDisabledSystem, headEnabledSystem, headEnabledHardDeleteSystem).foreach { system =>
      if (system != null) Await.result(system.terminate(), 60.seconds)
    }
    headDisabledSystem = null
    headEnabledSystem = null
    headEnabledHardDeleteSystem = null
    deleteTable()
    super.beforeStopContainers()
  }

  private def newTestProbe(system: ActorSystem): TestProbe = TestProbe()(system)

  private def newActor(system: ActorSystem, persistenceId: String, failureProbe: ActorRef): ActorRef =
    system.actorOf(
      Props(classOf[TestPersistentActor], persistenceId, failureProbe),
      s"actor-${persistenceId.hashCode}-${System.nanoTime()}"
    )

  private def withActor[T](system: ActorSystem, persistenceId: String)(
      action: (ActorRef, TestProbe) => T
  ): T = {
    val probe = newTestProbe(system)
    val actor = newActor(system, persistenceId, probe.ref)
    try action(actor, probe)
    finally system.stop(actor)
  }

  private def persistOk(system: ActorSystem, persistenceId: String, payload: String): Persisted =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(Persist(payload), probe.ref)
      probe.expectMsg(60.seconds, Persisted(payload))
    }

  private def persistAllOk(
      system: ActorSystem,
      persistenceId: String,
      payloads: immutable.Seq[String]
  ): PersistedAll =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(PersistAllPayloads(payloads), probe.ref)
      probe.expectMsgType[PersistedAll](60.seconds)
    }

  /** A write that DynamoDB refuses (a failed condition or a service error) stops the actor. */
  private def persistFails(system: ActorSystem, persistenceId: String, payloads: immutable.Seq[String]): Throwable =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(PersistAllPayloads(payloads), probe.ref)
      probe.expectMsgType[PersistFailed](60.seconds).cause
    }

  /** A write that the JournalHead path rejects before DynamoDB is the responsibility of onPersistRejected. */
  private def persistRejected(
      system: ActorSystem,
      persistenceId: String,
      payloads: immutable.Seq[String]
  ): Throwable =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(PersistAllPayloads(payloads), probe.ref)
      probe.expectMsgType[PersistRejected](60.seconds).cause
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

    "accept a write whose sequence numbers have a gap" in {
      val pid = "head-gap-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))
      headSequenceNr(pid) shouldBe Some(3L)

      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        Await.result(dao.putMessages(Seq(journalRow(headEnabledSystem, pid, 5L, "e5"))).runWith(Sink.head), 60.seconds)
      } finally dao.dispose()

      // a gap is accepted (a rejected write of the same actor leaves one), and the head moves to the written seqNr
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L, 5L)
      headSequenceNr(pid) shouldBe Some(5L)
    }

    "reject a write whose JournalHead item is ahead" in {
      val pid = "head-ahead-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      // another writer got ahead of this one
      val headAheadItem = new com.amazonaws.services.dynamodbv2.model.PutItemRequest()
        .withTableName(journalHeadTableName)
        .withItem(
          Map(
            "persistence-id" -> new AttributeValue().withS(pid),
            "sequence-nr"    -> new AttributeValue().withN("10"),
            "events"         -> new AttributeValue().withL(java.util.Collections.emptyList[AttributeValue]())
          ).asJava
        )
      dynamoDBClient.putItem(headAheadItem)

      val dao       = newWriteJournalDao(headEnabledSystem)
      val exception = failedWith(dao, Seq(journalRow(headEnabledSystem, pid, 4L, "e4")))
      dao.dispose()

      exception shouldBe a[JournalHeadSequenceMismatchException]
      exception.asInstanceOf[JournalHeadSequenceMismatchException].expectedPreviousSequenceNr shouldBe 3L
      exception.asInstanceOf[JournalHeadSequenceMismatchException].actualSequenceNr shouldBe 10L
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
      headSequenceNr(pid) shouldBe Some(10L)
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

    "keep the order of two persist calls in one command handler" in {
      val pid = "head-two-persists-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistTwice("e1", "e2"), probe.ref)
        probe.expectMsg(60.seconds, PersistedSecond("e2"))
      }

      // both writes of one asyncWriteMessages call reach DynamoDB in the order the actor produced them
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(2L)
    }

    "keep the actor alive after a rejected write and accept the next write" in {
      val pid = "head-rejected-then-persist-1"
      persistOk(headEnabledSystem, pid, "e1")
      headSequenceNr(pid) shouldBe Some(1L)

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistRejectedThenPersist(100, "after"), probe.ref)
        // Pekko does not take the 100 rejected sequence numbers back, so "after" is written above the head
        probe.fishForMessage(60.seconds, "a rejected write followed by a successful persist") {
          case PersistRejected(_) => false
          case Persisted(_)       => true
        }
      }

      // a gap is left where the rejected write was, and the head moves to the write that succeeded
      journalSequenceNrs(pid) shouldBe Seq(1L, 102L)
      headSequenceNr(pid) shouldBe Some(102L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(102L)
    }

    "stop the actor when the first write of a new aggregate is rejected, and write from 1 after a restart" in {
      // a new aggregate has no JournalHead item yet, so the condition accepts only a write that starts at 1
      val pid = "head-new-aggregate-rejected-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistAllPayloads((1 to 100).map(index => s"e$index").toVector), probe.ref)
        val rejected = probe
          .fishForMessage(60.seconds, "the rejection of the first write") {
            case PersistRejected(_) => true
            case _                  => false
          }.asInstanceOf[PersistRejected]
        rejected.cause shouldBe a[JournalHeadTooManyEventsException]
        journalSequenceNrs(pid) shouldBe Seq.empty
        headItem(pid) shouldBe None

        // the actor keeps the advanced sequence numbers, so its next write starts above 1 and stops the actor
        actor.tell(Persist("after"), probe.ref)
        val failed = probe
          .fishForMessage(60.seconds, "the failure of the next write of the same actor") {
            case PersistFailed(_) => true
            case _                => false
          }.asInstanceOf[PersistFailed]
        failed.cause shouldBe a[JournalHeadMissingException]
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      // after the restart, recovery finds nothing, so the aggregate starts at sequence number 1 again
      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
    }

    "stop the actor when the first write of a new aggregate exceeds the item size, and write from 1 after a restart" in {
      val pid = "head-new-aggregate-rejected-size-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(Persist("x" * 410000), probe.ref)
        val rejected = probe
          .fishForMessage(60.seconds, "the rejection of the first write") {
            case PersistRejected(_) => true
            case _                  => false
          }.asInstanceOf[PersistRejected]
        rejected.cause shouldBe a[JournalHeadItemTooLargeException]

        actor.tell(Persist("after"), probe.ref)
        val failed = probe
          .fishForMessage(60.seconds, "the failure of the next write of the same actor") {
            case PersistFailed(_) => true
            case _                => false
          }.asInstanceOf[PersistFailed]
        failed.cause shouldBe a[JournalHeadMissingException]
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
    }

    "write the first write and reject the second one of the same batch" in {
      val pid = "head-batch-partial-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistThenOverLimit("e1", 100), probe.ref)
        probe.expectMsgType[PersistRejected](60.seconds)
      }

      // the first write of the batch is durable, the rejected one wrote nothing
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
    }

    "reject a write whose transaction would exceed 4 MB" in {
      val pid = "head-4mb-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        // 99 events with a 43,000 character tag each: the journal rows alone exceed 4 MB, the head item stays small
        actor.tell(PersistTaggedAll(99, 43000), probe.ref)
        val rejected = probe.expectMsgType[PersistRejected](120.seconds)
        rejected.cause shouldBe a[JournalHeadTransactionTooLargeException]
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None
    }

    "keep the JournalHead item and its stream unchanged on a soft delete" in {
      val pid = "head-soft-delete-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))
      val headBefore = headItem(pid)
      headStreamRecords(pid, 1).size shouldBe 1

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }

      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(true, true, true)
      headItem(pid) shouldBe headBefore
      headStreamRecords(pid, 1).size shouldBe 1
    }

    "keep the JournalHead item and its stream unchanged on a hard delete" in {
      val pid = "head-hard-delete-1"
      persistAllOk(headEnabledHardDeleteSystem, pid, immutable.Seq("e1", "e2", "e3"))
      val headBefore = headItem(pid)
      headStreamRecords(pid, 1).size shouldBe 1

      withActor(headEnabledHardDeleteSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }

      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe headBefore
      headStreamRecords(pid, 1).size shouldBe 1
    }

    "keep writing above the JournalHead item after every event was hard deleted" in {
      val pid = "head-hard-delete-write-1"
      persistAllOk(headEnabledHardDeleteSystem, pid, immutable.Seq("e1", "e2", "e3"))
      withActor(headEnabledHardDeleteSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headSequenceNr(pid) shouldBe Some(3L)

      // the journal is empty, but the JournalHead item is the lower bound of the highest sequence number
      persistOk(headEnabledHardDeleteSystem, pid, "e4")
      journalSequenceNrs(pid) shouldBe Seq(4L)
      headSequenceNr(pid) shouldBe Some(4L)
    }

    "reject an invalid write as a value so that the write queue keeps working" in {
      val pid = "head-invalid-write-1"
      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        val exception = failedWith(
          dao,
          Seq(journalRow(headEnabledSystem, pid, 1L, "e1"), journalRow(headEnabledSystem, pid, 3L, "e3"))
        )
        exception shouldBe a[JournalHeadInvalidWriteException]

        // the queue of the same dao still writes: the exception did not abort the stream
        Await.result(dao.putMessages(Seq(journalRow(headEnabledSystem, pid, 1L, "e1"))).runWith(Sink.head), 60.seconds)
        headSequenceNr(pid) shouldBe Some(1L)
      } finally dao.dispose()
    }

    "accept 99 events in one persistAll and reject 100 events" in {
      val acceptedPid = "head-99-events-1"
      persistAllOk(headEnabledSystem, acceptedPid, (1 to 99).map(i => s"e$i").toVector) shouldBe PersistedAll(99)
      journalSequenceNrs(acceptedPid) shouldBe (1L to 99L).toSeq
      headSequenceNr(acceptedPid) shouldBe Some(99L)

      val rejectedPid = "head-100-events-1"
      val failure     = persistRejected(headEnabledSystem, rejectedPid, (1 to 100).map(i => s"e$i").toVector)
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
      val failure     = persistRejected(headEnabledSystem, rejectedPid, immutable.Seq("x" * 410000))
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

      // run the migration tool for real
      val client = newV2Client()
      try {
        val result = JournalHeadMigration.run(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = true
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

      // a write that happens while a node still runs with head mode off leaves the head behind
      persistOk(headDisabledSystem, existingPid, "e5")
      journalSequenceNrs(existingPid) shouldBe Seq(1L, 2L, 3L, 4L, 5L)
      headSequenceNr(existingPid) shouldBe Some(4L)

      // the tool reports the raise in dry-run mode and does not write
      val client2 = newV2Client()
      try {
        val dryRun = JournalHeadMigration.run(
          client2,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = false
        )
        dryRun.scannedPersistenceIds should be >= 2
        dryRun.updatedJournalHeads shouldBe 1
        dryRun.raisedPersistenceIds shouldBe Seq(existingPid)
        headSequenceNr(existingPid) shouldBe Some(4L)

        // a second run raises the head to the last sequence number of the journal
        val executed = JournalHeadMigration.run(
          client2,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = true
        )
        executed.updatedJournalHeads shouldBe 1
        executed.raisedPersistenceIds shouldBe Seq(existingPid)
      } finally client2.close()

      headSequenceNr(existingPid) shouldBe Some(5L)
      headItem(existingPid).get("events").getL.isEmpty shouldBe true

      // and the aggregate continues at sequence number 6
      persistOk(headEnabledSystem, existingPid, "e6")
      journalSequenceNrs(existingPid) shouldBe Seq(1L, 2L, 3L, 4L, 5L, 6L)
      headSequenceNr(existingPid) shouldBe Some(6L)
    }

    "deliver the JournalHead stream records in sequence number order" in {
      val pid = "head-stream-1"
      persistOk(headEnabledSystem, pid, "e1")
      persistOk(headEnabledSystem, pid, "e2")
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e3", "e4"))

      val records = headStreamRecords(pid, 3)

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
    }

  }

  private def headStreamRecords(
      persistenceId: String,
      expectedRecords: Int
  ): Seq[software.amazon.awssdk.services.dynamodb.model.Record] = {
    val v2Client      = newV2Client()
    val streamsClient = newV2StreamsClient()
    try {
      val streamArn = v2Client
        .describeTable(DescribeTableRequest.builder().tableName(journalHeadTableName).build())
        .table()
        .latestStreamArn()
      val shardIds = waitForStreamShards(streamsClient, streamArn)
      readStreamRecords(streamsClient, streamArn, shardIds, persistenceId, expectedRecords)
    } finally {
      v2Client.close()
      streamsClient.close()
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
      persistenceId: String,
      expectedRecords: Int
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
    while (iterators.nonEmpty && recordsOfPersistenceId.size < expectedRecords && System.nanoTime() < deadline) {
      iterators = iterators.flatMap { iterator =>
        val response = streamsClient.getRecords(GetRecordsRequest.builder().shardIterator(iterator).build())
        collected ++= response.records().asScala
        Option(response.nextShardIterator())
      }
      if (recordsOfPersistenceId.size < expectedRecords) Thread.sleep(200)
    }
    collected.toSeq
  }

}
