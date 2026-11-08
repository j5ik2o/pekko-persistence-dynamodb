package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.file.Path
import scala.util.matching.Regex

object SchemaDrift {

  private val ColumnPair: Regex      = "([A-Za-z0-9-]+)\\s*=\\s*\"([^\"]*)\"".r
  private val IndexName: Regex       = "\"IndexName\"\\s*:\\s*\"([^\"]+)\"".r
  private val AttributeName: Regex   = "\"AttributeName\"\\s*:\\s*\"([^\"]+)\"".r
  private val IndexRow: Regex        = "^\\s*\\|\\s*index\\s*\\|\\s*([A-Za-z0-9]+)".r
  private val HelperAttribute: Regex = "withAttributeName\\(\"([^\"]+)\"\\)".r
  private val TableRow: Regex        = """(?m)^\s*\|\s*table\s*\|\s*([^|]*?)\s*\|([^|]*)\|([^|]*)\|""".r
  private val Backticked: Regex      = """`([^`]+)`""".r
  private val RangeKeyEntry: Regex   = """\{[^{}]*"KeyType"\s*:\s*"RANGE"[^{}]*\}""".r

  private val Systems: Seq[String] = Seq("journal", "snapshot", "state")

  def check(repo: Repo): Seq[KnownProblem] = {
    val problems = checkColumns(repo) ++ checkIndexes(repo) ++ checkHelperAttributes(repo) ++
      checkDocsClaims(repo) ++ checkLegacyTableShape(repo) ++ checkTableFormatKeys(repo)
    problems
      .groupBy(_.id)
      .map { case (id, found) => KnownProblem(id, found.map(_.detail).distinct.sorted.mkString("; ")) }
      .toSeq
      .sortBy(_.id)
  }

  private def checkColumns(repo: Repo): Seq[KnownProblem] = {
    Systems.flatMap { system =>
      val reference  = referenceColumns(repo, system)
      val documented = documentedColumns(repo, system)

      val referenceOnly = (reference.keySet -- documented.keySet).toSeq.sorted.map { key =>
        KnownProblem(
          columnProblemId(system, "reference-only", key),
          s"$system reference.conf only: $key = ${render(reference(key))}"
        )
      }
      val docsOnly = (documented.keySet -- reference.keySet).toSeq.sorted.map { key =>
        KnownProblem(
          columnProblemId(system, "docs-only", key),
          s"$system customize.md only: $key = ${render(documented(key))}"
        )
      }
      val mismatched = (reference.keySet & documented.keySet).toSeq.sorted.collect {
        case key if reference(key) != documented(key) =>
          KnownProblem(
            columnProblemId(system, "value-mismatch", key),
            s"$system value mismatch: $key reference=${render(reference(key))} docs=${render(documented(key))}"
          )
      }
      referenceOnly ++ docsOnly ++ mismatched
    }
  }

  private def checkIndexes(repo: Repo): Seq[KnownProblem] = {
    val documented = documentedIndexNames(repo)
    val current    = currentJsonIndexNames(repo)
    val legacy     = legacyJsonIndexNames(repo)

    val docsOnly = (documented -- current).toSeq.sorted.map {
      case "TagsIndex" =>
        KnownProblem(
          "journal-tags-index-removed",
          s"table-format.md lists TagsIndex, but no current table JSON creates it; legacy JSON still has it: ${legacy.contains("TagsIndex")}"
        )
      case "GetSnaphotRowsIndex" =>
        KnownProblem(
          "snapshot-get-rows-index-typo",
          "table-format.md spells GetSnaphotRowsIndex; the JSON and reference.conf use GetSnapshotRowsIndex"
        )
      case name =>
        KnownProblem(s"index-docs-only:$name", s"table-format.md lists $name, but no current table JSON creates it")
    }

    val currentOnly = (current -- documented).toSeq.sorted.map {
      case "GetSnapshotRowsIndex" =>
        KnownProblem(
          "snapshot-get-rows-index-typo",
          "the current snapshot table JSON uses GetSnapshotRowsIndex; table-format.md spells it GetSnaphotRowsIndex"
        )
      case name =>
        KnownProblem(
          s"index-undocumented:$name",
          s"the current table JSON creates $name, but table-format.md does not list it"
        )
    }

    docsOnly ++ currentOnly ++ checkReferenceIndexes(repo)
  }

  private def checkReferenceIndexes(repo: Repo): Seq[KnownProblem] = {
    Seq("journal" -> "journal-table.json", "snapshot" -> "snapshot-table.json").flatMap { case (system, jsonFile) =>
      val referenceKey = if (system == "journal") "get-journal-rows-index-name" else "get-snapshot-rows-index-name"
      val quotedKey    = java.util.regex.Pattern.quote(referenceKey)
      val pattern      = ("(?m)^\\s*" + quotedKey + "\\s*[=:]\\s*(?:\"([^\"]+)\"|([^\\s#]+))").r
      val keyPattern   = ("(?m)^\\s*" + quotedKey + "\\s*[=:]").r
      val path         = repo.root.resolve(s"$system/$system-base/src/main/resources/reference.conf")
      val text         = repo.read(path)
      val matches      = pattern.findAllMatchIn(text).toVector
      val keyPresent   = keyPattern.findFirstIn(text).isDefined
      val reference    = matches.map(matched => Option(matched.group(1)).getOrElse(matched.group(2))).toSet
      val table        = indexNamesIn(repo.read(repo.root.resolve(s"tools/dynamodb-setup/$jsonFile")))
      if (matches.isEmpty) {
        Seq(
          KnownProblem(
            if (keyPresent) s"index-reference-unparsed:$system" else s"index-reference-missing-key:$system",
            if (keyPresent) s"$system reference.conf has $referenceKey but its value could not be read"
            else s"$system reference.conf has no $referenceKey"
          )
        )
      } else {
        (reference -- table).toSeq.sorted.map { name =>
          KnownProblem(
            s"index-reference-missing:$system:$name",
            s"$system reference.conf names $name, but $jsonFile does not create it"
          )
        }
      }
    }
  }

  private def checkHelperAttributes(repo: Repo): Seq[KnownProblem] = {
    val helperPath =
      repo.root.resolve(
        "test/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/utils/DynamoDBContainerHelper.scala"
      )
    val helper = HelperAttribute.findAllMatchIn(repo.read(helperPath)).map(_.group(1)).toSet
    val json   = tableJsonFiles(repo).flatMap(path => attributeNamesIn(repo.read(path))).toSet
    (helper -- json).toSeq.sorted.map { name =>
      KnownProblem(
        s"helper-attribute-not-in-json:$name",
        s"DynamoDBContainerHelper uses attribute $name, but no table JSON declares it"
      )
    }
  }

  /** The docs lost the removed index and the state sort-key claim, so they must not come back, and the legacy table
    * JSON must keep its own shape.
    */
  private def checkDocsClaims(repo: Repo): Seq[KnownProblem] = {
    val tableFormat = repo.read(repo.root.resolve("docs/src/sphinx/table-format.md"))
    val customize   = repo.read(repo.root.resolve("docs/src/sphinx/customize.md"))
    val problems    = Vector.newBuilder[KnownProblem]

    if ("""\bpersistenceId\b""".r.findFirstIn(tableFormat).isDefined) {
      problems += KnownProblem(
        "docs-camel-case-persistence-id",
        "table-format.md spells persistenceId; the snapshot index uses the persistence-id column"
      )
    }
    if ("""(?i)has not the sort[- ]key""".r.findFirstIn(customize).isDefined) {
      problems += KnownProblem(
        "state-sort-key-doc-claim",
        "customize.md claims that the state plugin has no sort key, but state reference.conf defines sort-key-column-name and a SortKeyResolver"
      )
    }
    val stateShardingBlocks = docBlocks(customize)
      .filter(block => block.contains("j5ik2o.dynamo-db-state") && block.contains("shard-count"))
    if (stateShardingBlocks.isEmpty || !stateShardingBlocks.exists(_.contains("sort-key-resolver-class-name"))) {
      problems += KnownProblem(
        "state-sort-key-resolver-doc-missing",
        "customize.md does not document the state sort-key-resolver-class-name"
      )
    }
    problems.result()
  }

  /** `table-format.md` and the generated state table JSON both describe the state primary key. The docs once lost the
    * state sort key, so the State row must name the RANGE key that the table JSON declares.
    */
  private def checkTableFormatKeys(repo: Repo): Seq[KnownProblem] =
    stateSortKeyProblem(
      repo.read(repo.root.resolve("docs/src/sphinx/table-format.md")),
      repo.read(repo.root.resolve("tools/dynamodb-setup/state-table.json"))
    ).toSeq

  private def checkLegacyTableShape(repo: Repo): Seq[KnownProblem] = {
    val legacyJournal = repo.read(repo.root.resolve("tools/dynamodb-setup/legacy-journal-table.json"))
    val problems      = Vector.newBuilder[KnownProblem]
    if (!indexNamesIn(legacyJournal).contains("TagsIndex")) {
      problems += KnownProblem(
        "legacy-journal-tags-index-missing",
        "legacy-journal-table.json no longer creates TagsIndex, so the legacy table format changed"
      )
    }
    problems.result()
  }

  private def referenceColumns(repo: Repo, system: String): Map[String, Set[String]] = {
    val path   = repo.root.resolve(s"$system/$system-base/src/main/resources/reference.conf")
    val text   = repo.read(path)
    val headAt = text.indexOf("head {")
    columnPairs(if (headAt >= 0) text.substring(0, headAt) else text)
  }

  private def documentedColumns(repo: Repo, system: String): Map[String, Set[String]] = {
    val markdown = repo.read(repo.root.resolve("docs/src/sphinx/customize.md"))
    val marker   = s"j5ik2o.dynamo-db-$system"
    mergeMaps(
      docBlocks(markdown)
        .filter(block => block.contains(marker) && !block.contains("head {"))
        .map(columnPairs)
    )
  }

  private def columnPairs(text: String): Map[String, Set[String]] = {
    val pairs = ColumnPair
      .findAllMatchIn(text)
      .filter(matched => matched.group(1).endsWith("column-name"))
      .map(matched => (matched.group(1), matched.group(2)))
      .toVector
    pairs
      .groupBy { case (key, _) => key }
      .map { case (key, values) => key -> values.map { case (_, value) => value }.toSet }
  }

  private def mergeMaps(maps: Seq[Map[String, Set[String]]]): Map[String, Set[String]] = {
    maps.foldLeft(Map.empty[String, Set[String]]) { (accumulated, current) =>
      current.foldLeft(accumulated) { case (inner, (key, values)) =>
        inner.updated(key, inner.getOrElse(key, Set.empty) ++ values)
      }
    }
  }

  private def docBlocks(markdown: String): Seq[String] = {
    val lines   = markdown.linesIterator.toVector
    val blocks  = Vector.newBuilder[String]
    val current = new StringBuilder
    var inBlock = false
    lines.foreach { line =>
      val trimmed = line.trim
      if (!inBlock && trimmed.startsWith("```")) {
        inBlock = true
        current.clear()
      } else if (inBlock && trimmed.startsWith("```")) {
        blocks += current.toString()
        inBlock = false
      } else if (inBlock) {
        current.append(line).append('\n')
      }
    }
    blocks.result()
  }

  private def documentedIndexNames(repo: Repo): Set[String] = {
    val markdown = repo.read(repo.root.resolve("docs/src/sphinx/table-format.md"))
    markdown.linesIterator.flatMap(line => IndexRow.findFirstMatchIn(line).map(_.group(1))).toSet
  }

  private def tableJsonFiles(repo: Repo): Seq[Path] =
    repo.files(".json").filter(path => repo.relative(path).startsWith("tools/dynamodb-setup/"))

  private def currentJsonIndexNames(repo: Repo): Set[String] =
    tableJsonFiles(repo)
      .filterNot(path => path.getFileName.toString.startsWith("legacy-"))
      .flatMap(path => indexNamesIn(repo.read(path)))
      .toSet

  private def legacyJsonIndexNames(repo: Repo): Set[String] =
    tableJsonFiles(repo)
      .filter(path => path.getFileName.toString.startsWith("legacy-"))
      .flatMap(path => indexNamesIn(repo.read(path)))
      .toSet

  private def indexNamesIn(json: String): Set[String] =
    IndexName.findAllMatchIn(json).map(_.group(1)).toSet

  private def attributeNamesIn(json: String): Set[String] =
    AttributeName.findAllMatchIn(json).map(_.group(1)).toSet

  private def columnProblemId(system: String, kind: String, key: String): String =
    (system, kind, key) match {
      case ("state", "reference-only", "revision-nr-column-name") => "state-revision-column-name"
      case ("state", "docs-only", "sequence-nr-column-name")      => "state-revision-column-name"
      case ("snapshot", "reference-only", "tags-column-name")     => "snapshot-tags-column-name-missing"
      case _                                                      => s"column-drift:$system:$kind:$key"
    }

  private def render(values: Set[String]): String = values.toSeq.sorted.mkString(", ")

  private[schemacheck] def stateSortKeyProblem(tableFormat: String, stateTableJson: String): Option[KnownProblem] = {
    val documented = TableRow
      .findAllMatchIn(tableFormat)
      .collectFirst { case matched if matched.group(1).trim == "State" => matched.group(3) }
      .flatMap(cell => Backticked.findFirstMatchIn(cell).map(_.group(1)))
    val declared = RangeKeyEntry
      .findFirstMatchIn(stateTableJson)
      .flatMap(entry => AttributeName.findFirstMatchIn(entry.matched).map(_.group(1)))
    (documented, declared) match {
      case (Some(documentedValue), Some(declaredValue)) if documentedValue == declaredValue => None
      case _ =>
        Some(
          KnownProblem(
            "state-sort-key-table-format",
            s"table-format.md State sort key is ${documented.getOrElse("<missing>")}, but state-table.json declares RANGE key ${declared.getOrElse("<missing>")}"
          )
        )
    }
  }
}
