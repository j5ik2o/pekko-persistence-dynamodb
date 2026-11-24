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

import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props, Status }
import org.apache.pekko.Done
import org.apache.pekko.persistence._
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.testkit.TestProbe
import com.amazonaws.services.dynamodbv2.model.{
  AttributeValue,
  DeleteItemRequest,
  GetItemRequest,
  PutItemRequest,
  ScanRequest
}
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

object PersistenceIdKeySpec extends DockerAssignedPort {

  final case class PersistAll(payloads: immutable.Seq[String])
  final case class DeleteTo(sequenceNr: Long)
  final case class Snap(payload: String)
  final case class DeleteSnapshotAt(sequenceNr: Long)
  final case class DeleteSnapshotsTo(sequenceNr: Long)

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
      case DeleteTo(sequenceNr)          => deleteMessages(sequenceNr)
      case DeleteMessagesSuccess(_)      => probe ! Completed()
      case DeleteMessagesFailure(ex, _)  => probe ! Failed(ex)
      case Snap(payload)                 => saveSnapshot(payload)
      case SaveSnapshotSuccess(_)        => probe ! Completed()
      case SaveSnapshotFailure(_, ex)    => probe ! Failed(ex)
      case DeleteSnapshotAt(sequenceNr)  => deleteSnapshot(sequenceNr)
      case DeleteSnapshotSuccess(_)      => probe ! Completed()
      case DeleteSnapshotFailure(_, ex)  => probe ! Failed(ex)
      case DeleteSnapshotsTo(sequenceNr) => deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = sequenceNr))
      case DeleteSnapshotsSuccess(_)     => probe ! Completed()
      case DeleteSnapshotsFailure(_, ex) => probe ! Failed(ex)
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

  /** A journal or snapshot row as the table stores it. */
  final case class StoredRow(pkey: String, skey: String, sequenceNr: Long, deleted: Option[Boolean])

  /** How a test system is configured. `beforeFix` uses the key resolvers of 1.3.0 (`BeforeKeyFix`), so it writes the
    * rows that a deployment of 1.3.0 already has in its tables. `legacyKeys = false` sets
    * `legacy-keys-enabled = false`.
    */
  final case class Mode(
      headEnabled: Boolean = false,
      softDelete: Boolean = true,
      separator: Option[String] = None,
      beforeFix: Boolean = false,
      legacyKeys: Boolean = true
  )
}

/** Issue #1014 with the AWS SDK v1 client, which cannot use the JournalHead. The snapshot store, the JournalHead, and
  * the request counts are covered by the journal-v2 suite. The default key resolvers split a persistence id at the
  * first separator as plain text, so ids that shared a key in 1.3.0 get keys of their own. The journal rows that 1.3.0
  * wrote under the old keys keep working next to the rows written after the change: replay, the highest sequence
  * number, soft and hard deletes, and in-place updates.
  */
final class PersistenceIdKeySpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {
  import PersistenceIdKeySpec._

  override lazy val dynamoDBPort: Int      = PersistenceIdKeySpec.dynamoDBPort
  override lazy val controller             = PersistenceIdKeySpec.controller
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
    val resolvers =
      if (mode.beforeFix)
        s"""
           |j5ik2o.dynamo-db-journal.partition-key-resolver-class-name = "${classOf[
            BeforeKeyFix.JournalPartitionKeyResolver
          ].getName}"
           |j5ik2o.dynamo-db-journal.sort-key-resolver-class-name = "${classOf[
            BeforeKeyFix.JournalSortKeyResolver
          ].getName}"
           |""".stripMargin
      else ""
    val legacyKeys =
      if (mode.legacyKeys) ""
      else """
             |j5ik2o.dynamo-db-journal.legacy-keys-enabled = false
             |""".stripMargin
    ConfigFactory
      .parseString(
        separator + resolvers + legacyKeys + "\npekko.persistence.snapshot-store.plugin = \"pekko.persistence.no-snapshot-store\""
      )
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
          "keys",
          if (mode.headEnabled) "head" else "nohead",
          if (mode.softDelete) "soft" else "hard",
          mode.separator.fold("dash")(s => s"sep${s.map(_.toInt).mkString("x")}"),
          if (mode.beforeFix) "before" else "after",
          if (mode.legacyKeys) "legacy" else "nolegacy"
        ).mkString("-")
        val created = ActorSystem(name, config(mode))
        systems += mode -> created
        created
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

  // --- keys --------------------------------------------------------------------------------------------------------

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

  /** The first id `candidate(n)` whose key under `old` is `target`. */
  private def idWithOldKey(
      target: (String, String),
      candidate: Int => String,
      old: String => (String, String)
  ): String =
    Iterator.from(0).take(1000000).map(candidate).find(id => old(id) == target).get

  // --- the actor ---------------------------------------------------------------------------------------------------

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

  private def tryCommand(mode: Mode, persistenceId: String, message: Any): Either[Throwable, Unit] =
    withActor(mode, persistenceId) { (actor, probe, _) =>
      actor ! message
      probe.expectMsgAnyClassOf(timeout, classOf[Completed], classOf[Failed]) match {
        case Failed(cause) => Left(cause)
        case _             => Right(())
      }
    }

  private def command(mode: Mode, persistenceId: String, message: Any): Unit =
    tryCommand(mode, persistenceId, message).left.foreach(cause =>
      fail(s"$message for $persistenceId failed: $cause", cause)
    )

  private def tryUpdateEvent(
      mode: Mode,
      persistenceId: String,
      sequenceNr: Long,
      payload: String
  ): Either[Throwable, Unit] = {
    val sys   = system(mode)
    val probe = TestProbe()(sys)
    recover(mode, persistenceId) // starts the journal plugin actor
    val journal = Await.result(sys.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(timeout), timeout)
    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgAnyClassOf(timeout, classOf[Done], classOf[Status.Failure]) match {
      case Status.Failure(cause) => Left(cause)
      case _                     => Right(())
    }
  }

  private def updateEvent(mode: Mode, persistenceId: String, sequenceNr: Long, payload: String): Unit =
    tryUpdateEvent(mode, persistenceId, sequenceNr, payload).left.foreach(cause =>
      fail(s"the update of $persistenceId@$sequenceNr failed: $cause", cause)
    )

  private def events(recovered: Recovered): Vector[(Long, String)] = recovered.events

  // --- the tables --------------------------------------------------------------------------------------------------

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
          item("pkey").getS,
          item("skey").getS,
          item("sequence-nr").getN.toLong,
          item.get("deleted").map(_.getBOOL.booleanValue())
        )
      )
      .sortBy(row => (row.sequenceNr, row.skey))

  /** Moves the row under `from` to `to`, as a release before 1.3.0 did when it overwrote the row of another id that
    * shared the key.
    */
  private def moveRow(table: String, from: (String, String), to: (String, String)): Unit = {
    def key(k: (String, String)) =
      Map("pkey" -> new AttributeValue().withS(k._1), "skey" -> new AttributeValue().withS(k._2)).asJava
    val item = dynamoDBClient.getItem(new GetItemRequest().withTableName(table).withKey(key(from))).getItem
    item should not be null
    val moved = new java.util.HashMap[String, AttributeValue](item)
    moved.putAll(key(to))
    dynamoDBClient.putItem(new PutItemRequest().withTableName(table).withItem(moved))
    dynamoDBClient.deleteItem(new DeleteItemRequest().withTableName(table).withKey(key(from)))
    ()
  }

  // --- the modes ---------------------------------------------------------------------------------------------------

  private val fixed      = Mode()
  private val fixedHard  = Mode(softDelete = false)
  private val before     = Mode(beforeFix = true)
  private val noLegacy   = Mode(legacyKeys = false)
  private val pipe       = Mode(separator = Some("|"))
  private val pipeBefore = pipe.copy(beforeFix = true)
  private val dot        = Mode(separator = Some("."))

  /** An id whose key changes: it has two separators. */
  private def changedId(name: String): String = {
    val id = s"${uniquePrefix(name)}-part-1"
    journalKey(before, id, 1) should not be journalKey(fixed, id, 1)
    id
  }

  private def expectJournalKeyConflict(result: Either[Throwable, Long], persistenceId: String, other: String): Unit =
    result match {
      case Left(conflict: JournalRowStoreException.KeyConflict) =>
        conflict.persistenceId shouldBe persistenceId
        conflict.conflictingPersistenceId shouldBe Some(other)
      case other => fail(s"expected a key conflict, got $other")
    }

  // --- issue #1009: ids that shared a key, or had none, in 1.3.0 -----------------------------------------------------

  /** Both ids persist, and each replays only its own events. */
  private def bothIdsWork(mode: Mode, a: String, b: String): Unit = {
    persist(mode, a, "a1") shouldBe Right(1L)
    persist(mode, b, "b1") shouldBe Right(1L)
    persist(mode, a, "a2") shouldBe Right(2L)

    val recoveredA = recover(mode, a)
    events(recoveredA) shouldBe Vector(1L -> "a1", 2L -> "a2")
    recoveredA.lastSequenceNr shouldBe 2L
    val recoveredB = recover(mode, b)
    events(recoveredB) shouldBe Vector(1L -> "b1")
    recoveredB.lastSequenceNr shouldBe 1L
    storedRows(journalTableName, a).map(_.sequenceNr) shouldBe Vector(1L, 2L)
    storedRows(journalTableName, b).map(_.sequenceNr) shouldBe Vector(1L)
  }

  /** The id persists and replays. */
  private def idWorks(mode: Mode, id: String): Unit = {
    persist(mode, id, "e1", "e2") shouldBe Right(2L)
    persist(mode, id, "e3") shouldBe Right(3L)
    val recovered = recover(mode, id)
    events(recovered) shouldBe Vector(1L -> "e1", 2L -> "e2", 3L -> "e3")
    recovered.lastSequenceNr shouldBe 3L
    storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(1L, 2L, 3L)
  }

  "ids that collided in 1.3.0" should {
    Seq(false).foreach { head =>
      val label = "without the JournalHead"

      s"both persist and replay when they differ after the second separator ($label)" in {
        val mode   = fixed.copy(headEnabled = head)
        val (a, b) = collidingPair(s"${uniquePrefix("Two")}-X-", id => journalKey(before, id, 1))
        bothIdsWork(mode, a, b)
      }

      s"both persist and replay with the separator '|' ($label)" in {
        val mode   = pipe.copy(headEnabled = head)
        val (a, b) = collidingPair(s"${uniquePrefix("Account")}|", id => journalKey(pipeBefore, id, 1))
        bothIdsWork(mode, a, b)
        journalKey(mode, a, 1)._1 should startWith(a.takeWhile(_ != '|') + "-")
      }

      s"persist and replay when the id ends with the separator ($label)" in {
        idWorks(fixed.copy(headEnabled = head), s"${uniquePrefix("Trailing")}-")
      }

      s"persist and replay with the separator '.' ($label)" in {
        idWorks(dot.copy(headEnabled = head), s"${uniquePrefix("Order")}.1")
      }
    }
  }

  // --- a new key that is the 1.3.0 key of another id ---------------------------------------------------------------

  "an id whose new key holds the 1.3.0 row of another id" should {

    "fail with a key conflict and leave that row, for 'Order-' and 'Order--<n>'" in {
      val prefix = uniquePrefix("Order")
      val x      = s"$prefix-"
      val y      = idWithOldKey(journalKey(fixed, x, 1), n => s"$prefix--$n", journalKey(before, _, 1))
      persistOk(before, y, "y1")
      expectJournalKeyConflict(persist(fixed, x, "x1"), x, y)
      events(recover(fixed, y)) shouldBe Vector(1L -> "y1")
      storedRows(journalTableName, x) shouldBe empty
    }

    "fail with a key conflict and leave that row, for 'A|B' and 'AB|<n>'" in {
      val x = "A|B"
      val y = idWithOldKey(journalKey(pipe, x, 1), n => s"AB|$n", journalKey(pipeBefore, _, 1))
      persistOk(pipeBefore, y, "y1")
      expectJournalKeyConflict(persist(pipe, x, "x1"), x, y)
      events(recover(pipe, y)) shouldBe Vector(1L -> "y1")
      storedRows(journalTableName, x) shouldBe empty
    }

  }

  // --- rows written by 1.3.0 ---------------------------------------------------------------------------------------

  "journal rows written by 1.3.0" should {

    "replay in order with the rows written after the change, and give the highest sequence number" in {
      val id = changedId("Replay")
      persistOk(before, id, "e1", "e2", "e3")
      persistOk(fixed, id, "e4", "e5")
      val recovered = recover(fixed, id)
      events(recovered) shouldBe (1L to 5L).map(n => n -> s"e$n").toVector
      recovered.lastSequenceNr shouldBe 5L
      val rows = storedRows(journalTableName, id)
      rows.map(_.sequenceNr) shouldBe (1L to 5L).toVector
      rows.map(r => (r.pkey, r.skey)) shouldBe
      (1L to 3L).map(journalKey(before, id, _)) ++ (4L to 5L).map(journalKey(fixed, id, _))
    }

    "be removed by a hard delete" in {
      val id = changedId("Hard")
      persistOk(before, id, "e1", "e2", "e3")
      persistOk(fixedHard, id, "e4")
      command(fixedHard, id, DeleteTo(3))
      val recovered = recover(fixedHard, id)
      events(recovered) shouldBe Vector(4L -> "e4")
      recovered.lastSequenceNr shouldBe 4L
      storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(4L)
    }

    "stay deleted after a soft delete" in {
      val id = changedId("Soft")
      persistOk(before, id, "e1", "e2", "e3")
      persistOk(fixed, id, "e4")
      command(fixed, id, DeleteTo(2))
      val recovered = recover(fixed, id)
      events(recovered) shouldBe Vector(3L -> "e3", 4L -> "e4")
      recovered.lastSequenceNr shouldBe 4L
      val rows = storedRows(journalTableName, id)
      rows.map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        2L -> Some(true),
        3L -> Some(false),
        4L -> Some(false)
      )
      rows.filter(_.deleted.contains(true)).map(r => (r.pkey, r.skey)) shouldBe Seq(1L, 2L).map(
        journalKey(fixed, id, _)
      )
    }

    "be updated in place" in {
      val id = changedId("Update")
      persistOk(before, id, "e1", "e2")
      persistOk(fixed, id, "e3")
      updateEvent(fixed, id, 1, "e1-updated")
      updateEvent(fixed, id, 3, "e3-updated")
      events(recover(fixed, id)) shouldBe Vector(1L -> "e1-updated", 2L -> "e2", 3L -> "e3-updated")
      storedRows(journalTableName, id).map(r => (r.pkey, r.skey)) shouldBe
      Seq(journalKey(before, id, 1), journalKey(before, id, 2), journalKey(fixed, id, 3))
    }

    "refuse an in-place update of an event that is soft deleted under its 1.3.0 key" in {
      val id = changedId("UpdateDeleted")
      persistOk(before, id, "e1", "e2")
      command(before, id, DeleteTo(1)) // 1.3.0 soft deletes under the old key
      tryUpdateEvent(fixed, id, 1, "e1-updated").isLeft shouldBe true
      events(recover(fixed, id)) shouldBe Vector(2L -> "e2")
    }

    "keep the rows of another id that overwrote the shared key before 1.3.0" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Victim")}-X-", id => journalKey(before, id, 1))
      persistOk(before, a, "a1", "a2")
      // Before 1.3.0, b overwrote the row of a at sequence number 1: the event a1 is lost.
      persistOk(fixed, b, "b1")
      moveRow(journalTableName, journalKey(fixed, b, 1), journalKey(before, b, 1))
      val lost = recover(fixed, a)
      events(lost) shouldBe Vector(2L -> "a2")
      lost.lastSequenceNr shouldBe 2L
      command(fixed, a, DeleteTo(2))
      events(recover(fixed, a)) shouldBe empty
      tryUpdateEvent(fixed, a, 1, "must-not-be-written").isLeft shouldBe true
      events(recover(fixed, b)) shouldBe Vector(1L -> "b1")
      storedRows(journalTableName, b).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(1L -> Some(false))
    }

    "not be confused with the row of another id when a delete or a soft delete names a shared key" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Guard")}-X-", id => journalKey(before, id, 1))
      persistOk(before, b, "b1") // the 1.3.0 key that a shares now holds the row of b
      val driver                          = context(fixed).journalRowWriteDriver
      implicit val sys: ActorSystem       = system(fixed)
      val eventOfA                        = PersistenceIdWithSeqNr(PersistenceId(a), SequenceNumber(1))
      def run[T](source: Source[T, _]): T = Await.result(source.runWith(Sink.last), timeout)
      run(Source.single(eventOfA).via(driver.singleDeleteJournalRowFlow))
      run(
        Source
          .single(Seq(eventOfA, eventOfA.copy(sequenceNumber = SequenceNumber(2)))).via(
            driver.multiDeleteJournalRowsFlow
          )
      )
      run(
        Source
          .single(JournalRow(PersistenceId(a), SequenceNumber(1), deleted = true, Array.emptyByteArray, 0L))
          .via(driver.singlePutJournalRowFlow)
      )
      events(recover(fixed, b)) shouldBe Vector(1L -> "b1")
      storedRows(journalTableName, b).map(r => (r.pkey, r.skey)) shouldBe Vector(journalKey(before, b, 1))
    }

    "keep working with the separator '|', whose partition key also changes" in {
      val pipeHard = pipe.copy(softDelete = false)
      val id       = s"${uniquePrefix("Account")}|01ABC"
      journalKey(pipeBefore, id, 1)._1 should not be journalKey(pipe, id, 1)._1
      persistOk(pipeBefore, id, "e1", "e2", "e3")
      persistOk(pipe, id, "e4")
      command(pipe, id, DeleteTo(1))
      command(pipeHard, id, DeleteTo(2))
      val recovered = recover(pipe, id)
      events(recovered) shouldBe Vector(3L -> "e3", 4L -> "e4")
      recovered.lastSequenceNr shouldBe 4L
      storedRows(journalTableName, id).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        3L -> Some(false),
        4L -> Some(false)
      )
    }

    "be left alone by deletes and updates when legacy-keys-enabled is false" in {
      val id = changedId("NoLegacy")
      persistOk(before, id, "e1", "e2")
      tryUpdateEvent(noLegacy, id, 1, "e1-updated").isLeft shouldBe true
      command(noLegacy.copy(softDelete = false), id, DeleteTo(1))
      events(recover(noLegacy, id)) shouldBe Vector(1L -> "e1", 2L -> "e2")
      storedRows(journalTableName, id).map(r => (r.pkey, r.skey)) shouldBe Seq(1L, 2L).map(journalKey(before, id, _))
    }
  }

  "rows written after the change" should {

    "be replayed by 1.3.0, whose deletes do not reach them until the upgrade" in {
      val id = changedId("Rollback")
      persistOk(fixed, id, "e1", "e2", "e3")
      val rolledBack = recover(before, id)
      events(rolledBack) shouldBe Vector(1L -> "e1", 2L -> "e2", 3L -> "e3")
      rolledBack.lastSequenceNr shouldBe 3L
      persistOk(before, id, "e4")
      events(recover(before, id)).map(_._1) shouldBe Vector(1L, 2L, 3L, 4L)

      // 1.3.0 writes its soft delete under the old key, so the rows of the new release stay undeleted.
      command(before, id, DeleteTo(2))
      events(recover(before, id)).map(_._1) shouldBe Vector(1L, 2L, 3L, 4L)

      // After the upgrade, the same delete removes both copies.
      command(fixed, id, DeleteTo(2))
      events(recover(fixed, id)).map(_._1) shouldBe Vector(3L, 4L)
      storedRows(journalTableName, id).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        2L -> Some(true),
        3L -> Some(false),
        4L -> Some(false)
      )
    }
  }

  // --- ids longer than the sort key limit --------------------------------------------------------------------------

  "an id whose sort key would exceed 1,024 bytes" should {

    "keep working with the rows that 1.3.0 wrote, under a hashed sort key of the same form for every event" in {
      val id = s"${uniquePrefix("Long")}-item-${"x" * 1000}"
      journalKey(before, id, 1)._2.length should be < 1024
      persistOk(before, id, "e1", "e2")
      persistOk(fixed, id, "e3", "e4")
      val sortKeys = Seq(3L, 4L).map(journalKey(fixed, id, _)._2)
      sortKeys.foreach { key =>
        key should startWith("#h1#")
        key.length shouldBe 4 + 64 + 1 + 19
      }
      sortKeys.map(_.dropRight(20)).distinct should have size 1
      val recovered = recover(fixed, id)
      events(recovered).map(_._1) shouldBe Vector(1L, 2L, 3L, 4L)
      recovered.lastSequenceNr shouldBe 4L
      command(fixedHard, id, DeleteTo(3))
      persistOk(fixed, id, "e5")
      events(recover(fixed, id)) shouldBe Vector(4L -> "e4", 5L -> "e5")
      storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(4L, 5L)
    }
  }
}
