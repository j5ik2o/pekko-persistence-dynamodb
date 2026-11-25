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
import scala.util.hashing.MurmurHash3

private[dynamodb] final case class VerifySettings(
    timeout: FiniteDuration = 300.seconds,
    segments: Int = 1,
    maxRate: Option[Double] = None,
    scanPageSize: Option[Int] = None
) {
  require(timeout >= Duration.Zero, "--verify-timeout must not be negative")
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
    // persistence ids whose index did not show the rows of the table, attribute for attribute, at the last look
    indexMismatch: Seq[String],
    usage: Usage,
    elapsedMillis: Long
) {

  /** Every row of the table is under the key it belongs under, and the index shows exactly the rows of the table. */
  def verified: Boolean = toMove == 0 && notMovable == 0 && indexMismatch.isEmpty
}

/** The one check that says whether the application can start after a key migration (issue #1014).
  *
  * It always reads the whole table and the whole `persistence-id` index; it takes no `--persistence-id`. It passes only
  * when every row is under the key that the plan gives it, and when, for every persistence id, the index shows the same
  * rows as the table: the same keys and the same values of the projected attributes (such as `deleted`), and no row
  * that the table no longer holds. The index is updated asynchronously, so the persistence ids that differ are queried
  * again until they match or `timeout` passes; the timeout bounds the whole check, including the waits for
  * `--max-rate`.
  */
private[dynamodb] object KeyVerification {

  def run(
      client: DynamoDbClient,
      table: MigrationTable,
      indexName: String,
      plan: KeyPlan,
      settings: VerifySettings,
      progress: String => Unit = _ => ()
  ): VerifyReport = {
    val started  = System.nanoTime()
    val deadline = started + settings.timeout.toNanos
    val usage    = new KeyMigration.UsageCounter
    val limiter  = settings.maxRate.map(new KeyMigration.RateLimiter(_))
    def emit(text: String): Unit =
      try progress(text)
      catch { case NonFatal(_) => () }

    val projected = projectedAttributes(client, table, indexName, usage)

    // The table: the placement of every row, and the projected rows of each persistence id.
    var rows       = 0L
    var toMove     = 0L
    var notMovable = 0L
    val examples   = mutable.ArrayBuffer.empty[String]
    val expected   = mutable.HashMap.empty[String, Digest]
    scan(client, table.tableName, None, settings, usage, limiter) { item =>
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

    // The index, read once, and then the persistence ids that differ, one by one until they match.
    val observed = mutable.HashMap.empty[String, Digest]
    scan(client, table.tableName, Some(indexName), settings, usage, limiter) { item =>
      val id = item(table.persistenceIdColumn).s()
      observed.update(id, observed.getOrElse(id, Digest.empty) + projected(item))
    }
    def differs(id: String, digest: Option[Digest]): Boolean =
      digest.getOrElse(Digest.empty) != expected.getOrElse(id, Digest.empty)
    // An id that differs stays in the list until a query shows the rows of the table; the list at the timeout is the
    // result, whether an id was queried again or not.
    var mismatch = (expected.keySet ++ observed.keySet).filter(id => differs(id, observed.get(id))).toVector.sorted
    var round    = 0
    while (mismatch.nonEmpty && System.nanoTime() < deadline) {
      round += 1
      Thread.sleep(math.min(math.min(1000L, 100L * round), math.max(0L, (deadline - System.nanoTime()) / 1000000L)))
      mismatch = mismatch.filterNot { id =>
        System.nanoTime() < deadline &&
        query(client, table, indexName, id, deadline, usage, limiter)
          .exists(rows => !differs(id, Some(rows.foldLeft(Digest.empty)(_ + projected(_)))))
      }
    }
    VerifyReport(
      tableName = table.tableName,
      indexName = indexName,
      scannedRows = rows,
      persistenceIds = (expected.keySet ++ observed.keySet).size,
      toMove = toMove,
      notMovable = notMovable,
      examples = examples.toVector,
      indexMismatch = mismatch,
      usage = usage.snapshot,
      elapsedMillis = (System.nanoTime() - started) / 1000000L
    )
  }

  def render(report: VerifyReport, direction: String, legacyKeysLine: Boolean): Seq[String] = {
    val lines = mutable.ArrayBuffer.empty[String]
    lines += s"[verify] key migration ($direction) of ${report.tableName}: scanned ${report.scannedRows} rows of " +
    s"${report.persistenceIds} persistence ids; ${report.toMove} rows to move, ${report.notMovable} rows not movable; " +
    s"${report.indexMismatch.size} persistence ids whose index ${report.indexName} does not match the table"
    report.examples.foreach(e => lines += s"[verify]   - $e")
    if (report.indexMismatch.nonEmpty)
      lines += s"[verify] index does not match: ${report.indexMismatch.take(50).mkString(", ")}"
    if (report.verified) {
      lines += "[verify] verified: every row is under the key it belongs under, and the index matches the table; " +
      "the application can start"
      if (legacyKeysLine) lines += "[verify] legacy-keys-enabled = false is safe: no row is left under an old key"
    } else
      lines += "[verify] not verified: do not start the application; run --execute for the rows to move, resolve the " +
      "rows that are not movable, or run --verify again later for the index"
    lines += s"[verify] requests: ${report.usage.totalRequests}, consumed capacity units: " +
    f"${report.usage.totalCapacityUnits}%.1f, elapsed: ${report.elapsedMillis} ms"
    report.usage.describe.foreach(text => lines += s"[verify]   $text")
    lines.toVector
  }

  /** The rows of one persistence id, as a count and a sum of 64-bit hashes of their projected attributes. */
  private[migration] final case class Digest(count: Long, sum: Long) {
    def +(hash: Long): Digest = Digest(count + 1, sum + hash)
  }

  private[migration] object Digest {
    val empty: Digest = Digest(0, 0)
  }

  /** The attributes that the index projects, as a function that hashes them from a table or index item. */
  private def projectedAttributes(
      client: DynamoDbClient,
      table: MigrationTable,
      indexName: String,
      usage: KeyMigration.UsageCounter
  ): Map[String, AttributeValue] => Long = {
    val response = client.describeTable(DescribeTableRequest.builder().tableName(table.tableName).build())
    usage.record("DescribeTable", Nil)
    val index = Option(response.table().globalSecondaryIndexes())
      .map(_.asScala).getOrElse(Nil)
      .find(_.indexName() == indexName)
      .getOrElse(throw new IllegalArgumentException(s"the table ${table.tableName} has no index $indexName"))
    val keys =
      (Seq(table.partitionKeyColumn, table.sortKeyColumn) ++ index.keySchema().asScala.map(_.attributeName())).toSet
    val projection = index.projection()
    val names: Option[Set[String]] = projection.projectionType() match {
      case ProjectionType.ALL       => None
      case ProjectionType.KEYS_ONLY => Some(keys)
      case _ => Some(keys ++ Option(projection.nonKeyAttributes()).map(_.asScala.toSet).getOrElse(Set.empty))
    }
    item => hash(names.fold(item)(n => item.filter { case (name, _) => n.contains(name) }))
  }

  private def hash(item: Map[String, AttributeValue]): Long = {
    val text = item.toSeq.sortBy(_._1).map { case (name, value) => s"$name=${canonical(value)}" }.mkString("\u0001")
    (MurmurHash3.stringHash(text, 0x9747b28c).toLong << 32) ^
    (MurmurHash3.stringHash(text, 0x5bd1e995).toLong & 0xffffffffL)
  }

  /** A text that is the same for equal values. */
  private def canonical(value: AttributeValue): String =
    if (value.s() != null) "S:" + value.s()
    else if (value.n() != null) "N:" + BigDecimal(value.n()).bigDecimal.stripTrailingZeros().toPlainString
    else if (value.b() != null) "B:" + value.b().asByteArray().map(b => "%02x".format(b & 0xff)).mkString
    else if (value.bool() != null) "BOOL:" + value.bool()
    else if (value.nul() != null) "NULL"
    else if (value.hasL) value.l().asScala.map(canonical).mkString("L:[", ",", "]")
    else if (value.hasM)
      value.m().asScala.toSeq.sortBy(_._1).map { case (k, v) => s"$k=${canonical(v)}" }.mkString("M:{", ",", "}")
    else if (value.hasSs) value.ss().asScala.sorted.mkString("SS:[", ",", "]")
    else if (value.hasNs)
      value
        .ns().asScala.map(n => BigDecimal(n).bigDecimal.stripTrailingZeros().toPlainString).sorted.mkString(
          "NS:[",
          ",",
          "]"
        )
    else if (value.hasBs)
      value
        .bs().asScala.map(_.asByteArray().map(b => "%02x".format(b & 0xff)).mkString).sorted.mkString("BS:[", ",", "]")
    else "?"

  private def scan(
      client: DynamoDbClient,
      tableName: String,
      indexName: Option[String],
      settings: VerifySettings,
      usage: KeyMigration.UsageCounter,
      limiter: Option[KeyMigration.RateLimiter]
  )(f: Map[String, AttributeValue] => Unit): Unit =
    (0 until settings.segments).foreach { segment =>
      var startKey = Option.empty[java.util.Map[String, AttributeValue]]
      var more     = true
      while (more) {
        limiter.foreach(_.acquire())
        val builder = ScanRequest.builder().tableName(tableName).returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
        indexName match {
          case Some(name) => builder.indexName(name) // an index can only be read eventually consistent
          case None       => builder.consistentRead(true)
        }
        if (settings.segments > 1) builder.segment(segment).totalSegments(settings.segments)
        settings.scanPageSize.foreach(limit => builder.limit(limit))
        startKey.foreach(builder.exclusiveStartKey)
        val response = client.scan(builder.build())
        usage.record(if (indexName.isDefined) "Scan (index)" else "Scan", Option(response.consumedCapacity()).toList)
        response.items().asScala.foreach(item => f(item.asScala.toMap))
        startKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
        more = startKey.nonEmpty
      }
    }

  /** The rows that the index shows for one persistence id, or none when the deadline passed before all were read. */
  private def query(
      client: DynamoDbClient,
      table: MigrationTable,
      indexName: String,
      persistenceId: String,
      deadline: Long,
      usage: KeyMigration.UsageCounter,
      limiter: Option[KeyMigration.RateLimiter]
  ): Option[Vector[Map[String, AttributeValue]]] = {
    val items    = Vector.newBuilder[Map[String, AttributeValue]]
    var startKey = Option.empty[java.util.Map[String, AttributeValue]]
    var more     = true
    try {
      while (more) {
        if (System.nanoTime() >= deadline) return None
        limiter.foreach(_.acquire())
        if (System.nanoTime() >= deadline) return None
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
        expression.javaValues.foreach(builder.expressionAttributeValues)
        startKey.foreach(builder.exclusiveStartKey)
        val response = client.query(builder.build())
        usage.record("Query", Option(response.consumedCapacity()).toList)
        items ++= response.items().asScala.map(_.asScala.toMap)
        startKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
        more = startKey.nonEmpty
      }
      Some(items.result())
    } catch { case NonFatal(_) => None }
  }

}
