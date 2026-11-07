package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.LocalDate

final case class KnownProblem(id: String, detail: String)

final case class AllowlistEntry(id: String, category: String, reason: String, expires: LocalDate, issue: String)

object Allowlist {

  val Categories: Set[String] = Set("schema-drift", "factory-class")

  def load(path: Path): Seq[AllowlistEntry] = {
    if (!Files.isRegularFile(path)) {
      sys.error(s"known-problems allowlist not found: $path")
    }
    val lines = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).linesIterator
    lines.zipWithIndex.flatMap { case (line, index) =>
      val trimmed = line.trim
      if (trimmed.isEmpty || trimmed.startsWith("#")) {
        None
      } else {
        val fields = trimmed.split("\\|", -1).map(_.trim)
        if (fields.length != 5) {
          sys.error(s"$path:${index + 1}: expected 5 fields separated by '|', got ${fields.length}")
        } else {
          val expires = scala.util.Try(LocalDate.parse(fields(3))).getOrElse {
            sys.error(s"$path:${index + 1}: invalid expiry date '${fields(3)}', expected YYYY-MM-DD")
          }
          Some(AllowlistEntry(fields(0), fields(1), fields(2), expires, fields(4)))
        }
      }
    }.toVector
  }

  def verify(
      category: String,
      problems: Seq[KnownProblem],
      entries: Seq[AllowlistEntry],
      today: LocalDate = LocalDate.now()
  ): Unit = {
    val unknownCategories = entries.filterNot(entry => Categories.contains(entry.category)).map(_.id).distinct.sorted
    val relevant          = entries.filter(_.category == category)
    val problemIds        = problems.map(_.id).toSet
    val entryIds          = relevant.map(_.id)
    val duplicateIds = entryIds.groupBy(identity).collect { case (id, occurrences) if occurrences.size > 1 => id }.toSet
    val staleEntries = entryIds.filterNot(problemIds.contains).distinct.sorted
    val missingEntries    = problems.filterNot(problem => entryIds.contains(problem.id))
    val expiredEntries    = relevant.filter(_.expires.isBefore(today))
    val incompleteEntries = relevant.filter(entry => entry.reason.isEmpty || entry.issue.isEmpty)

    val failures = Vector.newBuilder[String]
    unknownCategories.foreach(id => failures += s"allowlist entry has an unknown category: $id")
    duplicateIds.toSeq.sorted.foreach(id => failures += s"duplicate allowlist entry: $id")
    staleEntries.foreach(id => failures += s"allowlist entry no longer reproduces: $id")
    missingEntries.foreach(problem =>
      failures += s"problem missing from the allowlist: ${problem.id}\n  ${problem.detail}"
    )
    expiredEntries.foreach(entry =>
      failures += s"allowlist entry expired on ${entry.expires}: ${entry.id} (${entry.issue})"
    )
    incompleteEntries.foreach(entry => failures += s"allowlist entry needs a reason and an owner issue: ${entry.id}")

    val messages = failures.result()
    if (messages.nonEmpty) {
      sys.error(s"known-problems.allowlist has ${messages.size} problem(s):\n${messages.mkString("\n")}")
    }
  }
}
