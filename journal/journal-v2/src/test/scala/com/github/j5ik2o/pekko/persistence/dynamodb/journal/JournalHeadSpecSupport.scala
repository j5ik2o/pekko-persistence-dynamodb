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
import org.apache.pekko.stream.scaladsl.Sink
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
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.serialization.ByteArrayJournalSerializer
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DynamoDBContainerHelper }
import com.typesafe.config.{ Config, ConfigFactory }
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
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.jdk.CollectionConverters._

object JournalHeadSpecSupport {
  val dynamoDBHost: String = "localhost"

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

/** The container, the tables, the ActorSystems, and the helpers that every JournalHead suite shares. Like
  * `DynamoDBContainerHelper` itself, it creates the tables and the ActorSystems again for each test.
  */
abstract class JournalHeadSpecSupport extends AnyWordSpec with Matchers with ScalaFutures with DynamoDBContainerHelper {

  import JournalHeadSpecSupport._

  override val legacyJournalTable: Boolean = false

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(120, Seconds), interval = Span(200, Millis))

  protected val journalColumnsDefConfig: JournalColumnsDefConfig = JournalColumnsDefConfig(
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

  protected val headConfig: JournalHeadConfig = JournalHeadConfig(
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

  protected var headDisabledSystem: ActorSystem          = _
  protected var headEnabledSystem: ActorSystem           = _
  protected var headEnabledHardDeleteSystem: ActorSystem = _

  protected def config(headEnabled: Boolean, softDelete: Boolean = true): Config = {
    ConfigHelper.config(
      Some("journal-reference"),
      legacyConfigFormat = false,
      legacyJournalMode = false,
      dynamoDBHost = JournalHeadSpecSupport.dynamoDBHost,
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

  protected def newTestProbe(system: ActorSystem): TestProbe = TestProbe()(system)

  /** The plugin actor Pekko creates under `/system` from `pekko.persistence.journal.plugin`. */
  protected def journalActor(system: ActorSystem): ActorRef =
    Await.result(
      system.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(30.seconds),
      30.seconds
    )

  protected def newActor(system: ActorSystem, persistenceId: String, failureProbe: ActorRef): ActorRef =
    system.actorOf(
      Props(classOf[TestPersistentActor], persistenceId, failureProbe),
      s"actor-${persistenceId.hashCode}-${System.nanoTime()}"
    )

  protected def withActor[T](system: ActorSystem, persistenceId: String)(
      action: (ActorRef, TestProbe) => T
  ): T = {
    val probe = newTestProbe(system)
    val actor = newActor(system, persistenceId, probe.ref)
    try action(actor, probe)
    finally system.stop(actor)
  }

  protected def persistOk(system: ActorSystem, persistenceId: String, payload: String): Persisted =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(Persist(payload), probe.ref)
      probe.expectMsg(60.seconds, Persisted(payload))
    }

  protected def persistAllOk(
      system: ActorSystem,
      persistenceId: String,
      payloads: immutable.Seq[String]
  ): PersistedAll =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(PersistAllPayloads(payloads), probe.ref)
      probe.expectMsgType[PersistedAll](60.seconds)
    }

  /** A write that fails (a limit of the plugin, a failed condition or a service error) stops the actor. */
  protected def persistFails(system: ActorSystem, persistenceId: String, payloads: immutable.Seq[String]): Throwable =
    withActor(system, persistenceId) { (actor, probe) =>
      actor.tell(PersistAllPayloads(payloads), probe.ref)
      probe.expectMsgType[PersistFailed](60.seconds).cause
    }

  /** The actor is stopped by Pekko after onPersistFailure: it receives no further command. */
  protected def expectStopped(probe: TestProbe, actor: ActorRef): Unit = {
    probe.watch(actor)
    probe.expectTerminated(actor, 60.seconds)
  }

  /** A write over a limit is a failure, not a rejection: onPersistFailure runs and the actor stops. */
  protected def expectPersistFailed(probe: TestProbe, actor: ActorRef): Throwable = {
    val failed = probe
      .fishForMessage(120.seconds, "the failure of a write over a limit") {
        case PersistFailed(_)   => true
        case PersistRejected(_) => fail("a write over a limit must fail the actor, not be rejected")
        case _                  => false
      }.asInstanceOf[PersistFailed]
    expectStopped(probe, actor)
    failed.cause
  }

  protected def headItem(persistenceId: String): Option[Map[String, AttributeValue]] = {
    val getItemRequest = new com.amazonaws.services.dynamodbv2.model.GetItemRequest()
      .withTableName(journalHeadTableName)
      .withKey(Map("persistence-id" -> new AttributeValue().withS(persistenceId)).asJava)
      .withConsistentRead(true)
    Option(dynamoDBClient.getItem(getItemRequest).getItem).map(_.asScala.toMap)
  }

  protected def journalRows(persistenceId: String): Seq[Map[String, AttributeValue]] = {
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

  protected def journalSequenceNrs(persistenceId: String): Seq[Long] =
    journalRows(persistenceId).map(_("sequence-nr").getN.toLong)

  protected def headSequenceNr(persistenceId: String): Option[Long] =
    headItem(persistenceId).map(_("sequence-nr").getN.toLong)

  protected def putJournalRow(persistenceId: String, sequenceNr: Long): Unit = {
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

  protected def putSnapshotRow(persistenceId: String, sequenceNr: Long): Unit = {
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

  protected def deleteJournalRows(persistenceId: String): Unit =
    journalRows(persistenceId).foreach { row =>
      val request = new DeleteItemRequest()
        .withTableName(journalTableName)
        .withKey(Map("pkey" -> row("pkey"), "skey" -> row("skey")).asJava)
      dynamoDBClient.deleteItem(request)
    }

  protected def payloadOf(system: ActorSystem, message: Array[Byte]): Any =
    SerializationExtension(system)
      .serializerFor(classOf[PersistentRepr])
      .fromBinary(message, classOf[PersistentRepr])
      .asInstanceOf[PersistentRepr]
      .payload

  protected def journalPayloads(system: ActorSystem, persistenceId: String): Seq[(Long, Any)] =
    journalRows(persistenceId).map(row =>
      row("sequence-nr").getN.toLong -> payloadOf(system, row("message").getB.array())
    )

  protected def headPayloads(system: ActorSystem, persistenceId: String): Seq[(Long, Any)] =
    headItem(persistenceId).toSeq.flatMap(
      _("events").getL.asScala.map(event =>
        event.getM.get("sequence-nr").getN.toLong -> payloadOf(system, event.getM.get("message").getB.array())
      )
    )

  protected def updateEvent(system: ActorSystem, persistenceId: String, sequenceNr: Long, payload: String): Any = {
    val probe = newTestProbe(system)
    journalActor(system).tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgAnyClassOf(60.seconds, classOf[Done], classOf[Status.Failure]) match {
      case Status.Failure(cause) => fail(s"the update failed: $cause", cause)
      case other                 => other
    }
  }

  protected def updateEventFails(
      system: ActorSystem,
      persistenceId: String,
      sequenceNr: Long,
      payload: String
  ): Throwable = {
    val probe = newTestProbe(system)
    journalActor(system).tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgType[Status.Failure](60.seconds).cause
  }

  protected def newWriteJournalDao(system: ActorSystem): WriteJournalDaoImpl = {
    val pluginConfig  = JournalPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
    val pluginContext = JournalPluginContext(system, pluginConfig)
    val serializer =
      new ByteArrayJournalSerializer(SerializationExtension(system), pluginConfig.tagSeparator, None, None)
    implicit val ec: ExecutionContext = pluginContext.pluginExecutor
    new WriteJournalDaoImpl(pluginContext, pluginContext.journalRowWriteDriver, serializer)(ec, system)
  }

  protected def journalRow(
      system: ActorSystem,
      persistenceId: String,
      sequenceNr: Long,
      payload: String
  ): JournalRow = {
    val serializer                    = new ByteArrayJournalSerializer(SerializationExtension(system), ",", None, None)
    implicit val ec: ExecutionContext = system.dispatcher
    Await.result(serializer.serialize(PersistentRepr(payload, sequenceNr, persistenceId)), 30.seconds)
  }

  protected def journalRowWithMessage(
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
  protected def headItemSize(persistenceId: String, firstMessageLength: Int, secondMessageLength: Int): Int =
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

  protected def messageLengthsAtItemSize(persistenceId: String, itemSizeBytes: Int): (Int, Int) = {
    val available = itemSizeBytes - headItemSize(persistenceId, 0, 0)
    (available / 2, available / 2 + available % 2)
  }

  protected def failedWith(dao: WriteJournalDaoImpl, rows: Seq[JournalRow]): Throwable = {
    implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
    Await.result(dao.putMessages(rows).runWith(Sink.head).failed, 60.seconds)
  }

  protected def newV2Client(): V2DynamoDbClient =
    V2DynamoDbClient
      .builder()
      .endpointOverride(URI.create(s"http://${JournalHeadSpecSupport.dynamoDBHost}:$dynamoDBPort"))
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
      .build()

  protected def newV2StreamsClient(): DynamoDbStreamsClient =
    DynamoDbStreamsClient
      .builder()
      .endpointOverride(URI.create(s"http://${JournalHeadSpecSupport.dynamoDBHost}:$dynamoDBPort"))
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
      .build()

  protected def headStreamRecords(
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

  protected def waitForStreamShards(
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

  protected def readStreamRecords(
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
