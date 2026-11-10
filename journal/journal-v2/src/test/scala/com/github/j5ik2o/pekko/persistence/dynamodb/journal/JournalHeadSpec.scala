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

import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props, Status }
import org.apache.pekko.persistence.journal.Tagged
import org.apache.pekko.persistence.{ PersistentActor, PersistentRepr }
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.testkit.TestProbe
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, DeleteItemRequest, PutItemRequest, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{
  JournalColumnsDefConfig,
  JournalHeadColumnsDefConfig,
  JournalHeadConfig,
  JournalPluginConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.WriteJournalDaoImpl
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v2.V2JournalRowWriteDriver
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.serialization.ByteArrayJournalSerializer
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DynamoDBContainerHelper, RandomPortUtil }
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue => V2AttributeValue,
  CancellationReason,
  DescribeStreamRequest,
  DescribeTableRequest,
  GetItemRequest,
  GetItemResponse,
  GetRecordsRequest,
  GetShardIteratorRequest,
  ShardIteratorType,
  TransactWriteItemsResponse,
  TransactionCanceledException
}
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import software.amazon.awssdk.services.dynamodb.{
  DynamoDbAsyncClient => V2DynamoDbAsyncClient,
  DynamoDbClient => V2DynamoDbClient
}

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters._

object JournalHeadSpec {
  val dynamoDBHost: String = "localhost"
  val dynamoDBPort: Int    = RandomPortUtil.temporaryServerPort()

  /** The writes the plugin rejected before DynamoDB was called. The perf probe reports this count with the duration. */
  val localRejections: AtomicInteger = new AtomicInteger(0)

  final case class Persist(payload: String)
  final case class PersistAllPayloads(payloads: immutable.Seq[String])
  final case class PersistTwice(first: String, second: String)
  final case class PersistRejectedThenPersist(overLimitCount: Int, after: String)
  final case class PersistThenOverLimit(first: String, overLimitCount: Int)
  final case class PersistTaggedAll(count: Int, tagLength: Int)
  final case class PersistUnserializable()
  final case class DeleteTo(sequenceNr: Long)
  final case class PersistChain(count: Int, progress: java.util.concurrent.atomic.AtomicLong)
  final case class ChainDone()
  final case class Persisted(payload: String)
  final case class PersistedAll(size: Int)
  final case class PersistedSecond(payload: String)
  final case class Deleted()
  final case class PersistFailed(cause: Throwable)
  final case class PersistRejected(cause: Throwable)

  /** No serializer is bound to this class, and it is not `java.io.Serializable`, so Pekko cannot serialize it. */
  final class NotSerializableEvent

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
      case PersistUnserializable() =>
        persist(new NotSerializableEvent) { _ => () }
      case PersistChain(count, progress) =>
        val replyTo = sender()
        def next(index: Int): Unit =
          if (index > count) replyTo ! ChainDone()
          else
            persist(s"e$index") { _ =>
              progress.set(index.toLong)
              next(index + 1)
            }
        next(1)
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
      localRejections.incrementAndGet()
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
    info(s"PERF localRejections=${localRejections.get()}")
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

  /** The plugin actor Pekko creates under `/system` from `pekko.persistence.journal.plugin`. */
  private def journalActor(system: ActorSystem): ActorRef =
    Await.result(
      system.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(30.seconds),
      30.seconds
    )

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

  /** A write that fails (a limit of the plugin, a failed condition or a service error) stops the actor. */
  private def persistFails(system: ActorSystem, persistenceId: String, payloads: immutable.Seq[String]): Throwable =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(PersistAllPayloads(payloads), probe.ref)
      probe.expectMsgType[PersistFailed](60.seconds).cause
    }

  /** The actor is stopped by Pekko after onPersistFailure: it receives no further command. */
  private def expectStopped(probe: TestProbe, actor: ActorRef): Unit = {
    probe.watch(actor)
    probe.expectTerminated(actor, 60.seconds)
  }

  /** A write over a limit is a failure, not a rejection: onPersistFailure runs and the actor stops. */
  private def expectPersistFailed(probe: TestProbe, actor: ActorRef): Throwable = {
    val failed = probe
      .fishForMessage(120.seconds, "the failure of a write over a limit") {
        case PersistFailed(_)   => true
        case PersistRejected(_) => fail("a write over a limit must fail the actor, not be rejected")
        case _                  => false
      }.asInstanceOf[PersistFailed]
    expectStopped(probe, actor)
    failed.cause
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

  private def putJournalRow(persistenceId: String, sequenceNr: Long): Unit = {
    val request = new PutItemRequest()
      .withTableName(journalTableName)
      .withItem(
        Map(
          "pkey"           -> new AttributeValue().withS(persistenceId),
          "skey"           -> new AttributeValue().withS(sequenceNr.toString),
          "persistence-id" -> new AttributeValue().withS(persistenceId),
          "sequence-nr"    -> new AttributeValue().withN(sequenceNr.toString)
        ).asJava
      )
    dynamoDBClient.putItem(request)
  }

  private def putSnapshotRow(persistenceId: String, sequenceNr: Long): Unit = {
    val request = new PutItemRequest()
      .withTableName(snapshotTableName)
      .withItem(
        Map(
          "pkey"           -> new AttributeValue().withS(persistenceId),
          "skey"           -> new AttributeValue().withS(sequenceNr.toString),
          "persistence-id" -> new AttributeValue().withS(persistenceId),
          "sequence-nr"    -> new AttributeValue().withN(sequenceNr.toString)
        ).asJava
      )
    dynamoDBClient.putItem(request)
  }

  private def deleteJournalRows(persistenceId: String): Unit =
    journalRows(persistenceId).foreach { row =>
      val request = new DeleteItemRequest()
        .withTableName(journalTableName)
        .withKey(Map("pkey" -> row("pkey"), "skey" -> row("skey")).asJava)
      dynamoDBClient.deleteItem(request)
    }

  private def payloadOf(system: ActorSystem, message: Array[Byte]): Any =
    SerializationExtension(system)
      .serializerFor(classOf[PersistentRepr])
      .fromBinary(message, classOf[PersistentRepr])
      .asInstanceOf[PersistentRepr]
      .payload

  private def journalPayloads(system: ActorSystem, persistenceId: String): Seq[(Long, Any)] =
    journalRows(persistenceId).map(row =>
      row("sequence-nr").getN.toLong -> payloadOf(system, row("message").getB.array())
    )

  private def headPayloads(system: ActorSystem, persistenceId: String): Seq[(Long, Any)] =
    headItem(persistenceId).toSeq.flatMap(
      _("events").getL.asScala.map(event =>
        event.getM.get("sequence-nr").getN.toLong -> payloadOf(system, event.getM.get("message").getB.array())
      )
    )

  private def updateEvent(system: ActorSystem, persistenceId: String, sequenceNr: Long, payload: String): Any = {
    val probe = newTestProbe(system)
    journalActor(system).tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgAnyClassOf(60.seconds, classOf[Done], classOf[Status.Failure]) match {
      case Status.Failure(cause) => fail(s"the update failed: $cause", cause)
      case other                 => other
    }
  }

  private def updateEventFails(
      system: ActorSystem,
      persistenceId: String,
      sequenceNr: Long,
      payload: String
  ): Throwable = {
    val probe = newTestProbe(system)
    journalActor(system).tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgType[Status.Failure](60.seconds).cause
  }

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

  private def journalRowWithMessage(
      persistenceId: String,
      sequenceNr: Long,
      messageLength: Int
  ): JournalRow =
    JournalRow(
      persistenceId = PersistenceId(persistenceId),
      sequenceNumber = SequenceNumber(sequenceNr),
      deleted = false,
      message = Array.fill(messageLength)(0x41.toByte),
      ordering = sequenceNr
    )

  /** Two events keep each journal row under 400 KB while the head item reaches the exact item size limit. */
  private def headItemSize(persistenceId: String, firstMessageLength: Int, secondMessageLength: Int): Int =
    DynamoDBItemSizeCalculator.journalHeadItemSize(
      JournalHeadRow(
        PersistenceId(persistenceId),
        SequenceNumber(2),
        Seq(
          JournalHeadEvent(SequenceNumber(1), Array.fill(firstMessageLength)(0x41.toByte)),
          JournalHeadEvent(SequenceNumber(2), Array.fill(secondMessageLength)(0x41.toByte))
        )
      ),
      headConfig.columnsDefConfig
    )

  private def messageLengthsAtItemSize(persistenceId: String, itemSizeBytes: Int): (Int, Int) = {
    val available = itemSizeBytes - headItemSize(persistenceId, 0, 0)
    (available / 2, available / 2 + available % 2)
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

  /** Counts the read calls the migration makes without changing any of them, so the perf probe reports a measured
    * reads-per-persistence-id instead of a number read off the source.
    */
  private def countingClient(
      delegate: V2DynamoDbClient,
      getItems: AtomicInteger,
      scans: AtomicInteger
  ): V2DynamoDbClient =
    java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[V2DynamoDbClient].getClassLoader,
        Array(classOf[V2DynamoDbClient]),
        new java.lang.reflect.InvocationHandler {
          override def invoke(proxy: Any, method: java.lang.reflect.Method, args: Array[AnyRef]): AnyRef = {
            method.getName match {
              case "getItem" => getItems.incrementAndGet()
              case "scan"    => scans.incrementAndGet()
              case _         =>
            }
            try method.invoke(delegate, Option(args).getOrElse(Array.empty[AnyRef]): _*)
            catch {
              case error: java.lang.reflect.InvocationTargetException => throw error.getCause
            }
          }
        }
      )
      .asInstanceOf[V2DynamoDbClient]

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

      // a gap is accepted (a write that Pekko itself refuses, such as a serialization failure, leaves one), and the head moves to the written seqNr
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

    "stop the actor when a write exceeds the event limit and continue without a gap after a restart" in {
      val pid = "head-over-limit-then-persist-1"
      persistOk(headEnabledSystem, pid, "e1")
      headSequenceNr(pid) shouldBe Some(1L)

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistRejectedThenPersist(100, "after"), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadTooManyEventsException]
      }

      // nothing of the call was written, so there is no gap and the sequence number 2 is still free
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)

      persistOk(headEnabledSystem, pid, "e2")
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(2L)
    }

    "stop the actor when the first write of a new aggregate exceeds the event limit, and write from 1 after a restart" in {
      val pid = "head-new-aggregate-rejected-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistAllPayloads((1 to 100).map(index => s"e$index").toVector), probe.ref)
        val cause = expectPersistFailed(probe, actor)
        cause shouldBe a[JournalHeadTooManyEventsException]
        cause.asInstanceOf[JournalHeadTooManyEventsException].eventCount shouldBe 100
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
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadItemTooLargeException]
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
    }

    "write nothing of a batch when its second write exceeds a limit" in {
      val pid = "head-batch-partial-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistThenOverLimit("e1", 100), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadTooManyEventsException]
      }

      // every write of the call is checked before the first one is sent, so the first write is not durable either
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
    }

    "stop the actor when a journal row exceeds 400 KB and continue without a gap after a restart" in {
      val pid = "head-row-size-over-1"
      persistOk(headEnabledSystem, pid, "e1")

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        // The tag makes the journal row exceed 400 KB while the JournalHead item, which does not carry tags, stays
        // small. The failure must happen before DynamoDB is called.
        actor.tell(PersistTaggedAll(1, 410000), probe.ref)
        val cause = expectPersistFailed(probe, actor)
        cause shouldBe a[JournalHeadJournalRowTooLargeException]
        cause.asInstanceOf[JournalHeadJournalRowTooLargeException].limitBytes shouldBe
        JournalHeadLimits.MaxItemSizeBytes
      }
      journalSequenceNrs(pid) shouldBe Seq(1L)

      persistOk(headEnabledSystem, pid, "e2")
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
    }

    "stop the actor when a journal row exceeds 400 KB and head.enabled is false, and continue without a gap after a restart" in {
      val pid = "head-disabled-row-size-over-1"
      persistOk(headDisabledSystem, pid, "e1")

      withActor(headDisabledSystem, pid) { (actor, probe) =>
        // The head-less path has no JournalHead item to check, but the row itself still must fail before DynamoDB
        // sees it, so onPersistFailure runs and the actor stops.
        actor.tell(PersistTaggedAll(1, 410000), probe.ref)
        val cause = expectPersistFailed(probe, actor)
        cause shouldBe a[JournalHeadJournalRowTooLargeException]
        cause.asInstanceOf[JournalHeadJournalRowTooLargeException].limitBytes shouldBe
        JournalHeadLimits.MaxItemSizeBytes
      }
      journalSequenceNrs(pid) shouldBe Seq(1L)

      persistOk(headDisabledSystem, pid, "e2")
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headItem(pid) shouldBe None
    }

    "reject only the write whose event cannot be serialized, keep the actor alive, and accept the next write when head.enabled is true" in {
      val pid = "head-serialization-rejected-1"
      persistOk(headEnabledSystem, pid, "e1")

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistUnserializable(), probe.ref)
        val rejected = probe.expectMsgType[PersistRejected](60.seconds)
        rejected.cause should not be a[JournalHeadException]
        probe.expectNoMessage(500.millis)

        // the actor is the same one and still alive; Pekko does not hand back the rejected sequence number
        actor.tell(Persist("after"), probe.ref)
        probe.expectMsg(60.seconds, Persisted("after"))
      }

      info(s"observed journal sequence numbers: ${journalSequenceNrs(pid)}, head: ${headSequenceNr(pid)}")
      journalSequenceNrs(pid) shouldBe Seq(1L, 3L)
      headSequenceNr(pid) shouldBe Some(3L)
    }

    "reject only the write whose event cannot be serialized, keep the actor alive, and accept the next write when head.enabled is false" in {
      val pid = "head-disabled-serialization-rejected-1"
      persistOk(headDisabledSystem, pid, "e1")

      withActor(headDisabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistUnserializable(), probe.ref)
        val rejected = probe.expectMsgType[PersistRejected](60.seconds)
        rejected.cause should not be a[JournalHeadException]
        probe.expectNoMessage(500.millis)

        actor.tell(Persist("after"), probe.ref)
        probe.expectMsg(60.seconds, Persisted("after"))
      }

      info(s"observed journal sequence numbers: ${journalSequenceNrs(pid)}")
      journalSequenceNrs(pid) shouldBe Seq(1L, 3L)
      headItem(pid) shouldBe None
    }

    "update the journal row and the JournalHead event of an in-place event update in one transaction when head mode is enabled" in {
      val pid = "head-in-place-update-1"
      persistOk(headEnabledSystem, pid, "e1")

      headItem(pid).get.contains("revision") shouldBe false

      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "updated")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "updated")
      headSequenceNr(pid) shouldBe Some(1L)
      headItem(pid).get("revision").getN shouldBe "1"
      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(false)
    }

    "raise the revision of the JournalHead item by one on every in-place event update and keep the sequence number" in {
      val pid = "head-in-place-update-revision-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2"))

      updateEvent(headEnabledSystem, pid, 1L, "first") shouldBe Done
      updateEvent(headEnabledSystem, pid, 2L, "second") shouldBe Done
      updateEvent(headEnabledSystem, pid, 2L, "third") shouldBe Done

      headItem(pid).get("revision").getN shouldBe "3"
      headSequenceNr(pid) shouldBe Some(2L)
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "first", 2L -> "third")
    }

    "write no revision when the next write replaces the JournalHead item after an in-place event update" in {
      val pid = "head-in-place-update-revision-2"
      persistOk(headEnabledSystem, pid, "e1")
      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done
      headItem(pid).get("revision").getN shouldBe "1"

      persistOk(headEnabledSystem, pid, "e2")

      headItem(pid).get.contains("revision") shouldBe false
      headSequenceNr(pid) shouldBe Some(2L)
    }

    "deliver an in-place event update as a stream record with the same sequence number and a higher revision" in {
      val pid = "head-in-place-update-stream-1"
      persistOk(headEnabledSystem, pid, "e1")
      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done

      val images = headStreamRecords(pid, 2)
        .map(_.dynamodb().newImage())
        .filter(_.get("persistence-id").s() == pid)

      images.map(_.get("sequence-nr").n().toLong) shouldBe Seq(1L, 1L)
      images.map(image => Option(image.get("revision")).map(_.n().toLong)) shouldBe Seq(None, Some(1L))
      images.map(image =>
        payloadOf(headEnabledSystem, image.get("events").l().get(0).m().get("message").b().asByteArray)
      ) shouldBe Seq("e1", "updated")
    }

    "refuse an in-place update of a soft deleted event and keep it deleted" in {
      val pid = "head-in-place-update-deleted-1"
      persistOk(headEnabledSystem, pid, "e1")
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(1L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }
      val headBefore = headItem(pid)

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadRowUpdateRejectedException]
      failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDeleted

      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(true)
      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headItem(pid) shouldBe headBefore
    }

    "keep the other events of the JournalHead item on an in-place event update of one event of a persistAll write" in {
      val pid = "head-in-place-update-2"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      updateEvent(headEnabledSystem, pid, 2L, "updated") shouldBe Done

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "updated", 3L -> "e3")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "updated", 3L -> "e3")
      headSequenceNr(pid) shouldBe Some(3L)
    }

    "refuse an in-place update of an event that is not in the latest write of the JournalHead item" in {
      val pid = "head-in-place-update-3"
      persistOk(headEnabledSystem, pid, "e1")
      persistOk(headEnabledSystem, pid, "e2")

      // The head holds only the events of the latest write, so the head stream has already delivered event 1 and
      // cannot be told about a change. The update is refused instead of changing only the journal row.
      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadAsyncUpdateEventUnsupportedException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "e2")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(2L -> "e2")
    }

    "leave the journal and the JournalHead item unchanged when an in-place event update would make the head item exceed 400 KB" in {
      val pid     = "head-in-place-update-4"
      val payload = "x" * 125000
      persistAllOk(headEnabledSystem, pid, immutable.Seq(payload, payload, payload))

      // each journal row stays far below 400 KB, but the three events of the head would not fit with the new one
      val failure = updateEventFails(headEnabledSystem, pid, 3L, "y" * 160000)
      failure shouldBe a[JournalHeadItemTooLargeException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> payload, 2L -> payload, 3L -> payload)
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> payload, 2L -> payload, 3L -> payload)
      headItem(pid).get.contains("revision") shouldBe false
    }

    "refuse an in-place event update whose journal row would exceed 400 KB before it calls DynamoDB" in {
      val pid = "head-in-place-update-4b"
      persistOk(headEnabledSystem, pid, "e1")

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "x" * 410000)
      failure shouldBe a[JournalHeadJournalRowTooLargeException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
    }

    "end every in-place event update that races with persists in a typed outcome and keep the journal and the head in agreement" in {
      val pid      = "head-in-place-race-1"
      val count    = 60
      val progress = new java.util.concurrent.atomic.AtomicLong(0)
      // the journal plugin starts on its first use, and the updates go to the plugin directly
      persistOk(headEnabledSystem, "head-in-place-race-start-plugin", "e1")
      val journal = journalActor(headEnabledSystem)
      val probe   = newTestProbe(headEnabledSystem)
      val actor   = newActor(headEnabledSystem, pid, probe.ref)

      import org.apache.pekko.pattern.ask
      implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(120.seconds)

      actor.tell(PersistChain(count, progress), probe.ref)
      var updates                     = Vector.empty[scala.concurrent.Future[Any]]
      var persistOutcome: Option[Any] = None
      var lastSent                    = 0L
      val deadline                    = System.nanoTime() + 120.seconds.toNanos
      while (persistOutcome.isEmpty && System.nanoTime() < deadline) {
        // The actor persists the next event as soon as one is confirmed, so the updates of the event just confirmed
        // run at the same time as the write of the next one.
        val latest = progress.get()
        if (latest > lastSent) {
          // three updates of the same event also race with each other, on top of the next write
          updates ++= (1 to 3).map(n => journal ? DynamoDBJournal.InPlaceUpdateEvent(pid, latest, s"u$latest-$n"))
          lastSent = latest
        }
        persistOutcome = Option(probe.receiveOne(1.millis))
      }
      headEnabledSystem.stop(actor)

      val results = updates.map(update => Await.ready(update, 120.seconds).value.get)
      val outcomes = results.map {
        case scala.util.Success(_)  => "Done"
        case scala.util.Failure(ex) => ex.getClass.getSimpleName
      }
      val counts = outcomes.groupBy(identity).map { case (outcome, same) => outcome -> same.size }
      info(s"updates sent=${updates.size} outcomes=$counts")
      info(s"persist outcome=$persistOutcome persisted up to ${progress.get()} of $count")

      val typed = Set(
        "Done",
        classOf[JournalHeadAsyncUpdateEventUnsupportedException].getSimpleName,
        classOf[JournalHeadRowUpdateRejectedException].getSimpleName,
        classOf[JournalHeadConcurrentUpdateException].getSimpleName
      )
      outcomes.filterNot(typed.contains) shouldBe empty

      val journalBySeq = journalPayloads(headEnabledSystem, pid).toMap
      headPayloads(headEnabledSystem, pid).foreach { case (sequenceNr, payload) =>
        journalBySeq.get(sequenceNr) shouldBe Some(payload)
      }
    }

    "refuse an in-place event update whose journal row was hard deleted and create no journal row" in {
      val pid = "head-in-place-update-5"
      persistOk(headEnabledSystem, pid, "e1")
      // A hard delete removes the journal row and leaves the head alone, so the head still holds event 1.
      deleteJournalRows(pid)

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadRowUpdateRejectedException]
      failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe
      JournalRowUpdateRejection.RowDoesNotExist

      journalSequenceNrs(pid) shouldBe Seq.empty
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
    }

    "refuse an in-place update when the aggregate has no JournalHead item" in {
      val pid = "head-in-place-update-6"
      persistOk(headDisabledSystem, pid, "e1")
      // Pekko starts the journal plugin of a system on its first use, and the update goes to the plugin directly.
      persistOk(headEnabledSystem, "head-in-place-update-6-start-plugin", "e1")

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadMissingException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headItem(pid) shouldBe None
    }

    "report an in-place update of an event that cannot be serialized as an illegal argument that keeps the cause" in {
      val pid = "head-in-place-update-7"
      persistOk(headEnabledSystem, pid, "e1")

      val probe = newTestProbe(headEnabledSystem)
      journalActor(headEnabledSystem)
        .tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, new Object), probe.ref)
      val failure = probe.expectMsgType[Status.Failure](60.seconds).cause

      failure shouldBe an[IllegalArgumentException]
      failure.getCause should not be null
      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
    }

    "allow an in-place event update when head mode is disabled" in {
      val pid = "head-disabled-in-place-update-1"
      persistOk(headDisabledSystem, pid, "e1")

      val journal = journalActor(headDisabledSystem)
      val probe   = newTestProbe(headDisabledSystem)
      journal.tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, "updated"), probe.ref)

      probe.expectMsg(60.seconds, Done)
      journalSequenceNrs(pid) shouldBe Seq(1L)
    }

    "fail a write whose transaction would exceed 4 MB and stop the actor" in {
      val pid = "head-4mb-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        // 99 events with a 43,000 character tag each: the journal rows alone exceed 4 MB, the head item stays small
        actor.tell(PersistTaggedAll(99, 43000), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadTransactionTooLargeException]
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

    "not write the JournalHead item on a soft delete when the head is ahead" in {
      val pid = "head-soft-delete-ahead-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      // A soft delete that went through the JournalHead transaction would fail here: the journal rows already exist,
      // so the row condition rejects the write, and the head is ahead of the first sequence number.
      val headAheadItem = new PutItemRequest()
        .withTableName(journalHeadTableName)
        .withItem(
          Map(
            "persistence-id" -> new AttributeValue().withS(pid),
            "sequence-nr"    -> new AttributeValue().withN("10"),
            "events"         -> new AttributeValue().withL(java.util.Collections.emptyList[AttributeValue]())
          ).asJava
        )
      dynamoDBClient.putItem(headAheadItem)

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }

      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(true, true, true)
      headSequenceNr(pid) shouldBe Some(10L)
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

    "accept 99 events in one persistAll and fail 100 events" in {
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

    "accept an event that keeps the JournalHead item under the size limit and fail one that exceeds it" in {
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

    "accept a JournalHead item of exactly 409600 bytes and reject 409601 bytes on the same queue" in {
      // Two events keep each journal row under 400 KB while the head item reaches the exact item size limit. One
      // event at the head limit would put the journal row itself over the limit, and DynamoDB would refuse it first.
      val acceptedPid                     = "head-size-boundary-accepted-1"
      val rejectedPid                     = "head-size-boundary-rejected-1"
      val (acceptedFirst, acceptedSecond) = messageLengthsAtItemSize(acceptedPid, JournalHeadLimits.MaxItemSizeBytes)
      val (rejectedFirst, rejectedSecond) =
        messageLengthsAtItemSize(rejectedPid, JournalHeadLimits.MaxItemSizeBytes + 1)

      headItemSize(acceptedPid, acceptedFirst, acceptedSecond) shouldBe JournalHeadLimits.MaxItemSizeBytes
      headItemSize(rejectedPid, rejectedFirst, rejectedSecond) shouldBe JournalHeadLimits.MaxItemSizeBytes + 1

      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        Await.result(
          dao
            .putMessages(
              Seq(
                journalRowWithMessage(acceptedPid, 1L, acceptedFirst),
                journalRowWithMessage(acceptedPid, 2L, acceptedSecond)
              )
            ).runWith(Sink.head),
          120.seconds
        ) shouldBe 2L
        journalSequenceNrs(acceptedPid) shouldBe Seq(1L, 2L)
        headSequenceNr(acceptedPid) shouldBe Some(2L)

        val exception = failedWith(
          dao,
          Seq(
            journalRowWithMessage(rejectedPid, 1L, rejectedFirst),
            journalRowWithMessage(rejectedPid, 2L, rejectedSecond)
          )
        )
        exception shouldBe a[JournalHeadItemTooLargeException]
        exception.asInstanceOf[JournalHeadItemTooLargeException].itemSizeBytes shouldBe
        JournalHeadLimits.MaxItemSizeBytes + 1
        journalSequenceNrs(rejectedPid) shouldBe Seq.empty
        headItem(rejectedPid) shouldBe None

        // the rejection did not abort the queue of the same dao
        Await.result(
          dao.putMessages(Seq(journalRowWithMessage(rejectedPid, 1L, 1))).runWith(Sink.head),
          60.seconds
        ) shouldBe 1L
        headSequenceNr(rejectedPid) shouldBe Some(1L)
      } finally dao.dispose()
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

    "remove the revision when the migration raises a JournalHead item that is behind the journal" in {
      val pid = "head-migration-revision-1"
      persistOk(headEnabledSystem, pid, "e1")
      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done
      headItem(pid).get("revision").getN shouldBe "1"
      // a node that runs with head mode off writes the journal and leaves the head behind
      persistOk(headDisabledSystem, pid, "e2")

      val client = newV2Client()
      try {
        val result = JournalHeadMigration.run(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = true
        )
        result.raisedPersistenceIds should contain(pid)
      } finally client.close()

      headSequenceNr(pid) shouldBe Some(2L)
      headItem(pid).get("events").getL.isEmpty shouldBe true
      headItem(pid).get.contains("revision") shouldBe false
    }

    "migrate an aggregate whose journal events were all hard deleted from its snapshot" in {
      val pid = "head-snapshot-only-1"
      persistAllOk(headDisabledSystem, pid, immutable.Seq("e1", "e2", "e3"))
      deleteJournalRows(pid)
      journalRows(pid) shouldBe empty

      // the snapshot is the only record of the aggregate's last sequence number
      putSnapshotRow(pid, 3L)

      val client = newV2Client()
      try {
        // without the snapshot, the aggregate has no journal row and the tool cannot see it
        JournalHeadMigration.run(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = true,
          snapshotTable = None
        )
        headSequenceNr(pid) shouldBe None

        val result = JournalHeadMigration.run(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = true,
          snapshotTable = Some(JournalHeadMigration.SnapshotTable(snapshotTableName, "persistence-id", "sequence-nr"))
        )
        result.createdJournalHeads should be >= 1
        result.snapshotTableScanned shouldBe true
      } finally client.close()

      headSequenceNr(pid) shouldBe Some(3L)
      headItem(pid).get("events").getL.isEmpty shouldBe true

      // the aggregate continues above the snapshot instead of failing with JournalHeadMissingException
      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        Await.result(
          dao.putMessages(Seq(journalRow(headEnabledSystem, pid, 4L, "e4"))).runWith(Sink.head),
          60.seconds
        ) shouldBe 1L
      } finally dao.dispose()

      journalSequenceNrs(pid) shouldBe Seq(4L)
      headSequenceNr(pid) shouldBe Some(4L)
    }

    "preserve a head-only aggregate through migration and recover above its retained watermark" in {
      val pid = "head-only-hard-delete-migration-1"
      withActor(headEnabledHardDeleteSystem, pid) { (actor, probe) =>
        actor.tell(PersistAllPayloads(immutable.Seq("e1", "e2", "e3")), probe.ref)
        probe.expectMsg(60.seconds, PersistedAll(3))
        updateEvent(headEnabledHardDeleteSystem, pid, 3L, "updated-e3") shouldBe Done
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
        headEnabledHardDeleteSystem.stop(actor)
        expectStopped(probe, actor)
      }

      journalRows(pid) shouldBe empty
      val snapshots = dynamoDBClient.scan(
        new ScanRequest()
          .withTableName(snapshotTableName)
          .withConsistentRead(true)
          .withFilterExpression("#pid = :pid")
          .withExpressionAttributeNames(Map("#pid" -> "persistence-id").asJava)
          .withExpressionAttributeValues(Map(":pid" -> new AttributeValue().withS(pid)).asJava)
      )
      snapshots.getItems.asScala shouldBe empty
      val retainedHead = headItem(pid).get
      retainedHead("sequence-nr").getN shouldBe "3"
      retainedHead("revision").getN shouldBe "1"
      headPayloads(headEnabledHardDeleteSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "e2", 3L -> "updated-e3")

      val client = newV2Client()
      try {
        Seq(false, true, true).foreach { execute =>
          val result = JournalHeadMigration.run(
            client,
            journalTableName,
            journalColumnsDefConfig,
            headConfig,
            execute = execute,
            snapshotTable = Some(JournalHeadMigration.SnapshotTable(snapshotTableName, "persistence-id", "sequence-nr"))
          )
          result.snapshotTableScanned shouldBe true
          result.raisedPersistenceIds should not contain pid
          result.createdJournalHeads + result.updatedJournalHeads + result.existingJournalHeads shouldBe
          result.scannedPersistenceIds
          headItem(pid) shouldBe Some(retainedHead)
          journalRows(pid) shouldBe empty
          info(s"head-only migration execute=$execute result=$result; retained sequence=3 revision=1 events=1,2,3")
        }
      } finally client.close()

      persistOk(headEnabledHardDeleteSystem, pid, "e4") shouldBe Persisted("e4")
      journalPayloads(headEnabledHardDeleteSystem, pid) shouldBe Seq(4L -> "e4")
      headSequenceNr(pid) shouldBe Some(4L)
      headPayloads(headEnabledHardDeleteSystem, pid) shouldBe Seq(4L -> "e4")
      info(s"head-only recovery journal=${journalSequenceNrs(pid)} head=${headSequenceNr(pid)}")
    }

    "report the same counts in dry-run and execute and converge on a rerun" in {
      val journalOnlyPid  = "head-plan-journal-only-1"
      val snapshotOnlyPid = "head-plan-snapshot-only-1"
      val bothPid         = "head-plan-both-1"
      persistAllOk(headDisabledSystem, journalOnlyPid, immutable.Seq("e1", "e2"))
      persistAllOk(headDisabledSystem, bothPid, immutable.Seq("e1", "e2", "e3"))
      putSnapshotRow(snapshotOnlyPid, 5L)
      putSnapshotRow(bothPid, 7L)

      val client = newV2Client()
      try {
        // The target of an aggregate is the higher of its journal version and its snapshot version.
        val dryRun =
          JournalHeadMigration.run(client, journalTableName, journalColumnsDefConfig, headConfig, execute = false)
        dryRun.createdJournalHeads should be >= 3
        dryRun.updatedJournalHeads shouldBe 0

        val executed =
          JournalHeadMigration.run(client, journalTableName, journalColumnsDefConfig, headConfig, execute = true)
        executed.scannedPersistenceIds shouldBe dryRun.scannedPersistenceIds
        executed.createdJournalHeads shouldBe dryRun.createdJournalHeads
        executed.updatedJournalHeads shouldBe dryRun.updatedJournalHeads
        executed.existingJournalHeads shouldBe dryRun.existingJournalHeads
        executed.raisedPersistenceIds shouldBe dryRun.raisedPersistenceIds

        headSequenceNr(journalOnlyPid) shouldBe Some(2L)
        headSequenceNr(snapshotOnlyPid) shouldBe Some(5L)
        headSequenceNr(bothPid) shouldBe Some(7L)

        val rerun =
          JournalHeadMigration.run(client, journalTableName, journalColumnsDefConfig, headConfig, execute = true)
        rerun.createdJournalHeads shouldBe 0
        rerun.updatedJournalHeads shouldBe 0
        rerun.existingJournalHeads shouldBe rerun.scannedPersistenceIds
        rerun.raisedPersistenceIds shouldBe Seq.empty
      } finally client.close()
    }

    "resolve the snapshot source from the config or the command line, and fail when neither is given" in {
      val configured = ConfigFactory.parseString(
        """
          |j5ik2o.dynamo-db-snapshot {
          |  table-name = "ConfiguredSnapshot"
          |  columns-def {
          |    persistence-id-column-name = "pid"
          |    sequence-nr-column-name = "seq"
          |  }
          |}
          |""".stripMargin
      )

      JournalHeadMigration.resolveSnapshotTable(Array.empty, configured) shouldBe
      Some(JournalHeadMigration.SnapshotTable("ConfiguredSnapshot", "pid", "seq"))

      JournalHeadMigration.resolveSnapshotTable(
        Array(
          "--snapshot-table",
          "CustomSnapshot",
          "--snapshot-persistence-id-column",
          "p",
          "--snapshot-sequence-nr-column",
          "s"
        ),
        configured
      ) shouldBe Some(JournalHeadMigration.SnapshotTable("CustomSnapshot", "p", "s"))

      JournalHeadMigration.resolveSnapshotTable(Array("--without-snapshots"), configured) shouldBe None

      // Without the config and without a named table the tool must not silently scan the default `Snapshot` table.
      val missingSource = intercept[IllegalArgumentException] {
        JournalHeadMigration.resolveSnapshotTable(Array.empty, ConfigFactory.empty())
      }
      missingSource.getMessage should include("--snapshot-table")
      missingSource.getMessage should include("--without-snapshots")

      // A named table is a source on its own, so a deployment without the snapshot plugin can name the table.
      JournalHeadMigration.resolveSnapshotTable(
        Array("--snapshot-table", "CustomSnapshot"),
        ConfigFactory.empty()
      ) shouldBe
      Some(JournalHeadMigration.SnapshotTable("CustomSnapshot", "persistence-id", "sequence-nr"))

      Seq(
        "--snapshot-table",
        "--snapshot-persistence-id-column",
        "--snapshot-sequence-nr-column"
      ).foreach { option =>
        val failure = intercept[IllegalArgumentException] {
          JournalHeadMigration.resolveSnapshotTable(Array(option), configured)
        }
        failure.getMessage should include(option)
      }
    }

    "report a missing snapshot table and still migrate from the journal" in {
      val pid = "head-missing-snapshot-1"
      persistAllOk(headDisabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      val client = newV2Client()
      try {
        val result = JournalHeadMigration.run(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute = true,
          snapshotTable = Some(
            JournalHeadMigration.SnapshotTable("MissingSnapshotTable", "persistence-id", "sequence-nr")
          )
        )
        result.snapshotTableScanned shouldBe false
        result.createdJournalHeads should be >= 1
      } finally client.close()

      headSequenceNr(pid) shouldBe Some(3L)
    }

    "report the planner timing and reads for the perf probe" in {
      assume(sys.env.get("C4_PERF").contains("1"), "set C4_PERF=1 to run the perf probe")

      val journalPids  = (1 to 150).map(index => s"head-perf-journal-$index")
      val snapshotPids = (1 to 30).map(index => s"head-perf-snapshot-$index")
      val bothPids     = (1 to 20).map(index => s"head-perf-both-$index")
      journalPids.foreach(persistenceId => putJournalRow(persistenceId, 3L))
      bothPids.foreach { persistenceId =>
        putJournalRow(persistenceId, 3L)
        putSnapshotRow(persistenceId, 5L)
      }
      snapshotPids.foreach(persistenceId => putSnapshotRow(persistenceId, 7L))

      val client   = newV2Client()
      val getItems = new AtomicInteger()
      val scans    = new AtomicInteger()
      val counted  = countingClient(client, getItems, scans)
      try {
        def measure(): (Long, JournalHeadMigration.Result) = {
          val started = System.nanoTime()
          val result =
            JournalHeadMigration.run(counted, journalTableName, journalColumnsDefConfig, headConfig, execute = false)
          (System.nanoTime() - started, result)
        }

        measure() // warmup, discarded
        val runs            = (1 to 3).map(_ => measure())
        val durationsMillis = runs.map(_._1 / 1000000L).sorted
        val medianMillis    = durationsMillis(durationsMillis.size / 2)
        val result          = runs.head._2
        val readsPerRun     = getItems.get() / 4
        val readsPerPid     = readsPerRun.toDouble / result.scannedPersistenceIds
        val scansPerRun     = scans.get() / 4

        info(
          s"PERF fixturePersistenceIds=${result.scannedPersistenceIds} dryRunMs=${durationsMillis.mkString(",")}" +
          s" medianMs=$medianMillis readsPerPersistenceId=$readsPerPid scansPerRun=$scansPerRun"
        )

        val firstExecute =
          JournalHeadMigration.run(counted, journalTableName, journalColumnsDefConfig, headConfig, execute = true)
        val secondExecute =
          JournalHeadMigration.run(counted, journalTableName, journalColumnsDefConfig, headConfig, execute = true)
        info(
          s"PERF executeCreated=${firstExecute.createdJournalHeads}" +
          s" secondExecuteCreated=${secondExecute.createdJournalHeads}" +
          s" secondExecuteUpdated=${secondExecute.updatedJournalHeads}" +
          s" secondExecuteExisting=${secondExecute.existingJournalHeads}"
        )

        firstExecute.createdJournalHeads should be >= 200
        secondExecute.createdJournalHeads shouldBe 0
        secondExecute.updatedJournalHeads shouldBe 0
        secondExecute.existingJournalHeads shouldBe secondExecute.scannedPersistenceIds
      } finally client.close()
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

// The tests below drive the real V2JournalRowWriteDriver flow with a fake v2 async client. They need the AWS SDK v2
// types, so they live in this allowlisted file; project/module-boundaries.allowlist only shrinks.
object V2JournalRowWriteDriverSpec {

  /** What the fake answers when the driver reads the JournalHead item after a failed head condition. */
  sealed trait FakeHead
  object FakeHead {

    /** The test does not expect a head read. */
    case object Unexpected extends FakeHead

    /** The JournalHead item does not exist. */
    case object Missing extends FakeHead

    /** The JournalHead item has this `sequence-nr`. */
    final case class At(sequenceNr: Long) extends FakeHead
  }

  /** A v2 async client whose `transactWriteItems` fails with a cancelled transaction `failures` times, then succeeds.
    * `failedItemIndex` is the transaction item whose cancellation reason carries `reasonCode`. `head` answers the head
    * read that follows a failed head condition.
    */
  final class FakeTransactWriteClient(
      failures: Int,
      reasonCode: String,
      failedItemIndex: Int = 1,
      head: FakeHead = FakeHead.Unexpected
  ) {
    val calls: AtomicInteger     = new AtomicInteger(0)
    val headReads: AtomicInteger = new AtomicInteger(0)
    private val remaining        = new AtomicInteger(failures)

    val client: V2DynamoDbAsyncClient = Proxy
      .newProxyInstance(
        classOf[V2DynamoDbAsyncClient].getClassLoader,
        Array(classOf[V2DynamoDbAsyncClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
            method.getName match {
              case "transactWriteItems" =>
                calls.incrementAndGet()
                if (remaining.getAndDecrement() > 0) failed(cancelled(reasonCode, failedItemIndex))
                else CompletableFuture.completedFuture(successfulResponse)
              case "getItem" =>
                headReads.incrementAndGet()
                head match {
                  case FakeHead.At(sequenceNr) =>
                    CompletableFuture.completedFuture(headItem(args(0).asInstanceOf[GetItemRequest], sequenceNr))
                  case FakeHead.Missing =>
                    CompletableFuture.completedFuture(GetItemResponse.builder().build())
                  case FakeHead.Unexpected => throw new UnsupportedOperationException("unexpected call: getItem")
                }
              case "close"       => null
              case "serviceName" => "dynamodb"
              case "toString"    => "FakeTransactWriteClient"
              case "hashCode"    => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"      => java.lang.Boolean.valueOf(proxy.asInstanceOf[AnyRef] eq args(0))
              case other         => throw new UnsupportedOperationException(s"unexpected call: $other")
            }
        }
      )
      .asInstanceOf[V2DynamoDbAsyncClient]

    private def successfulResponse: TransactWriteItemsResponse = {
      val builder = TransactWriteItemsResponse.builder()
      builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
      builder.build()
    }

    private def headItem(request: GetItemRequest, sequenceNr: Long): GetItemResponse = {
      val persistenceId = Option(request.key())
        .flatMap(key => Option(key.get("persistence-id")))
        .map(_.s())
        .getOrElse("")
      val item = Map(
        "persistence-id" -> V2AttributeValue.builder().s(persistenceId).build(),
        "sequence-nr"    -> V2AttributeValue.builder().n(sequenceNr.toString).build()
      ).asJava
      GetItemResponse.builder().item(item).build()
    }

    private def cancelled(code: String, failedItemIndex: Int): TransactionCanceledException = {
      val builder = TransactionCanceledException.builder()
      builder.message(s"Transaction cancelled: $code")
      builder.cancellationReasons(
        java.util.Arrays.asList(
          CancellationReason.builder().code(if (failedItemIndex == 0) code else "None").build(),
          CancellationReason.builder().code(if (failedItemIndex == 1) code else "None").build()
        )
      )
      builder.build().asInstanceOf[TransactionCanceledException]
    }

    private def failed(cause: Throwable): CompletableFuture[TransactWriteItemsResponse] = {
      val future = new CompletableFuture[TransactWriteItemsResponse]()
      future.completeExceptionally(cause)
      future
    }
  }

}

final class V2JournalRowWriteDriverSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import V2JournalRowWriteDriverSpec._

  private var system: ActorSystem = _
  private val contexts            = new java.util.concurrent.ConcurrentLinkedQueue[JournalPluginContext]()

  private def baseConfig(writeBackoff: String) = ConfigFactory
    .parseString(s"j5ik2o.dynamo-db-journal.write-backoff { $writeBackoff }")
    .withFallback(
      ConfigHelper.config(
        defaultResource = None,
        legacyConfigFormat = false,
        legacyJournalMode = false,
        dynamoDBHost = "localhost",
        dynamoDBPort = 1,
        clientVersion = ClientVersion.V2.toString,
        clientType = ClientType.Sync.toString,
        headEnabled = true
      )
    ).resolve()

  private val retryingBackoff =
    "enabled = true, min-backoff = 1ms, max-backoff = 2ms, random-factor = 0.0, max-restarts = 2"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    system = ActorSystem("v2-journal-row-write-driver-spec", baseConfig(retryingBackoff))
  }

  override protected def afterAll(): Unit = {
    contexts.forEach(context => context.journalRowWriteDriver.dispose())
    if (system != null) Await.result(system.terminate(), 60.seconds)
    super.afterAll()
  }

  private def write(
      client: V2DynamoDbAsyncClient,
      persistenceId: String,
      writeBackoff: String = retryingBackoff
  ): Future[Long] = {
    implicit val mat: Materializer = SystemMaterializer(system).materializer
    val pluginContext = JournalPluginContext(
      system,
      JournalPluginConfig.fromConfig(baseConfig(writeBackoff).getConfig("j5ik2o.dynamo-db-journal"))
    )
    contexts.add(pluginContext)
    val request = JournalHeadWriteRequest
      .of(
        Seq(
          JournalRow(
            PersistenceId(persistenceId),
            SequenceNumber(1L),
            deleted = false,
            message = Array[Byte](1, 2, 3),
            ordering = 1L
          )
        ),
        pluginContext.pluginConfig.headConfig,
        pluginContext.pluginConfig.columnsDefConfig,
        pluginContext.partitionKeyResolver,
        pluginContext.sortKeyResolver
      )
      .fold(cause => throw cause, identity)
    val driver = new V2JournalRowWriteDriver(pluginContext, Some(client), None)
    Source.single(request).via(driver.putJournalRowsWithHeadFlow).runWith(Sink.head)
  }

  private def await[A](future: Future[A]): A = Await.result(future, 30.seconds)

  "V2JournalRowWriteDriver" should {

    "retry a transaction conflict and complete the write once the conflict clears" in {
      val fake = new FakeTransactWriteClient(failures = 1, reasonCode = "TransactionConflict")

      await(write(fake.client, "conflict-then-success")) shouldBe 1L

      fake.calls.get() shouldBe 2
    }

    "retry throttling like a transaction conflict" in {
      val fake = new FakeTransactWriteClient(failures = 2, reasonCode = "ProvisionedThroughputExceeded")

      await(write(fake.client, "throttled-then-success")) shouldBe 1L

      fake.calls.get() shouldBe 3
    }

    "fail with JournalHeadConcurrentWriteException after the retries are exhausted" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "TransactionConflict")

      val failure = intercept[JournalHeadConcurrentWriteException](await(write(fake.client, "conflict-exhausted")))

      fake.calls.get() shouldBe 3
      failure.persistenceId shouldBe "conflict-exhausted"
      failure.attempts shouldBe 3
      failure.reason shouldBe "TransactionConflict"
    }

    "make a single attempt when max-restarts is 0" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "TransactionConflict")

      val failure = intercept[JournalHeadConcurrentWriteException](
        await(write(fake.client, "no-restarts", "enabled = false, max-restarts = 0"))
      )

      fake.calls.get() shouldBe 1
      failure.attempts shouldBe 1
    }

    "not wait between attempts when write-backoff is disabled" in {
      val fake    = new FakeTransactWriteClient(failures = 2, reasonCode = "TransactionConflict")
      val started = System.nanoTime()

      await(
        write(
          fake.client,
          "disabled-backoff",
          "enabled = false, min-backoff = 10s, max-backoff = 10s, random-factor = 0.0, max-restarts = 2"
        )
      ) shouldBe 1L

      fake.calls.get() shouldBe 3
      (System.nanoTime() - started).nanos should be < 5.seconds
    }

    "wait between attempts when write-backoff is enabled" in {
      val fake    = new FakeTransactWriteClient(failures = 2, reasonCode = "TransactionConflict")
      val started = System.nanoTime()

      await(
        write(
          fake.client,
          "enabled-backoff",
          "enabled = true, min-backoff = 200ms, max-backoff = 200ms, random-factor = 0.0, max-restarts = 2"
        )
      ) shouldBe 1L

      fake.calls.get() shouldBe 3
      (System.nanoTime() - started).nanos should be >= 400.millis
    }

    "fail with DuplicateJournalEventsException, without a retry, when a journal row exists" in {
      val fake = new FakeTransactWriteClient(failures = 1, reasonCode = "ConditionalCheckFailed", failedItemIndex = 0)

      val failure = intercept[DuplicateJournalEventsException](await(write(fake.client, "duplicate")))

      fake.calls.get() shouldBe 1
      failure.persistenceId shouldBe "duplicate"
    }

    "retry when the JournalHead item changed after the condition was checked" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.At(0L)
      )

      await(write(fake.client, "head-changed-then-success")) shouldBe 1L

      fake.calls.get() shouldBe 2
      fake.headReads.get() shouldBe 1
    }

    "fail with JournalHeadConcurrentWriteException when the JournalHead item keeps changing" in {
      val fake = new FakeTransactWriteClient(
        failures = 100,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.At(0L)
      )

      val failure = intercept[JournalHeadConcurrentWriteException](await(write(fake.client, "head-keeps-changing")))

      fake.calls.get() shouldBe 3
      fake.headReads.get() shouldBe 3
      failure.attempts shouldBe 3
    }

    "fail with JournalHeadMissingException, without a retry, when the JournalHead item does not exist" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.Missing
      )

      intercept[JournalHeadMissingException](await(write(fake.client, "head-missing")))

      fake.calls.get() shouldBe 1
      fake.headReads.get() shouldBe 1
    }

    "fail with JournalHeadSequenceMismatchException, without a retry, when the JournalHead item is ahead" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.At(5L)
      )

      intercept[JournalHeadSequenceMismatchException](await(write(fake.client, "head-ahead")))

      fake.calls.get() shouldBe 1
      fake.headReads.get() shouldBe 1
    }

    "fail with the SDK exception, without a retry, for a cancellation reason it does not recognize" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "ValidationError")

      val failure = intercept[TransactionCanceledException](await(write(fake.client, "unknown-reason")))

      fake.calls.get() shouldBe 1
      failure.cancellationReasons().asScala.map(_.code()) should contain("ValidationError")
    }
  }

}
