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

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private[dynamodb] final case class VerifySettings(
    timeout: FiniteDuration = 300.seconds,
    segments: Int = 1,
    maxRate: Option[Double] = None,
    scanPageSize: Option[Int] = None
) {
  require(timeout >= 1.second, "--verify-timeout must be at least 1 second")
  require(segments >= 1, "--segments must be at least 1")
  require(maxRate.forall(_ > 0), "--max-rate must be positive")
  require(scanPageSize.forall(_ >= 1), "--scan-page-size must be at least 1")
}

private[dynamodb] final case class VerifyReport(
    tableName: String,
    indexName: String,
    scannedRows: Long,
    persistenceIds: Int,
    // rows that are not under the key they belong under, in the whole table
    toMove: Long,
    notMovable: Long,
    examples: Seq[String],
    // what is wrong with the definition of the index (keys, projection, status)
    indexProblems: Seq[String],
    // persistence ids whose index did not show the rows of the table, attribute for attribute, at the last look
    indexMismatch: Seq[String],
    // persistence ids whose index could not be queried at the last look, with the error (no permission, throttling)
    indexErrors: Seq[(String, String)],
    // what the timeout stopped, when it did
    stoppedBy: Option[String],
    // with --reverse: groups of persistence ids that share their keys in 1.3.0, whose writes collide after the rollback
    sharedKeyGroups: Seq[Seq[String]],
    usage: Usage,
    elapsedMillis: Long
) {

  /** When it was checked, every row of the table was under the key it belongs under and the index showed the rows of
    * the table; with --reverse, no two persistence ids share their keys of 1.3.0. It is an observation of that moment:
    * the index is eventually consistent, and a later read is not proven to see the same.
    */
  def verified: Boolean =
    toMove == 0 && notMovable == 0 && indexProblems.isEmpty && indexMismatch.isEmpty && indexErrors.isEmpty &&
    stoppedBy.isEmpty && sharedKeyGroups.isEmpty
}

/** The check that a key migration ends with (issue #1014).
  *
  * It always reads the whole table and the whole `persistence-id` index; it takes no `--persistence-id`. It first
  * checks the definition of the index (its keys and their types, that it projects what a replay and a snapshot load
  * read, and that it is ACTIVE). It passes only when every row is under the key that the plan gives it, and when, for
  * every persistence id, the index shows the same rows as the table: the same keys and the same values of the projected
  * attributes, compared as SHA-256 digests of an unambiguous encoding, and no row that the table no longer holds. With
  * --reverse, it also fails when two persistence ids share their keys of 1.3.0, because their writes collide after the
  * rollback. The persistence ids that differ are queried again until they match or `timeout` passes; the timeout bounds
  * the whole check, each request included.
  *
  * What it says is what it observed. The index is eventually consistent, so a read after the check is not proven to see
  * the same rows; the documentation says what to do before the application starts.
  */
private[dynamodb] object KeyVerification {

  def run(
      client: DynamoDbClient,
      table: MigrationTable,
      indexName: String,
      plan: KeyPlan,
      settings: VerifySettings,
      progress: String => Unit = _ => (),
      reverse: Boolean = false
  ): VerifyReport = {
    val started  = System.nanoTime()
    val deadline = started + settings.timeout.toNanos
    val usage    = new KeyMigration.UsageCounter
    val limiter  = settings.maxRate.map(new KeyMigration.RateLimiter(_))
    def emit(text: String): Unit =
      try progress(text)
      catch { case NonFatal(_) => () }

    val (projected, indexProblems) = indexDefinition(client, table, indexName, usage, deadline)

    // The table: the placement of every row, and the projected rows of each persistence id.
    var rows       = 0L
    var toMove     = 0L
    var notMovable = 0L
    val examples   = mutable.ArrayBuffer.empty[String]
    val expected   = mutable.HashMap.empty[String, Digest]
    val tableDone = scan(client, table.tableName, None, settings, deadline, usage, limiter) { item =>
      val id     = item(table.persistenceIdColumn).s()
      val seq    = item(table.sequenceNrColumn).n().toLong
      val stored = StoredKey(item(table.partitionKeyColumn).s(), item(table.sortKeyColumn).s())
      rows += 1
      plan.placement(id, seq, stored) match {
        case Placement.InPlace => ()
        case Placement.Move(target, _) =>
          toMove += 1
          if (examples.size < 20) examples += s"$id@$seq $stored should be under $target"
        case Placement.Unmovable(reason) =>
          notMovable += 1
          if (examples.size < 20) examples += s"$id@$seq $stored: $reason"
      }
      expected.update(id, expected.getOrElse(id, Digest.empty) + projected(item))
    }
    emit(s"phase=verify table rows=$rows ids=${expected.size}")

    // With --reverse, ids that share their keys of 1.3.0 collide when one of them writes after the rollback, whatever
    // their sequence numbers are now.
    val shared =
      if (!reverse) Vector.empty[Seq[String]]
      else
        expected.keys.toVector
          .flatMap(id => plan.sharedKeyGroup(id).map(_ -> id))
          .groupBy(_._1)
          .values
          .collect { case members if members.size > 1 => members.map(_._2).sorted }
          .toVector
          .sortBy(_.head)

    // The index, read once, and then the persistence ids that differ, one by one until they match.
    val observed = mutable.HashMap.empty[String, Digest]
    val indexDone =
      tableDone && indexProblems.isEmpty &&
      scan(client, table.tableName, Some(indexName), settings, deadline, usage, limiter) { item =>
        val id = item(table.persistenceIdColumn).s()
        observed.update(id, observed.getOrElse(id, Digest.empty) + projected(item))
      }
    def differs(id: String, digest: Option[Digest]): Boolean =
      digest.getOrElse(Digest.empty) != expected.getOrElse(id, Digest.empty)
    // An id that differs stays in the list until a query shows the rows of the table; the list at the timeout is the
    // result, whether an id was queried again or not.
    var mismatch =
      if (!indexDone) Vector.empty[String]
      else (expected.keySet ++ observed.keySet).filter(id => differs(id, observed.get(id))).toVector.sorted
    val errors = mutable.LinkedHashMap.empty[String, String]
    var round  = 0
    while (mismatch.nonEmpty && System.nanoTime() < deadline) {
      round += 1
      Thread.sleep(math.min(math.min(1000L, 100L * round), math.max(0L, (deadline - System.nanoTime()) / 1000000L)))
      mismatch = mismatch.filterNot { id =>
        System.nanoTime() < deadline && {
          query(client, table, indexName, id, deadline, usage, limiter, projected) match {
            case Right(Some(digest)) =>
              errors.remove(id)
              !differs(id, Some(digest))
            case Right(None) => false // the deadline passed while it read
            case Left(error) =>
              errors.update(id, error)
              false
          }
        }
      }
    }
    val failed = errors.filter { case (id, _) => mismatch.contains(id) }.toVector
    // A check that ends after the timeout does not pass, even when its last response matched.
    val stoppedBy =
      if (indexProblems.nonEmpty) None
      else if (!tableDone) Some(s"the scan of the table ${table.tableName}")
      else if (!indexDone) Some(s"the scan of the index $indexName")
      else if (System.nanoTime() >= deadline) Some("the check, which ended after the timeout")
      else None
    VerifyReport(
      tableName = table.tableName,
      indexName = indexName,
      scannedRows = rows,
      persistenceIds = (expected.keySet ++ observed.keySet).size,
      toMove = toMove,
      notMovable = notMovable,
      examples = examples.toVector,
      indexProblems = indexProblems,
      indexMismatch = mismatch.filterNot(id => failed.exists(_._1 == id)),
      indexErrors = failed,
      stoppedBy = stoppedBy,
      sharedKeyGroups = shared,
      usage = usage.snapshot,
      elapsedMillis = (System.nanoTime() - started) / 1000000L
    )
  }

  /** `legacyKeysSetting` is the setting key of this plugin and the command for the other plugin, for the line on
    * `legacy-keys-enabled`; none when the line does not apply.
    */
  def render(report: VerifyReport, direction: String, legacyKeysSetting: Option[(String, String)]): Seq[String] = {
    val lines = mutable.ArrayBuffer.empty[String]
    lines += s"[verify] key migration ($direction) of ${report.tableName}: scanned ${report.scannedRows} rows of " +
    s"${report.persistenceIds} persistence ids; ${report.toMove} rows to move, ${report.notMovable} rows not movable; " +
    s"${report.indexMismatch.size} persistence ids whose index ${report.indexName} does not match the table"
    report.examples.foreach(e => lines += s"[verify]   - $e")
    report.indexProblems.foreach(p => lines += s"[verify] the index ${report.indexName} cannot serve the plugin: $p")
    report.stoppedBy.foreach(what =>
      lines += s"[verify] the timeout stopped $what; raise --verify-timeout (it bounds the whole check, requests included)"
    )
    report.indexErrors.take(50).foreach { case (id, error) =>
      lines += s"[verify] the index could not be queried for $id: $error (check the permissions and the throttling)"
    }
    if (report.indexMismatch.nonEmpty)
      lines += s"[verify] index does not match: ${report.indexMismatch.take(50).mkString(", ")}"
    report.sharedKeyGroups.take(50).foreach { group =>
      lines += s"[verify] these persistence ids share their keys in 1.3.0, so their writes collide after the rollback: " +
      group.mkString(", ")
    }
    if (report.verified) {
      lines += "[verify] matched: when it was checked, every row was under the key it belongs under and the index " +
      "showed the rows of the table. The index is eventually consistent, so this does not prove that a later read sees " +
      "the same; read the documentation on starting the application"
      legacyKeysSetting.foreach { case (setting, other) =>
        lines += s"[verify] $setting = false: no row of this table was left under an old key. It is one table: set " +
        s"the setting of the other plugin only after $other --verify matches too"
      }
    } else
      lines += "[verify] not matched: do not start the application; run --execute for the rows to move, resolve the " +
      "rows that are not movable, fix the index, or run --verify again later"
    lines += s"[verify] requests: ${report.usage.totalRequests}, consumed capacity units: " +
    f"${report.usage.totalCapacityUnits}%.1f, elapsed: ${report.elapsedMillis} ms"
    report.usage.describe.foreach(text => lines += s"[verify]   $text")
    lines.toVector
  }

  /** The rows of one persistence id: their number, and two sums of 64-bit parts of the SHA-256 of each row. */
  private[migration] final case class Digest(count: Long, sumA: Long, sumB: Long) {
    def +(hash: (Long, Long)): Digest = Digest(count + 1, sumA + hash._1, sumB + hash._2)
  }

  private[migration] object Digest {
    val empty: Digest = Digest(0, 0, 0)
  }

  private def timeoutOf(deadline: Long): AwsRequestOverrideConfiguration =
    AwsRequestOverrideConfiguration
      .builder()
      .apiCallTimeout(java.time.Duration.ofNanos(math.max(1000000L, deadline - System.nanoTime())))
      .build()

  /** Checks that the index can serve the plugin, and gives the function that hashes the projected attributes of a row.
    */
  private def indexDefinition(
      client: DynamoDbClient,
      table: MigrationTable,
      indexName: String,
      usage: KeyMigration.UsageCounter,
      deadline: Long
  ): (Map[String, AttributeValue] => (Long, Long), Seq[String]) = {
    val response = client.describeTable(
      DescribeTableRequest.builder().tableName(table.tableName).overrideConfiguration(timeoutOf(deadline)).build()
    )
    usage.record("DescribeTable", Nil)
    val description = response.table()
    Option(description.globalSecondaryIndexes()).map(_.asScala).getOrElse(Nil).find(_.indexName() == indexName) match {
      case None =>
        ((_: Map[String, AttributeValue]) => (0L, 0L), Seq(s"the table ${table.tableName} has no index $indexName"))
      case Some(index) =>
        val types = Option(description.attributeDefinitions())
          .map(_.asScala.map(d => d.attributeName() -> d.attributeTypeAsString()).toMap)
          .getOrElse(Map.empty)
        val keySchema = index.keySchema().asScala.map(k => k.keyTypeAsString() -> k.attributeName()).toMap
        val problems  = Vector.newBuilder[String]
        if (
          keySchema.get("HASH") != Some(table.persistenceIdColumn) || types.get(table.persistenceIdColumn) != Some("S")
        )
          problems += s"its partition key must be ${table.persistenceIdColumn} (S), not ${keySchema.get("HASH")}"
        if (keySchema.get("RANGE") != Some(table.sequenceNrColumn) || types.get(table.sequenceNrColumn) != Some("N"))
          problems += s"its sort key must be ${table.sequenceNrColumn} (N), not ${keySchema.get("RANGE")}"
        if (index.indexStatusAsString() != "ACTIVE")
          problems += s"its status is ${index.indexStatusAsString()}, not ACTIVE"
        val keys       = Set(table.partitionKeyColumn, table.sortKeyColumn) ++ keySchema.values
        val projection = index.projection()
        val names: Option[Set[String]] = projection.projectionType() match {
          case ProjectionType.ALL       => None
          case ProjectionType.KEYS_ONLY => Some(keys)
          case _ => Some(keys ++ Option(projection.nonKeyAttributes()).map(_.asScala.toSet).getOrElse(Set.empty))
        }
        val missing = names.fold(Seq.empty[String])(n => table.indexAttributes.filterNot(n.contains))
        if (missing.nonEmpty)
          problems += s"it does not project ${missing.mkString(", ")}, which a replay or a snapshot load reads"
        (
          (item: Map[String, AttributeValue]) =>
            hash(names.fold(item)(n => item.filter { case (name, _) => n.contains(name) })),
          problems.result()
        )
    }
  }

  /** SHA-256 of an encoding that gives each value its type and length, so that different rows never encode alike. */
  private[migration] def hash(item: Map[String, AttributeValue]): (Long, Long) = {
    val out                         = new java.io.ByteArrayOutputStream()
    def bytes(b: Array[Byte]): Unit = { out.write(ByteBuffer.allocate(4).putInt(b.length).array()); out.write(b) }
    def text(t: String): Unit       = bytes(t.getBytes(StandardCharsets.UTF_8))
    def number(n: String): Unit     = text(BigDecimal(n).bigDecimal.stripTrailingZeros().toPlainString)
    def value(v: AttributeValue): Unit =
      if (v.s() != null) { out.write('S'); text(v.s()) }
      else if (v.n() != null) { out.write('N'); number(v.n()) }
      else if (v.b() != null) { out.write('B'); bytes(v.b().asByteArray()) }
      else if (v.bool() != null) { out.write('T'); out.write(if (v.bool().booleanValue()) 1 else 0) }
      else if (v.nul() != null) out.write('0')
      else if (v.hasL) { out.write('L'); text(v.l().size.toString); v.l().asScala.foreach(value) }
      else if (v.hasM) {
        out.write('M'); text(v.m().size.toString)
        v.m().asScala.toSeq.sortBy(_._1).foreach { case (k, e) => text(k); value(e) }
      } else if (v.hasSs) { out.write('s'); text(v.ss().size.toString); v.ss().asScala.sorted.foreach(text) }
      else if (v.hasNs) {
        out.write('n'); text(v.ns().size.toString)
        v.ns().asScala.map(n => BigDecimal(n).bigDecimal.stripTrailingZeros().toPlainString).sorted.foreach(text)
      } else if (v.hasBs) {
        out.write('b'); text(v.bs().size.toString)
        v.bs().asScala.map(_.asByteArray().map(x => "%02x".format(x & 0xff)).mkString).sorted.foreach(text)
      } else out.write('?')
    item.toSeq.sortBy(_._1).foreach { case (name, v) => text(name); value(v) }
    val d = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(out.toByteArray))
    (d.getLong, d.getLong)
  }

  private def scan(
      client: DynamoDbClient,
      tableName: String,
      indexName: Option[String],
      settings: VerifySettings,
      deadline: Long,
      usage: KeyMigration.UsageCounter,
      limiter: Option[KeyMigration.RateLimiter]
  )(f: Map[String, AttributeValue] => Unit): Boolean =
    (0 until settings.segments).forall { segment =>
      var startKey = Option.empty[java.util.Map[String, AttributeValue]]
      var more     = true
      var inTime   = true
      while (more && inTime) {
        if (!limiter.forall(_.acquireBefore(deadline)) || System.nanoTime() >= deadline) inTime = false
        else {
          val builder = ScanRequest
            .builder()
            .tableName(tableName)
            .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
            .overrideConfiguration(timeoutOf(deadline))
          indexName match {
            case Some(name) => builder.indexName(name) // an index can only be read eventually consistent
            case None       => builder.consistentRead(true)
          }
          if (settings.segments > 1) builder.segment(segment).totalSegments(settings.segments)
          settings.scanPageSize.foreach(limit => builder.limit(limit))
          startKey.foreach(builder.exclusiveStartKey)
          try {
            val response = client.scan(builder.build())
            usage.record(
              if (indexName.isDefined) "Scan (index)" else "Scan",
              Option(response.consumedCapacity()).toList
            )
            response.items().asScala.foreach(item => f(item.asScala.toMap))
            startKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
            more = startKey.nonEmpty
            // a page that arrives after the timeout does not count
            if (System.nanoTime() >= deadline) inTime = false
          } catch {
            case _: software.amazon.awssdk.core.exception.ApiCallTimeoutException => inTime = false
          }
        }
      }
      inTime
    }

  /** The digest of the rows that the index shows for one persistence id, folded page by page, or none when the deadline
    * passed before all were read.
    */
  private def query(
      client: DynamoDbClient,
      table: MigrationTable,
      indexName: String,
      persistenceId: String,
      deadline: Long,
      usage: KeyMigration.UsageCounter,
      limiter: Option[KeyMigration.RateLimiter],
      projected: Map[String, AttributeValue] => (Long, Long)
  ): Either[String, Option[Digest]] = {
    var digest   = Digest.empty
    var startKey = Option.empty[java.util.Map[String, AttributeValue]]
    var more     = true
    try {
      while (more) {
        if (System.nanoTime() >= deadline || !limiter.forall(_.acquireBefore(deadline))) return Right(None)
        val expression = new KeyMigration.Expression
        val condition =
          s"${expression.name(table.persistenceIdColumn)} = ${expression.value(AttributeValue.builder().s(persistenceId).build())}"
        val builder = QueryRequest
          .builder()
          .tableName(table.tableName)
          .indexName(indexName)
          .keyConditionExpression(condition)
          .expressionAttributeNames(expression.javaNames)
          .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
          .overrideConfiguration(timeoutOf(deadline))
        expression.javaValues.foreach(builder.expressionAttributeValues)
        startKey.foreach(builder.exclusiveStartKey)
        val response = client.query(builder.build())
        usage.record("Query", Option(response.consumedCapacity()).toList)
        response.items().asScala.foreach(item => digest = digest + projected(item.asScala.toMap))
        startKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
        more = startKey.nonEmpty
        if (System.nanoTime() >= deadline) return Right(None)
      }
      Right(Some(digest))
    } catch {
      case _: software.amazon.awssdk.core.exception.ApiCallTimeoutException => Right(None)
      case NonFatal(e) => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }

}
