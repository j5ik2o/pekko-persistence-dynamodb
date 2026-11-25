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
import scala.concurrent.duration._
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

  private def newTable(index: Boolean = false): String = {
    val name = s"keys-${tableCounter.incrementAndGet()}"
    val builder = CreateTableRequest
      .builder()
      .tableName(name)
      .attributeDefinitions(
        (Seq(
          AttributeDefinition.builder().attributeName("pkey").attributeType(ScalarAttributeType.S).build(),
          AttributeDefinition.builder().attributeName("skey").attributeType(ScalarAttributeType.S).build()
        ) ++ (if (index)
                Seq(
                  AttributeDefinition
                    .builder().attributeName("persistence-id").attributeType(ScalarAttributeType.S).build(),
                  AttributeDefinition
                    .builder().attributeName("sequence-nr").attributeType(ScalarAttributeType.N).build()
                )
              else Seq.empty)).asJava
      )
      .keySchema(
        KeySchemaElement.builder().attributeName("pkey").keyType(KeyType.HASH).build(),
        KeySchemaElement.builder().attributeName("skey").keyType(KeyType.RANGE).build()
      )
      .billingMode(BillingMode.PAY_PER_REQUEST)
    if (index)
      builder.globalSecondaryIndexes(
        GlobalSecondaryIndex
          .builder()
          .indexName("rows")
          .keySchema(
            KeySchemaElement.builder().attributeName("persistence-id").keyType(KeyType.HASH).build(),
            KeySchemaElement.builder().attributeName("sequence-nr").keyType(KeyType.RANGE).build()
          )
          .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
          .build()
      )
    client.createTable(builder.build())
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
      down: AtomicBoolean = new AtomicBoolean(false),
      query: QueryRequest => Option[QueryResponse] = _ => None,
      concurrent: AtomicInteger = new AtomicInteger(),
      maxConcurrent: AtomicInteger = new AtomicInteger(),
      scans: java.util.concurrent.ConcurrentLinkedQueue[ScanRequest] = new java.util.concurrent.ConcurrentLinkedQueue()
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
            args.headOption.foreach {
              case r: ScanRequest => scans.add(r)
              case _              =>
            }
            val faked = args.headOption.collect { case r: QueryRequest => query(r) }.flatten
            if (transaction) maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), math.max)
            val result =
              try faked.getOrElse(method.invoke(client, Option(args).getOrElse(Array.empty[AnyRef]): _*))
              catch { case e: InvocationTargetException => throw e.getCause }
              finally if (transaction) concurrent.decrementAndGet()
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
      // per move: one GetItem of the target key before the moves, one GetItem of the row, and one TransactWriteItems
      report.usage.requests("GetItem") shouldBe 4
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
      report.alreadyGone shouldBe 1
      report.moved shouldBe 0
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

    "keep to --max-rate" in {
      val table = newTable()
      (1 to 30).foreach(i => putRow(table, k(i), "A", i.toLong))
      val p       = plan((1 to 30).map(i => ("A", i.toLong) -> k(100 + i)): _*)
      val started = System.nanoTime()
      val report  = run(journal(table), p, execute = true, settings = _.copy(maxRate = Some(20.0), parallelism = 8))
      val seconds = (System.nanoTime() - started) / 1e9
      report.moved shouldBe 30
      seconds should be >= 1.3
    }

    "scan in the given segments, page by page" in {
      val table = newTable()
      (1 to 30).foreach(i => putRow(table, k(i), "A", i.toLong))
      val scans = new java.util.concurrent.ConcurrentLinkedQueue[ScanRequest]()
      val c     = hooked(scans = scans)
      val report = run(
        journal(table),
        plan((1 to 30).map(i => ("A", i.toLong) -> k(100 + i)): _*),
        execute = false,
        c = c,
        settings = _.copy(segments = 3, scanPageSize = Some(4))
      )
      report.scannedRows shouldBe 30
      report.candidates.map(_.sequenceNr).sorted shouldBe (1L to 30L)
      val requests = scans.asScala.toVector
      requests.map(_.segment().intValue()).distinct.sorted shouldBe Seq(0, 1, 2)
      requests.foreach(_.totalSegments().intValue() shouldBe 3)
      requests.count(_.exclusiveStartKey().isEmpty) shouldBe 3 // each segment starts once, the rest continue
      requests.size should be > 3
    }

    "move at most --parallelism rows at a time, whatever --segments is" in {
      val table = newTable()
      (1 to 20).foreach(i => putRow(table, k(i), "A", i.toLong))
      val maxConcurrent = new AtomicInteger()
      val c             = hooked(before = _ => Thread.sleep(20), maxConcurrent = maxConcurrent)
      val report = run(
        journal(table),
        plan((1 to 20).map(i => ("A", i.toLong) -> k(100 + i)): _*),
        execute = true,
        c = c,
        settings = _.copy(segments = 8, parallelism = 1)
      )
      report.moved shouldBe 20
      maxConcurrent.get() shouldBe 1
    }

    "give a shared target key to the row that the dry-run names, and block the other" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      putRow(table, k(3), "C", 1)
      // A and B need key 9. The move of A is slow, so B would win a race for the key.
      val p   = plan(("A", 1L) -> k(9), ("B", 1L) -> k(9), ("C", 1L) -> k(4))
      val dry = run(journal(table), p, execute = false)
      dry.blocked.map(b => (b.candidate.persistenceId, b.occupantPersistenceId)) shouldBe Seq("B" -> "A")
      val slowA = Proxy
        .newProxyInstance(
          classOf[DynamoDbClient].getClassLoader,
          Array(classOf[DynamoDbClient]),
          new InvocationHandler {
            override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
              args.headOption.foreach {
                case r: TransactWriteItemsRequest
                    if r
                      .transactItems().asScala.exists(i =>
                        Option(i.put()).exists(_.item().get("persistence-id").s() == "A")
                      ) =>
                  Thread.sleep(300)
                case _ =>
              }
              try method.invoke(client, Option(args).getOrElse(Array.empty[AnyRef]): _*)
              catch { case e: InvocationTargetException => throw e.getCause }
            }
          }
        )
        .asInstanceOf[DynamoDbClient]
      val report = run(journal(table), p, execute = true, c = slowA)
      report.moved shouldBe 2
      report.remaining.map(_._1.persistenceId) shouldBe Seq("B")
      owner(table, k(9)) shouldBe Some("A" -> 1L)
      owner(table, k(2)) shouldBe Some("B" -> 1L)
    }

    "find a shared target key also when another move frees it" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      putRow(table, k(3), "C", 1)
      val p   = plan(("A", 1L) -> k(3), ("B", 1L) -> k(3), ("C", 1L) -> k(4))
      val dry = run(journal(table), p, execute = false)
      dry.orderedMoves shouldBe 1
      dry.blocked.map(b => (b.candidate.persistenceId, b.occupantPersistenceId)) shouldBe Seq("B" -> "A")
      val report = run(journal(table), p, execute = true)
      report.moved shouldBe 2
      report.remaining.map(_._1.persistenceId) shouldBe Seq("B")
      owner(table, k(3)) shouldBe Some("A" -> 1L)
    }

    "list a row whose target key a swap fills as blocked" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      putRow(table, k(3), "C", 1)
      val p   = plan(("A", 1L) -> k(2), ("B", 1L) -> k(1), ("C", 1L) -> k(1))
      val dry = run(journal(table), p, execute = false)
      dry.cycles.map(_.map(_.persistenceId).sorted) shouldBe Seq(Seq("A", "B"))
      dry.orderedMoves shouldBe 0
      dry.blocked.map(b => (b.candidate.persistenceId, b.occupantPersistenceId)) shouldBe Seq("C" -> "B")
      val report = run(journal(table), p, execute = true)
      report.swapped shouldBe 2
      report.remaining.map(_._1.persistenceId) shouldBe Seq("C")
    }

    "count a swap whose response was lost as swapped" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      val lost   = new AtomicBoolean(false)
      val c      = hooked(after = _ => if (lost.compareAndSet(false, true)) throw SdkClientException.create("lost"))
      val report = run(journal(table), plan(("A", 1L) -> k(2), ("B", 1L) -> k(1)), execute = true, c = c)
      report.swapped shouldBe 2
      report.remaining shouldBe empty
      owner(table, k(1)) shouldBe Some("B" -> 1L)
      owner(table, k(2)) shouldBe Some("A" -> 1L)
    }

    "move the rest of a cycle that another writer broke" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      val c = hooked(before =
        index =>
          if (index == 1)
            client.deleteItem(
              DeleteItemRequest.builder().tableName(table).key(Map("pkey" -> s("p2"), "skey" -> s("s2")).asJava).build()
            )
      )
      val report = run(journal(table), plan(("A", 1L) -> k(2), ("B", 1L) -> k(1)), execute = true, c = c)
      report.moved shouldBe 1
      report.alreadyGone shouldBe 1
      report.remaining shouldBe empty
      allRows(table).map(r => (r("skey").s(), r("persistence-id").s())) shouldBe Vector("s2" -> "A")
    }

    "not swap rows that do not fit in one transaction" in {
      val table = newTable()
      val big = AttributeValue
        .builder().b(software.amazon.awssdk.core.SdkBytes.fromByteArray(Array.fill(390 * 1024)(1.toByte))).build()
      (1 to 11).foreach(i => putRow(table, k(i), s"P$i", 1, "message" -> big))
      val p      = plan((1 to 11).map(i => (s"P$i", 1L) -> k(i % 11 + 1)): _*)
      val report = run(journal(table), p, execute = true)
      report.swapped shouldBe 0
      report.remaining should have size 11
      report.remaining.head._2 should include("more than")
      (1 to 11).foreach(i => owner(table, k(i)) shouldBe Some(s"P$i" -> 1L))
    }

    "list a row that would exceed the item size of DynamoDB under its new key" in {
      val table = newTable()
      // 400 KB minus a little: the long target key makes the item too large.
      val message = AttributeValue
        .builder().b(software.amazon.awssdk.core.SdkBytes.fromByteArray(Array.fill(409000)(1.toByte))).build()
      putRow(table, k(1), "A", 1, "message" -> message)
      val target = StoredKey("p" * 2048, "s" * 1024)
      val report = run(journal(table), plan(("A", 1L) -> target), execute = true)
      report.moved shouldBe 0
      report.remaining should have size 1
      report.remaining.head._2 should include("rejected")
      owner(table, k(1)) shouldBe Some("A" -> 1L)
    }

    "wait until the index shows the moved rows, and report the ids that it does not show" in {
      val table   = newTable(index = true)
      val indexed = journal(table).copy(readIndex = Some("rows"))
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      val p     = plan(("A", 1L) -> k(11), ("B", 1L) -> k(12))
      val stale = new AtomicInteger()
      // The index first returns the old keys, as DynamoDB does until it has applied the moves.
      def staleRow(id: String, key: StoredKey) =
        QueryResponse.builder().items(Map("pkey" -> s(key.partitionKey), "skey" -> s(key.sortKey)).asJava).build()
      val c = hooked(query = r => {
        val id = r.expressionAttributeValues().values().asScala.head.s()
        if (id == "B" || stale.incrementAndGet() <= 2) Some(staleRow(id, if (id == "A") k(1) else k(2))) else None
      })
      val report = run(indexed, p, execute = true, c = c, settings = _.copy(indexWait = 3.seconds))
      report.moved shouldBe 2
      report.indexLagging shouldBe Seq("B")
      report.incomplete shouldBe true
      report.usage.requests("Query") should be > 3L
      val clean = newTable(index = true)
      putRow(clean, k(1), "A", 1)
      val ok = run(journal(clean).copy(readIndex = Some("rows")), plan(("A", 1L) -> k(11)), execute = true)
      ok.indexLagging shouldBe empty
      ok.incomplete shouldBe false
    }

    "report the shared-key groups of snapshots without counting the sequence numbers between them as missing" in {
      val table = newTable()
      putRow(table, k(1), "A", 100)
      putRow(table, k(2), "B", 100)
      val grouped = new KeyPlan {
        override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
          Placement.InPlace
        override def sharedKeyGroup(persistenceId: String): Option[String] = Some("g")
      }
      val report = run(snapshots(table).copy(sequential = false), grouped, execute = false)
      val lines  = KeyMigration.render(report, "test")
      lines.mkString("\n") should include("A (snapshots=1, highest=100)")
      lines.mkString("\n") should not include "missing"
      val journalLines = KeyMigration.render(run(journal(table), grouped, execute = false), "test").mkString("\n")
      journalLines should include("99 missing")
    }

    "say whether legacy-keys-enabled = false is safe" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "C", 1)
      def line(p: KeyPlan) =
        KeyMigration
          .render(run(journal(table), p, execute = false), "test", legacyKeysCheck = true).filter(
            _.contains("legacy-keys-enabled")
          )
      line(plan()) shouldBe Seq(
        "[dry-run] legacy-keys-enabled = false: safe; no row is left under an old key (0 to move, 0 blocked, 0 not movable)"
      )
      line(plan(("A", 1L) -> k(2))).head should include("not safe; 1 rows to move (1 of them blocked)")
    }

    "fail an --execute that leaves a selected row that cannot move (review of #1025, round 3)" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      val unmovable = new KeyPlan {
        override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
          Placement.Unmovable("1.3.0 cannot build a key for this persistence id")
        override def sharedKeyGroup(persistenceId: String): Option[String] = None
      }
      val report = run(journal(table), unmovable, execute = true)
      report.unmovable should have size 1
      report.incomplete shouldBe true
      KeyMigration.render(report, "test").mkString("\n") should include("not complete")
    }

    "not say that legacy-keys-enabled = false is safe when other ids, outside the selection, still have rows to move" in {
      val table = newTable()
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      val p      = plan(("B", 1L) -> k(12))
      val report = run(journal(table), p, execute = false, settings = _.copy(persistenceIds = Some(Set("A"))))
      report.candidates shouldBe empty
      report.noRowLeftBehind shouldBe false
      val lines = KeyMigration.render(report, "test", legacyKeysCheck = true).mkString("\n")
      lines should include("not safe")
      lines should include("outside the selection")
    }

    "check the index also for the rows that a stopped run moved, when it runs again" in {
      val table   = newTable(index = true)
      val indexed = journal(table).copy(readIndex = Some("rows"))
      putRow(table, k(1), "A", 1)
      putRow(table, k(2), "B", 1)
      val p = plan(("A", 1L) -> k(11), ("B", 1L) -> k(12))
      // The first run moves both rows and stops before the index catches up.
      val first = run(
        indexed,
        p,
        execute = true,
        c = hooked(query =
          r => Some(QueryResponse.builder().items(Map("pkey" -> s("p1"), "skey" -> s("s1")).asJava).build())
        ),
        settings = _.copy(indexWait = 1.second)
      )
      first.indexLagging should not be empty
      // The second run finds nothing to move; the index still lags for A, and the run must not report success.
      val stale = hooked(query = r => {
        val id = r.expressionAttributeValues().values().asScala.head.s()
        if (id == "A") Some(QueryResponse.builder().items(Map("pkey" -> s("p1"), "skey" -> s("s1")).asJava).build())
        else None
      })
      val again = run(indexed, p, execute = true, c = stale, settings = _.copy(indexWait = 1.second))
      again.candidates shouldBe empty
      again.indexLagging shouldBe Seq("A")
      again.incomplete shouldBe true
      run(indexed, p, execute = true).indexLagging shouldBe empty
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
