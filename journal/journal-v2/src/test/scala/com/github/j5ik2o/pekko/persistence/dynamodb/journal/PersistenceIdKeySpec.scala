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
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.DuplicateJournalEventsException
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.store.JournalRowStoreException
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.store.SnapshotStoreException
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
      defaults: Boolean = false,
      explicit130Names: Boolean = false,
      legacyKeys: Boolean = true,
      legacySnapshotResolvers: Boolean = false
  )
}

/** Issue #1014 (the second stage of #1009): the default key resolvers split a persistence id at the first separator as
  * plain text, so ids that shared a key in 1.3.0 get keys of their own. The rows that 1.3.0 wrote under the old keys
  * keep working next to the rows written after the change: replay, the highest sequence number, soft and hard deletes,
  * in-place updates, and snapshot loads, deletes, and saves.
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
      else if (mode.defaults) "" // the resolvers of reference.conf
      else if (mode.explicit130Names)
        // the class names that the configuration examples of 1.3.0 named
        Seq(
          "j5ik2o.dynamo-db-journal.partition-key-resolver-class-name" -> classOf[
            PartitionKeyResolver.PersistenceIdBased
          ],
          "j5ik2o.dynamo-db-journal.sort-key-resolver-class-name" -> classOf[SortKeyResolver.PersistenceIdWithSeqNr],
          "j5ik2o.dynamo-db-snapshot.partition-key-resolver-class-name" -> classOf[
            snapshot.PartitionKeyResolver.PersistenceIdBased
          ],
          "j5ik2o.dynamo-db-snapshot.sort-key-resolver-class-name" -> classOf[
            snapshot.SortKeyResolver.PersistenceIdWithSeqNr
          ]
        ).map { case (key, cls) => s"""\n$key = "${cls.getName}"""" }.mkString
      else
        // the resolvers of the new key format, which the configuration selects while the default keeps the 1.3.0 keys
        Seq(
          "j5ik2o.dynamo-db-journal.partition-key-resolver-class-name" -> classOf[
            PartitionKeyResolver.PlainTextPersistenceIdBased
          ],
          "j5ik2o.dynamo-db-journal.sort-key-resolver-class-name" -> classOf[
            SortKeyResolver.PlainTextPersistenceIdWithSeqNr
          ],
          "j5ik2o.dynamo-db-snapshot.partition-key-resolver-class-name" -> classOf[
            snapshot.PartitionKeyResolver.PlainTextPersistenceIdBased
          ],
          "j5ik2o.dynamo-db-snapshot.sort-key-resolver-class-name" -> classOf[
            snapshot.SortKeyResolver.PlainTextPersistenceIdWithSeqNr
          ]
        ).map { case (key, cls) => s"""\n$key = "${cls.getName}"""" }.mkString
    val legacyKeys =
      if (mode.legacyKeys) ""
      else """
             |j5ik2o.dynamo-db-journal.legacy-keys-enabled = false
             |j5ik2o.dynamo-db-snapshot.legacy-keys-enabled = false
             |""".stripMargin
    val legacySnapshotResolvers =
      if (!mode.legacySnapshotResolvers) ""
      else
        Seq(
          "partition-key-resolver-class-name" -> classOf[snapshot.PartitionKeyResolver.PersistenceIdBased],
          "sort-key-resolver-class-name"      -> classOf[snapshot.SortKeyResolver.PersistenceIdWithSeqNr]
        ).map { case (key, cls) => s"""\nj5ik2o.dynamo-db-snapshot.$key = "${cls.getName}"""" }.mkString
    val counter = s"""
         |j5ik2o.dynamo-db-journal.dynamo-db-client.v2.execution-interceptor-class-names = ["${classOf[
                      RequestCounter
                    ].getName}"]
         |j5ik2o.dynamo-db-snapshot.dynamo-db-client.v2.execution-interceptor-class-names = ["${classOf[
                      RequestCounter
                    ].getName}"]
         |""".stripMargin
    ConfigFactory
      .parseString(separator + resolvers + legacyKeys + legacySnapshotResolvers + counter)
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
          if (mode.beforeFix) "before"
          else if (mode.defaults) "defaults"
          else if (mode.explicit130Names) "explicit"
          else "after",
          if (mode.legacyKeys) "legacy" else "nolegacy",
          if (mode.legacySnapshotResolvers) "legacysnap" else "snap"
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

  private def headSequenceNr(persistenceId: String): Option[Long] =
    Option(
      dynamoDBClient
        .getItem(
          new GetItemRequest()
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
  private val noLegacy      = Mode(legacyKeys = false)
  private val pipe          = Mode(separator = Some("|"))
  private val pipeBefore    = pipe.copy(beforeFix = true)
  private val dot           = Mode(separator = Some("."))

  /** An id whose key changes: it has two separators. */
  private def changedId(name: String): String = {
    val id = s"${uniquePrefix(name)}-part-1"
    journalKey(before, id, 1) should not be journalKey(fixed, id, 1)
    snapshotKey(before, id, 1) should not be snapshotKey(fixed, id, 1)
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

  /** Both ids persist, snapshot, and persist again, and each replays only its own events and snapshot. */
  private def bothIdsWork(mode: Mode, a: String, b: String): Unit = {
    persist(mode, a, "a1") shouldBe Right(1L)
    persist(mode, b, "b1") shouldBe Right(1L)
    tryCommand(mode, a, Snap(s"snapshot-of-$a")) shouldBe Right(())
    tryCommand(mode, b, Snap(s"snapshot-of-$b")) shouldBe Right(())
    persist(mode, a, "a2") shouldBe Right(2L)

    val recoveredA = recover(mode, a)
    recoveredA.snapshot shouldBe Some(1L -> s"snapshot-of-$a")
    events(recoveredA) shouldBe Vector(2L -> "a2")
    recoveredA.lastSequenceNr shouldBe 2L
    val recoveredB = recover(mode, b)
    recoveredB.snapshot shouldBe Some(1L -> s"snapshot-of-$b")
    events(recoveredB) shouldBe empty
    recoveredB.lastSequenceNr shouldBe 1L
    storedRows(journalTableName, a).map(_.sequenceNr) shouldBe Vector(1L, 2L)
    storedRows(journalTableName, b).map(_.sequenceNr) shouldBe Vector(1L)
    storedRows(snapshotTableName, a).map(_.sequenceNr) shouldBe Vector(1L)
    storedRows(snapshotTableName, b).map(_.sequenceNr) shouldBe Vector(1L)
    if (mode.headEnabled) {
      headSequenceNr(a) shouldBe Some(2L)
      headSequenceNr(b) shouldBe Some(1L)
    }
  }

  /** The id persists, replays, and saves and loads a snapshot. */
  private def idWorks(mode: Mode, id: String): Unit = {
    persist(mode, id, "e1", "e2") shouldBe Right(2L)
    tryCommand(mode, id, Snap("s2")) shouldBe Right(())
    persist(mode, id, "e3") shouldBe Right(3L)
    val recovered = recover(mode, id)
    recovered.snapshot shouldBe Some(2L -> "s2")
    events(recovered) shouldBe Vector(3L -> "e3")
    recovered.lastSequenceNr shouldBe 3L
    storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(1L, 2L, 3L)
  }

  "ids that collided in 1.3.0" should {
    Seq(false, true).foreach { head =>
      val label = if (head) "with the JournalHead" else "without the JournalHead"

      s"both persist, replay, and snapshot when they differ after the second separator ($label)" in {
        val mode   = fixed.copy(headEnabled = head)
        val (a, b) = collidingPair(s"${uniquePrefix("Two")}-X-", id => journalKey(before, id, 1))
        bothIdsWork(mode, a, b)
      }

      s"both persist, replay, and snapshot with the separator '|' ($label)" in {
        val mode   = pipe.copy(headEnabled = head)
        val (a, b) = collidingPair(s"${uniquePrefix("Account")}|", id => journalKey(pipeBefore, id, 1))
        bothIdsWork(mode, a, b)
        journalKey(mode, a, 1)._1 should startWith(a.takeWhile(_ != '|') + "-")
      }

      s"persist, replay, and snapshot when the id ends with the separator ($label)" in {
        idWorks(fixed.copy(headEnabled = head), s"${uniquePrefix("Trailing")}-")
      }

      s"persist, replay, and snapshot with the separator '.' ($label)" in {
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

    "fail with DuplicateJournalEventsException when the JournalHead is enabled" in {
      val prefix = uniquePrefix("HeadOrder")
      val x      = s"$prefix-"
      val y      = idWithOldKey(journalKey(fixed, x, 1), n => s"$prefix--$n", journalKey(before, _, 1))
      persistOk(beforeHead, y, "y1")
      persist(fixedHead, x, "x1").left.toOption.get shouldBe a[DuplicateJournalEventsException]
      events(recover(fixedHead, y)) shouldBe Vector(1L -> "y1")
      headSequenceNr(x) shouldBe None
    }

    "fail to save a snapshot with a key conflict and leave the other snapshot" in {
      val prefix = uniquePrefix("SnapOrder")
      val x      = s"$prefix-"
      // A pair whose snapshot keys meet and whose journal keys do not, so that x can persist its event.
      val y = Iterator
        .from(0)
        .map(n => s"$prefix--$n")
        .find(id =>
          snapshotKey(before, id, 1) == snapshotKey(fixed, x, 1) && journalKey(before, id, 1) != journalKey(fixed, x, 1)
        )
        .get
      persistOk(before, y, "y1")
      command(before, y, Snap(s"snapshot-of-$y"))
      persistOk(fixed, x, "x1")
      tryCommand(fixed, x, Snap(s"snapshot-of-$x")) match {
        case Left(conflict: SnapshotStoreException.KeyConflict) =>
          conflict.persistenceId shouldBe x
          conflict.conflictingPersistenceId shouldBe Some(y)
        case other => fail(s"expected a key conflict, got $other")
      }
      recover(fixed, y).snapshot shouldBe Some(1L -> s"snapshot-of-$y")
      recover(fixed, x).snapshot shouldBe None
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
      // the events under their 1.3.0 keys are marked deleted there
      rows.filter(_.deleted.contains(true)).map(r => (r.pkey, r.skey)) shouldBe Seq(1L, 2L).map(
        journalKey(before, id, _)
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

    "be soft deleted under the 1.3.0 key when the new key holds another id's 1.3.0 row (review of #1022)" in {
      // With `|`, the new key of `C|D` is the 1.3.0 key of `CD|<n>`.
      val x = "C|D"
      val y = idWithOldKey(journalKey(pipe, x, 1), n => s"CD|$n", journalKey(pipeBefore, _, 1))
      persistOk(pipeBefore, x, "x1", "x2")
      persistOk(pipeBefore, y, "y1")
      command(pipe, x, DeleteTo(2))
      val recovered = recover(pipe, x)
      events(recovered) shouldBe empty
      recovered.lastSequenceNr shouldBe 2L
      storedRows(journalTableName, x).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        2L -> Some(true)
      )
      events(recover(pipe, y)) shouldBe Vector(1L -> "y1")
    }

    "keep working when the new partition key of the id would exceed 2,048 bytes (review of #1022)" in {
      val pipeHard = pipe.copy(softDelete = false)
      val id       = ("A" * 2008) + "|B" // 1.3.0 gave it a 42-byte partition key
      journalKey(pipeBefore, id, 1)._1.length shouldBe 42
      persistOk(pipeBefore, id, "e1", "e2")
      persistOk(pipe, id, "e3", "e4")
      updateEvent(pipe, id, 1, "e1-updated")
      events(recover(pipe, id)) shouldBe Vector(1L -> "e1-updated", 2L -> "e2", 3L -> "e3", 4L -> "e4")
      command(pipeHard, id, DeleteTo(1))
      command(pipe, id, DeleteTo(3))
      val recovered = recover(pipe, id)
      events(recovered) shouldBe Vector(4L -> "e4")
      recovered.lastSequenceNr shouldBe 4L
      journalKey(pipe, id, 1)._1 should startWith("#h1#")
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

  "snapshots written by 1.3.0" should {

    "load, and be removed by a delete of one snapshot or by criteria" in {
      val id = changedId("SnapLoad")
      persistOk(before, id, "e1")
      command(before, id, Snap("s1"))
      persistOk(before, id, "e2")
      command(before, id, Snap("s2"))
      persistOk(before, id, "e3")
      command(before, id, Snap("s3"))
      persistOk(fixed, id, "e4")
      command(fixed, id, Snap("s4"))

      recover(fixed, id).snapshot shouldBe Some(4L -> "s4")
      command(fixed, id, DeleteSnapshotAt(4))
      recover(fixed, id).snapshot shouldBe Some(3L -> "s3")
      command(fixed, id, DeleteSnapshotAt(3))
      recover(fixed, id).snapshot shouldBe Some(2L -> "s2")
      command(fixed, id, DeleteSnapshotsTo(2))
      val recovered = recover(fixed, id)
      recovered.snapshot shouldBe None
      events(recovered) shouldBe Vector(1L -> "e1", 2L -> "e2", 3L -> "e3", 4L -> "e4")
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

    "lose to the snapshot under the current key when both keys hold one at the same sequence number" in {
      val id = changedId("SnapTwice")
      persistOk(before, id, "e1")
      command(before, id, Snap("old"))
      command(noLegacy, id, Snap("new")) // leaves the old row, as a save stopped before its delete does
      storedRows(snapshotTableName, id).map(r => (r.pkey, r.skey)).toSet shouldBe
      Set(snapshotKey(before, id, 1), snapshotKey(fixed, id, 1))
      recover(fixed, id).snapshot shouldBe Some(1L -> "new")
      command(fixed, id, DeleteSnapshotsTo(1))
      storedRows(snapshotTableName, id) shouldBe empty
      recover(fixed, id).snapshot shouldBe None
    }

    "not remove the snapshot of another id that overwrote the shared key before 1.3.0" in {
      val (a, b) = collidingPair(s"${uniquePrefix("SnapVictim")}-X-", id => snapshotKey(before, id, 1))
      persistOk(before, a, s"$a-e1")
      command(before, a, Snap(s"snapshot-of-$a"))
      persistOk(fixed, b, s"$b-e1")
      command(fixed, b, Snap(s"snapshot-of-$b"))
      moveRow(snapshotTableName, snapshotKey(fixed, b, 1), snapshotKey(before, b, 1)) // b overwrote a before 1.3.0
      recover(fixed, a).snapshot shouldBe None
      command(fixed, a, Snap(s"new-snapshot-of-$a"))
      command(fixed, a, DeleteSnapshotsTo(1))
      recover(fixed, b).snapshot shouldBe Some(1L -> s"snapshot-of-$b")
    }
  }

  "the legacy snapshot resolvers" should {

    "not fail the recovery of an id that they cannot build a key for, when it has no snapshot (review of #1022)" in {
      val mode = fixed.copy(legacySnapshotResolvers = true)
      val id   = s"${uniquePrefix("Order")}-" // the legacy sort key resolver throws for it, as 1.3.0 did
      persistOk(mode, id, "e1")
      val recovered = recover(mode, id)
      recovered.snapshot shouldBe None
      events(recovered) shouldBe Vector(1L -> "e1")
    }
  }

  "the default configuration, and the 1.3.0 class names set explicitly," should {

    "keep the keys of 1.3.0, and fail where 1.3.0 failed, with the same exception" in {
      def outcome(key: => (String, String)): Either[Class[_], (String, String)] =
        scala.util.Try(key).toEither.left.map(_.getClass)
      val alphabet = Seq("a", "b", "-", "|", ".")
      val ids = (1 to 3).flatMap(n =>
        Seq.fill(n)(alphabet).foldLeft(Seq(""))((acc, cs) => acc.flatMap(a => cs.map(a + _)))
      ) ++ Seq("Order-", "shopping-cart-1", "UserAccount|33", "Order.1", s"Order-item-${"x" * 1010}")
      for {
        separator <- Seq("-", "|", ".")
        mode <- Seq(
          Mode(defaults = true, separator = Some(separator)),
          Mode(explicit130Names = true, separator = Some(separator))
        )
      } {
        ids.foreach { id =>
          withClue(s"journal id=[$id] separator=[$separator]: ") {
            outcome(journalKey(mode, id, 7)) shouldBe outcome(
              (
                BeforeKeyFix.partitionKey(id, separator, context(mode)._1.pluginConfig.shardCount),
                BeforeKeyFix.sortKey(id, 7, separator)
              )
            )
          }
          withClue(s"snapshot id=[$id] separator=[$separator]: ") {
            outcome(snapshotKey(mode, id, 7)) shouldBe outcome(
              (
                BeforeKeyFix.partitionKey(id, separator, context(mode)._2.pluginConfig.shardCount),
                BeforeKeyFix.sortKey(id, 7, separator)
              )
            )
          }
        }
      }
    }

    "fail to persist an id that 1.3.0 could not write, as 1.3.0 did" in {
      Seq(Mode(defaults = true), Mode(explicit130Names = true)).foreach { mode =>
        persist(mode, s"${uniquePrefix("Order")}-", "e1").left.toOption.get shouldBe an[ArrayIndexOutOfBoundsException]
      }
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

      // After the upgrade, the same delete marks the copies under the new keys deleted too.
      command(fixed, id, DeleteTo(2))
      events(recover(fixed, id)).map(_._1) shouldBe Vector(3L, 4L)
      storedRows(journalTableName, id).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        1L -> Some(true),
        2L -> Some(true),
        2L -> Some(true),
        3L -> Some(false),
        4L -> Some(false)
      )
    }
  }

  "rows in the format of issue #831 (the sort key is the sequence number)" should {

    "behave as they did in 1.3.0: replayed and loaded, and left by deletes until they are migrated" in {

      /** Writes three events and two snapshots, moves event 1 and snapshot 2 to the #831 keys, and deletes. */
      def scenario(
          mode: Mode,
          id: String
      ): (Vector[(Long, String)], Option[(Long, Any)], Seq[(Long, Boolean)], Seq[(Long, Boolean)]) = {
        persistOk(mode, id, "e1", "e2")
        command(mode, id, Snap("s2"))
        persistOk(mode, id, "e3")
        command(mode, id, Snap("s3"))
        moveRow(journalTableName, journalKey(mode, id, 1), (journalKey(mode, id, 1)._1, "1"))
        moveRow(snapshotTableName, snapshotKey(mode, id, 2), (snapshotKey(mode, id, 2)._1, "2"))
        recover(mode, id).snapshot shouldBe Some(3L -> "s3")
        command(mode, id, DeleteSnapshotAt(3))
        recover(mode, id).snapshot shouldBe Some(2L -> "s2")
        command(mode.copy(softDelete = false), id, DeleteTo(2))
        command(mode, id, DeleteSnapshotsTo(3))
        val recovered = recover(mode, id)
        (
          events(recovered),
          recovered.snapshot,
          storedRows(journalTableName, id).map(r => r.sequenceNr -> (r.skey == r.sequenceNr.toString)),
          storedRows(snapshotTableName, id).map(r => r.sequenceNr -> (r.skey == r.sequenceNr.toString))
        )
      }
      // The outcome of the same scenario with 1.3.0 (main ecec433f): the #831 rows stay until they are migrated.
      scenario(fixed, uniquePrefix("Format831")) shouldBe (
        (
          Vector(3L -> "e3"),
          Some(2L   -> "s2"),
          Seq(1L    -> true, 3L -> false),
          Seq(2L    -> true)
        )
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
      command(fixed, id, Snap("s4"))
      val sortKeys = Seq(3L, 4L).map(journalKey(fixed, id, _)._2)
      sortKeys.foreach { key =>
        key should startWith("#h1#")
        key.length shouldBe 4 + 64 + 1 + 19 + 1
      }
      sortKeys.map(_.dropRight(21)).distinct should have size 1
      val recovered = recover(fixed, id)
      recovered.snapshot shouldBe Some(4L -> "s4")
      recovered.lastSequenceNr shouldBe 4L
      command(fixedHard, id, DeleteTo(3))
      persistOk(fixed, id, "e5")
      events(recover(fixed, id)) shouldBe Vector(5L -> "e5")
      storedRows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(4L, 5L)
    }
  }

  "an id whose text is the hashed form of another id's sort key" should {

    "not share the snapshot key of that id (review of #1022)" in {
      // The snapshot store has 64 shards, and both ids are in shard 36 there.
      val long    = s"Order-${"x" * 1005}60"
      val literal = "Order-#h1#7358fdb239e60f150ab56a7449063d4f5ab8e568fd330a81c290784ee7d70946"
      snapshotKey(fixed, long, 1)._1 shouldBe snapshotKey(fixed, literal, 1)._1
      snapshotKey(fixed, long, 1) should not be snapshotKey(fixed, literal, 1)
      Seq(long, literal).foreach { id =>
        persistOk(fixed, id, "e1")
        command(fixed, id, Snap(s"snapshot-of-${id.length}"))
      }
      recover(fixed, long).snapshot shouldBe Some(1L -> s"snapshot-of-${long.length}")
      recover(fixed, literal).snapshot shouldBe Some(1L -> s"snapshot-of-${literal.length}")
    }
  }

  // --- the cost --------------------------------------------------------------------------------------------------

  "the requests" should {

    final case class Cost(
        persist: Map[String, Long],
        update: Map[String, Long],
        softDelete: Map[String, Long],
        hardDelete: Map[String, Long],
        snapshotSave: Map[String, Long],
        snapshotLoad: Map[String, Long],
        snapshotDelete: Map[String, Long]
    ) {
      def extraOver(base: Cost): Map[String, Map[String, Long]] = {
        def diff(a: Map[String, Long], b: Map[String, Long]) =
          (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0L) - b.getOrElse(k, 0L))).filter(_._2 != 0L).toMap
        Map(
          "persist"        -> diff(persist, base.persist),
          "update"         -> diff(update, base.update),
          "softDelete"     -> diff(softDelete, base.softDelete),
          "hardDelete"     -> diff(hardDelete, base.hardDelete),
          "snapshotSave"   -> diff(snapshotSave, base.snapshotSave),
          "snapshotLoad"   -> diff(snapshotLoad, base.snapshotLoad),
          "snapshotDelete" -> diff(snapshotDelete, base.snapshotDelete)
        )
      }
    }

    /** The requests of each operation on `id`: three events, one update, a soft delete of one event, a hard delete of
      * one more, and a snapshot saved, loaded, and deleted. `writer` writes the events and the snapshot first, so the
      * rows are under its keys; `mode` runs the operations.
      */
    def cost(writer: Mode, mode: Mode, id: String): Cost = {
      val (_, persistCost) = RequestCounter.during(persistOk(writer, id, "e1", "e2", "e3"))
      command(writer, id, Snap("s3"))
      val (_, updateCost)     = RequestCounter.during(updateEvent(mode, id, 3, "e3-updated"))
      val (_, softDeleteCost) = RequestCounter.during(command(mode, id, DeleteTo(1)))
      val (_, hardDeleteCost) = RequestCounter.during(command(mode.copy(softDelete = false), id, DeleteTo(2)))
      val (_, loadCost)       = RequestCounter.during(recover(mode, id).snapshot shouldBe Some(3L -> "s3"))
      val (_, saveCost)       = RequestCounter.during(command(mode, id, Snap("s3-again")))
      val (_, deleteCost)     = RequestCounter.during(command(mode, id, DeleteSnapshotsTo(3)))
      events(recover(mode, id)) shouldBe Vector(3L -> "e3-updated")
      storedRows(snapshotTableName, id) shouldBe empty
      Cost(persistCost, updateCost, softDeleteCost, hardDeleteCost, saveCost, loadCost, deleteCost)
    }

    "not grow for an id whose key does not change" in {
      val unchanged = cost(fixed, fixed, s"${uniquePrefix("Same")}-1")
      val base      = cost(before, before, s"${uniquePrefix("Same")}-1")
      info(s"unchanged id: ${unchanged.extraOver(base)}")
      unchanged.extraOver(base).values.foreach(_ shouldBe empty)
    }

    "grow only by the conditional requests on the old key for an id whose key changes" in {
      val base     = cost(before, before, changedId("CostBase"))
      val newRows  = cost(fixed, fixed, changedId("CostNew"))
      val oldRows  = cost(before, fixed, changedId("CostOld"))
      val noLegacy = cost(this.noLegacy, this.noLegacy, changedId("CostOff"))
      info(s"changed id, rows under the new key: ${newRows.extraOver(base)}")
      info(s"changed id, rows under the 1.3.0 key: ${oldRows.extraOver(base)}")
      info(s"changed id, legacy-keys-enabled = false: ${noLegacy.extraOver(base)}")
      newRows.extraOver(base) shouldBe Map(
        "persist" -> Map.empty,
        "update"  -> Map.empty,
        // marked deleted in place under both keys: two UpdateItem instead of the PutItem of 1.3.0
        "softDelete"     -> Map("UpdateItem" -> 2L, "PutItem" -> -1L),
        "hardDelete"     -> Map("DeleteItem" -> 1L),
        "snapshotSave"   -> Map("DeleteItem" -> 1L),
        "snapshotLoad"   -> Map.empty,
        "snapshotDelete" -> Map.empty
      )
      oldRows.extraOver(base) shouldBe Map(
        "persist"        -> Map.empty,
        "update"         -> Map("UpdateItem" -> 1L, "GetItem" -> 2L),
        "softDelete"     -> Map("UpdateItem" -> 2L, "PutItem" -> -1L),
        "hardDelete"     -> Map("DeleteItem" -> 1L),
        "snapshotSave"   -> Map("DeleteItem" -> 1L),
        "snapshotLoad"   -> Map.empty,
        "snapshotDelete" -> Map.empty
      )
      noLegacy.extraOver(base).values.foreach(_ shouldBe empty)
    }

    "not grow with the default configuration or the 1.3.0 class names, which keep the 1.3.0 keys" in {
      val base = cost(before, before, changedId("DefaultsBase"))
      Seq(Mode(defaults = true), Mode(explicit130Names = true)).foreach { mode =>
        val measured = cost(mode, mode, changedId("Defaults"))
        info(
          s"changed id, ${if (mode.defaults) "default configuration" else "1.3.0 class names"}: ${measured.extraOver(base)}"
        )
        measured.extraOver(base).values.foreach(_ shouldBe empty)
      }
    }

    "grow for an in-place update with the JournalHead only when the row is under the 1.3.0 key" in {
      def updateCost(writer: Mode, mode: Mode, id: String): Map[String, Long] = {
        persistOk(writer, id, "e1", "e2")
        val (_, cost) = RequestCounter.during(updateEvent(mode, id, 2, "e2-updated"))
        events(recover(mode, id)) shouldBe Vector(1L -> "e1", 2L -> "e2-updated")
        cost
      }
      def diff(a: Map[String, Long], b: Map[String, Long]) =
        (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0L) - b.getOrElse(k, 0L))).filter(_._2 != 0L).toMap
      val base    = updateCost(beforeHead, beforeHead, changedId("HeadCostBase"))
      val newRows = updateCost(fixedHead, fixedHead, changedId("HeadCostNew"))
      val oldRows = updateCost(beforeHead, fixedHead, changedId("HeadCostOld"))
      info(s"JournalHead update, rows under the new key: ${diff(newRows, base)}")
      info(s"JournalHead update, rows under the 1.3.0 key: ${diff(oldRows, base)}")
      diff(newRows, base) shouldBe empty
      diff(oldRows, base) shouldBe Map("TransactWriteItems" -> 1L, "GetItem" -> 3L)
    }
  }
}
