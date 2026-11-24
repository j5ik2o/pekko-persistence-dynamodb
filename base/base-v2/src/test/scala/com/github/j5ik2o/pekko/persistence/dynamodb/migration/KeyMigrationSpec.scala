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
package com.github.j5ik2o.pekko.persistence.dynamodb.migration

import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ DockerAssignedPort, DynamoDBContainerHelper }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.lang.reflect.{ InvocationHandler, InvocationTargetException, Method, Proxy }
import java.net.URI
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.jdk.CollectionConverters._

object KeyMigrationSpec extends DockerAssignedPort

/** The moves of the key migration engine on DynamoDB Local, with a plan that names the target key of each row. The
  * journal and snapshot plans are tested with the plugins in journal-v2 (`JournalKeyMigrationSpec`).
  */
final class KeyMigrationSpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {

  override lazy val dynamoDBPort: Int = KeyMigrationSpec.dynamoDBPort
  override lazy val controller        = KeyMigrationSpec.controller

  private lazy val client: DynamoDbClient = DynamoDbClient
    .builder()
    .endpointOverride(URI.create(s"http://$dockerHost:$dynamoDBPort"))
    .region(Region.AP_NORTHEAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
    .build()

  private val tableCounter = new AtomicInteger()

  private def newTable(): String = {
    val name = s"keys-${tableCounter.incrementAndGet()}"
    client.createTable(
      CreateTableRequest
        .builder()
        .tableName(name)
        .attributeDefinitions(
          AttributeDefinition.builder().attributeName("pkey").attributeType(ScalarAttributeType.S).build(),
          AttributeDefinition.builder().attributeName("skey").attributeType(ScalarAttributeType.S).build()
        )
        .keySchema(
          KeySchemaElement.builder().attributeName("pkey").keyType(KeyType.HASH).build(),
          KeySchemaElement.builder().attributeName("skey").keyType(KeyType.RANGE).build()
        )
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .build()
    )
    name
  }

  private def journal(name: String): MigrationTable =
    MigrationTable(
      name,
      "pkey",
      "skey",
      "persistence-id",
      "sequence-nr",
      Seq("deleted", "tags", "ordering"),
      DuplicateRule.DeletedFlag("deleted")
    )

  private def snapshots(name: String): MigrationTable =
    MigrationTable(
      name,
      "pkey",
      "skey",
      "persistence-id",
      "sequence-nr",
      Seq.empty,
      DuplicateRule.LatestCreated("created")
    )

  private def k(n: Int): StoredKey = StoredKey(s"p$n", s"s$n")

  private def s(text: String): AttributeValue   = AttributeValue.builder().s(text).build()
  private def n(value: Long): AttributeValue    = AttributeValue.builder().n(value.toString).build()
  private def b(value: Boolean): AttributeValue = AttributeValue.builder().bool(value).build()

  private def putRow(table: String, key: StoredKey, id: String, seq: Long, extra: (String, AttributeValue)*): Unit = {
    client.putItem(
      PutItemRequest
        .builder()
        .tableName(table)
        .item(
          (Map(
            "pkey"           -> s(key.partitionKey),
            "skey"           -> s(key.sortKey),
            "persistence-id" -> s(id),
            "sequence-nr"    -> n(seq),
            "message" -> AttributeValue
              .builder().b(software.amazon.awssdk.core.SdkBytes.fromUtf8String(s"$id-$seq")).build()
          ) ++ extra).asJava
        )
        .build()
    )
    ()
  }

  private def rowAt(table: String, key: StoredKey): Option[Map[String, AttributeValue]] = {
    val item = client
      .getItem(
        GetItemRequest
          .builder()
          .tableName(table)
          .consistentRead(true)
          .key(Map("pkey" -> s(key.partitionKey), "skey" -> s(key.sortKey)).asJava)
          .build()
      )
      .item()
    if (item == null || item.isEmpty) None else Some(item.asScala.toMap)
  }

  private def owner(table: String, key: StoredKey): Option[(String, Long)] =
    rowAt(table, key).map(item => item("persistence-id").s() -> item("sequence-nr").n().toLong)

  private def allRows(table: String): Vector[Map[String, AttributeValue]] =
    client
      .scan(ScanRequest.builder().tableName(table).consistentRead(true).build()).items().asScala.toVector.map(
        _.asScala.toMap
      )

  /** A plan that names the target key of each (persistence id, sequence number); other rows stay. */
  private def plan(targets: ((String, Long), StoredKey)*): KeyPlan = new KeyPlan {
    private val byRow = targets.toMap
    override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
      byRow.get(persistenceId -> sequenceNr) match {
        case Some(target) if target != stored => Placement.Move(target, KeyForm.Release130)
        case _                                => Placement.InPlace
      }
    override def sharedKeyGroup(persistenceId: String): Option[String] = None
  }

  private def run(
      table: MigrationTable,
      keyPlan: KeyPlan,
      execute: Boolean,
      c: DynamoDbClient = client,
      settings: MigrationSettings => MigrationSettings = identity
  ): MigrationReport = {
    val report = KeyMigration.run(c, table, keyPlan, settings(MigrationSettings(execute = execute)))
    KeyMigration.render(report, "test").foreach(line => info(line))
    report
  }

  /** A client that runs `before` ahead of each TransactWriteItems and `after` once it returned. */
  private def hooked(
      before: Int => Unit = _ => (),
      after: Int => Unit = _ => (),
      down: AtomicBoolean = new AtomicBoolean(false)
  ): DynamoDbClient = {
    val count = new AtomicInteger()
    Proxy
      .newProxyInstance(
        classOf[DynamoDbClient].getClassLoader,
        Array(classOf[DynamoDbClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
            if (down.get() && method.getName != "close") throw SdkClientException.create("the connection is down")
            val transaction = method.getName == "transactWriteItems"
            val index       = if (transaction) count.incrementAndGet() else 0
            if (transaction) before(index)
            val result =
              try method.invoke(client, Option(args).getOrElse(Array.empty[AnyRef]): _*)
              catch { case e: InvocationTargetException => throw e.getCause }
            if (transaction) after(index)
            result
          }
        }
      )
      .asInstanceOf[DynamoDbClient]
  }

  "a dry-run" should {
    "report the moves without writing" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      putRow(table, k(5), "C", 1)
      val before = allRows(table)
      val report = run(journal(table), plan(("A", 1L) -> k(3), ("B", 1L) -> k(1), ("C", 1L) -> k(5)), execute = false)
      report.scannedRows shouldBe 3
      report.inPlaceRows shouldBe 1
      report.candidates.map(_.persistenceId).sorted shouldBe Seq("A", "B")
      report.directMoves shouldBe 1
      report.orderedMoves shouldBe 1
      allRows(table).toSet shouldBe before.toSet
      report.usage.requests.keySet shouldBe Set("Scan", "GetItem")
    }
  }

  "--execute" should {
    "move each row in one transaction, keep every attribute, and find nothing to move when run again" in {
      val table  = newTable()
      putRow(table, k(1), "A", 1, "deleted" -> b(false), "tags" -> s("t1"), "ordering" -> n(7))
      putRow(table, k(2), "A", 2, "deleted" -> b(true))
      val before = rowAt(journal(table).tableName, k(1)).get
      val p      = plan(("A", 1L) -> k(11), ("A", 2L) -> k(12))
      val report = run(journal(table), p, execute = true)
      report.moved shouldBe 2
      report.remaining shouldBe empty
      rowAt(table, k(1)) shouldBe None
      rowAt(table, k(2)) shouldBe None
      rowAt(table, k(11)).get shouldBe (before ++ Map("pkey" -> s("p11"), "skey" -> s("s11")))
      rowAt(table, k(12)).get("deleted") shouldBe b(true)
      // one consistent GetItem of the row and one TransactWriteItems per move
      report.usage.requests("GetItem") shouldBe 2
      report.usage.requests("TransactWriteItems") shouldBe 2
      val again = run(journal(table), p, execute = true)
      again.candidates shouldBe empty
      again.moved shouldBe 0
      allRows(table) should have size 2
    }

    "move a row after the row that holds its target key has moved" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      putRow(table, k(3), "C", 1)
      val report = run(journal(table), plan(("A", 1L) -> k(2), ("B", 1L) -> k(3), ("C", 1L) -> k(4)), execute = true)
      report.moved shouldBe 3
      owner(table, k(2)) shouldBe Some("A" -> 1L)
      owner(table, k(3)) shouldBe Some("B" -> 1L)
      owner(table, k(4)) shouldBe Some("C" -> 1L)
      owner(table, k(1)) shouldBe None
    }

    "swap rows that hold each other's target keys in one transaction" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      putRow(table, k(3), "C", 1)
      putRow(table, k(4), "D", 1)
      putRow(table, k(5), "E", 1)
      // A <-> B, and C -> D -> E -> C
      val p =
        plan(("A", 1L) -> k(2), ("B", 1L) -> k(1), ("C", 1L) -> k(4), ("D", 1L) -> k(5), ("E", 1L) -> k(3))
      val dry = run(journal(table), p, execute = false)
      dry.cycles.map(_.size).sorted shouldBe Seq(2, 3)
      val report = run(journal(table), p, execute = true)
      report.swapped shouldBe 5
      report.remaining shouldBe empty
      Seq(1 -> "B", 2 -> "A", 3 -> "E", 4 -> "C", 5 -> "D").foreach { case (key, id) =>
        owner(table, k(key)) shouldBe Some(id -> 1L)
      }
      allRows(table) should have size 5
      run(journal(table), p, execute = false).candidates shouldBe empty
    }

    "leave a row whose target key holds a row of another persistence id that does not move, and list it" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "C", 7)
      putRow(table, k(3), "D", 1)
      // A wants C's key; D wants A's key, so D waits for A
      val p   = plan(("A", 1L) -> k(2), ("D", 1L) -> k(1))
      val dry = run(journal(table), p, execute = false)
      dry.blocked.map(b => (b.candidate.persistenceId, b.occupantPersistenceId, b.occupantSequenceNr)) shouldBe Seq(
        ("A", "C", 7L)
      )
      dry.waiting.map(_.persistenceId) shouldBe Seq("D")
      val report = run(journal(table), p, execute = true)
      report.moved shouldBe 0
      report.remaining.map(_._1.persistenceId).sorted shouldBe Seq("A", "D")
      owner(table, k(1)) shouldBe Some("A" -> 1L)
      owner(table, k(2)) shouldBe Some("C" -> 7L)
      owner(table, k(3)) shouldBe Some("D" -> 1L)
    }

    "merge a journal row into its copy under the target key, deleted when either copy is" in {
      val table = newTable()
      putRow(table, k(1), "A", 1, "deleted" -> b(true))
      putRow(table, k(2), "A", 1, "deleted" -> b(false))
      putRow(table, k(3), "A", 2, "deleted" -> b(false))
      putRow(table, k(4), "A", 2, "deleted" -> b(true))
      val p     = plan(("A", 1L) -> k(2), ("A", 2L) -> k(4))
      val dry   = run(journal(table), p, execute = false)
      dry.duplicates should have size 2
      val report = run(journal(table), p, execute = true)
      report.merged shouldBe 2
      allRows(table).map(r => (r("skey").s(), r("deleted").bool().booleanValue())).toSet shouldBe Set(
        "s2" -> true,
        "s4" -> true
      )
    }

    "merge a snapshot into its copy under the target key, keeping the one saved later" in {
      val table  = newTable()
      putRow(table, k(1), "A", 1, "created" -> n(200))
      putRow(table, k(2), "A", 1, "created" -> n(100))
      putRow(table, k(3), "A", 2, "created" -> n(100))
      putRow(table, k(4), "A", 2, "created" -> n(200))
      val report = run(snapshots(table), plan(("A", 1L) -> k(2), ("A", 2L) -> k(4)), execute = true)
      report.merged shouldBe 2
      allRows(table).map(r => (r("skey").s(), r("created").n())).toSet shouldBe Set("s2" -> "200", "s4" -> "200")
    }

    "count a transaction whose response was lost as moved, without a second copy" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "A", 2)
      val lost = new AtomicBoolean(false)
      val c = hooked(after =
        _ => if (lost.compareAndSet(false, true)) throw SdkClientException.create("the response was lost")
      )
      val report = run(journal(table), plan(("A", 1L) -> k(11), ("A", 2L) -> k(12)), execute = true, c = c)
      report.moved shouldBe 2
      report.remaining shouldBe empty
      allRows(table).map(_("skey").s()).sorted shouldBe Seq("s11", "s12")
    }

    "leave every row once when it stops, and finish the moves when it runs again" in {
      val table = newTable()
      (1 to 10).foreach(i => putRow(table, k(i), "A", i.toLong))
      val p = plan((1 to 10).map(i => ("A", i.toLong) -> k(100 + i)): _*)
      // After the fourth transaction every request fails, as if the process lost DynamoDB.
      val down = new AtomicBoolean(false)
      val c    = hooked(after = index => if (index == 4) down.set(true), down = down)
      val stopped1 =
        run(journal(table), p, execute = true, c = c, settings = _.copy(parallelism = 1, maxAttempts = 2))
      stopped1.moved shouldBe 4
      stopped1.remaining should have size 6
      val stopped = allRows(table)
      stopped should have size 10
      stopped.map(_("sequence-nr").n().toLong).sorted shouldBe (1L to 10L)
      stopped.count(r => r("skey").s().length == 4) shouldBe 4
      val report = run(journal(table), p, execute = true)
      report.remaining shouldBe empty
      allRows(table).map(_("skey").s()).sorted shouldBe (101 to 110).map(i => s"s$i").sorted
    }

    "move a row as it is after another writer changed it" in {
      val table   = newTable()
      putRow(table, k(1), "A", 1, "deleted" -> b(false))
      val changed = new AtomicBoolean(false)
      val c = hooked(before =
        _ =>
          if (changed.compareAndSet(false, true))
            client.updateItem(
              UpdateItemRequest
                .builder()
                .tableName(table)
                .key(Map("pkey" -> s("p1"), "skey" -> s("s1")).asJava)
                .updateExpression("SET #d = :t")
                .expressionAttributeNames(Map("#d" -> "deleted").asJava)
                .expressionAttributeValues(Map(":t" -> b(true)).asJava)
                .build()
            )
      )
      val report = run(journal(table), plan(("A", 1L) -> k(2)), execute = true, c = c)
      report.moved shouldBe 1
      rowAt(table, k(2)).get("deleted") shouldBe b(true)
      rowAt(table, k(1)) shouldBe None
    }

    "count a row that another writer deleted before the move as gone" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      val c = hooked(before =
        _ =>
          client.deleteItem(
            DeleteItemRequest.builder().tableName(table).key(Map("pkey" -> s("p1"), "skey" -> s("s1")).asJava).build()
          )
      )
      val report = run(journal(table), plan(("A", 1L) -> k(2)), execute = true, c = c)
      report.alreadyGone + report.moved shouldBe 1
      allRows(table) shouldBe empty
    }

    "move rows whose keys are at the limits of DynamoDB (2,048 and 1,024 bytes of UTF-8)" in {
      val table  = newTable()
      val pkey   = "あ" * 682 + "xx" // 682 * 3 + 2 = 2,048 bytes
      val skey   = "€" * 341 + "x"  // 341 * 3 + 1 = 1,024 bytes
      val target = StoredKey(pkey, skey)
      pkey.getBytes("UTF-8").length shouldBe 2048
      skey.getBytes("UTF-8").length shouldBe 1024
      putRow(table, k(1), "A", 1)
      putRow(table, target, "B", 1)
      val report = run(
        journal(table),
        plan(("A", 1L) -> StoredKey(pkey.dropRight(1) + "y", skey), ("B", 1L) -> k(2)),
        execute = true
      )
      report.moved shouldBe 2
      owner(table, StoredKey(pkey.dropRight(1) + "y", skey)) shouldBe Some("A" -> 1L)
      owner(table, k(2)) shouldBe Some("B" -> 1L)
    }

    "keep to --max-rate and scan in parallel segments" in {
      val table = newTable()
      (1 to 30).foreach(i => putRow(table, k(i), "A", i.toLong))
      val p       = plan((1 to 30).map(i => ("A", i.toLong) -> k(100 + i)): _*)
      val started = System.nanoTime()
      val report =
        run(journal(table), p, execute = true, settings = _.copy(maxRate = Some(20.0), segments = 3, parallelism = 8))
      val seconds = (System.nanoTime() - started) / 1e9
      report.moved shouldBe 30
      seconds should be >= 1.3
    }

    "move only the selected persistence ids" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      val report = run(
        journal(table),
        plan(("A", 1L) -> k(11), ("B", 1L) -> k(12)),
        execute = true,
        settings = _.copy(persistenceIds = Some(Set("B")))
      )
      report.moved shouldBe 1
      owner(table, k(1)) shouldBe Some("A" -> 1L)
      owner(table, k(12)) shouldBe Some("B" -> 1L)
    }
  }

  "SequenceRanges" should {
    "count the missing sequence numbers" in {
      val ranges = new KeyMigration.SequenceRanges
      Seq(2L, 3L, 7L, 5L, 6L, 3L, 10L).foreach(ranges.add)
      ranges.count shouldBe 6
      ranges.missing(10, 3) shouldBe ((4L, Seq(1L, 4L, 8L)))
      ranges.missing(12, 10) shouldBe ((6L, Seq(1L, 4L, 8L, 9L, 11L, 12L)))
    }
  }
}
