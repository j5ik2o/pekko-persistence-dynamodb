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
import org.apache.pekko.testkit.TestProbe
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, GetItemRequest, PutItemRequest, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.migration.JournalKeyMigration
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.store.JournalRowStoreException
import com.github.j5ik2o.pekko.persistence.dynamodb.migration.{ KeyMigration, MigrationReport, MigrationSettings }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.migration.SnapshotKeyMigration
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DockerAssignedPort, DynamoDBContainerHelper }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue => V2AttributeValue,
  BatchWriteItemRequest,
  PutRequest,
  WriteRequest
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => V2DynamoDbClient }

import java.lang.reflect.{ InvocationHandler, InvocationTargetException, Method, Proxy }
import java.net.URI
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters._

object JournalKeyMigrationSpec extends DockerAssignedPort {

  /** The key resolvers of a test system: those of 1.3.0 as it shipped (`BeforeKeyFix`, a node that still runs 1.3.0),
    * the PlainText resolvers of the new key format, or the defaults of reference.conf.
    */
  sealed trait Form
  case object V130 extends Form
  case object PlainText extends Form
  case object Defaults extends Form

  final case class Mode(
      form: Form = PlainText,
      headEnabled: Boolean = false,
      softDelete: Boolean = true,
      separator: Option[String] = None,
      legacyKeys: Boolean = true,
      hooked: Boolean = false
  )
}

/** The key migration tools on DynamoDB Local with the journal and snapshot plugins (issue #1014): the rows that 1.3.0
  * wrote move to the keys of the PlainText resolvers, the application keeps working before, during, and after the move,
  * and `--reverse` moves them back for 1.3.0.
  */
final class JournalKeyMigrationSpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {
  import JournalKeyMigrationSpec._
  import PersistenceIdKeySpec.{
    Completed,
    DeleteSnapshotAt,
    DeleteSnapshotsTo,
    DeleteTo,
    Failed,
    PersistAll,
    Persisted,
    Recorder,
    Recovered,
    Snap
  }

  override lazy val dynamoDBPort: Int      = JournalKeyMigrationSpec.dynamoDBPort
  override lazy val controller             = JournalKeyMigrationSpec.controller
  override val legacyJournalTable: Boolean = false

  private val timeout   = 60.seconds
  private val idCounter = new AtomicInteger(0)

  private def unique(name: String): String = s"$name${idCounter.incrementAndGet()}"

  private var systems: Map[Mode, ActorSystem] = Map.empty

  private lazy val v2Client: V2DynamoDbClient = V2DynamoDbClient
    .builder()
    .endpointOverride(URI.create(s"http://$dockerHost:$dynamoDBPort"))
    .region(Region.AP_NORTHEAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
    .build()

  override def afterStartContainers(): Unit = {
    super.afterStartContainers()
    createTable()
  }

  override def beforeStopContainers(): Unit = {
    systems.values.foreach(s => Await.result(s.terminate(), timeout))
    systems = Map.empty
    deleteTable()
    super.beforeStopContainers()
  }

  // --- the systems -------------------------------------------------------------------------------------------------

  private def config(mode: Mode): Config = {
    val separator = mode.separator.fold("")(s => s"""
         |j5ik2o.dynamo-db-journal.persistence-id-separator = "$s"
         |j5ik2o.dynamo-db-snapshot.persistence-id-separator = "$s"
         |""".stripMargin)
    val names: Seq[(String, Class[_])] = mode.form match {
      case V130 =>
        Seq(
          "j5ik2o.dynamo-db-journal.partition-key-resolver-class-name" -> classOf[
            BeforeKeyFix.JournalPartitionKeyResolver
          ],
          "j5ik2o.dynamo-db-journal.sort-key-resolver-class-name" -> classOf[BeforeKeyFix.JournalSortKeyResolver],
          "j5ik2o.dynamo-db-snapshot.partition-key-resolver-class-name" -> classOf[
            BeforeKeyFix.SnapshotPartitionKeyResolverBeforeFix
          ],
          "j5ik2o.dynamo-db-snapshot.sort-key-resolver-class-name" -> classOf[
            BeforeKeyFix.SnapshotSortKeyResolverBeforeFix
          ]
        )
      case PlainText =>
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
        )
      case Defaults => Seq.empty
    }
    val resolvers = names.map { case (key, cls) => s"""\n$key = "${cls.getName}"""" }.mkString
    val legacyKeys =
      if (mode.legacyKeys) ""
      else """
             |j5ik2o.dynamo-db-journal.legacy-keys-enabled = false
             |j5ik2o.dynamo-db-snapshot.legacy-keys-enabled = false
             |""".stripMargin
    val hook =
      if (!mode.hooked) ""
      else
        Seq("journal", "snapshot")
          .map(p =>
            s"""\nj5ik2o.dynamo-db-$p.dynamo-db-client.v2.execution-interceptor-class-names = ["${classOf[
                RequestHook
              ].getName}"]"""
          )
          .mkString
    ConfigFactory
      .parseString(separator + resolvers + legacyKeys + hook)
      .withFallback(
        ConfigHelper.config(
          Some("journal-reference"),
          legacyConfigFormat = false,
          legacyJournalMode = false,
          dynamoDBHost = "localhost",
          dynamoDBPort = dynamoDBPort,
          clientVersion = ClientVersion.V2.toString,
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
          "migration",
          mode.form.toString.toLowerCase,
          if (mode.headEnabled) "head" else "nohead",
          if (mode.softDelete) "soft" else "hard",
          mode.separator.fold("dash")(s => s"sep${s.map(_.toInt).mkString("x")}"),
          if (mode.legacyKeys) "legacy" else "nolegacy",
          if (mode.hooked) "hooked" else "plain"
        ).mkString("-")
        val created = ActorSystem(name, config(mode))
        systems += mode -> created
        created
      }
    )
  }

  private def journalConfig(mode: Mode): JournalPluginConfig =
    JournalPluginConfig.fromConfig(system(mode).settings.config.getConfig("j5ik2o.dynamo-db-journal"))

  private def snapshotConfig(mode: Mode): SnapshotPluginConfig =
    SnapshotPluginConfig.fromConfig(system(mode).settings.config.getConfig("j5ik2o.dynamo-db-snapshot"))

  private var journalContexts: Map[Mode, JournalPluginContext]   = Map.empty
  private var snapshotContexts: Map[Mode, SnapshotPluginContext] = Map.empty

  private def journalKey(mode: Mode, id: String, seq: Long): (String, String) = {
    val ctx = synchronized {
      journalContexts.getOrElse(
        mode, {
          val created = JournalPluginContext(system(mode), journalConfig(mode))
          journalContexts += mode -> created
          created
        }
      )
    }
    val pid = PersistenceId(id)
    (
      ctx.partitionKeyResolver.resolve(pid, SequenceNumber(seq)).asString,
      ctx.sortKeyResolver.resolve(pid, SequenceNumber(seq)).asString
    )
  }

  private def snapshotKey(mode: Mode, id: String, seq: Long): (String, String) = {
    val ctx = synchronized {
      snapshotContexts.getOrElse(
        mode, {
          val created = SnapshotPluginContext(system(mode), snapshotConfig(mode))
          snapshotContexts += mode -> created
          created
        }
      )
    }
    val pid = PersistenceId(id)
    (
      ctx.partitionKeyResolver.resolve(pid, SequenceNumber(seq)).asString,
      ctx.sortKeyResolver.resolve(pid, SequenceNumber(seq)).asString
    )
  }

  // --- the migration -----------------------------------------------------------------------------------------------

  /** Runs the journal and the snapshot tool with the configuration of `mode`, for the given ids only. */
  private def migrate(
      mode: Mode,
      ids: Set[String],
      execute: Boolean,
      reverse: Boolean = false,
      client: V2DynamoDbClient = v2Client,
      settings: MigrationSettings => MigrationSettings = identity,
      snapshots: Boolean = true
  ): (MigrationReport, MigrationReport) = {
    val base = settings(MigrationSettings(execute = execute, persistenceIds = Some(ids)))
    val j = KeyMigration.run(
      client,
      JournalKeyMigration.table(journalConfig(mode)),
      JournalKeyMigration.plan(journalConfig(mode), reverse),
      base
    )
    val s = KeyMigration.run(
      if (snapshots) client else v2Client,
      SnapshotKeyMigration.table(snapshotConfig(mode)),
      SnapshotKeyMigration.plan(snapshotConfig(mode), reverse),
      base
    )
    (KeyMigration.render(j, "journal") ++ KeyMigration.render(s, "snapshot")).foreach(line => info(line))
    (j, s)
  }

  /** A client that runs `before` ahead of each TransactWriteItems, and fails every request once `down` is set. */
  private def hooked(
      before: Int => Unit = _ => (),
      down: AtomicBoolean = new AtomicBoolean(false)
  ): V2DynamoDbClient = {
    val count = new AtomicInteger()
    Proxy
      .newProxyInstance(
        classOf[V2DynamoDbClient].getClassLoader,
        Array(classOf[V2DynamoDbClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
            if (down.get()) throw SdkClientException.create("the connection is down")
            if (method.getName == "transactWriteItems") before(count.incrementAndGet())
            try method.invoke(v2Client, Option(args).getOrElse(Array.empty[AnyRef]): _*)
            catch { case e: InvocationTargetException => throw e.getCause }
          }
        }
      )
      .asInstanceOf[V2DynamoDbClient]
  }

  // --- the actor ---------------------------------------------------------------------------------------------------

  private def withActor[T](mode: Mode, id: String)(action: (ActorRef, TestProbe, Recovered) => T): T = {
    val sys       = system(mode)
    val probe     = TestProbe()(sys)
    val actor     = sys.actorOf(Props(new Recorder(id, probe.ref)), s"recorder-${id.hashCode.abs}-${System.nanoTime()}")
    val recovered = probe.expectMsgType[Recovered](timeout)
    try action(actor, probe, recovered)
    finally sys.stop(actor)
  }

  private def recover(mode: Mode, id: String): Recovered = withActor(mode, id)((_, _, r) => r)

  private def persist(mode: Mode, id: String, payloads: String*): Either[Throwable, Long] =
    withActor(mode, id) { (actor, probe, _) =>
      actor ! PersistAll(payloads.toVector)
      probe.expectMsgAnyClassOf(timeout, classOf[Persisted], classOf[Failed]) match {
        case Persisted(last) => Right(last)
        case Failed(cause)   => Left(cause)
      }
    }

  private def persistOk(mode: Mode, id: String, payloads: String*): Long =
    persist(mode, id, payloads: _*).fold(cause => fail(s"persist of $id failed: $cause", cause), identity)

  private def command(mode: Mode, id: String, message: Any): Unit =
    withActor(mode, id) { (actor, probe, _) =>
      actor ! message
      probe.expectMsgAnyClassOf(timeout, classOf[Completed], classOf[Failed]) match {
        case Failed(cause) => fail(s"$message for $id failed: $cause", cause)
        case _             => ()
      }
    }

  private def updateEvent(mode: Mode, id: String, seq: Long, payload: String): Unit = {
    val sys   = system(mode)
    val probe = TestProbe()(sys)
    recover(mode, id)
    val journal = Await.result(sys.actorSelection("/system/j5ik2o.dynamo-db-journal").resolveOne(timeout), timeout)
    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(id, seq, payload), probe.ref)
    probe.expectMsgAnyClassOf(timeout, classOf[Done], classOf[Status.Failure]) match {
      case Status.Failure(cause) => fail(s"the update of $id@$seq failed: $cause", cause)
      case _                     => ()
    }
  }

  private def events(mode: Mode, id: String): Vector[(Long, String)] = recover(mode, id).events

  // --- the tables --------------------------------------------------------------------------------------------------

  final case class Row(pkey: String, skey: String, sequenceNr: Long, deleted: Option[Boolean])

  private def rows(table: String, id: String): Vector[Row] =
    dynamoDBClient
      .scan(
        new ScanRequest()
          .withTableName(table)
          .withConsistentRead(true)
          .withFilterExpression("#pid = :pid")
          .withExpressionAttributeNames(Map("#pid" -> "persistence-id").asJava)
          .withExpressionAttributeValues(Map(":pid" -> new AttributeValue().withS(id)).asJava)
      )
      .getItems
      .asScala
      .toVector
      .map(_.asScala)
      .map(item =>
        Row(
          item("pkey").getS,
          item("skey").getS,
          item("sequence-nr").getN.toLong,
          item.get("deleted").map(_.getBOOL.booleanValue())
        )
      )
      .sortBy(r => (r.sequenceNr, r.skey))

  /** Copies the row under `from` to `to`, with `change` applied, and deletes it under `from` unless `keep`. */
  private def copyRow(
      table: String,
      from: (String, String),
      to: (String, String),
      keep: Boolean = false,
      change: java.util.Map[String, AttributeValue] => Unit = _ => ()
  ): Unit = {
    def key(k: (String, String)) =
      Map("pkey" -> new AttributeValue().withS(k._1), "skey" -> new AttributeValue().withS(k._2)).asJava
    val item = dynamoDBClient.getItem(new GetItemRequest().withTableName(table).withKey(key(from))).getItem
    item should not be null
    val copy = new java.util.HashMap[String, AttributeValue](item)
    copy.putAll(key(to))
    change(copy)
    dynamoDBClient.putItem(new PutItemRequest().withTableName(table).withItem(copy))
    if (!keep) dynamoDBClient.deleteItem(table, key(from))
    ()
  }

  private def headItem(id: String): Option[Map[String, AttributeValue]] =
    Option(
      dynamoDBClient
        .getItem(
          new GetItemRequest()
            .withTableName(journalHeadTableName)
            .withKey(Map("persistence-id" -> new AttributeValue().withS(id)).asJava)
            .withConsistentRead(true)
        )
        .getItem
    ).map(_.asScala.toMap)

  // --- the modes ---------------------------------------------------------------------------------------------------

  private val v130      = Mode(form = V130)
  private val v130Head  = Mode(form = V130, headEnabled = true)
  private val plain     = Mode()
  private val plainHard = Mode(softDelete = false)
  private val plainHead = Mode(headEnabled = true)
  private val noLegacy  = Mode(legacyKeys = false)

  /** An id whose key changes: it has two separators. */
  private def changedId(name: String): String = {
    val id = s"${unique(name)}-part-1"
    journalKey(v130, id, 1) should not be journalKey(plain, id, 1)
    snapshotKey(v130, id, 1) should not be snapshotKey(plain, id, 1)
    id
  }

  private def underNewKeys(mode: Mode, id: String): Unit = {
    rows(journalTableName, id).foreach(r => (r.pkey, r.skey) shouldBe journalKey(mode, id, r.sequenceNr))
    rows(snapshotTableName, id).foreach(r => (r.pkey, r.skey) shouldBe snapshotKey(mode, id, r.sequenceNr))
  }

  /** Writes three events and a snapshot at 3 with 1.3.0. */
  private def writtenBy130(id: String, mode: Mode = v130): Unit = {
    persistOk(mode, id, "e1", "e2", "e3")
    command(mode, id, Snap("s3"))
  }

  /** The id works with the new key format: replay, persist, update, soft and hard delete, snapshots. */
  private def worksWith(mode: Mode, id: String): Unit = {
    val recovered = recover(mode, id)
    recovered.snapshot shouldBe Some(3L -> "s3")
    recovered.lastSequenceNr shouldBe 3L
    persistOk(mode, id, "e4") shouldBe 4L
    updateEvent(mode, id, 4, "e4-updated")
    command(mode, id, DeleteTo(1))
    command(mode.copy(softDelete = false), id, DeleteTo(2))
    command(mode, id, Snap("s4"))
    command(mode, id, DeleteSnapshotsTo(3))
    val after = recover(mode, id)
    after.snapshot shouldBe Some(4L -> "s4")
    after.lastSequenceNr shouldBe 4L
    events(mode, id) shouldBe Vector.empty // the snapshot at 4 covers every event
    // a hard delete leaves the row that the soft delete marked, as without the migration
    rows(journalTableName, id).map(_.sequenceNr) shouldBe (if (mode.softDelete) Vector(1L, 3L, 4L) else Vector(3L, 4L))
    rows(snapshotTableName, id).map(_.sequenceNr) shouldBe Vector(4L)
    underNewKeys(mode, id)
  }

  // --- the tests ---------------------------------------------------------------------------------------------------

  "the dry-run" should {

    "list the rows to move per id, the ids that shared keys in 1.3.0, and write nothing" in {
      val changed   = Seq.fill(2)(changedId("Dry"))
      val unchanged = s"${unique("Same")}-1"
      changed.foreach(writtenBy130(_))
      writtenBy130(unchanged)
      val prefix = unique("Shared")
      val pair = Iterator
        .from(0)
        .map(n => s"$prefix-X-$n")
        .take(5000)
        .toVector
        .groupBy(id => journalKey(v130, id, 1))
        .collectFirst { case (_, ids) if ids.size >= 2 => ids.take(2) }
        .get
      persistOk(v130, pair(0), "a1", "a2")
      // Up to 1.2.0, the write of pair(1) replaced event 1 of pair(0) under the key they shared.
      copyRow(
        journalTableName,
        journalKey(v130, pair(0), 1),
        journalKey(v130, pair(1), 1),
        keep = true,
        change = _.put("persistence-id", new AttributeValue().withS(pair(1)))
      )
      val before = (changed :+ unchanged).map(id => rows(journalTableName, id))
      val ids    = (changed :+ unchanged).toSet ++ pair
      val (j, s) = migrate(plain, ids, execute = false)
      j.movesByPersistenceId.toMap shouldBe changed.map(_ -> 3).toMap ++ Map(pair(0) -> 1, pair(1) -> 1)
      s.movesByPersistenceId.toMap shouldBe changed.map(_ -> 1).toMap
      val group = j.sharedKeyGroups.find(_.ids.exists(_.persistenceId == pair(0))).get
      group.ids.map(i => (i.persistenceId, i.missingSequenceNrs, i.firstMissingSequenceNrs)) shouldBe Seq(
        (pair(0), 1L, Seq(1L)),
        (pair(1), 0L, Seq.empty)
      ).sortBy(_._1)
      (changed :+ unchanged).map(id => rows(journalTableName, id)) shouldBe before
    }
  }

  "the migration" should {

    "move the rows of 1.3.0, after which every operation works, also with legacy-keys-enabled = false" in {
      val ids = Seq.fill(3)(changedId("Move"))
      ids.foreach(writtenBy130(_))
      val (dryJ, dryS) = migrate(plain, ids.toSet, execute = false)
      dryJ.candidates should have size 9
      dryS.candidates should have size 3
      val (j, s) = migrate(plain, ids.toSet, execute = true)
      j.moved shouldBe 9
      s.moved shouldBe 3
      j.remaining shouldBe empty
      ids.foreach(underNewKeys(plain, _))
      val (againJ, againS) = migrate(plain, ids.toSet, execute = false)
      againJ.candidates shouldBe empty
      againS.candidates shouldBe empty
      worksWith(plain, ids(0))
      worksWith(plainHard, ids(1))
      worksWith(noLegacy, ids(2))
    }

    "let 'Order-' write once the 1.3.0 row of 'Order--<n>' has moved from its key" in {
      val prefix = unique("Order")
      val x      = s"$prefix-"
      val y = Iterator
        .from(0)
        .map(n => s"$prefix--$n")
        .find(id => journalKey(v130, id, 1) == journalKey(plain, x, 1))
        .get
      persistOk(v130, y, "y1")
      persist(plain, x, "x1") match {
        case Left(_: JournalRowStoreException.KeyConflict) =>
        case other                                         => fail(s"expected a key conflict, got $other")
      }
      val (j, _) = migrate(plain, Set(x, y), execute = true)
      j.moved shouldBe 1
      persistOk(plain, x, "x1") shouldBe 1L
      events(plain, x) shouldBe Vector(1L -> "x1")
      events(plain, y) shouldBe Vector(1L -> "y1")
    }

    "move a row after the row that holds its target key, with the separator '|'" in {
      val pipe130   = v130.copy(separator = Some("|"))
      val pipePlain = plain.copy(separator = Some("|"))
      // 1.3.0 split `QB|<n>` into characters, so its old key is the new key of `Q|B` when the shards are equal.
      val x = "Q|B"
      val y = Iterator
        .from(0)
        .take(100000)
        .map(n => s"QB|$n")
        .find(id => journalKey(pipe130, id, 1) == journalKey(pipePlain, x, 1))
        .get
      persistOk(pipe130, x, "x1")
      persistOk(pipe130, y, "y1")
      val (dry, _) = migrate(pipePlain, Set(x, y), execute = false)
      dry.directMoves shouldBe 1
      dry.orderedMoves shouldBe 1
      val (j, _) = migrate(pipePlain, Set(x, y), execute = true)
      j.moved shouldBe 2
      events(pipePlain, x) shouldBe Vector(1L -> "x1")
      events(pipePlain, y) shouldBe Vector(1L -> "y1")
      persistOk(pipePlain, x, "x2") shouldBe 2L
    }

    "merge an event and a snapshot that are under both keys" in {
      val id = changedId("Both")
      writtenBy130(id)
      // The event 1 under both keys, deleted only under the old key; the snapshot under both keys, newer under the old.
      copyRow(journalTableName, journalKey(v130, id, 1), journalKey(plain, id, 1), keep = true)
      copyRow(
        journalTableName,
        journalKey(v130, id, 1),
        journalKey(v130, id, 1),
        keep = true,
        change = _.put("deleted", new AttributeValue().withBOOL(true))
      )
      copyRow(
        snapshotTableName,
        snapshotKey(v130, id, 3),
        snapshotKey(plain, id, 3),
        keep = true,
        change = _.put("created", new AttributeValue().withN("1"))
      )
      val (dryJ, dryS) = migrate(plain, Set(id), execute = false)
      dryJ.duplicates.map(_.sequenceNr) shouldBe Seq(1L)
      dryS.duplicates.map(_.sequenceNr) shouldBe Seq(3L)
      val (j, s) = migrate(plain, Set(id), execute = true)
      j.merged shouldBe 1
      s.merged shouldBe 1
      rows(journalTableName, id).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        2L -> Some(false),
        3L -> Some(false)
      )
      rows(snapshotTableName, id) should have size 1
      underNewKeys(plain, id)
      events(plain, id) shouldBe Vector.empty
      recover(plain, id).snapshot shouldBe Some(3L -> "s3")
    }

    "leave the JournalHead items as they are" in {
      val id = changedId("Head")
      writtenBy130(id, v130Head)
      val head = headItem(id)
      head.map(_("sequence-nr").getN) shouldBe Some("3")
      val (j, _) = migrate(plainHead, Set(id), execute = true)
      j.moved shouldBe 3
      headItem(id) shouldBe head
      val recovered = recover(plainHead, id)
      recovered.lastSequenceNr shouldBe 3L
      persistOk(plainHead, id, "e4") shouldBe 4L
      updateEvent(plainHead, id, 4, "e4-updated")
      command(plainHead.copy(softDelete = false), id, DeleteTo(2))
      rows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(3L, 4L)
      headItem(id).map(_("sequence-nr").getN) shouldBe Some("4")
      underNewKeys(plainHead, id)
    }

    "move the rows of issue #831, so that deletes reach them, also with the resolvers of 1.3.0 configured" in {
      Seq(plain, Mode(form = Defaults)).foreach { mode =>
        val id = unique("Format831")
        persistOk(mode, id, "e1", "e2")
        command(mode, id, Snap("s2"))
        copyRow(journalTableName, journalKey(mode, id, 1), (journalKey(mode, id, 1)._1, "1"))
        copyRow(snapshotTableName, snapshotKey(mode, id, 2), (snapshotKey(mode, id, 2)._1, "2"))
        val changed = changedId("Changed831")
        writtenBy130(changed)
        val (j, s) = migrate(mode, Set(id, changed), execute = true)
        if (mode.form == Defaults) {
          // only the rows of #831 move; the rows of 1.3.0 are under the keys of the configured resolvers
          j.moved shouldBe 1
          s.moved shouldBe 1
        } else {
          j.moved shouldBe 4
          s.moved shouldBe 2
        }
        underNewKeys(mode, id)
        command(mode.copy(softDelete = false), id, DeleteTo(2))
        command(mode, id, DeleteSnapshotsTo(2))
        rows(journalTableName, id) shouldBe empty
        rows(snapshotTableName, id) shouldBe empty
      }
    }

    "end with the same rows when it stops part way and runs again, or a response is lost" in {
      val ids = Seq.fill(2)(changedId("Stop"))
      ids.foreach(writtenBy130(_))
      val down = new AtomicBoolean(false)
      val c    = hooked(before = index => if (index == 3) down.set(true), down = down)
      val (stopped, _) = migrate(
        plain,
        ids.toSet,
        execute = true,
        client = c,
        settings = _.copy(parallelism = 1, maxAttempts = 2),
        snapshots = false
      )
      stopped.moved shouldBe 3 // the third transaction is sent before the connection goes down
      stopped.remaining should have size 3
      ids.foreach(id => rows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(1L, 2L, 3L))
      // the application works in between
      events(plain, ids(0)) shouldBe Vector.empty
      recover(plain, ids(0)).lastSequenceNr shouldBe 3L
      val lost = new AtomicInteger()
      val lossy = Proxy
        .newProxyInstance(
          classOf[V2DynamoDbClient].getClassLoader,
          Array(classOf[V2DynamoDbClient]),
          new InvocationHandler {
            override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
              val result =
                try method.invoke(v2Client, Option(args).getOrElse(Array.empty[AnyRef]): _*)
                catch { case e: InvocationTargetException => throw e.getCause }
              if (method.getName == "transactWriteItems" && lost.incrementAndGet() % 2 == 1)
                throw SdkClientException.create("the response was lost")
              result
            }
          }
        )
        .asInstanceOf[V2DynamoDbClient]
      val (j, s) = migrate(plain, ids.toSet, execute = true, client = lossy)
      j.remaining shouldBe empty
      s.remaining shouldBe empty
      ids.foreach { id =>
        rows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(1L, 2L, 3L)
        rows(snapshotTableName, id).map(_.sequenceNr) shouldBe Vector(3L)
        underNewKeys(plain, id)
      }
      worksWith(plain, ids(1))
    }

    "move the rows as the application left them while it writes to the same ids" in {
      val id = changedId("Concurrent")
      writtenBy130(id)
      val done = new AtomicBoolean(false)
      // Before the first move, the application persists, soft deletes event 1, updates event 2, and hard deletes
      // nothing yet; after the migration, it hard deletes event 2.
      val c = hooked(before =
        _ =>
          if (done.compareAndSet(false, true)) {
            persistOk(plain, id, "e4")
            command(plain, id, DeleteTo(1))
            updateEvent(plain, id, 2, "e2-updated")
          }
      )
      val (j, _) = migrate(plain, Set(id), execute = true, client = c, settings = _.copy(parallelism = 1))
      j.remaining shouldBe empty
      underNewKeys(plain, id)
      rows(journalTableName, id).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        1L -> Some(true),
        2L -> Some(false),
        3L -> Some(false),
        4L -> Some(false)
      )
      val recovered = recover(plain, id)
      recovered.lastSequenceNr shouldBe 4L
      events(plain, id) shouldBe Vector(4L -> "e4")
      recover(plain.copy(), id).snapshot shouldBe Some(3L -> "s3")
    }
  }

  "a delete that runs while the rows move" should {

    "delete the events that move between its requests" in {
      val mode = plain.copy(softDelete = false, hooked = true)
      val id   = changedId("DeleteRace")
      writtenBy130(id)
      recover(mode, id)
      // After the first DeleteItem of the hard delete, every row of the id moves to its new key.
      RequestHook.arm {
        case r: software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest => r.tableName() == journalTableName
        case _                                                                   => false
      }(migrate(plain, Set(id), execute = true))
      try command(mode, id, DeleteTo(2))
      finally RequestHook.disarm()
      rows(journalTableName, id).map(_.sequenceNr) shouldBe Vector(3L)
      events(plain, id) shouldBe Vector.empty
      recover(plain, id).lastSequenceNr shouldBe 3L
    }

    "delete a snapshot that moves after the query that found it" in {
      val mode = plain.copy(hooked = true)
      val id   = changedId("SnapshotRace")
      writtenBy130(id)
      withActor(mode, id) { (actor, probe, _) =>
        RequestHook.arm {
          case r: software.amazon.awssdk.services.dynamodb.model.QueryRequest => r.tableName() == snapshotTableName
          case _                                                              => false
        }(migrate(plain, Set(id), execute = true))
        try {
          actor ! DeleteSnapshotAt(3)
          probe.expectMsgType[Completed](timeout)
        } finally RequestHook.disarm()
      }
      rows(snapshotTableName, id) shouldBe empty
      recover(plain, id).snapshot shouldBe None
    }
  }

  "a rolling update from 1.3.0" should {

    "keep the events of both nodes in the replay, fail the ids whose new key holds an old row, and need a migration" in {
      val id = changedId("Rolling")
      persistOk(v130, id, "e1", "e2")
      persistOk(plain, id, "e3")
      persistOk(v130, id, "e4")
      events(v130, id).map(_._1) shouldBe Vector(1L, 2L, 3L, 4L)
      events(plain, id).map(_._1) shouldBe Vector(1L, 2L, 3L, 4L)
      // a hard delete on a 1.3.0 node misses the event that the new node wrote under the new key
      command(v130.copy(softDelete = false), id, DeleteTo(3))
      events(plain, id).map(_._1) shouldBe Vector(3L, 4L)
      // the same delete on a new node reaches both keys
      command(plainHard, id, DeleteTo(3))
      events(plain, id).map(_._1) shouldBe Vector(4L)
      // after the migration, the rows that the 1.3.0 node wrote are under the new keys too
      migrate(plain, Set(id), execute = true)._1.moved shouldBe 1
      underNewKeys(plain, id)
    }
  }

  "--reverse" should {

    "move the rows back for 1.3.0, and list the rows that 1.3.0 cannot hold" in {
      val ids = Seq.fill(2)(changedId("Reverse"))
      ids.foreach(writtenBy130(_))
      migrate(plain, ids.toSet, execute = true)
      persistOk(plain, ids(0), "e4")
      // `Order-` has no key in 1.3.0; two ids that share a 1.3.0 key cannot both go back.
      val trailing = s"${unique("Order")}-"
      persistOk(plain, trailing, "t1")
      val prefix = unique("Pair")
      val pair = Iterator
        .from(0)
        .map(n => s"$prefix-X-$n")
        .take(5000)
        .toVector
        .groupBy(id => journalKey(v130, id, 1))
        .collectFirst { case (_, group) if group.size >= 2 => group.take(2) }
        .get
      pair.foreach(persistOk(plain, _, "p1"))
      val all       = ids.toSet + trailing ++ pair
      val (dryJ, _) = migrate(plain, all, execute = false, reverse = true)
      dryJ.unmovable.map(_.persistenceId) shouldBe Seq(trailing)
      dryJ.blocked.map(_.candidate.persistenceId) should have size 1
      val (j, s) = migrate(plain, all, execute = true, reverse = true)
      j.moved shouldBe 7 + 1 // the events of ids, and one of the pair
      j.remaining.map(_._1.persistenceId) should have size 1
      s.moved shouldBe 2
      ids.foreach { id =>
        rows(journalTableName, id).foreach(r => (r.pkey, r.skey) shouldBe journalKey(v130, id, r.sequenceNr))
      }
      // 1.3.0 replays, updates, deletes, and loads the snapshot
      val id = ids(0)
      recover(v130, id).snapshot shouldBe Some(3L -> "s3")
      events(v130, id) shouldBe Vector(4L -> "e4")
      updateEvent(v130, id, 4, "e4-updated")
      command(v130.copy(softDelete = false), id, DeleteTo(2))
      command(v130, id, DeleteTo(3))
      command(v130, id, DeleteSnapshotAt(3))
      rows(journalTableName, id).map(r => r.sequenceNr -> r.deleted) shouldBe Vector(
        3L -> Some(true),
        4L -> Some(false)
      )
      events(v130, id) shouldBe Vector(4L -> "e4-updated")
      recover(v130, id).snapshot shouldBe None
    }
  }

  "the command line" should {

    "run a dry-run by default and move the rows with --execute, for the journal and the snapshot store" in {
      val id = changedId("Cli")
      writtenBy130(id)
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]
      val args  = Seq("--persistence-id", id, "--max-rate", "100", "--segments", "2", "--progress")
      JournalKeyMigration.run(args, config(plain), lines += _).candidates should have size 3
      SnapshotKeyMigration.run(args, config(plain), lines += _).candidates should have size 1
      lines.filter(_.contains("key migration (forward)")).foreach(_ should startWith("[dry-run] "))
      rows(journalTableName, id).map(r => (r.pkey, r.skey)) shouldBe (1L to 3L).map(journalKey(v130, id, _))
      JournalKeyMigration.run("--execute" +: args, config(plain), lines += _).moved shouldBe 3
      SnapshotKeyMigration.run("--execute" +: args, config(plain), lines += _).moved shouldBe 1
      underNewKeys(plain, id)
      JournalKeyMigration.run(args, config(plain), lines += _).candidates shouldBe empty
      lines.foreach(line => info(line))
      an[IllegalArgumentException] should be thrownBy JournalKeyMigration.run(Seq("--bogus"), config(plain), _ => ())
      an[IllegalArgumentException] should be thrownBy JournalKeyMigration.run(Seq("--max-rate"), config(plain), _ => ())
      an[IllegalArgumentException] should be thrownBy JournalKeyMigration.run(
        Seq("--parallelism", "0"),
        config(plain),
        _ => ()
      )
    }
  }

  "the cost" should {

    /** Writes `rows` rows of 1.3.0 with BatchWriteItem: half of them of ids whose key changes, ten events per id. */
    def load(rows: Int, prefix: String): Set[String] = {
      val ids = (0 until rows / 10).map(n => if (n % 2 == 0) s"$prefix-$n-x" else s"$prefix$n-x").toVector
      val jc  = journalConfig(v130)
      val items = for {
        id <- ids
        seq <- 1L to 10L
      } yield {
        val key = (BeforeKeyFix.partitionKey(id, "-", jc.shardCount), BeforeKeyFix.sortKey(id, seq, "-"))
        Map(
          "pkey"           -> V2AttributeValue.builder().s(key._1).build(),
          "skey"           -> V2AttributeValue.builder().s(key._2).build(),
          "persistence-id" -> V2AttributeValue.builder().s(id).build(),
          "sequence-nr"    -> V2AttributeValue.builder().n(seq.toString).build(),
          "deleted"        -> V2AttributeValue.builder().bool(false).build(),
          "ordering"       -> V2AttributeValue.builder().n(seq.toString).build(),
          "message"        -> V2AttributeValue.builder().b(SdkBytes.fromByteArray(Array.fill(200)(1.toByte))).build()
        ).asJava
      }
      implicit val ec: ExecutionContext = ExecutionContext.global
      Await.result(
        Future.traverse(items.grouped(25).toVector) { batch =>
          Future {
            v2Client.batchWriteItem(
              BatchWriteItemRequest
                .builder()
                .requestItems(
                  Map(
                    jc.tableName -> batch
                      .map(i => WriteRequest.builder().putRequest(PutRequest.builder().item(i).build()).build()).asJava
                  ).asJava
                )
                .build()
            )
          }
        },
        30.minutes
      )
      ids.toSet
    }

    "be measured for a table of KEY_MIGRATION_ROWS rows (200 by default)" in {
      val count  = sys.env.get("KEY_MIGRATION_ROWS").map(_.toInt).getOrElse(200)
      val prefix = unique("Perf")
      val ids    = load(count, prefix)
      def measure(execute: Boolean): MigrationReport =
        KeyMigration.run(
          v2Client,
          JournalKeyMigration.table(journalConfig(plain)),
          JournalKeyMigration.plan(journalConfig(plain), reverse = false),
          MigrationSettings(
            execute = execute,
            parallelism = sys.env.get("KEY_MIGRATION_PARALLELISM").map(_.toInt).getOrElse(4),
            persistenceIds = Some(ids)
          )
        )
      val dry  = measure(execute = false)
      val exec = measure(execute = true)
      val done = measure(execute = false)
      Seq("dry-run" -> dry, "execute" -> exec, "dry-run after" -> done).foreach { case (label, r) =>
        info(
          s"rows=$count $label: scanned=${r.scannedRows} to-move=${r.candidates.size} moved=${r.moved} " +
          s"elapsed=${r.elapsedMillis}ms requests=${r.usage.requests} capacity=${r.usage.capacityUnits}"
        )
      }
      dry.candidates should have size (count / 2)
      exec.moved shouldBe (count / 2)
      done.candidates shouldBe empty
    }
  }
}
