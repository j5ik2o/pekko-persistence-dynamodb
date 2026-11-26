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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.lang.reflect.{ InvocationHandler, InvocationTargetException, Method, Proxy }
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random

object KeyMigrationDryRunPropertySpec extends DockerAssignedPort

/** The dry-run against `--execute` on generated tables and plans (issue #1028, design
  * docs/design/key-migration-1028-output-and-arguments.md, sections 0.4 and 3).
  *
  * P1: in a run without E1 (DynamoDB rejects an item over 400 KB), E2 (the tool gives up after its last attempt), and
  * E3 (another writer), the dry-run and `--execute` give the same rows to move, to merge, and to leave. Lost responses
  * and failures that end before the last attempt happen in P1.
  *
  * P2: in a run with E1 or E2 (and no other writer), `--execute` stops short of the dry-run and goes nowhere else.
  *
  * E3 is not generated: it is outside the contract of `--writers-stopped`, and `keyMigrationWriters.qnt` covers it.
  */
final class KeyMigrationDryRunPropertySpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {

  override lazy val dynamoDBPort: Int = KeyMigrationDryRunPropertySpec.dynamoDBPort
  override lazy val controller        = KeyMigrationDryRunPropertySpec.controller

  private lazy val client: DynamoDbClient = DynamoDbClient
    .builder()
    .endpointOverride(URI.create(s"http://$dockerHost:$dynamoDBPort"))
    .region(Region.AP_NORTHEAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
    .build()

  private type Item = Map[String, AttributeValue]

  private val Cases = 200

  private val tableCounter = new AtomicInteger()

  private def newTable(): String = {
    val name = s"dry-${tableCounter.incrementAndGet()}"
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

  private def s(text: String): AttributeValue   = AttributeValue.builder().s(text).build()
  private def n(value: Long): AttributeValue    = AttributeValue.builder().n(value.toString).build()
  private def b(value: Boolean): AttributeValue = AttributeValue.builder().bool(value).build()

  private val keys: Vector[StoredKey] = (1 to 8).map(i => StoredKey(s"p$i", s"s$i")).toVector
  private val lrows: Vector[(String, Long)] = for {
    id <- Vector("A", "B", "C")
    seq <- Vector(1L, 2L)
  } yield (id, seq)

  private def keyOf(item: Item): StoredKey = StoredKey(item("pkey").s(), item("skey").s())
  private def keyMap(key: StoredKey): Item = Map("pkey" -> s(key.partitionKey), "skey" -> s(key.sortKey))

  // ---------------------------------------------------------------------------------------------------------------
  // The generated case
  // ---------------------------------------------------------------------------------------------------------------

  private sealed trait Fault
  private case object NoFault extends Fault
  private case object LostResponse extends Fault // the first transaction is applied and its response lost
  private case object FailsOnce extends Fault    // the first transaction fails before it is sent
  private case object Rejected extends Fault     // E1: DynamoDB rejects the first transaction (400 KB)
  private case object FailsAlways extends Fault  // E2: every transaction fails before it is sent; the tool gives up

  private final case class Case(
      seed: Long,
      snapshot: Boolean,
      items: Map[StoredKey, Item],
      plan: KeyPlan,
      selection: Option[Set[String]],
      planDescription: String,
      // the target depends on the persistence id and the sequence number only, as in ResolverKeyPlan
      byRow: Boolean
  ) {
    def table(name: String): MigrationTable =
      if (snapshot)
        MigrationTable(
          name,
          "pkey",
          "skey",
          "persistence-id",
          "sequence-nr",
          Seq.empty,
          DuplicateRule.LatestCreated("created")
        )
      else
        MigrationTable(
          name,
          "pkey",
          "skey",
          "persistence-id",
          "sequence-nr",
          Seq("deleted"),
          DuplicateRule.DeletedFlag("deleted")
        )
    override def toString: String =
      s"Case(seed=$seed, ${if (snapshot) "snapshot" else "journal"}, selection=$selection, plan=$planDescription, " +
      s"items=${items.toSeq.sortBy(_._1.partitionKey).map { case (k, i) => s"$k=${describe(i)}" }.mkString("; ")})"
  }

  private def describe(item: Item): String =
    (item - "pkey" - "skey").toSeq
      .sortBy(_._1).map { case (k, v) => s"$k:${Option(v.s()).orElse(Option(v.n())).getOrElse(v.toString)}" }.mkString(
        ","
      )

  /** java.util.Random gives nearly the same first values for nearby seeds, so the seed is mixed first. */
  private def random(seed: Long): Random = new Random(new java.util.SplittableRandom(seed).nextLong())

  private def generate(seed: Long): Case = {
    val rnd      = random(seed)
    val snapshot = rnd.nextBoolean()
    val items = keys.flatMap { key =>
      val r = rnd.nextDouble()
      if (r < 0.20) None
      else if (r < 0.85) {
        val (id, seq) = lrows(rnd.nextInt(lrows.size))
        val variant   = rnd.nextInt(2)
        val rule =
          if (snapshot) "created" -> n(100L + rnd.nextInt(2))
          else "deleted"          -> b(rnd.nextBoolean())
        Some(
          key -> (keyMap(key) ++ Map(
            "persistence-id" -> s(id),
            "sequence-nr"    -> n(seq),
            "message"        -> s(s"$id-$seq-v$variant"),
            rule
          ))
        )
      } else Some(key -> (keyMap(key) + ("message" -> s("not a row"))))
    }.toMap
    def choose(): Placement = {
      val r = rnd.nextDouble()
      if (r < 0.25) Placement.InPlace
      else if (r < 0.85) Placement.Move(keys(rnd.nextInt(keys.size)), KeyForm.Release130)
      else Placement.Unmovable("generated: not movable")
    }
    def resolve(choice: Placement, stored: StoredKey): Placement = choice match {
      case Placement.Move(target, _) if target == stored => Placement.InPlace
      case other                                         => other
    }
    // Mostly per (persistence id, sequence number), as ResolverKeyPlan; sometimes per stored key.
    val byRow = rnd.nextDouble() < 0.8
    val (plan, description) =
      if (byRow) {
        val choices = lrows.map(l => l -> choose()).toMap
        (
          new KeyPlan {
            override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
              choices.get(persistenceId -> sequenceNr).fold[Placement](Placement.InPlace)(resolve(_, stored))
            override def sharedKeyGroup(persistenceId: String): Option[String] = None
          },
          s"by row ${choices.toSeq.sortBy(_._1).mkString(" ")}"
        )
      } else {
        val choices = keys.map(k => k -> choose()).toMap
        (
          new KeyPlan {
            override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
              choices.get(stored).fold[Placement](Placement.InPlace)(resolve(_, stored))
            override def sharedKeyGroup(persistenceId: String): Option[String] = None
          },
          s"by key ${choices.toSeq.sortBy(_._1.partitionKey).mkString(" ")}"
        )
      }
    val selection =
      if (rnd.nextDouble() < 0.7) None
      else Some(Set("A", "B", "C").filter(_ => rnd.nextBoolean())).filter(_.nonEmpty).orElse(Some(Set("A")))
    Case(seed, snapshot, items, plan, selection, description, byRow)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Running a case
  // ---------------------------------------------------------------------------------------------------------------

  /** A client that applies `faults` to the transactions of each candidate, found by the key that its Delete removes. */
  private def faulty(faults: Map[StoredKey, Fault]): DynamoDbClient = {
    val sent = mutable.HashMap.empty[StoredKey, Int]
    Proxy
      .newProxyInstance(
        classOf[DynamoDbClient].getClassLoader,
        Array(classOf[DynamoDbClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
            def call(): AnyRef =
              try method.invoke(client, Option(args).getOrElse(Array.empty[AnyRef]): _*)
              catch { case e: InvocationTargetException => throw e.getCause }
            args.headOption match {
              case Some(r: TransactWriteItemsRequest) =>
                val source = r
                  .transactItems().asScala.flatMap(i => Option(i.delete())).headOption.map(d =>
                    StoredKey(d.key().get("pkey").s(), d.key().get("skey").s())
                  )
                val fault = source.flatMap(faults.get).getOrElse(NoFault)
                val index = source.fold(0)(k => sent.synchronized { val i = sent.getOrElse(k, 0); sent(k) = i + 1; i })
                fault match {
                  case LostResponse if index == 0 =>
                    call(); throw SdkClientException.create("generated: the response was lost")
                  case FailsOnce if index == 0 => throw SdkClientException.create("generated: a transient failure")
                  case Rejected if index == 0 =>
                    throw DynamoDbException
                      .builder()
                      .message("generated: Item size has exceeded the maximum allowed size")
                      .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationException").build())
                      .build()
                  case FailsAlways => throw SdkClientException.create("generated: the connection is down")
                  case _           => call()
                }
              case _ => call()
            }
          }
        }
      )
      .asInstanceOf[DynamoDbClient]
  }

  private def load(name: String, items: Map[StoredKey, Item]): Unit =
    items.values.foreach { item =>
      client.putItem(PutItemRequest.builder().tableName(name).item(item.asJava).build())
      ()
    }

  private def tableOf(name: String): Map[StoredKey, Item] =
    client
      .scan(ScanRequest.builder().tableName(name).consistentRead(true).build())
      .items()
      .asScala
      .map(_.asScala.toMap)
      .map(i => keyOf(i) -> i)
      .toMap

  private final case class DrySets(
      moved: Set[Candidate],
      merged: Set[Candidate],
      remaining: Set[Candidate]
  )

  private final case class ExecSets(moved: Set[Candidate], merged: Set[Candidate], remaining: Set[Candidate])

  /** The sets of a dry-run, with the checks that its sections partition the candidates. */
  private def drySets(c: Case, dry: MigrationReport): DrySets = {
    val candidates = dry.candidates.toSet
    val sections = Seq(
      "duplicates"  -> dry.duplicates,
      "conflicting" -> dry.conflictingCopies.map(_._1),
      "cycles"      -> dry.cycles.flatten,
      "blocked"     -> dry.blocked.map(_.candidate),
      "waiting"     -> dry.waiting
    )
    val listed = sections.flatMap(_._2)
    withClue(s"$c\n${KeyMigration.render(dry, "dry").mkString("\n")}\n") {
      dry.candidates.distinct should have size dry.candidates.size.toLong
      listed.distinct should have size listed.size.toLong // the sections are disjoint
      listed.toSet.subsetOf(candidates) shouldBe true
      val moved   = candidates -- listed
      val sources = dry.candidates.map(_.from).toSet
      (dry.directMoves + dry.orderedMoves) shouldBe moved.size
      dry.directMoves shouldBe moved.count(m => !sources.contains(m.to))
      DrySets(moved, dry.duplicates.toSet, candidates -- moved -- dry.duplicates)
    }
  }

  private def execSets(c: Case, exec: MigrationReport): ExecSets = {
    val candidates = exec.candidates.toSet
    val merged     = exec.mergedRows.map(_._1)
    val remaining  = exec.remaining.map(_._1)
    withClue(s"$c\n${KeyMigration.render(exec, "exec").mkString("\n")}\n") {
      merged.distinct should have size merged.size.toLong
      remaining.distinct should have size remaining.size.toLong
      (merged.toSet intersect remaining.toSet) shouldBe empty
      (merged.toSet ++ remaining.toSet).subsetOf(candidates) shouldBe true
      val moved = candidates -- merged -- remaining
      exec.moved shouldBe moved.size
      exec.merged shouldBe merged.size
      exec.alreadyGone shouldBe 0
      exec.writerSigns shouldBe empty
      ExecSets(moved, merged.toSet, remaining.toSet)
    }
  }

  /** The table that `exec` says it left: moved rows under their target key, merged copies removed, the rest as before.
    */
  private def expectedTable(c: Case, before: Map[StoredKey, Item], exec: ExecSets): Map[StoredKey, Item] = {
    val left  = (exec.moved ++ exec.merged).map(_.from)
    var table = before -- left
    exec.moved.foreach(m => table += m.to -> (before(m.from) ++ keyMap(m.to)))
    exec.merged.foreach { m =>
      val source = before(m.from)
      val target = table(m.to)
      table += m.to -> (
        if (c.snapshot) {
          def created(i: Item) = BigDecimal(i("created").n())
          if (created(source) > created(target)) source ++ keyMap(m.to) else target
        } else {
          def deleted(i: Item) = i.get("deleted").exists(v => Option(v.bool()).exists(_.booleanValue()))
          if (deleted(source) && !deleted(target)) target + ("deleted" -> b(true)) else target
        }
      )
    }
    table
  }

  private def runCase(
      c: Case,
      faults: Map[StoredKey, Fault]
  ): (MigrationReport, MigrationReport, Map[StoredKey, Item], Map[StoredKey, Item]) = {
    val name = newTable()
    load(name, c.items)
    val before = tableOf(name)
    val dry =
      KeyMigration.run(client, c.table(name), c.plan, MigrationSettings(execute = false, persistenceIds = c.selection))
    tableOf(name) shouldBe before // a dry-run writes nothing
    val maxAttempts = if (faults.values.exists(_ == FailsAlways)) 2 else 10
    val exec = KeyMigration.run(
      faulty(faults),
      c.table(name),
      c.plan,
      MigrationSettings(execute = true, persistenceIds = c.selection, maxAttempts = maxAttempts)
    )
    (dry, exec, before, tableOf(name))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Witnesses: the shapes that the cases must reach
  // ---------------------------------------------------------------------------------------------------------------

  private val seen                                   = mutable.HashMap.empty[String, Int].withDefaultValue(0)
  private def saw(name: String, cond: Boolean): Unit = if (cond) seen(name) += 1

  private def witness(c: Case, dry: MigrationReport, d: DrySets, e: ExecSets): Unit = {
    val sources = dry.candidates.map(_.from).toSet
    saw("direct", dry.directMoves > 0)
    saw("after", dry.orderedMoves > 0)
    saw("merge journal", !c.snapshot && e.merged.nonEmpty)
    saw("merge snapshot", c.snapshot && e.merged.nonEmpty)

    saw("conflicting copies", dry.conflictingCopies.nonEmpty)
    saw("cycle", dry.cycles.nonEmpty)
    saw("blocked by a row", dry.blocked.exists(b => !b.byItem && !b.outsideSelection))
    saw("blocked by an item", dry.blocked.exists(_.byItem))
    saw("blocked outside the selection", dry.blocked.exists(_.outsideSelection))
    saw("waiting", dry.waiting.nonEmpty)
    saw("not movable", dry.unmovable.exists(!_.notARow))
    val byTarget = dry.candidates.groupBy(_.to)
    saw(
      "2.2: a winner that does not reach the target its copy needs",
      byTarget.values.exists { group =>
        val winner = group.minBy(g => (g.from.partitionKey, g.from.sortKey))
        group.exists(o =>
          o != winner && o.persistenceId == winner.persistenceId && o.sequenceNr == winner.sequenceNr
        ) &&
        !e.moved.contains(winner)
      }
    )
    saw(
      "2.3: a row that waits for a copy with other contents",
      dry.candidates.exists(a => dry.conflictingCopies.exists(_._1.from == a.to))
    )
    saw("three candidates need one target", byTarget.values.exists(_.size >= 3))
    saw("ordered move through a source", d.moved.exists(m => sources.contains(m.to)))
  }

  "the dry-run" should {
    "give the rows that --execute moves, merges, and leaves, without E1, E2, or E3 (P1, #1028)" in {
      (0 until Cases).foreach { i =>
        val c   = generate(0x10280000L + i)
        val rnd = random(c.seed ^ 0x5eedL)
        val faults = keys
          .map(k =>
            k -> { val r = rnd.nextDouble(); if (r < 0.1) LostResponse else if (r < 0.2) FailsOnce else NoFault }
          ).toMap
        val (dry, exec, before, after) = runCase(c, faults)
        val d                          = drySets(c, dry)
        val e                          = execSets(c, exec)
        withClue(
          s"$c\n${KeyMigration.render(dry, "dry").mkString("\n")}\n${KeyMigration.render(exec, "exec").mkString("\n")}\n"
        ) {
          d.moved shouldBe e.moved
          d.merged shouldBe e.merged
          d.remaining shouldBe e.remaining
          dry.unmovable.toSet shouldBe exec.unmovable.toSet
          after shouldBe expectedTable(c, before, e)
        }
        witness(c, dry, d, e)
        saw("lost response", faults.exists { case (k, f) => f == LostResponse && e.moved.exists(_.from == k) })
      }
      info(seen.toSeq.sortBy(_._1).map { case (k, v) => s"$k: $v" }.mkString("; "))
      val needed = Seq(
        "direct",
        "after",
        "merge journal",
        "merge snapshot",
        "conflicting copies",
        "cycle",
        "blocked by a row",
        "blocked by an item",
        "blocked outside the selection",
        "waiting",
        "not movable",
        "2.2: a winner that does not reach the target its copy needs",
        "2.3: a row that waits for a copy with other contents",
        "three candidates need one target",
        "ordered move through a source",
        "lost response"
      )
      needed.foreach(w => withClue(w)(seen(w) should be >= 3))
    }

    "stop short of the dry-run and go nowhere else with E1 or E2 (P2, #1028)" in {
      val shortfall = mutable.HashMap.empty[String, Int].withDefaultValue(0)
      (0 until Cases).foreach { i =>
        val c   = generate(0x10281000L + i)
        val rnd = random(c.seed ^ 0xfa17L)
        // the candidates are those of a dry-run of the same table
        val name0 = newTable()
        load(name0, c.items)
        val candidates =
          KeyMigration
            .run(
              client,
              c.table(name0),
              c.plan,
              MigrationSettings(execute = false, persistenceIds = c.selection)
            ).candidates
        if (candidates.nonEmpty) {
          val kind   = if (rnd.nextBoolean()) Rejected else FailsAlways
          val chosen = rnd.shuffle(candidates).take(1 + rnd.nextInt(3))
          val (d, e) = checkP2(c, chosen.map(_.from -> kind).toMap)
          val plan   = if (c.byRow) "by row" else "by key"
          if ((e.moved ++ e.merged).size < (d.moved ++ d.merged).size) shortfall(s"$kind $plan") += 1
          if (!e.moved.subsetOf(d.moved) || !e.merged.subsetOf(d.merged)) shortfall(s"swap $plan") += 1
        }
      }
      info(shortfall.toSeq.sortBy(_._1).map { case (k, v) => s"$k: $v" }.mkString("; "))
      Seq("Rejected by row", "FailsAlways by row").foreach(k => withClue(k)(shortfall(k) should be >= 3))
    }

    "only swap a move and a merge, and go nowhere else, when a plan per stored key loses a row (P2, seed 271061123)" in {
      // Three copies of C@2: those under keys 2 and 4 need key 8, the one under key 6 needs key 4. The dry-run moves
      // key 2, merges key 4 into it, and moves key 6 to key 4. When the tool gives up on key 4, key 6 merges into the
      // copy that stays under key 4. ResolverKeyPlan never gives such a plan (ResolverKeyPlanPropertySpec).
      def row(key: Int, id: String, seq: Long, deleted: Boolean): (StoredKey, Item) =
        keys(key - 1) -> (keyMap(keys(key - 1)) ++ Map(
          "persistence-id" -> s(id),
          "sequence-nr"    -> n(seq),
          "message"        -> s(s"$id-$seq-v0"),
          "deleted"        -> b(deleted)
        ))
      def notARow(key: Int): (StoredKey, Item) =
        keys(key - 1) -> (keyMap(keys(key - 1)) + ("message" -> s("not a row")))
      val items = Map(
        notARow(1),
        row(2, "C", 2, deleted = true),
        notARow(3),
        row(4, "C", 2, deleted = false),
        row(6, "C", 2, deleted = false),
        row(7, "C", 1, deleted = true)
      )
      val targets = Map(1 -> 8, 2 -> 8, 3 -> 8, 4 -> 8, 5 -> 1, 6 -> 4, 7 -> 4, 8 -> 1).map { case (f, t) =>
        keys(f - 1) -> keys(t - 1)
      }
      val plan = new KeyPlan {
        override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
          targets.get(stored).fold[Placement](Placement.InPlace)(Placement.Move(_, KeyForm.Release130))
        override def sharedKeyGroup(persistenceId: String): Option[String] = None
      }
      val c        = Case(271061123L, snapshot = false, items, plan, None, "seed 271061123", byRow = false)
      val (d, e)   = checkP2(c, Map(keys(3) -> FailsAlways))
      val fromKey6 = d.moved.find(_.from == keys(5)).getOrElse(fail("the dry-run moves the copy under key 6"))
      e.merged should contain(fromKey6)
    }
  }

  /** P2 on one case: the checks for every plan, and for a plan by row (as ResolverKeyPlan) the inclusions of the moves
    * and the merges one by one. A plan per stored key can turn a move into a merge (design section 0.4).
    */
  private def checkP2(c: Case, faults: Map[StoredKey, Fault]): (DrySets, ExecSets) = {
    val (dry, exec, before, after) = runCase(c, faults)
    val d                          = drySets(c, dry)
    val e                          = execSets(c, exec)
    val identity                   = c.table("identity")
    def rows(table: Map[StoredKey, Item]): Map[(String, Long), Set[StoredKey]] =
      table.toSeq.flatMap { case (key, item) => identity.rowIdentity(item).map(_ -> key) }.groupBy(_._1).map {
        case (row, keys) => row -> keys.map(_._2).toSet
      }
    val predicted = expectedTable(c, before, ExecSets(d.moved, d.merged, d.remaining))
    withClue(
      s"${faults.toSeq.mkString(", ")}\n$c\n${KeyMigration.render(dry, "dry").mkString("\n")}\n${KeyMigration.render(exec, "exec").mkString("\n")}\n"
    ) {
      (e.moved ++ e.merged).subsetOf(d.moved ++ d.merged) shouldBe true
      d.remaining.subsetOf(e.remaining) shouldBe true
      ((d.moved ++ d.merged) -- (e.moved ++ e.merged)).subsetOf(e.remaining) shouldBe true
      dry.unmovable.toSet shouldBe exec.unmovable.toSet
      after shouldBe expectedTable(c, before, e)
      // no row is lost, and each copy is under a key it was under or that the dry-run gives it
      rows(after).keySet shouldBe rows(before).keySet
      rows(after).foreach { case (row, keys) =>
        withClue(row)(keys.subsetOf(rows(before)(row) ++ rows(predicted).getOrElse(row, Set.empty)) shouldBe true)
      }
      if (c.byRow) {
        e.moved.subsetOf(d.moved) shouldBe true
        e.merged.subsetOf(d.merged) shouldBe true
      }
    }
    (d, e)
  }
}
