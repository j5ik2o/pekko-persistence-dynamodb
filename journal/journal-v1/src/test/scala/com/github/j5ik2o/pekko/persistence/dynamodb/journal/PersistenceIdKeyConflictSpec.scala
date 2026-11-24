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
import org.apache.pekko.persistence._
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.testkit.TestProbe
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.store.JournalRowStoreException
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DockerAssignedPort, DynamoDBContainerHelper }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object PersistenceIdKeyConflictSpec extends DockerAssignedPort {

  final case class PersistAll(payloads: immutable.Seq[String])
  final case class DeleteTo(sequenceNr: Long)

  final case class Persisted(lastSequenceNr: Long)
  final case class Recovered(events: Vector[(Long, String)], snapshot: Option[(Long, Any)], lastSequenceNr: Long)
  final case class Failed(cause: Throwable)
  final case class Completed()

  class Recorder(val persistenceId: String, probe: ActorRef) extends PersistentActor {
    private var events: Vector[(Long, String)] = Vector.empty
    private var snapshot: Option[(Long, Any)]  = None

    override def receiveRecover: Receive = {
      case SnapshotOffer(metadata, state) => snapshot = Some(metadata.sequenceNr -> state)
      case event: String                  => events :+= (lastSequenceNr -> event)
      case RecoveryCompleted              => probe ! Recovered(events, snapshot, lastSequenceNr)
    }

    override def receiveCommand: Receive = {
      case PersistAll(payloads) =>
        var remaining = payloads.size
        persistAll(payloads) { _ =>
          remaining -= 1
          if (remaining == 0) probe ! Persisted(lastSequenceNr)
        }
      case DeleteTo(sequenceNr)         => deleteMessages(sequenceNr)
      case DeleteMessagesSuccess(_)     => probe ! Completed()
      case DeleteMessagesFailure(ex, _) => probe ! Failed(ex)
    }

    override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
      probe ! Failed(cause)
      super.onPersistFailure(cause, event, seqNr)
    }

    override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
      probe ! Failed(cause)
      super.onPersistRejected(cause, event, seqNr)
    }
  }

  final case class Mode(headEnabled: Boolean = false, softDelete: Boolean = true, separator: Option[String] = None)

  final case class StoredRow(persistenceId: String, sequenceNr: Long, deleted: Option[Boolean])
}

/** Issue #1009 with the AWS SDK v1 client, which cannot use the JournalHead. The snapshot store and the JournalHead are
  * covered by the journal-v2 suite. The default key resolvers give two persistence ids the same key when they differ
  * only after the second separator, or always with the separator `|`. A write must not replace the row of the other id:
  * it fails, and the replay, the highest sequence number, and the snapshot of the id that wrote first stay as they
  * were.
  */
final class PersistenceIdKeyConflictSpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {
  import PersistenceIdKeyConflictSpec._

  override lazy val dynamoDBPort: Int      = PersistenceIdKeyConflictSpec.dynamoDBPort
  override lazy val controller             = PersistenceIdKeyConflictSpec.controller
  override val legacyJournalTable: Boolean = false

  protected def clientVersion: String = ClientVersion.V1.toString

  private val timeout   = 60.seconds
  private val idCounter = new AtomicInteger(0)

  private def uniquePrefix(name: String): String = s"$name${idCounter.incrementAndGet()}"

  private var systems: Map[Mode, ActorSystem]           = Map.empty
  private var contexts: Map[Mode, JournalPluginContext] = Map.empty

  private def config(mode: Mode): Config = {
    val separator = mode.separator.fold("")(s => s"""
         |j5ik2o.dynamo-db-journal.persistence-id-separator = "$s"
         |""".stripMargin)
    ConfigFactory
      .parseString(separator + "\npekko.persistence.snapshot-store.plugin = \"pekko.persistence.no-snapshot-store\"")
      .withFallback(
        ConfigHelper.config(
          Some("journal-reference"),
          legacyConfigFormat = false,
          legacyJournalMode = false,
          dynamoDBHost = "localhost",
          dynamoDBPort = dynamoDBPort,
          clientVersion = clientVersion,
          clientType = ClientType.Sync.toString,
          softDelete = mode.softDelete,
          headEnabled = mode.headEnabled
        )
      )
  }

  private def system(mode: Mode): ActorSystem = synchronized {
    systems.getOrElse(
      mode, {
        val name = Seq(
          "conflict",
          if (mode.headEnabled) "head" else "nohead",
          if (mode.softDelete) "soft" else "hard",
          mode.separator.fold("dash")(s => s"sep${s.map(_.toInt).mkString("x")}")
        ).mkString("-")
        val created = ActorSystem(name, config(mode))
        systems += mode -> created
        created
      }
    )
  }

  private def context(mode: Mode): JournalPluginContext = synchronized {
    contexts.getOrElse(
      mode, {
        val sys = system(mode)
        val journal = JournalPluginContext(
          sys,
          JournalPluginConfig.fromConfig(sys.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
        )
        contexts += mode -> journal
        journal
      }
    )
  }

  override def afterStartContainers(): Unit = {
    super.afterStartContainers()
    createTable()
  }

  override def beforeStopContainers(): Unit = {
    contexts.values.foreach(_.journalRowWriteDriver.dispose())
    contexts = Map.empty
    systems.values.foreach(s => Await.result(s.terminate(), timeout))
    systems = Map.empty
    deleteTable()
    super.beforeStopContainers()
  }

  private def journalKey(mode: Mode, persistenceId: String, sequenceNr: Long): (String, String) = {
    val ctx = context(mode)
    val pid = PersistenceId(persistenceId)
    val seq = SequenceNumber(sequenceNr)
    (ctx.partitionKeyResolver.resolve(pid, seq).asString, ctx.sortKeyResolver.resolve(pid, seq).asString)
  }

  /** Two different ids `<prefix><n>` that get the same key at sequence number 1 under `key`. */
  private def collidingPair(prefix: String, key: String => (String, String)): (String, String) = {
    val seen = scala.collection.mutable.Map.empty[(String, String), String]
    Iterator
      .from(0)
      .map { n =>
        val pid = s"$prefix$n"
        val k   = key(pid)
        val hit = seen.get(k).map(_ -> pid)
        seen.getOrElseUpdate(k, pid)
        hit
      }
      .collectFirst { case Some(pair) => pair }
      .get
  }

  private def withActor[T](mode: Mode, persistenceId: String)(action: (ActorRef, TestProbe, Recovered) => T): T = {
    val sys   = system(mode)
    val probe = TestProbe()(sys)
    val actor = sys.actorOf(
      Props(new Recorder(persistenceId, probe.ref)),
      s"recorder-${persistenceId.hashCode.abs}-${System.nanoTime()}"
    )
    val recovered = probe.expectMsgType[Recovered](timeout)
    try action(actor, probe, recovered)
    finally sys.stop(actor)
  }

  private def recover(mode: Mode, persistenceId: String): Recovered =
    withActor(mode, persistenceId)((_, _, recovered) => recovered)

  private def persist(mode: Mode, persistenceId: String, payloads: String*): Either[Throwable, Long] =
    withActor(mode, persistenceId) { (actor, probe, _) =>
      actor ! PersistAll(payloads.toVector)
      probe.expectMsgAnyClassOf(timeout, classOf[Persisted], classOf[Failed]) match {
        case Persisted(last) => Right(last)
        case Failed(cause)   => Left(cause)
      }
    }

  private def persistOk(mode: Mode, persistenceId: String, payloads: String*): Long =
    persist(mode, persistenceId, payloads: _*) match {
      case Right(last) => last
      case Left(cause) => fail(s"persist of $persistenceId failed: $cause", cause)
    }

  private def command(mode: Mode, persistenceId: String, message: Any): Either[Throwable, Unit] =
    withActor(mode, persistenceId) { (actor, probe, _) =>
      actor ! message
      probe.expectMsgAnyClassOf(timeout, classOf[Completed], classOf[Failed]) match {
        case Failed(cause) => Left(cause)
        case _             => Right(())
      }
    }

  private def commandOk(mode: Mode, persistenceId: String, message: Any): Unit =
    command(mode, persistenceId, message).left.foreach(cause => fail(s"$message for $persistenceId failed", cause))

  private def updateEvent(
      mode: Mode,
      persistenceId: String,
      sequenceNr: Long,
      payload: String
  ): Either[Throwable, Done] = {
    val sys = system(mode)
    recover(mode, persistenceId) // starts the journal plugin actor
    val probe   = TestProbe()(sys)
    val journal = Await.result(sys.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(timeout), timeout)
    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgAnyClassOf(timeout, classOf[Done], classOf[Status.Failure]) match {
      case Status.Failure(cause) => Left(cause)
      case done: Done            => Right(done)
    }
  }

  private def storedRows(table: String, persistenceId: String): Vector[StoredRow] =
    dynamoDBClient
      .scan(
        new ScanRequest()
          .withTableName(table)
          .withConsistentRead(true)
          .withFilterExpression("#pid = :pid")
          .withExpressionAttributeNames(Map("#pid" -> "persistence-id").asJava)
          .withExpressionAttributeValues(Map(":pid" -> new AttributeValue().withS(persistenceId)).asJava)
      )
      .getItems
      .asScala
      .toVector
      .map(_.asScala)
      .map(item =>
        StoredRow(
          item("persistence-id").getS,
          item("sequence-nr").getN.toLong,
          item.get("deleted").map(_.getBOOL.booleanValue())
        )
      )
      .sortBy(_.sequenceNr)

  private val headDisabled = Mode()
  private val hardDelete   = Mode(softDelete = false)
  private val pipe         = Mode(separator = Some("|"))

  private def expectKeyConflict(result: Either[Throwable, Long], persistenceId: String, other: String): Unit =
    result match {
      case Left(conflict: JournalRowStoreException.KeyConflict) =>
        conflict.persistenceId shouldBe persistenceId
        conflict.conflictingPersistenceId shouldBe Some(other)
        conflict.getMessage should include("issues/1009")
      case other => fail(s"expected a key conflict, got $other")
    }

  "a persistence id whose journal key holds an event of another id" should {

    "fail to persist and leave the other id's events when the JournalHead is disabled" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Off")}-X-", id => journalKey(headDisabled, id, 1))
      persistOk(headDisabled, a, "a1", "a2")
      expectKeyConflict(persist(headDisabled, b, "b1"), b, a)

      val recoveredA = recover(headDisabled, a)
      recoveredA.events shouldBe Vector(1L -> "a1", 2L -> "a2")
      recoveredA.lastSequenceNr shouldBe 2L
      recover(headDisabled, b).events shouldBe empty
      storedRows(journalTableName, b) shouldBe empty
    }

    "fail to persist with the separator '|', which shares a key with a single separator" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Account")}|", id => journalKey(pipe, id, 1))
      persistOk(pipe, a, "a1")
      expectKeyConflict(persist(pipe, b, "b1"), b, a)
      recover(pipe, a).events shouldBe Vector(1L -> "a1")
    }

    "not change the other id's event by an in-place update" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Update")}-X-", id => journalKey(headDisabled, id, 1))
      persistOk(headDisabled, a, "a1")
      updateEvent(headDisabled, b, 1, "b1-updated").isLeft shouldBe true
      recover(headDisabled, a).events shouldBe Vector(1L -> "a1")
    }

    "not delete the other id's event through the journal row driver" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Delete")}-X-", id => journalKey(headDisabled, id, 1))
      persistOk(headDisabled, a, "a1")
      val driver                    = context(headDisabled).journalRowWriteDriver
      implicit val sys: ActorSystem = system(headDisabled)
      val eventOfB                  = PersistenceIdWithSeqNr(PersistenceId(b), SequenceNumber(1))
      Await.result(Source.single(eventOfB).via(driver.singleDeleteJournalRowFlow).runWith(Sink.last), timeout)
      Await.result(
        Source
          .single(Seq(eventOfB, eventOfB.copy(sequenceNumber = SequenceNumber(2))))
          .via(driver.multiDeleteJournalRowsFlow)
          .runWith(Sink.last),
        timeout
      )
      recover(headDisabled, a).events shouldBe Vector(1L -> "a1")
    }
  }

  "a persistence id without a conflict" should {

    "write, replay, update, and delete its events as before" in {
      val id = s"${uniquePrefix("Plain")}-1"
      persistOk(headDisabled, id, (1 to 30).map(n => s"e$n"): _*)
      recover(headDisabled, id).lastSequenceNr shouldBe 30L
      updateEvent(headDisabled, id, 30, "e30-updated").isRight shouldBe true
      commandOk(headDisabled, id, DeleteTo(10)) // soft delete rewrites the rows of the same id
      val afterSoft = recover(headDisabled, id)
      afterSoft.events.map(_._1) shouldBe (11L to 30L).toVector
      afterSoft.events.last shouldBe (30L -> "e30-updated")
      storedRows(journalTableName, id).count(_.deleted.contains(true)) shouldBe 10
      commandOk(hardDelete, id, DeleteTo(20))
      val afterHard = recover(hardDelete, id)
      afterHard.events.map(_._1) shouldBe (21L to 30L).toVector
      afterHard.lastSequenceNr shouldBe 30L
    }
  }
}
