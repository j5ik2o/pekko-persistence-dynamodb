package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.file.Files
import java.util.regex.Pattern

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class RuleTableSpec extends AnyFreeSpec with Matchers {

  "every rule row names an enforcement path that exists" in {
    val repo  = Repo.locate()
    val table = RuleTable.parse(repo.read(repo.root.resolve("AGENTS.md")))

    info(s"checked ${table.rules.size} rule rows")
    table.errors.foreach(error => info(s"malformed rule row: $error"))

    table.errors shouldBe empty
    table.rules should not be empty

    val missing = table.rules.filterNot(rule => Files.isRegularFile(repo.root.resolve(rule.path)))
    missing.foreach(rule => info(s"missing enforcement path: ${rule.path} (${rule.mistake})"))

    missing shouldBe empty
  }

  "a rule row that does not start with a pipe is reported instead of dropped" in {
    val source =
      "## Rule table\n" +
      "\n" +
      "| Repeated mistake | Rule | Enforcement |\n" +
      "|:--|:--|:--|\n" +
      "Missing leading pipe | Keep the pipe. | test `schema-check/example.scala` |\n" +
      "| Valid row | Keep the pipe. | test `schema-check/example.scala` |\n"

    val table = RuleTable.parse(source)

    info(s"parsed rule rows: ${table.rules.size}")
    table.errors.foreach(error => info(s"reported: $error"))
    table.errors.exists(_.contains("does not start with '|'")) shouldBe true
    table.rules.map(_.mistake) shouldBe Vector("Valid row")
  }
}

private object RuleTable {

  final case class Rule(mistake: String, path: String)

  final case class Parsed(rules: Seq[Rule], errors: Seq[String])

  private val Enforcement = Pattern.compile("^(type|check|test)\\s+`([^`]+)`$")
  private val Separator   = Pattern.compile("^\\|[-|: ]+\\|$")

  def parse(source: String): Parsed = {
    val lines = source.split("\n", -1).toVector
    val start = lines.indexWhere(_.trim == "## Rule table")
    if (start < 0) {
      Parsed(Vector.empty, Vector("AGENTS.md has no '## Rule table' section"))
    } else {
      val sectionLines = lines.drop(start + 1).takeWhile(line => !line.startsWith("## "))
      val tableStart   = sectionLines.indexWhere(_.trim.startsWith("|"))
      if (tableStart < 0) {
        Parsed(Vector.empty, Vector("AGENTS.md has a '## Rule table' section but no rule table rows"))
      } else {
        val tableBlock = sectionLines.drop(tableStart).takeWhile(_.trim.nonEmpty)
        val malformed  = tableBlock.filter(line => !line.trim.startsWith("|"))
        val tableLines = tableBlock.filter(_.trim.startsWith("|"))
        val body       = tableLines.drop(1).filterNot(line => Separator.matcher(line.trim).matches())

        val errors = Vector.newBuilder[String]
        malformed.foreach(line => errors += s"rule row does not start with '|': ${line.trim}")
        val rules = body.flatMap { line =>
          val cells = splitCells(line)
          if (cells.size != 3) {
            errors += s"expected 3 cells but got ${cells.size}: ${line.trim}"
            None
          } else {
            val matcher = Enforcement.matcher(cells(2))
            if (matcher.matches()) {
              val path = matcher.group(2).trim
              if (path.isEmpty || path.startsWith("/") || path.split("/").contains("..")) {
                errors += s"enforcement path must be relative to the repository root: ${cells(2)}"
                None
              } else {
                Some(Rule(cells(0), path))
              }
            } else {
              errors += s"enforcement must be 'type', 'check', or 'test' followed by a backticked path: ${cells(2)}"
              None
            }
          }
        }
        Parsed(rules, errors.result())
      }
    }
  }

  private def splitCells(line: String): Vector[String] = {
    val parts = line.trim.split("\\|", -1).toVector
    val withoutEdges =
      if (parts.size >= 2 && parts.head.isEmpty && parts.last.isEmpty) parts.slice(1, parts.size - 1)
      else parts
    withoutEdges.map(_.trim)
  }
}
