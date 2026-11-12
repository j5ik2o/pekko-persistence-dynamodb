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
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.Sink
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, PutItemRequest, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.PersistenceId
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.DockerAssignedPort
import com.typesafe.config.ConfigFactory
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue => V2AttributeValue,
  GetItemRequest,
  GetItemResponse
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => V2DynamoDbClient }

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object JournalHeadMigrationSpec extends DockerAssignedPort

final class JournalHeadMigrationSpec extends JournalHeadSpecSupport {

  import JournalHeadSpecSupport._

  override lazy val dynamoDBPort: Int = JournalHeadMigrationSpec.dynamoDBPort

  override lazy val controller = JournalHeadMigrationSpec.controller

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

  private def migrationFixture(
      execute: Boolean,
      observe: Boolean,
      fail: String = "",
      throwingSink: Boolean = false,
      snapshots: Boolean = true,
      reverse: Boolean = false,
      onFailure: (Vector[AnyRef], Vector[JournalHeadMigration.Progress]) => Unit = (_, _) => ()
  ): (JournalHeadMigration.Result, Vector[AnyRef], Vector[JournalHeadMigration.Progress]) = {
    import JournalHeadMigration._
    import software.amazon.awssdk.services.dynamodb.model.{
      ScanRequest => V2ScanRequest,
      ScanResponse,
      PutItemRequest => V2PutItemRequest,
      PutItemResponse,
      UpdateItemRequest,
      UpdateItemResponse,
      ResourceNotFoundException
    }
    val requests = scala.collection.mutable.ArrayBuffer.empty[AnyRef]
    val progress = scala.collection.mutable.ArrayBuffer.empty[Progress]
    var page     = 0
    def row(id: String, sequence: String): java.util.Map[String, V2AttributeValue] = Map(
      "persistence-id" -> V2AttributeValue.builder().s(id).build(),
      "sequence-nr"    -> V2AttributeValue.builder().n(sequence).build()
    ).asJava
    val client = java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[V2DynamoDbClient].getClassLoader,
        Array(classOf[V2DynamoDbClient]),
        new java.lang.reflect.InvocationHandler {
          override def invoke(proxy: Any, method: java.lang.reflect.Method, args: Array[AnyRef]): AnyRef = {
            val request = args(0)
            requests += request
            request match {
              case scan: V2ScanRequest =>
                scan.consistentRead().booleanValue() shouldBe true
                scan.projectionExpression() shouldBe "#persistenceId, #sequenceNr"
                if (scan.tableName() == "fixture-journal") {
                  page += 1
                  if (page == 2 && fail == "scan") throw new IllegalStateException("scan failure")
                  val items = page match {
                    case 1                     => List(row("p", if (reverse) "9" else "2"))
                    case 2 if fail == "decode" => List(row("p", "bad"))
                    case 2                     => List.empty[java.util.Map[String, V2AttributeValue]]
                    case 3                     => List(row("p", if (reverse) "2" else "9"))
                    case _                     => throw new AssertionError("unexpected extra page")
                  }
                  val response = ScanResponse.builder().items(items.asJava)
                  if (page < 3) response.lastEvaluatedKey(row("cursor", page.toString))
                  response.build()
                } else {
                  page shouldBe 3
                  if (fail == "missing") throw ResourceNotFoundException.builder().message("missing").build()
                  ScanResponse.builder().items(List(row("p", "12"), row("snapshot-only", "4")).asJava).build()
                }
              case get: GetItemRequest =>
                page shouldBe 3
                get.consistentRead().booleanValue() shouldBe true
                val id = get.key().get("persistence-id").s()
                id should not be "head-only"
                if (id == "p") GetItemResponse.builder().item(row("p", "5")).build()
                else GetItemResponse.builder().build()
              case put: V2PutItemRequest =>
                execute shouldBe true
                put.conditionExpression() shouldBe "attribute_not_exists(#persistenceId)"
                PutItemResponse.builder().build()
              case update: UpdateItemRequest =>
                execute shouldBe true
                update.conditionExpression() shouldBe "#sequenceNr < :latest"
                update.expressionAttributeValues().get(":latest").n() shouldBe (if (snapshots && fail != "missing") "12"
                                                                                else "9")
                UpdateItemResponse.builder().build()
              case other => throw new AssertionError(s"unexpected request $other")
            }
          }
        }
      ).asInstanceOf[V2DynamoDbClient]
    val snapshot = if (snapshots) Some(SnapshotTable("fixture-snapshot", "persistence-id", "sequence-nr")) else None
    val result =
      try {
        if (observe) {
          runObserved(
            client,
            "fixture-journal",
            journalColumnsDefConfig,
            headConfig,
            execute,
            snapshot,
            event => {
              progress += event
              if (throwingSink) throw new IllegalStateException("sink failure")
            },
            None,
            (_, _) => ()
          )
        } else
          JournalHeadMigration.run(client, "fixture-journal", journalColumnsDefConfig, headConfig, execute, snapshot)
      } catch {
        case error: RuntimeException =>
          onFailure(requests.toVector, progress.toVector)
          throw error
      }
    (result, requests.toVector, progress.toVector)
  }

  "JournalHead" should {
    "observe complete migration scans without changing results or SDK requests" in {
      import JournalHeadMigration._
      for (execute <- Seq(false, true)) {
        val silent   = migrationFixture(execute, observe = false)
        val observed = migrationFixture(execute, observe = true)
        observed._1 shouldBe silent._1
        observed._2 shouldBe silent._2
        val brokenSink = migrationFixture(execute, observe = true, throwingSink = true)
        brokenSink._1 shouldBe silent._1
        brokenSink._2 shouldBe silent._2
        observed._1 shouldBe Result(2, 1, 1, 0, Seq("p"), snapshotTableScanned = true)
        observed._3 shouldBe Vector(
          SourceStarted(Journal),
          ScanPage(Journal, 1L, 1L, 1L, false),
          ScanPage(Journal, 2L, 1L, 1L, false),
          ScanPage(Journal, 3L, 2L, 1L, true),
          SourceStarted(Snapshot),
          ScanPage(Snapshot, 1L, 2L, 2L, true),
          PhaseStarted("read-heads"),
          PhaseStarted("plan")
        ) ++ (if (execute) Vector(PhaseStarted("execute")) else Vector.empty)
        migrationFixture(execute, observe = true, reverse = true)._1 shouldBe observed._1
        val skipped = migrationFixture(execute, observe = true, snapshots = false)
        skipped._1 shouldBe Result(1, 0, 1, 0, Seq("p"), snapshotTableScanned = false)
        skipped._3 should contain(SnapshotSkipped)
        skipped._3 should not contain SnapshotUnavailable
        val missing = migrationFixture(execute, observe = true, fail = "missing")
        missing._1 shouldBe skipped._1
        missing._3 should contain(SnapshotUnavailable)
        missing._3 should not contain SnapshotSkipped
      }
    }

    "stop before head reads and writes when a scan page fails or cannot decode" in {
      import JournalHeadMigration._
      for (failure <- Seq("scan", "decode")) {
        var checked = false
        val error = intercept[RuntimeException] {
          migrationFixture(
            execute = true,
            observe = true,
            fail = failure,
            onFailure = (requests, progress) => {
              requests.size shouldBe 2
              progress shouldBe Vector(SourceStarted(Journal), ScanPage(Journal, 1L, 1L, 1L, false))
              checked = true
            }
          )
        }
        if (failure == "scan") error.getMessage shouldBe "scan failure"
        else error shouldBe a[NumberFormatException]
        checked shouldBe true
      }
    }

    "preserve a head-only watermark and attributes with progress enabled" in {
      val pid = "migration-progress-head-only"
      dynamoDBClient.putItem(
        new PutItemRequest()
          .withTableName(journalHeadTableName).withItem(
            Map(
              "persistence-id" -> new AttributeValue().withS(pid),
              "sequence-nr"    -> new AttributeValue().withN("42"),
              "events"         -> new AttributeValue().withL(java.util.Collections.emptyList[AttributeValue]()),
              "revision"       -> new AttributeValue().withN("7")
            ).asJava
          )
      )
      val before = headItem(pid)
      val client = newV2Client()
      try {
        JournalHeadMigration.runObserved(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          true,
          None,
          _ => (),
          None,
          (_, _) => ()
        )
        headItem(pid) shouldBe before
      } finally client.close()
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

    "preserve unselected and head-only attributes and raise a selected create race" in {
      val selected     = "selection-race"
      val snapshotOnly = "selection-snapshot-only"
      val headOnly     = "selection-head-only"
      val other        = "selection-other"
      putJournalRow(selected, 9L)
      putJournalRow(other, 11L)
      putSnapshotRow(snapshotOnly, 12L)
      val preserved = Map(
        "persistence-id" -> new AttributeValue().withS(headOnly),
        "sequence-nr"    -> new AttributeValue().withN("42"),
        "events"         -> new AttributeValue().withL(new AttributeValue().withS("preserve")),
        "revision"       -> new AttributeValue().withN("7"),
        "custom"         -> new AttributeValue().withS("preserve")
      )
      dynamoDBClient.putItem(new PutItemRequest().withTableName(journalHeadTableName).withItem(preserved.asJava))
      val beforeOther = headItem(other)
      val delegate    = newV2Client()
      val reads       = scala.collection.mutable.Set.empty[String]
      val client = Proxy
        .newProxyInstance(
          classOf[V2DynamoDbClient].getClassLoader,
          Array(classOf[V2DynamoDbClient]),
          new InvocationHandler {
            override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
              if (method.getName == "getItem")
                reads += args(0).asInstanceOf[GetItemRequest].key().get("persistence-id").s()
              if (method.getName == "putItem") {
                val request = args(0).asInstanceOf[software.amazon.awssdk.services.dynamodb.model.PutItemRequest]
                if (request.item().get("persistence-id").s() == selected) {
                  dynamoDBClient.putItem(
                    new PutItemRequest()
                      .withTableName(journalHeadTableName).withItem(
                        Map(
                          "persistence-id" -> new AttributeValue().withS(selected),
                          "sequence-nr"    -> new AttributeValue().withN("3"),
                          "events"         -> new AttributeValue().withL(new AttributeValue().withS("old")),
                          "revision"       -> new AttributeValue().withN("2")
                        ).asJava
                      )
                  )
                }
              }
              try method.invoke(delegate, args: _*)
              catch { case error: java.lang.reflect.InvocationTargetException => throw error.getCause }
            }
          }
        ).asInstanceOf[V2DynamoDbClient]
      try {
        def migrate(execute: Boolean): JournalHeadMigration.Result = JournalHeadMigration.runObserved(
          client,
          journalTableName,
          journalColumnsDefConfig,
          headConfig,
          execute,
          Some(JournalHeadMigration.SnapshotTable(snapshotTableName, "persistence-id", "sequence-nr")),
          _ => (),
          Some(Set(selected, snapshotOnly, headOnly, "selection-unknown").map(PersistenceId(_))),
          (_, _) => ()
        )
        migrate(false) shouldBe JournalHeadMigration.Result(2, 2, 0, 0, Seq.empty, true)
        headItem(selected) shouldBe None
        val executed = migrate(true)
        executed.createdJournalHeads shouldBe 1
        executed.updatedJournalHeads shouldBe 1
        headSequenceNr(selected) shouldBe Some(9L)
        headSequenceNr(snapshotOnly) shouldBe Some(12L)
        headItem(selected).get("events").getL.isEmpty shouldBe true
        headItem(selected).get.contains("revision") shouldBe false
        migrate(true) shouldBe JournalHeadMigration.Result(2, 0, 0, 2, Seq.empty, true)
        reads.toSet shouldBe Set(selected, snapshotOnly)
        headItem(headOnly) shouldBe Some(preserved)
        headItem(other) shouldBe beforeOther
      } finally delegate.close()
    }

    "select only source-observed IDs after all pages and propagate migration failures" in {
      import JournalHeadMigration._
      import software.amazon.awssdk.services.dynamodb.model.{
        ScanRequest,
        ScanResponse,
        PutItemRequest,
        PutItemResponse,
        UpdateItemRequest,
        UpdateItemResponse,
        ConditionalCheckFailedException,
        ResourceNotFoundException
      }
      val columns = JournalColumnsDefConfig(
        ConfigFactory.empty,
        "pkey",
        "skey",
        "persistence-id",
        "sequence-nr",
        "deleted",
        "message",
        "ordering",
        "tags"
      )
      val config = JournalHeadConfig.fromConfig(ConfigFactory.empty)
      def row(id: String, n: Long) = Map(
        "persistence-id" -> V2AttributeValue.builder.s(id).build,
        "sequence-nr"    -> V2AttributeValue.builder.n(n.toString).build
      ).asJava
      class Fixture {
        var jp                              = 0; var sp = 0
        var requests                        = Vector.empty[AnyRef]
        var heads                           = Map("p" -> 5L, "other" -> 1L, "head-only" -> 42L)
        var summary                         = Option.empty[(Int, Int)]
        var failure                         = ""
        var snapshot: Option[SnapshotTable] = Some(SnapshotTable.default)
        def reset(): Unit                   = { jp = 0; sp = 0; requests = Vector.empty; summary = None }
        val client = java.lang.reflect.Proxy
          .newProxyInstance(
            classOf[V2DynamoDbClient].getClassLoader,
            Array(classOf[V2DynamoDbClient]),
            new java.lang.reflect.InvocationHandler {
              def invoke(proxy: Any, m: java.lang.reflect.Method, a: Array[AnyRef]): AnyRef = {
                val req = a(0); requests :+= req
                if (m.getName == failure && failure != "scan") throw new IllegalStateException(failure + " failure")
                req match {
                  case r: ScanRequest =>
                    assert(r.consistentRead.booleanValue && r.projectionExpression == "#persistenceId, #sequenceNr")
                    assert(r.filterExpression == null && r.limit == null && r.segment == null)
                    val isJournal = r.tableName == "Journal"
                    val page = if (isJournal) { jp += 1; jp }
                    else { sp += 1; sp }
                    val cursor = Map("cursor" -> V2AttributeValue.builder.s((page - 1).toString).build).asJava
                    if (page == 1) assert(r.exclusiveStartKey.isEmpty) else assert(r.exclusiveStartKey == cursor)
                    if (failure == "scan" && page == 2) throw new IllegalStateException("scan failure")
                    if (failure == "decode" && page == 2)
                      return ScanResponse.builder.items(Map.empty[String, V2AttributeValue].asJava).build
                    if (failure == "missing" && !isJournal) throw ResourceNotFoundException.builder.build
                    val items = if (isJournal) page match {
                      case 1 => List(row("p", 2), row("other", 99))
                      case 2 => List.empty[java.util.Map[String, V2AttributeValue]]
                      case 3 => List(row("p", 9))
                    }
                    else
                      page match {
                        case 1 => List(row("snapshot-only", 4), row("other", 101))
                        case 2 => List.empty[java.util.Map[String, V2AttributeValue]]
                        case 3 => List(row("snapshot-only", 12))
                      }
                    val b = ScanResponse.builder.items(items.asJava)
                    if (page < 3)
                      b.lastEvaluatedKey(Map("cursor" -> V2AttributeValue.builder.s(page.toString).build).asJava)
                    b.build
                  case r: GetItemRequest =>
                    assert(
                      jp == 3 && (sp == 3 || snapshot.isEmpty || failure == "missing"),
                      "head read before final source page"
                    )
                    val id = r.key.get("persistence-id").s
                    assert(id != "head-only" && id != "unknown", s"source-unobserved head read: $id")
                    assert(r.consistentRead.booleanValue)
                    heads
                      .get(id).fold(GetItemResponse.builder.build)(n => GetItemResponse.builder.item(row(id, n)).build)
                  case r: PutItemRequest =>
                    assert(r.conditionExpression == "attribute_not_exists(#persistenceId)")
                    val id = r.item.get("persistence-id").s
                    if (heads.contains(id)) throw ConditionalCheckFailedException.builder.build
                    heads += id -> r.item.get("sequence-nr").n.toLong
                    PutItemResponse.builder.build
                  case r: UpdateItemRequest =>
                    assert(r.conditionExpression == "#sequenceNr < :latest")
                    assert(r.updateExpression == "SET #sequenceNr = :latest, #events = :emptyEvents REMOVE #revision")
                    val id = r.key.get("persistence-id").s;
                    val n  = r.expressionAttributeValues.get(":latest").n.toLong
                    if (!heads.get(id).exists(_ < n)) throw ConditionalCheckFailedException.builder.build
                    heads += id -> n; UpdateItemResponse.builder.build
                  case r => throw new AssertionError("unexpected request " + r)
                }
              }
            }
          ).asInstanceOf[V2DynamoDbClient]
        def run(execute: Boolean, ids: Option[Set[PersistenceId]]): Result = {
          runObserved(
            client,
            "Journal",
            columns,
            config,
            execute,
            snapshot,
            _ => (),
            ids,
            (r, o) => summary = Some((r, o))
          )
        }
        def scans   = requests.collect { case x: ScanRequest => x }
        def readIds = requests.collect { case x: GetItemRequest => x.key.get("persistence-id").s }.toSet
      }

      val all       = new Fixture
      val allResult = all.run(false, None)
      assert(allResult == Result(3, 1, 2, 0, Seq("other", "p"), true))
      assert(all.summary.isEmpty)
      val ids      = Some(Set("p", "snapshot-only", "head-only", "unknown").map(PersistenceId(_)))
      val dry      = new Fixture
      val expected = Result(2, 1, 1, 0, Seq("p"), true)
      assert(dry.run(false, ids) == expected, "late-page max/selection mismatch")
      assert(dry.scans == all.scans)
      assert(dry.readIds == Set("p", "snapshot-only"))
      assert(dry.summary.contains((4, 2)))
      assert(dry.requests.forall(r => r.isInstanceOf[ScanRequest] || r.isInstanceOf[GetItemRequest]))
      val ex = new Fixture
      assert(ex.run(true, ids) == expected)
      assert(ex.heads == Map("p" -> 9L, "snapshot-only" -> 12L, "other" -> 1L, "head-only" -> 42L))
      assert(ex.scans == all.scans)
      ex.reset()
      assert(ex.run(true, ids) == Result(2, 0, 0, 2, Seq.empty, true))
      assert(ex.requests.forall(r => r.isInstanceOf[ScanRequest] || r.isInstanceOf[GetItemRequest]))
      for (selection <- Seq(Set.empty[PersistenceId], Set("unknown", "head-only").map(PersistenceId(_)))) {
        val empty = new Fixture
        assert(empty.run(true, Some(selection)) == Result(0, 0, 0, 0, Seq.empty, true))
        assert(empty.requests.size == 6 && empty.summary.contains((selection.size, 0)))
      }
      for (missing <- Seq(false, true)) {
        val unavailable = new Fixture
        if (missing) unavailable.failure = "missing" else unavailable.snapshot = None
        assert(unavailable.run(true, ids) == Result(1, 0, 1, 0, Seq("p"), false))
        assert(unavailable.summary.contains((4, 1)))
        assert(unavailable.readIds == Set("p"))
      }
      for (failure <- Seq("scan", "decode", "getItem", "putItem", "updateItem")) {
        val broken = new Fixture
        broken.failure = failure
        val error = intercept[Exception](broken.run(true, ids))
        if (failure != "decode") assert(error.getMessage == failure + " failure")
        if (Set("scan", "decode")(failure)) assert(broken.requests.forall(_.isInstanceOf[ScanRequest]))
      }
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

  }
}
