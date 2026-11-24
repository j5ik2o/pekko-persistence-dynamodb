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
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
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

  /** How a test system is configured. `beforeFix` uses the key resolvers of the releases before the fix. */
  final case class Mode(
      headEnabled: Boolean = false,
      softDelete: Boolean = true,
      separator: Option[String] = None,
      beforeFix: Boolean = false
  )
}

/** Issue #1009: two persistence ids must never share a journal or snapshot key, and the rows that releases before the
  * fix wrote under the old keys must keep working: replay, the highest sequence number, soft and hard deletes, in-place
  * updates, and snapshot loads and deletes.
  */
final class PersistenceIdKeySpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {
  import PersistenceIdKeySpec._

  override lazy val dynamoDBPort: Int      = PersistenceIdKeySpec.dynamoDBPort
  override lazy val controller             = PersistenceIdKeySpec.controller
  override val legacyJournalTable: Boolean = false

  protected def clientVersion: String = ClientVersion.V2.toString

  private val timeout   = 60.seconds
  private val idCounter = new AtomicInteger(0)

  private def uniquePrefix(name: String): String = s"$name${idCounter.incrementAndGet()}"

  private var systems: Map[Mode, ActorSystem]                                    = Map.empty
  private var contexts: Map[Mode, (JournalPluginContext, SnapshotPluginContext)] = Map.empty

  private def config(mode: Mode): Config = {
    val separator = mode.separator.fold("")(s => s"""
         |j5ik2o.dynamo-db-journal.persistence-id-separator = "$s"
         |j5ik2o.dynamo-db-snapshot.persistence-id-separator = "$s"
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
           |j5ik2o.dynamo-db-snapshot.partition-key-resolver-class-name = "${classOf[
            BeforeKeyFix.SnapshotPartitionKeyResolverBeforeFix
          ].getName}"
           |j5ik2o.dynamo-db-snapshot.sort-key-resolver-class-name = "${classOf[
            BeforeKeyFix.SnapshotSortKeyResolverBeforeFix
          ].getName}"
           |""".stripMargin
      else ""
    ConfigFactory
      .parseString(separator + resolvers)
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
          if (mode.beforeFix) "before" else "after"
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
    contexts.values.foreach { case (journal, _) => journal.journalRowWriteDriver.dispose() }
    contexts = Map.empty
    systems.values.foreach(s => Await.result(s.terminate(), timeout))
    systems = Map.empty
    deleteTable()
    super.beforeStopContainers()
  }

  // --- keys --------------------------------------------------------------------------------------------------------

  private def context(mode: Mode): (JournalPluginContext, SnapshotPluginContext) = synchronized {
    contexts.getOrElse(
      mode, {
        val sys = system(mode)
        val journal = JournalPluginContext(
          sys,
          JournalPluginConfig.fromConfig(sys.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
        )
        val snapshot = SnapshotPluginContext(
          sys,
          SnapshotPluginConfig.fromConfig(sys.settings.config.getConfig("j5ik2o.dynamo-db-snapshot"))
        )
        contexts += mode -> (journal, snapshot)
        (journal, snapshot)
      }
    )
  }

  private def journalKey(mode: Mode, persistenceId: String, sequenceNr: Long): (String, String) = {
    val ctx = context(mode)._1
    val pid = PersistenceId(persistenceId)
    val seq = SequenceNumber(sequenceNr)
    (ctx.partitionKeyResolver.resolve(pid, seq).asString, ctx.sortKeyResolver.resolve(pid, seq).asString)
  }

  private def snapshotKey(mode: Mode, persistenceId: String, sequenceNr: Long): (String, String) = {
    val ctx = context(mode)._2
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

  private def command(mode: Mode, persistenceId: String, message: Any): Unit =
    withActor(mode, persistenceId) { (actor, probe, _) =>
      actor ! message
      probe.expectMsgAnyClassOf(timeout, classOf[Completed], classOf[Failed]) match {
        case Failed(cause) => fail(s"$message for $persistenceId failed: $cause", cause)
        case _             => ()
      }
    }

  private def updateEvent(mode: Mode, persistenceId: String, sequenceNr: Long, payload: String): Unit = {
    val sys   = system(mode)
    val probe = TestProbe()(sys)
    recover(mode, persistenceId) // starts the journal plugin actor
    val journal = Await.result(sys.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(timeout), timeout)
    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, payload), probe.ref)
    probe.expectMsgAnyClassOf(timeout, classOf[Done], classOf[Status.Failure]) match {
      case Status.Failure(cause) => fail(s"the update of $persistenceId@$sequenceNr failed: $cause", cause)
      case _                     => ()
    }
  }

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

  private def headSequenceNr(persistenceId: String): Option[Long] =
    Option(
      dynamoDBClient
        .getItem(
          new com.amazonaws.services.dynamodbv2.model.GetItemRequest()
            .withTableName(journalHeadTableName)
            .withKey(Map("persistence-id" -> new AttributeValue().withS(persistenceId)).asJava)
            .withConsistentRead(true)
        )
        .getItem
    ).map(_.get("sequence-nr").getN.toLong)

  // --- the modes ---------------------------------------------------------------------------------------------------

  private val fixed         = Mode()
  private val fixedHard     = Mode(softDelete = false)
  private val fixedHead     = Mode(headEnabled = true)
  private val fixedHeadHard = Mode(headEnabled = true, softDelete = false)
  private val before        = Mode(beforeFix = true)
  private val beforeHead    = Mode(headEnabled = true, beforeFix = true)

  /** An id whose key changes with the fix: it has two separators. */
  private def changedId(name: String): String = {
    val id = s"${uniquePrefix(name)}-part-1"
    journalKey(before, id, 1) should not be journalKey(fixed, id, 1)
    id
  }

  // --- new writes never share a key --------------------------------------------------------------------------------

  "ids that shared a key before the fix" should {

    "keep their own journal rows when the JournalHead is disabled" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Off")}-X-", id => journalKey(before, id, 1))
      journalKey(fixed, a, 1) should not be journalKey(fixed, b, 1)
      persistOk(fixed, a, "a1")
      persistOk(fixed, b, "b1")
      events(recover(fixed, a)) shouldBe Vector(1L -> "a1")
      events(recover(fixed, b)) shouldBe Vector(1L -> "b1")
      recover(fixed, a).lastSequenceNr shouldBe 1L
    }

    "keep their own journal rows when the JournalHead is enabled" in {
      val (a, b) = collidingPair(s"${uniquePrefix("On")}-X-", id => journalKey(beforeHead, id, 1))
      persistOk(fixedHead, a, "a1")
      persist(fixedHead, b, "b1") shouldBe Right(1L)
      events(recover(fixedHead, a)) shouldBe Vector(1L -> "a1")
      events(recover(fixedHead, b)) shouldBe Vector(1L -> "b1")
      headSequenceNr(b) shouldBe Some(1L)
    }

    "keep their own snapshots" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Snap")}-X-", id => snapshotKey(before, id, 1))
      snapshotKey(fixed, a, 1) should not be snapshotKey(fixed, b, 1)
      Seq(a, b).foreach { id =>
        persistOk(fixed, id, s"$id-e1")
        command(fixed, id, Snap(s"snapshot-of-$id"))
      }
      recover(fixed, a).snapshot shouldBe Some(1L -> s"snapshot-of-$a")
      recover(fixed, b).snapshot shouldBe Some(1L -> s"snapshot-of-$b")
    }

    "keep their own rows with the separator '|', which was read as a regular expression" in {
      val pipe       = Mode(separator = Some("|"))
      val pipeBefore = pipe.copy(beforeFix = true)
      val (a, b)     = collidingPair(s"${uniquePrefix("Account")}|", id => journalKey(pipeBefore, id, 1))
      journalKey(pipe, a, 1)._1 should startWith(a.takeWhile(_ != '|') + "-")
      persistOk(pipe, a, "a1")
      persistOk(pipe, b, "b1")
      events(recover(pipe, a)) shouldBe Vector(1L -> "a1")
      events(recover(pipe, b)) shouldBe Vector(1L -> "b1")
    }
  }

  "ids that failed to get a key before the fix" should {

    "persist and replay when the id ends with the separator" in {
      val id = s"${uniquePrefix("Trailing")}-"
      persistOk(fixed, id, "e1")
      events(recover(fixed, id)) shouldBe Vector(1L -> "e1")
      command(fixed, id, Snap("s1"))
      recover(fixed, id).snapshot shouldBe Some(1L -> "s1")
    }

    "persist and replay with the separator '.'" in {
      val dot = Mode(separator = Some("."))
      val id  = s"${uniquePrefix("Order")}.1"
      persistOk(dot, id, "e1")
      events(recover(dot, id)) shouldBe Vector(1L -> "e1")
      command(dot, id, Snap("s1"))
      recover(dot, id).snapshot shouldBe Some(1L -> "s1")
    }
  }

  // --- rows written before the fix ---------------------------------------------------------------------------------

  "journal rows written before the fix" should {

    "replay in order with the rows written after it, and give the highest sequence number" in {
      val id = changedId("Replay")
      persistOk(before, id, "e1", "e2", "e3")
      persistOk(fixed, id, "e4", "e5")
      val recovered = recover(fixed, id)
      events(recovered) shouldBe (1L to 5L).map(n => n -> s"e$n").toVector
      recovered.lastSequenceNr shouldBe 5L
      storedRows(journalTableName, id).map(_.sequenceNr) shouldBe (1L to 5L).toVector
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
      updateEvent(fixed, id, 1, "e1-updated")
      events(recover(fixed, id)) shouldBe Vector(1L -> "e1-updated", 2L -> "e2")
      storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(1L, 2L)
    }

    "work with the JournalHead: append, update, and hard delete" in {
      val id = changedId("Head")
      persistOk(beforeHead, id, "e1", "e2", "e3")
      headSequenceNr(id) shouldBe Some(3L)
      updateEvent(fixedHead, id, 3, "e3-updated")
      persistOk(fixedHead, id, "e4")
      headSequenceNr(id) shouldBe Some(4L)
      events(recover(fixedHead, id)) shouldBe Vector(1L -> "e1", 2L -> "e2", 3L -> "e3-updated", 4L -> "e4")
      command(fixedHeadHard, id, DeleteTo(3))
      val recovered = recover(fixedHeadHard, id)
      events(recovered) shouldBe Vector(4L -> "e4")
      recovered.lastSequenceNr shouldBe 4L
      storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(4L)
    }

    "keep the rows of another id that overwrote the shared key" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Victim")}-X-", id => journalKey(before, id, 1))
      persistOk(before, a, "a1", "a2")
      persistOk(before, b, "b1") // overwrites the row of a at sequence number 1: the event a1 is lost
      val lost = recover(fixed, a)
      events(lost) shouldBe Vector(2L -> "a2")
      lost.lastSequenceNr shouldBe 2L
      command(fixed, a, DeleteTo(2))
      events(recover(fixed, a)) shouldBe empty
      updateEventRejected(fixed, a, 1)
      events(recover(fixed, b)) shouldBe Vector(1L -> "b1")
      storedRows(journalTableName, b).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(1L -> Some(false))
    }

    "not be confused with the row of another id when a delete or a soft delete names a shared key" in {
      val (a, b) = collidingPair(s"${uniquePrefix("Guard")}-X-", id => journalKey(before, id, 1))
      persistOk(before, a, "a1")
      persistOk(before, b, "b1") // the shared key now holds the row of b
      val driver                          = context(fixed)._1.journalRowWriteDriver
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
      val pipe       = Mode(separator = Some("|"))
      val pipeHard   = pipe.copy(softDelete = false)
      val pipeBefore = pipe.copy(beforeFix = true)
      val id         = s"${uniquePrefix("Account")}|01ABC"
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
  }

  "snapshots written before the fix" should {

    "load, and be removed by a delete of one snapshot or by criteria" in {
      val id = changedId("SnapLoad")
      snapshotKey(before, id, 1) should not be snapshotKey(fixed, id, 1)
      persistOk(before, id, "e1")
      command(before, id, Snap("s1"))
      persistOk(before, id, "e2")
      command(before, id, Snap("s2"))
      persistOk(before, id, "e3")
      command(before, id, Snap("s3"))

      recover(fixed, id).snapshot shouldBe Some(3L -> "s3")
      command(fixed, id, DeleteSnapshotAt(3))
      recover(fixed, id).snapshot shouldBe Some(2L -> "s2")
      command(fixed, id, DeleteSnapshotsTo(2))
      val recovered = recover(fixed, id)
      recovered.snapshot shouldBe None
      events(recovered) shouldBe Vector(1L -> "e1", 2L -> "e2", 3L -> "e3")
      storedRows(snapshotTableName, id) shouldBe empty
    }

    "be replaced by a snapshot saved again at the same sequence number" in {
      val id = changedId("SnapSave")
      persistOk(before, id, "e1")
      command(before, id, Snap("old"))
      command(fixed, id, Snap("new"))
      recover(fixed, id).snapshot shouldBe Some(1L -> "new")
      storedRows(snapshotTableName, id).map(r => (r.pkey, r.skey)) shouldBe Vector(snapshotKey(fixed, id, 1))
    }

    "not remove the snapshot of another id that overwrote the shared key" in {
      val (a, b) = collidingPair(s"${uniquePrefix("SnapVictim")}-X-", id => snapshotKey(before, id, 1))
      Seq(a, b).foreach { id =>
        persistOk(before, id, s"$id-e1")
        command(before, id, Snap(s"snapshot-of-$id"))
      }
      recover(fixed, a).snapshot shouldBe None
      command(fixed, a, Snap(s"new-snapshot-of-$a"))
      command(fixed, a, DeleteSnapshotsTo(1))
      recover(fixed, b).snapshot shouldBe Some(1L -> s"snapshot-of-$b")
    }
  }

  "rows written after the fix" should {

    "be replayed by a release before it, whose deletes do not reach them until the upgrade" in {
      val id = changedId("Rollback")
      persistOk(fixed, id, "e1", "e2", "e3")
      val rolledBack = recover(before, id)
      events(rolledBack) shouldBe Vector(1L -> "e1", 2L -> "e2", 3L -> "e3")
      rolledBack.lastSequenceNr shouldBe 3L
      persistOk(before, id, "e4")
      events(recover(before, id)).map(_._1) shouldBe Vector(1L, 2L, 3L, 4L)

      // The older release writes its soft delete under the old key, so the rows of this release stay undeleted.
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

  private def updateEventRejected(mode: Mode, persistenceId: String, sequenceNr: Long): Unit = {
    val sys   = system(mode)
    val probe = TestProbe()(sys)
    recover(mode, persistenceId) // starts the journal plugin actor
    val journal = Await.result(sys.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(timeout), timeout)
    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(persistenceId, sequenceNr, "must-not-be-written"), probe.ref)
    probe.expectMsgType[Status.Failure](timeout)
    ()
  }
}
