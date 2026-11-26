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

import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.util.UUID
import java.util.concurrent.{ ConcurrentHashMap, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

/** The primary key of a journal or snapshot row. */
private[dynamodb] final case class StoredKey(partitionKey: String, sortKey: String) {
  override def toString: String = s"(pkey=$partitionKey, skey=$sortKey)"
}

/** The key form a row is moved from. */
private[dynamodb] sealed abstract class KeyForm(val label: String)

private[dynamodb] object KeyForm {

  /** The key of the default resolvers of 1.3.0 (`PersistenceIdBased`, `PersistenceIdWithSeqNr`). */
  case object Release130 extends KeyForm("1.3.0")

  /** The sort key `${sequenceNumber}` that releases before issue #831 gave an id without the separator. */
  case object Issue831 extends KeyForm("issue-831")

  /** The key of `PlainTextPersistenceIdBased` and `PlainTextPersistenceIdWithSeqNr` (issue #1009). */
  case object PlainText extends KeyForm("plain-text")
}

/** Where a row belongs. */
private[dynamodb] sealed trait Placement

private[dynamodb] object Placement {

  /** The row is stored under the key it belongs under. */
  case object InPlace extends Placement

  /** The row is stored under a key of the form `from` and belongs under `target`. */
  final case class Move(target: StoredKey, from: KeyForm) extends Placement

  /** The row is not moved, for the reason given. */
  final case class Unmovable(reason: String) extends Placement
}

/** Decides where each row belongs. The journal and the snapshot store give their own plan, built from the functions of
  * their key resolvers, so the tool never computes a key of its own.
  */
private[dynamodb] trait KeyPlan {

  def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement

  /** The ids with the same value shared their keys in 1.3.0 (issue #1009). None when 1.3.0 could not write the id. */
  def sharedKeyGroup(persistenceId: String): Option[String]
}

/** What the tool does when the target key already holds the same event or snapshot (the same persistence id and
  * sequence number), which happens when a release up to 1.3.0 and the new key format both wrote it.
  */
private[dynamodb] sealed trait DuplicateRule

private[dynamodb] object DuplicateRule {

  /** Journal rows: the copy under the target key stays, and it is marked deleted when either copy is. */
  final case class DeletedFlag(column: String) extends DuplicateRule

  /** Snapshot rows: the copy that was saved later (the larger `created`) stays under the target key. */
  final case class LatestCreated(column: String) extends DuplicateRule
}

private[dynamodb] final case class MigrationTable(
    tableName: String,
    partitionKeyColumn: String,
    sortKeyColumn: String,
    persistenceIdColumn: String,
    sequenceNrColumn: String,
    optionalColumns: Seq[String],
    duplicates: DuplicateRule,
    sequential: Boolean = true,
    // the attributes, other than the keys, that a replay or a snapshot load reads from the persistence-id index
    indexAttributes: Seq[String] = Seq.empty
) {

  /** The persistence id and the sequence number of a row, read as the plugin reads them. None for an item that is not a
    * row: an attribute is missing or of another type, or the number is not a whole number from 0 to Long.MaxValue. The
    * moves and `--verify` both read the items with it (#1035).
    */
  def rowIdentity(item: Map[String, AttributeValue]): Option[(String, Long)] =
    for {
      id <- item.get(persistenceIdColumn).flatMap(v => Option(v.s()))
      seq <- item
        .get(sequenceNrColumn).flatMap(v => Option(v.n()))
        .flatMap(n => Try(BigDecimal(n).toLongExact).toOption)
        .filter(_ >= 0)
    } yield (id, seq)
}

private[dynamodb] final case class MigrationSettings(
    execute: Boolean,
    segments: Int = 1,
    parallelism: Int = 4,
    maxRate: Option[Double] = None,
    scanPageSize: Option[Int] = None,
    maxAttempts: Int = 10,
    persistenceIds: Option[Set[String]] = None
) {
  ThreadCount.check("--segments", segments)
  ThreadCount.check("--parallelism", parallelism)
  maxRate.foreach(MaxRate.check)
  require(scanPageSize.forall(_ >= 1), "--scan-page-size must be at least 1")
  require(maxAttempts >= 1, "the number of attempts must be at least 1")
}

/** The values that `--segments` and `--parallelism` take (issue #1041). */
private[dynamodb] object ThreadCount {

  /** The moves and the dry-run start one thread for each segment, and one for each of `--parallelism`. A thousand
    * threads is far above what the connections of the client serve at the same time, and far below the 1,000,000
    * segments that a DynamoDB Scan takes, so a larger value only exhausts the memory of the JVM before the first
    * request. `--verify` reads the segments one after another, and takes the same range, so that `--segments` means one
    * range.
    */
  val Maximum: Int = 1000

  def check(name: String, value: Int): Unit =
    require(value >= 1 && value <= Maximum, s"$name must be from 1 to $Maximum: $value")
}

/** The values that `--max-rate` takes, for the moves and for `--verify` (issue #1028). */
private[dynamodb] object MaxRate {

  /** One request every 1,000 seconds. The interval, 1e12 nanoseconds, fits a Long with room for the slots. */
  val Minimum: Double = 0.001

  /** A rate below `Minimum`, or one that is not finite, would overflow the interval or not limit at all. */
  def check(rate: Double): Unit =
    require(
      java.lang.Double.isFinite(rate) && rate >= Minimum,
      s"--max-rate must be a finite number of at least $Minimum: $rate"
    )
}

/** A row that is stored under another key than the one it belongs under. */
private[dynamodb] final case class Candidate(
    persistenceId: String,
    sequenceNr: Long,
    from: StoredKey,
    to: StoredKey,
    form: KeyForm
) {
  def describe: String = s"$persistenceId@$sequenceNr ${form.label} $from -> $to"
}

private[dynamodb] final case class UnmovableRow(
    persistenceId: String,
    sequenceNr: Long,
    stored: StoredKey,
    reason: String
) {

  /** The item has no persistence id string or sequence number that the tool reads, so it is not a row of the table. */
  def notARow: Boolean = sequenceNr < 0
}

private[dynamodb] object UnmovableRow {

  /** An item without a persistence id or a sequence number. A row never has a negative sequence number. */
  def notARow(stored: StoredKey, reason: String): UnmovableRow = UnmovableRow("", -1L, stored, reason)
}

private[dynamodb] final case class BlockedMove(
    candidate: Candidate,
    occupantPersistenceId: String,
    occupantSequenceNr: Long,
    // the occupant belongs to a persistence id that --persistence-id left out, so it may move when it is selected
    outsideSelection: Boolean = false
) {

  /** The target key holds an item that is not a row of the table. */
  def byItem: Boolean = occupantSequenceNr < 0
}

private[dynamodb] object BlockedMove {

  /** The target key of `candidate` holds an item that is not a row. No --persistence-id makes that item move. */
  def byItem(candidate: Candidate): BlockedMove =
    BlockedMove(candidate, "", -1L, outsideSelection = false)
}

/** The rows of one persistence id in a group of ids that shared keys in 1.3.0. `missingSequenceNrs` counts the sequence
  * numbers from 1 to the highest one without a row. A missing number can be an event that another id of the group
  * overwrote up to 1.2.0, or one that a hard delete removed; the table cannot tell them apart.
  */
private[dynamodb] final case class IdSummary(
    persistenceId: String,
    rows: Long,
    highestSequenceNr: Long,
    missingSequenceNrs: Long,
    firstMissingSequenceNrs: Seq[Long]
)

private[dynamodb] final case class SharedKeyGroup(group: String, ids: Seq[IdSummary])

/** The requests the tool sent and the capacity units DynamoDB reported for them (ReturnConsumedCapacity = TOTAL). */
private[dynamodb] final case class Usage(requests: Map[String, Long], capacityUnits: Map[String, Double]) {
  def totalRequests: Long        = requests.values.sum
  def totalCapacityUnits: Double = capacityUnits.values.sum
  def describe: Seq[String] =
    requests.toSeq.sortBy(_._1).map { case (operation, count) =>
      f"$operation: requests=$count capacity-units=${capacityUnits.getOrElse(operation, 0.0)}%.1f"
    }
}

private[dynamodb] final case class MigrationReport(
    tableName: String,
    execute: Boolean,
    scannedRows: Long,
    inPlaceRows: Long,
    candidates: Seq[Candidate],
    unmovable: Seq[UnmovableRow],
    sharedKeyGroups: Seq[SharedKeyGroup],
    // dry-run: what the moves would do
    directMoves: Int,
    orderedMoves: Int,
    cycles: Seq[Seq[Candidate]],
    duplicates: Seq[Candidate],
    blocked: Seq[BlockedMove],
    waiting: Seq[Candidate],
    // --execute: what the moves did
    moved: Long,
    merged: Long,
    alreadyGone: Long,
    remaining: Seq[(Candidate, String)],
    usage: Usage,
    elapsedMillis: Long,
    filtered: Boolean = false,
    // dry-run: copies of the same row whose other attributes differ, which the tool does not merge
    conflictingCopies: Seq[(Candidate, String)] = Seq.empty,
    // --execute: the rows whose copy under the old key was removed by a merge
    mergedRows: Seq[(Candidate, String)] = Seq.empty,
    // --execute: signs that another writer used the table during the run, which the contract rules out
    writerSigns: Seq[String] = Seq.empty
) {
  def movesByPersistenceId: Seq[(String, Int)] =
    candidates.groupBy(_.persistenceId).map { case (id, rows) => id -> rows.size }.toSeq.sortBy(_._1)

  /** An `--execute` run that left a selected row under a key it does not belong under: not moved, or not movable. It
    * says nothing about the rows of other persistence ids or about the index; `KeyVerification` does.
    */
  def incomplete: Boolean = execute && (remaining.nonEmpty || unmovable.nonEmpty || writerSigns.nonEmpty)
}

/** Moves journal or snapshot rows from the key that an earlier release gave them to the key they belong under (issue
  * #1014). The plan of the journal or the snapshot store decides where each row belongs.
  *
  * A move is one TransactWriteItems: a Put under the target key on the condition that the key holds no row, and a
  * Delete under the stored key on the condition that the row is still what was read. Either both happen or neither, so
  * a stopped run leaves every row once, under the old key or the new one, and running the tool again continues where it
  * stopped. A retry after a lost response is detected by reading both keys again.
  *
  * When the target key holds the same event or snapshot, the two copies are merged (see `DuplicateRule`). When it holds
  * a row that moves too, the moves are ordered. Rows that hold each other's targets are not swapped: they are left and
  * listed, as is a row whose target holds a row of another persistence id that does not move.
  *
  * The tool does not say that the table or the index is ready for the application; `KeyVerification` does.
  */
private[dynamodb] object KeyMigration {

  def run(
      client: DynamoDbClient,
      table: MigrationTable,
      plan: KeyPlan,
      settings: MigrationSettings,
      progress: String => Unit = _ => ()
  ): MigrationReport = {
    val session = new Session(client, table, settings, progress)
    try session.run(plan)
    finally session.close()
  }

  /** The warnings that the tools print at the start, after the line of the locale.
    *
    * @param clientVersion
    *   the client-version of the plugin, before the tool replaces it with v2
    * @param execute
    *   true with `--execute`
    * @param journalHeadDisabled
    *   true for the journal tool when `head.enabled` is false
    */
  private[dynamodb] def startWarnings(
      clientVersion: String,
      execute: Boolean,
      journalHeadDisabled: Boolean
  ): Seq[String] =
    Seq(
      Some(
        s"warning: client-version is $clientVersion; the tool reads and writes without DAX, so drop the DAX item " +
        "and query caches before you start the application"
      ).filter(_ => clientVersion.endsWith("-dax")),
      Some(
        "warning: the JournalHead is disabled; an actor that recovers while the index lags can overwrite a moved " +
        "event (see #1026). Keep the application stopped until --verify matches twice, some minutes apart; two " +
        "matches make a lag less likely and do not prove that there is none"
      ).filter(_ => journalHeadDisabled),
      Some("warning: the tool cannot check that the writers are stopped; --writers-stopped states it")
        .filter(_ => execute)
    ).flatten

  /** The lines that the CLIs print for a report. */
  def render(report: MigrationReport, direction: String): Seq[String] = {
    val prefix                   = if (report.execute) "" else "[dry-run] "
    val lines                    = mutable.ArrayBuffer.empty[String]
    def line(text: String): Unit = lines += s"$prefix$text"
    def list[A](title: String, items: Seq[A])(show: A => String): Unit =
      if (items.nonEmpty) line(title + items.map(item => s"\n  - ${show(item)}").mkString)
    line(
      s"key migration ($direction) of ${report.tableName}: scanned ${report.scannedRows} rows, " +
      s"${report.inPlaceRows} under the key they belong under, ${report.candidates.size} to move " +
      s"(${report.candidates.groupBy(_.form.label).map { case (k, v) => s"$k=${v.size}" }.toSeq.sorted.mkString(", ")}), " +
      s"${report.unmovable.size} not movable"
    )
    list("rows to move per persistence id:", report.movesByPersistenceId) { case (id, count) => s"$id: $count" }
    if (!report.execute) {
      line(
        s"moves: ${report.directMoves} direct, ${report.orderedMoves} after another move, " +
        s"${report.cycles.map(_.size).sum} in ${report.cycles.size} cycles (not moved), ${report.duplicates.size} merges with a " +
        s"copy of the same row, ${report.blocked.size} blocked, ${report.waiting.size} waiting for a blocked row"
      )
      list("rows whose target key holds a row of another persistence id that does not move:", report.blocked) { b =>
        if (b.byItem) s"${b.candidate.describe} is blocked by an item that is not a row of this table"
        else {
          val outside =
            if (b.outsideSelection) " (outside --persistence-id; that row may move when its persistence id is selected)"
            else ""
          s"${b.candidate.describe} is blocked by ${b.occupantPersistenceId}@${b.occupantSequenceNr}$outside"
        }
      }
      list("rows that hold each other's target keys (the tool does not swap them; move them by hand):", report.cycles) {
        cycle =>
          cycle.map(c => s"${c.persistenceId}@${c.sequenceNr}").mkString(" <-> ")
      }
      list("rows whose target key already holds a copy of the same row (merged):", report.duplicates)(_.describe)
      list(
        "rows whose target key holds a copy of the same row with other contents (not merged; choose one by hand):",
        report.conflictingCopies
      ) { case (c, diff) => s"${c.describe}: $diff" }
      list("rows that wait for a blocked row:", report.waiting)(_.describe)
    } else {
      line(
        s"moved ${report.moved}, merged ${report.merged} with a copy of the same row, " +
        s"${report.alreadyGone} already moved or deleted by another writer, ${report.remaining.size} remaining"
      )
      list("remaining rows (not moved):", report.remaining) { case (c, reason) => s"${c.describe}: $reason" }
      list("merged rows (the copy under the old key was removed):", report.mergedRows) { case (c, kept) =>
        s"${c.describe}: $kept"
      }
      list(
        "WARNING: signs that another writer used the table during the run; stop every writer, run again, and run --verify:",
        report.writerSigns
      )(identity)
      if (report.incomplete)
        line(
          s"not finished: ${report.remaining.size} rows remaining, ${report.unmovable.size} rows not movable, " +
          s"${report.writerSigns.size} signs of another writer"
        )
    }
    list("rows that are not moved:", report.unmovable) { u =>
      if (u.notARow) s"an item that is not a row ${u.stored}: ${u.reason}"
      else s"${u.persistenceId}@${u.sequenceNr} ${u.stored}: ${u.reason}"
    }
    if (report.sharedKeyGroups.nonEmpty && report.sharedKeyGroups.head.ids.head.missingSequenceNrs >= 0)
      list(
        "persistence ids that shared keys in 1.3.0 (a missing sequence number may be an event that another id " +
        "overwrote up to 1.2.0, or a hard delete; the table cannot tell them apart):",
        report.sharedKeyGroups
      ) { group =>
        group.ids
          .map { s =>
            val missing =
              if (s.missingSequenceNrs == 0) "none missing"
              else s"${s.missingSequenceNrs} missing, first ${s.firstMissingSequenceNrs.mkString(",")}"
            s"${s.persistenceId} (rows=${s.rows}, highest=${s.highestSequenceNr}, $missing)"
          }
          .mkString("; ")
      }
    else
      list(
        "persistence ids that shared keys in 1.3.0 (up to 1.2.0, a snapshot of one of them could replace the " +
        "snapshot of another at the same sequence number):",
        report.sharedKeyGroups
      ) { group =>
        group.ids.map(s => s"${s.persistenceId} (snapshots=${s.rows}, highest=${s.highestSequenceNr})").mkString("; ")
      }
    if (report.filtered)
      line(
        "only the rows of the selected persistence ids were placed; the rows of the other persistence ids were not checked"
      )
    line(
      "this run does not check the persistence-id index or the rest of the table: run --verify, without " +
      "--persistence-id, before you start the application or set legacy-keys-enabled = false"
    )
    line(
      s"requests: ${report.usage.totalRequests}, consumed capacity units: " +
      f"${report.usage.totalCapacityUnits}%.1f, elapsed: ${report.elapsedMillis} ms"
    )
    report.usage.describe.foreach(text => line(s"  $text"))
    lines.toVector
  }

  /** The sequence numbers of one id, as disjoint ranges, so that a long journal costs a few entries. */
  private[migration] final class SequenceRanges {
    private val ranges = new java.util.TreeMap[java.lang.Long, java.lang.Long]()

    def add(n: Long): Unit = {
      val floor = ranges.floorEntry(n)
      if (floor != null && floor.getValue >= n) return
      var start = n
      var end   = n
      if (floor != null && floor.getValue == n - 1) {
        start = floor.getKey
        ranges.remove(floor.getKey)
      }
      val next = ranges.get(n + 1)
      if (next != null) {
        end = next
        ranges.remove(n + 1)
      }
      ranges.put(start, end)
    }

    def count: Long = ranges.asScala.map { case (s, e) => e - s + 1 }.sum

    /** The numbers from 1 to `highest` that are missing, up to `limit` of them, and how many are missing in all. */
    def missing(highest: Long, limit: Int): (Long, Seq[Long]) = {
      val first   = mutable.ArrayBuffer.empty[Long]
      var next    = 1L
      var missing = 0L
      ranges.asScala.foreach { case (s, e) =>
        if (s > next) {
          val upTo = math.min(s - 1, highest)
          if (upTo >= next) {
            missing += upTo - next + 1
            var n = next
            while (n <= upTo && first.size < limit) { first += n; n += 1 }
          }
        }
        next = math.max(next, e + 1)
      }
      if (highest >= next) {
        missing += highest - next + 1
        var n = next
        while (n <= highest && first.size < limit) { first += n; n += 1 }
      }
      (missing, first.toVector)
    }
  }

  private final class IdStats {
    var rows: Long    = 0L
    var highest: Long = 0L
    val sequenceNrs   = new SequenceRanges
  }

  private[migration] final class RateLimiter(perSecond: Double) {
    MaxRate.check(perSecond)
    private val intervalNanos = (1e9 / perSecond).toLong
    private var next          = System.nanoTime()

    def acquire(): Unit = {
      val wait = synchronized {
        val now  = System.nanoTime()
        val slot = math.max(now, next)
        next = slot + intervalNanos
        slot - now
      }
      if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
    }

    /** Waits for the next slot, but not past `deadline` (System.nanoTime). False when the deadline came first. */
    def acquireBefore(deadline: Long): Boolean = {
      val slotAt = synchronized {
        val now  = System.nanoTime()
        val slot = math.max(now, next)
        if (slot <= deadline) next = slot + intervalNanos
        slot
      }
      if (slotAt > deadline) false
      else {
        val wait = slotAt - System.nanoTime()
        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
        true
      }
    }
  }

  private[migration] final class UsageCounter {
    private val requests = new ConcurrentHashMap[String, AtomicLong]()
    private val units    = new ConcurrentHashMap[String, java.lang.Double]()

    def record(operation: String, capacity: Iterable[ConsumedCapacity]): Unit = {
      requests.computeIfAbsent(operation, _ => new AtomicLong()).incrementAndGet()
      val total = capacity.flatMap(c => Option(c).flatMap(c => Option(c.capacityUnits())).map(_.doubleValue())).sum
      units.merge(operation, total, (a: java.lang.Double, b: java.lang.Double) => a + b)
      ()
    }

    def snapshot: Usage =
      Usage(
        requests.asScala.map { case (k, v) => k -> v.get() }.toMap,
        units.asScala.map { case (k, v) => k -> v.doubleValue() }.toMap
      )
  }

  private type Item = Map[String, AttributeValue]

  /** Builds the attribute name and value placeholders of one expression. */
  private[migration] final class Expression {
    val names: mutable.LinkedHashMap[String, String]          = mutable.LinkedHashMap.empty
    val values: mutable.LinkedHashMap[String, AttributeValue] = mutable.LinkedHashMap.empty

    def name(attribute: String): String =
      names.collectFirst { case (placeholder, n) if n == attribute => placeholder }.getOrElse {
        val placeholder = s"#n${names.size}"
        names.update(placeholder, attribute)
        placeholder
      }

    def value(attributeValue: AttributeValue): String = {
      val placeholder = s":v${values.size}"
      values.update(placeholder, attributeValue)
      placeholder
    }

    def javaNames: java.util.Map[String, String] = names.toMap.asJava
    def javaValues: Option[java.util.Map[String, AttributeValue]] =
      if (values.isEmpty) None else Some(values.toMap.asJava)
  }

  private sealed trait Occupant
  private case object Free extends Occupant
  private case object SameRow extends Occupant
  private final case class OtherRow(persistenceId: String, sequenceNr: Long) extends Occupant
  // an item without a persistence id or a sequence number that the tool reads
  private case object OtherItem extends Occupant

  private sealed trait Outcome
  private case object Moved extends Outcome
  private case object Merged extends Outcome
  private case object Gone extends Outcome
  private final case class Blocked(persistenceId: String, sequenceNr: Long) extends Outcome
  // the target key holds an item that is not a row of the table
  private case object BlockedByItem extends Outcome
  private final case class Failed(reason: String) extends Outcome
  private final case class Conflict(detail: String) extends Outcome

  private final case class ScanResult(
      rows: Long,
      inPlace: Long,
      candidates: Vector[Candidate],
      unmovable: Vector[UnmovableRow],
      groups: Vector[SharedKeyGroup]
  )

  private final class Session(
      client: DynamoDbClient,
      table: MigrationTable,
      settings: MigrationSettings,
      progress: String => Unit
  ) {
    private val usage   = new UsageCounter
    private val limiter = settings.maxRate.map(new RateLimiter(_))
    // The scan and the moves have pools of their own, so --segments never raises the number of concurrent moves.
    private val scanPool                               = Executors.newFixedThreadPool(settings.segments)
    private val movePool                               = Executors.newFixedThreadPool(settings.parallelism)
    private val scanContext: ExecutionContext          = ExecutionContext.fromExecutorService(scanPool)
    private implicit val moveContext: ExecutionContext = ExecutionContext.fromExecutorService(movePool)

    def close(): Unit = {
      Seq(scanPool, movePool).foreach { pool =>
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
      }
    }

    private def emit(text: String): Unit =
      try progress(text)
      catch { case NonFatal(_) => () }

    private def selected(persistenceId: String): Boolean = settings.persistenceIds.forall(_.contains(persistenceId))

    /** The highest sequence number that the scan saw for each persistence id. */
    private var scannedHighest = Map.empty[String, Long]

    /** Rows that the scan did not see, or a change of a row after it was read, mean that another writer used the table.
      */
    private val signs                    = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    private def sign(text: String): Unit = { signs.add(text); () }

    private def unseenByScan(persistenceId: String, sequenceNr: Long): Boolean =
      scannedHighest.get(persistenceId).forall(sequenceNr > _)

    def run(plan: KeyPlan): MigrationReport = {
      val started = System.nanoTime()
      val scan    = scanTable(plan)
      emit(s"phase=scan finished rows=${scan.rows} candidates=${scan.candidates.size} unmovable=${scan.unmovable.size}")
      val base = MigrationReport(
        tableName = table.tableName,
        execute = settings.execute,
        scannedRows = scan.rows,
        inPlaceRows = scan.inPlace,
        candidates = scan.candidates,
        unmovable = scan.unmovable,
        sharedKeyGroups = scan.groups,
        directMoves = 0,
        orderedMoves = 0,
        cycles = Seq.empty,
        duplicates = Seq.empty,
        blocked = Seq.empty,
        waiting = Seq.empty,
        moved = 0,
        merged = 0,
        alreadyGone = 0,
        remaining = Seq.empty,
        usage = Usage(Map.empty, Map.empty),
        elapsedMillis = 0,
        filtered = settings.persistenceIds.isDefined
      )
      val report = if (settings.execute) execute(base) else dryRun(base)
      report.copy(usage = usage.snapshot, elapsedMillis = (System.nanoTime() - started) / 1000000L)
    }

    // --- scan --------------------------------------------------------------------------------------------------

    private def scanTable(plan: KeyPlan): ScanResult = {
      val lock    = new Object
      var rows    = 0L
      var inPlace = 0L

      val candidates = Vector.newBuilder[Candidate]
      val unmovable  = Vector.newBuilder[UnmovableRow]
      val stats      = mutable.HashMap.empty[String, IdStats]
      val pages      = new AtomicLong()
      def scanSegment(segment: Int): Unit = {
        var startKey = Option.empty[java.util.Map[String, AttributeValue]]
        var more     = true
        while (more) {
          val expression = new Expression
          val projection = Seq(
            table.partitionKeyColumn,
            table.sortKeyColumn,
            table.persistenceIdColumn,
            table.sequenceNrColumn
          ).map(expression.name).mkString(", ")
          val builder = ScanRequest
            .builder()
            .tableName(table.tableName)
            .consistentRead(true)
            .projectionExpression(projection)
            .expressionAttributeNames(expression.javaNames)
            .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
          if (settings.segments > 1) builder.segment(segment).totalSegments(settings.segments)
          settings.scanPageSize.foreach(limit => builder.limit(limit))
          startKey.foreach(builder.exclusiveStartKey)
          limiter.foreach(_.acquire())
          val response = client.scan(builder.build())
          usage.record("Scan", Option(response.consumedCapacity()).toList)
          val items = response.items().asScala.toVector
          lock.synchronized {
            items.foreach { item =>
              val stored = StoredKey(item.get(table.partitionKeyColumn).s(), item.get(table.sortKeyColumn).s())
              rows += 1
              table.rowIdentity(item.asScala.toMap) match {
                case None =>
                  // An item that no plugin wrote. It is listed only when every id is selected.
                  if (settings.persistenceIds.isEmpty)
                    unmovable += UnmovableRow.notARow(
                      stored,
                      "the item has no persistence-id string or sequence-nr number; the tool does not move it"
                    )
                case Some((persistenceId, sequenceNr)) =>
                  val s = stats.getOrElseUpdate(persistenceId, new IdStats)
                  s.rows += 1
                  s.highest = math.max(s.highest, sequenceNr)
                  s.sequenceNrs.add(sequenceNr)
                  // Only the rows of the selected ids are placed and counted.
                  if (selected(persistenceId)) {
                    plan.placement(persistenceId, sequenceNr, stored) match {
                      case Placement.InPlace => inPlace += 1
                      case Placement.Move(target, from) =>
                        candidates += Candidate(persistenceId, sequenceNr, stored, target, from)
                      case Placement.Unmovable(reason) =>
                        unmovable += UnmovableRow(persistenceId, sequenceNr, stored, reason)
                    }
                  }
              }
            }
          }
          startKey = Option(response.lastEvaluatedKey()).filterNot(_.isEmpty)
          more = startKey.nonEmpty
          val page = pages.incrementAndGet()
          emit(s"phase=scan segment=$segment pages=$page rows-observed=${lock.synchronized(rows)}")
        }
      }
      Await.result(
        Future.traverse((0 until settings.segments).toVector)(s => Future(scanSegment(s))(scanContext))(
          implicitly,
          scanContext
        ),
        Duration.Inf
      )
      val groups = stats.keys
        .flatMap(id => plan.sharedKeyGroup(id).map(_ -> id))
        .groupBy(_._1)
        .collect {
          case (group, members) if members.size > 1 && members.exists { case (_, id) => selected(id) } =>
            SharedKeyGroup(
              group,
              members.map(_._2).toVector.sorted.map { id =>
                val s = stats(id)
                // Snapshots are not saved at every sequence number, so a gap says nothing about them.
                val (missing, first) =
                  if (table.sequential) s.sequenceNrs.missing(s.highest, 10) else (-1L, Seq.empty[Long])
                IdSummary(id, s.rows, s.highest, missing, first)
              }
            )
        }
        .toVector
        .sortBy(_.ids.head.persistenceId)
      scannedHighest = stats.map { case (id, s) => id -> s.highest }.toMap
      ScanResult(rows, inPlace, candidates.result(), unmovable.result(), groups)
    }

    // --- dry-run -----------------------------------------------------------------------------------------------

    private def dryRun(base: MigrationReport): MigrationReport = {
      val bySource = base.candidates.map(c => c.from -> c).toMap
      val checked  = new AtomicLong()
      val occupants: Map[Candidate, Either[Candidate, Occupant]] =
        Await
          .result(
            Future.traverse(base.candidates.toVector) { c =>
              Future {
                val occupant = bySource.get(c.to) match {
                  case Some(other) => Left(other)
                  case None =>
                    limiter.foreach(_.acquire())
                    Right(occupantOf(c, read(c.to, projected = true)))
                }
                val n = checked.incrementAndGet()
                if (n % 1000 == 0) emit(s"phase=check-targets checked=$n of ${base.candidates.size}")
                c    -> occupant
              }
            },
            Duration.Inf
          )
          .toMap
      // Two rows with the same target key: only the one with the smallest stored key can move there, in the dry-run
      // and in --execute alike. A copy of the same row merges into it, and the row of another id is blocked by it.
      val winners = base.candidates.groupBy(_.to).map { case (to, group) => to -> winnerOf(group) }
      val successors: Map[Candidate, Either[Candidate, Occupant]] = occupants.map { case (c, occupant) =>
        val first = winners(c.to)
        if (first == c) c -> occupant
        else
          occupants(first) match {
            // The target holds a row or an item that blocks the winner, and the key is not kept for it: --execute
            // tries the others after it, and what the target holds blocks them too (or is their own row).
            case Right(OtherRow(_, _)) | Right(OtherItem)                                        => c -> occupant
            case _ if c.persistenceId == first.persistenceId && c.sequenceNr == first.sequenceNr => c -> Right(SameRow)
            case _ => c -> Right(OtherRow(first.persistenceId, first.sequenceNr))
          }
      }
      // Each candidate has at most one successor, the candidate whose row holds its target key.
      sealed trait Resolution
      case object Direct extends Resolution
      case object Duplicate extends Resolution
      case object After extends Resolution
      case object InCycle extends Resolution
      final case class Stuck(reason: String) extends Resolution
      val resolved = mutable.LinkedHashMap.empty[Candidate, Resolution]
      val cycles   = Vector.newBuilder[Vector[Candidate]]
      val blocked  = Vector.newBuilder[BlockedMove]
      base.candidates.foreach { start =>
        val path    = mutable.ArrayBuffer.empty[Candidate]
        var current = start
        var end     = false
        while (!end) {
          resolved.get(current) match {
            case Some(_) => end = true
            case None if path.contains(current) =>
              val cycle = path.drop(path.indexOf(current)).toVector
              cycle.foreach(resolved.update(_, InCycle))
              cycles += cycle
              end = true
            case None =>
              successors(current) match {
                case Right(Free)    => resolved.update(current, Direct); end = true
                case Right(SameRow) => resolved.update(current, Duplicate); end = true
                case Right(OtherItem) =>
                  blocked += BlockedMove.byItem(current)
                  resolved.update(current, Stuck("blocked by an item that is not a row"))
                  end = true
                case Right(OtherRow(id, seq)) =>
                  blocked += BlockedMove(current, id, seq, outsideSelection = !selected(id))
                  resolved.update(current, Stuck(s"blocked by $id@$seq"))
                  end = true
                case Left(next) =>
                  path += current
                  current = next
              }
          }
        }
        // Each row on the path takes its resolution from the row that holds its target key.
        path.reverseIterator.filterNot(resolved.contains).foreach { c =>
          val next = successors(c).left.toOption.get
          val resolution = resolved(next) match {
            case InCycle       => Stuck("waits for a row in a cycle, which the tool does not move")
            case Stuck(reason) => Stuck(s"waits for a row that cannot move ($reason)")
            case _             => After
          }
          resolved.update(c, resolution)
        }
      }
      // A copy of the same row merges only when every other attribute is equal. Each read takes a slot of --max-rate.
      def pacedRead(key: StoredKey): Option[Item] = {
        limiter.foreach(_.acquire())
        read(key, projected = false)
      }
      val duplicates = resolved.collect { case (c, Duplicate) => c }.toVector
      val conflicting = duplicates.flatMap { c =>
        val source = pacedRead(c.from)
        val target = pacedRead(c.to)
          .filter(isRowOf(_, c)).orElse(
            base.candidates
              .find(o => o != c && o.to == c.to && o.persistenceId == c.persistenceId && o.sequenceNr == c.sequenceNr)
              .flatMap(o => pacedRead(o.from))
          )
        (for {
          s <- source
          t <- target
        } yield differences(s, t)).filter(_.nonEmpty).map(c -> _)
      }
      base.copy(
        directMoves = resolved.values.count(_ == Direct),
        orderedMoves = resolved.values.count(_ == After),
        cycles = cycles.result(),
        duplicates = duplicates.filterNot(c => conflicting.exists(_._1 == c)),
        conflictingCopies = conflicting,
        blocked = blocked.result(),
        waiting = resolved.collect { case (c, Stuck(reason)) if reason.startsWith("waits") => c }.toVector
      )
    }

    /** The row that gets a target key that several rows need: the one with the smallest stored key. */
    private def winnerOf(group: Iterable[Candidate]): Candidate =
      group.minBy(c => (c.from.partitionKey, c.from.sortKey))

    // --- execute -----------------------------------------------------------------------------------------------

    private def execute(base: MigrationReport): MigrationReport = {
      val pending = mutable.LinkedHashMap(base.candidates.map(c => c.from -> c): _*)
      // The target keys whose winner did not reach them (Failed, Gone), with that winner. No other row gets such a key
      // in this run, so the next run gives it to the same row, as one uninterrupted run does.
      val reserved = mutable.LinkedHashMap.empty[StoredKey, Candidate]
      mergedRows.clear()
      val remaining = mutable.ArrayBuffer.empty[(Candidate, String)]
      val moved     = new AtomicLong()
      val merged    = new AtomicLong()
      val gone      = new AtomicLong()
      var lastEmit  = System.nanoTime()
      def report(): Unit =
        if (System.nanoTime() - lastEmit > 5.seconds.toNanos) {
          lastEmit = System.nanoTime()
          emit(
            s"phase=execute moved=${moved.get()} merged=${merged.get()} gone=${gone.get()} " +
            s"pending=${pending.size} remaining=${remaining.size}"
          )
        }
      var progressMade = true
      while (pending.nonEmpty && progressMade) {
        progressMade = false
        // A row whose target is held by a pending row waits until that row has moved. Of the rows that need the same
        // target, only the one that the dry-run names goes first; the others are tried after it, and not at all in this
        // run when that row did not reach the target.
        val ready = pending.values
          .groupBy(_.to)
          .collect { case (to, group) if !reserved.contains(to) => group }
          .map(winnerOf)
          .filterNot(c => pending.contains(c.to))
          .toVector
          .sortBy(c => (c.from.partitionKey, c.from.sortKey))
        if (ready.nonEmpty) {
          val outcomes = Await.result(
            Future.traverse(ready) { c =>
              Future {
                val outcome = moveOne(c)
                outcome match {
                  case Moved  => moved.incrementAndGet()
                  case Merged => merged.incrementAndGet()
                  case Gone =>
                    gone.incrementAndGet()
                    sign(s"${c.describe}: the row left its key, and is not under the target key")
                  case _ =>
                }
                c -> outcome
              }
            },
            Duration.Inf
          )
          outcomes.foreach { case (c, outcome) =>
            pending.remove(c.from)
            progressMade = true
            outcome match {
              case Blocked(id, seq) =>
                remaining += c -> (if (!selected(id))
                                     s"the target key holds $id@$seq, which is outside --persistence-id; it may move when its persistence id is selected"
                                   else s"the target key holds $id@$seq, which does not move")
                if (unseenByScan(id, seq))
                  sign(s"${c.describe}: the target key holds $id@$seq, which the scan did not see")
              case BlockedByItem =>
                remaining += c -> "the target key holds an item that is not a row of this table; move or remove it by hand"
              case Failed(reason) =>
                remaining += c -> reason
                reserved.update(c.to, c)
              case Gone =>
                reserved.update(c.to, c)
              case Conflict(detail) =>
                remaining += c -> s"the target key holds a copy of the same row with other contents ($detail); choose one by hand"
              case _ =>
            }
          }
          report()
        }
      }
      // What is left waits for another pending row: rows in cycles, which the tool does not swap, and the rows that
      // wait for them.
      val inCycles = findCycles(pending).flatten.toSet
      pending.values.foreach { c =>
        remaining += c -> (reserved.get(c.to) match {
          case Some(winner) =>
            s"the target key is kept for ${winner.persistenceId}@${winner.sequenceNr}, which did not reach it in this " +
            "run (its reason is listed); resolve that, then run again"
          case None if inCycles.contains(c) =>
            "in a cycle of rows that hold each other's target keys; the tool does not swap them"
          case None => "waits for a row that did not move"
        })
      }
      base.copy(
        moved = moved.get(),
        merged = merged.get(),
        alreadyGone = gone.get(),
        remaining = remaining.toVector,
        mergedRows = mergedRows.asScala.toVector,
        writerSigns = signs.asScala.toVector
      )
    }

    private def findCycles(pending: mutable.LinkedHashMap[StoredKey, Candidate]): Vector[Vector[Candidate]] = {
      val seen   = mutable.HashSet.empty[StoredKey]
      val cycles = Vector.newBuilder[Vector[Candidate]]
      pending.values.foreach { start =>
        val path    = mutable.ArrayBuffer.empty[Candidate]
        var current = Option(start)
        while (current.exists(c => !seen.contains(c.from))) {
          val c = current.get
          seen += c.from
          path += c
          current = pending.get(c.to)
        }
        current.foreach { c =>
          val index = path.indexOf(c)
          if (index >= 0) cycles += path.drop(index).toVector
        }
      }
      cycles.result()
    }

    // --- requests ----------------------------------------------------------------------------------------------

    private def keyMap(key: StoredKey): java.util.Map[String, AttributeValue] =
      Map(
        table.partitionKeyColumn -> AttributeValue.builder().s(key.partitionKey).build(),
        table.sortKeyColumn      -> AttributeValue.builder().s(key.sortKey).build()
      ).asJava

    private def read(key: StoredKey, projected: Boolean): Option[Item] = {
      val builder = GetItemRequest
        .builder()
        .tableName(table.tableName)
        .key(keyMap(key))
        .consistentRead(true)
        .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
      if (projected) {
        // The keys are projected too, so that an item without the other two attributes is not read as no item.
        val expression = new Expression
        builder
          .projectionExpression(
            Seq(table.partitionKeyColumn, table.sortKeyColumn, table.persistenceIdColumn, table.sequenceNrColumn)
              .map(expression.name).mkString(", ")
          )
          .expressionAttributeNames(expression.javaNames)
      }
      val response = client.getItem(builder.build())
      usage.record("GetItem", Option(response.consumedCapacity()).toList)
      if (response.hasItem && !response.item().isEmpty) Some(response.item().asScala.toMap) else None
    }

    private def isRowOf(item: Item, c: Candidate): Boolean =
      table.rowIdentity(item).contains((c.persistenceId, c.sequenceNr))

    private def occupantOf(c: Candidate, item: Option[Item]): Occupant =
      item.map(table.rowIdentity) match {
        case None                                                                  => Free
        case Some(None)                                                            => OtherItem
        case Some(Some((id, seq))) if id == c.persistenceId && seq == c.sequenceNr => SameRow
        case Some(Some((id, seq)))                                                 => OtherRow(id, seq)
      }

    /** The row under its key is still `item`: every attribute has the value that was read, and the optional columns
      * that were absent are still absent.
      */
    private def unchanged(item: Item, expression: Expression): String = {
      val present = item.toSeq
        .filterNot { case (name, _) => name == table.partitionKeyColumn || name == table.sortKeyColumn }
        .sortBy(_._1)
        .map { case (name, value) => s"${expression.name(name)} = ${expression.value(value)}" }
      val absent =
        table.optionalColumns.filterNot(item.contains).map(name => s"attribute_not_exists(${expression.name(name)})")
      (s"attribute_exists(${expression.name(table.partitionKeyColumn)})" +: (present ++ absent)).mkString(" AND ")
    }

    private def withKey(item: Item, key: StoredKey): java.util.Map[String, AttributeValue] =
      (item ++ keyMap(key).asScala).asJava

    private def put(item: Item, key: StoredKey, condition: Expression => String): TransactWriteItem = {
      val expression = new Expression
      val text       = condition(expression)
      val builder = Put
        .builder()
        .tableName(table.tableName)
        .item(withKey(item, key))
        .conditionExpression(text)
        .expressionAttributeNames(expression.javaNames)
      expression.javaValues.foreach(builder.expressionAttributeValues)
      TransactWriteItem.builder().put(builder.build()).build()
    }

    private def delete(key: StoredKey, item: Item): TransactWriteItem = {
      val expression = new Expression
      val text       = unchanged(item, expression)
      val builder = Delete
        .builder()
        .tableName(table.tableName)
        .key(keyMap(key))
        .conditionExpression(text)
        .expressionAttributeNames(expression.javaNames)
      expression.javaValues.foreach(builder.expressionAttributeValues)
      TransactWriteItem.builder().delete(builder.build()).build()
    }

    private def check(key: StoredKey, item: Item): TransactWriteItem = {
      val expression = new Expression
      val text       = unchanged(item, expression)
      val builder = ConditionCheck
        .builder()
        .tableName(table.tableName)
        .key(keyMap(key))
        .conditionExpression(text)
        .expressionAttributeNames(expression.javaNames)
      expression.javaValues.foreach(builder.expressionAttributeValues)
      TransactWriteItem.builder().conditionCheck(builder.build()).build()
    }

    private def markDeleted(key: StoredKey, item: Item, column: String): TransactWriteItem = {
      val expression = new Expression
      val condition  = unchanged(item, expression)
      val update = s"SET ${expression.name(column)} = ${expression.value(AttributeValue.builder().bool(true).build())}"
      val builder = Update
        .builder()
        .tableName(table.tableName)
        .key(keyMap(key))
        .updateExpression(update)
        .conditionExpression(condition)
        .expressionAttributeNames(expression.javaNames)
      expression.javaValues.foreach(builder.expressionAttributeValues)
      TransactWriteItem.builder().update(builder.build()).build()
    }

    /** Sends the transaction. Left is a transaction that was not applied, or whose outcome is not known (a lost
      * response); the caller reads the keys again to find out. A request that DynamoDB rejects as invalid is a failure
      * that a retry does not fix.
      */
    private def transact(items: Seq[TransactWriteItem]): Either[String, Unit] = {
      val request = TransactWriteItemsRequest
        .builder()
        .transactItems(items.asJava)
        // The SDK sends the same token when it retries, so a retry of a transaction that was applied succeeds.
        .clientRequestToken(UUID.randomUUID().toString)
        .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
        .build()
      try {
        val response = client.transactWriteItems(request)
        usage.record("TransactWriteItems", Option(response.consumedCapacity()).map(_.asScala.toList).getOrElse(Nil))
        Right(())
      } catch {
        case e: TransactionCanceledException =>
          usage.record("TransactWriteItems (canceled)", Nil)
          val reasons =
            Option(e.cancellationReasons()).map(_.asScala.map(r => Option(r.code()).getOrElse("None"))).getOrElse(Nil)
          Left(s"canceled: ${reasons.mkString(",")}")
        case e: DynamoDbException if Option(e.awsErrorDetails()).exists(_.errorCode() == "ValidationException") =>
          usage.record("TransactWriteItems (invalid)", Nil)
          throw e
        case e @ (_: DynamoDbException | _: SdkClientException) =>
          usage.record("TransactWriteItems (failed)", Nil)
          Left(s"failed: ${e.getMessage}")
      }
    }

    private def backoff(attempt: Int): Unit =
      Thread.sleep(math.min(1000L, 20L * (1L << math.min(attempt, 6))))

    /** Moves one row. Every attempt reads the row again, so a row that another writer changed is moved as it is now,
      * and a transaction whose response was lost is found applied (the row is gone from the stored key).
      */
    private val mergedRows = new java.util.concurrent.ConcurrentLinkedQueue[(Candidate, String)]()

    /** The attributes, other than the keys and the attribute that the duplicate rule merges, whose values differ. */
    private def differences(source: Item, target: Item): String = {
      val ruleColumn = table.duplicates match {
        case DuplicateRule.DeletedFlag(column)   => column
        case DuplicateRule.LatestCreated(column) => column
      }
      val ignored = Set(table.partitionKeyColumn, table.sortKeyColumn, ruleColumn)
      (source.keySet ++ target.keySet)
        .diff(ignored).toSeq.sorted.filter(n => source.get(n) != target.get(n)).map { name =>
          def show(v: Option[AttributeValue]): String = v.fold("absent") { value =>
            if (value.b() != null) s"${value.b().asByteArray().length} bytes" else value.toString.take(80)
          }
          s"$name differs: old key ${show(source.get(name))}, target key ${show(target.get(name))}"
        }.mkString("; ")
    }

    private def moveOne(c: Candidate): Outcome = {
      var attempt    = 0
      var result     = Option.empty[Outcome]
      var last       = ""
      var merging    = false
      var lastSource = Option.empty[Item]
      while (result.isEmpty && attempt < settings.maxAttempts) {
        if (attempt > 0) backoff(attempt)
        attempt += 1
        limiter.foreach(_.acquire())
        try {
          read(c.from, projected = false) match {
            case Some(source) if isRowOf(source, c) =>
              if (lastSource.exists(_ != source)) sign(s"${c.describe}: the row changed after the tool read it")
              lastSource = Some(source)
              transact(
                Seq(
                  put(source, c.to, e => s"attribute_not_exists(${e.name(table.partitionKeyColumn)})"),
                  delete(c.from, source)
                )
              ) match {
                case Right(()) => result = Some(Moved)
                case Left(reason) =>
                  last = reason
                  // The target holds a row, or the source changed, or the outcome is not known: read both again.
                  read(c.to, projected = false) match {
                    case Some(target) if isRowOf(target, c) =>
                      read(c.from, projected = false) match {
                        case Some(again) if isRowOf(again, c) =>
                          if (again != source) sign(s"${c.describe}: the row changed after the tool read it")
                          val diff = differences(again, target)
                          if (diff.nonEmpty) result = Some(Conflict(diff))
                          else {
                            merging = true
                            transact(merge(c, again, target)) match {
                              case Right(()) =>
                                result = Some(Merged)
                                mergedRows.add(c -> keptBy(again, target))
                              case Left(cause) => last = cause
                            }
                          }
                        case _ =>
                          // the lost transaction was applied, or a writer deleted the row
                          result = Some(if (merging) mergedAfterLostResponse(c) else Moved)
                      }
                    case Some(target) =>
                      result = Some(table.rowIdentity(target).fold[Outcome](BlockedByItem) { case (id, seq) =>
                        Blocked(id, seq)
                      })
                    case None => // the source changed or the transaction raced; try again
                  }
              }
            case _ =>
              // The row left its key: moved by an earlier attempt whose response was lost, or deleted.
              result =
                if (read(c.to, projected = true).exists(isRowOf(_, c)))
                  Some(if (merging) mergedAfterLostResponse(c) else Moved)
                else Some(Gone)
          }
        } catch {
          case e: DynamoDbException if Option(e.awsErrorDetails()).exists(_.errorCode() == "ValidationException") =>
            result = Some(Failed(s"DynamoDB rejected the request: ${e.getMessage}"))
          case NonFatal(e) => last = s"failed: ${e.getMessage}"
        }
      }
      result.getOrElse(settle(c, merging, last))
    }

    private def mergedAfterLostResponse(c: Candidate): Outcome = {
      mergedRows.add(c -> "kept the row under the target key (the response of the merge was lost; the keys were read)")
      Merged
    }

    /** After the last attempt, when the outcome of the last transaction is not known: read both keys to settle it. */
    private def settle(c: Candidate, merging: Boolean, last: String): Outcome =
      try {
        val source = read(c.from, projected = true).exists(isRowOf(_, c))
        val target = read(c.to, projected = true).exists(isRowOf(_, c))
        if (!source && target) { if (merging) mergedAfterLostResponse(c) else Moved }
        else if (source) Failed(s"gave up after ${settings.maxAttempts} attempts ($last)")
        else Failed(s"outcome unknown after ${settings.maxAttempts} attempts: the row is under neither key ($last)")
      } catch {
        case NonFatal(e) =>
          Failed(
            s"outcome unknown after ${settings.maxAttempts} attempts ($last); the keys could not be read: ${e.getMessage}"
          )
      }

    /** What a merge keeps, for the report. */
    private def keptBy(source: Item, target: Item): String = table.duplicates match {
      case DuplicateRule.DeletedFlag(column) =>
        val deleted = Seq(source, target).exists(_.get(column).exists(v => Option(v.bool()).exists(_.booleanValue())))
        s"kept the row under the target key, deleted = $deleted"
      case DuplicateRule.LatestCreated(column) =>
        def created(item: Item) = item.get(column).flatMap(v => Option(v.n())).getOrElse("?")
        s"kept the snapshot with the larger $column (old key ${created(source)}, target key ${created(target)}); the other was removed"
    }

    /** The transaction that removes the copy under the stored key when the target key holds the same row. */
    private def merge(c: Candidate, source: Item, target: Item): Seq[TransactWriteItem] = {
      val keep = table.duplicates match {
        case DuplicateRule.DeletedFlag(column) =>
          val sourceDeleted = source.get(column).exists(v => Option(v.bool()).exists(_.booleanValue()))
          val targetDeleted = target.get(column).exists(v => Option(v.bool()).exists(_.booleanValue()))
          if (sourceDeleted && !targetDeleted) markDeleted(c.to, target, column) else check(c.to, target)
        case DuplicateRule.LatestCreated(column) =>
          def created(item: Item): BigDecimal =
            item.get(column).flatMap(v => Option(v.n())).map(BigDecimal(_)).getOrElse(BigDecimal(0))
          if (created(source) > created(target)) put(source, c.to, e => unchanged(target, e)) else check(c.to, target)
      }
      Seq(keep, delete(c.from, source))
    }
  }
}
