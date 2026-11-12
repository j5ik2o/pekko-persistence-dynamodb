package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.util.regex.Pattern

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class DockerSuiteReferenceSpec extends AnyFreeSpec with Matchers {

  private val repo    = Repo.locate()
  private val agents  = repo.read(repo.root.resolve("AGENTS.md"))
  private val sources = TestSources.scan(repo, AgentsDoc.moduleDirectories(agents))

  "the Docker cost section lists every concrete DynamoDBContainerHelper suite under its module" in {
    val containerSuites = sources.containerSuites
    val discovered      = containerSuites.map(s => (s.module, s.name)).toSet
    val listed          = AgentsDoc.dockerCostSuites(agents)
    val counts          = AgentsDoc.dockerCostCounts(agents)

    containerSuites.foreach(s => info(s"container suite: ${s.module} ${s.name}"))
    info(s"concrete container suites: ${containerSuites.size}, stated counts: ${counts.mkString(", ")}")

    containerSuites should not be empty
    (listed.toSet -- discovered) shouldBe empty
    (discovered -- listed.toSet) shouldBe empty
    counts should not be empty
    all(counts) shouldBe containerSuites.size
  }

  "every testOnly pattern in Docker cost and Verification commands resolves to a concrete test class" in {
    val commands = AgentsDoc.testOnlyCommands(agents, "## Docker cost") ++
      AgentsDoc.testOnlyCommands(agents, "## Verification commands")
    commands should not be empty

    val unresolved = commands.filter { case (module, pattern) => sources.resolve(module, pattern).isEmpty }
    commands.foreach { case (module, pattern) =>
      info(s"$module/testOnly $pattern -> ${sources.resolve(module, pattern).map(_.name).mkString(", ")}")
    }
    unresolved shouldBe empty
  }

  "a suite that inherits the helper through an abstract class counts, and the abstract class does not" in {
    val source =
      "package a\n" +
      "abstract class Base(port: Int) extends TestKit(\n  system(\"x\")\n)\n    with DynamoDBContainerHelper {\n}\n" +
      "package nested {\n" +
      "  object LeafSpec extends DockerAssignedPort\n\n" +
      "  class LeafSpec\n      extends Base(\n        LeafSpec.port\n      ) {\n  }\n" +
      "}\n" +
      "final class PlainSpec extends AnyFreeSpec with Matchers\n"

    val parsed = TestSources(TestSources.declarations("m", source))

    parsed.containerSuites.map(_.name) shouldBe Vector("LeafSpec")
    parsed.resolve("m", "*Spec").map(_.name).toSet shouldBe Set("LeafSpec", "PlainSpec")
    parsed.resolve("m", "*Base") shouldBe empty
    parsed.resolve("m", "LeafSpec") shouldBe empty
  }
}

private final case class Declaration(module: String, name: String, concrete: Boolean, parents: Set[String])

private final case class TestSources(declarations: Seq[Declaration]) {

  private val Helper = "DynamoDBContainerHelper"

  private val parentsByName: Map[String, Set[String]] =
    declarations.groupBy(_.name).map { case (name, ds) => name -> ds.flatMap(_.parents).toSet }

  private def inherits(name: String, target: String): Boolean = {
    @annotation.tailrec
    def loop(pending: List[String], seen: Set[String]): Boolean = pending match {
      case Nil                         => false
      case head :: _ if head == target => true
      case head :: tail if seen(head)  => loop(tail, seen)
      case head :: tail                => loop(parentsByName.getOrElse(head, Set.empty).toList ++ tail, seen + head)
    }
    loop(parentsByName.getOrElse(name, Set.empty).toList, Set(name))
  }

  def containerSuites: Seq[Declaration] =
    declarations.filter(d => d.concrete && inherits(d.name, Helper))

  /** sbt matches a testOnly glob against the fully qualified name, and only `*Name` globs are supported here. */
  def resolve(module: String, pattern: String): Seq[Declaration] =
    if (!pattern.startsWith("*") || pattern.contains(".")) Vector.empty
    else {
      val regex = Pattern.compile(pattern.split("\\*", -1).map(Pattern.quote).mkString(".*"))
      declarations.filter(d => d.module == module && d.concrete && regex.matcher(d.name).matches())
    }
}

private object TestSources {

  private val Declared = Pattern.compile(
    "(?m)^[ \\t]*((?:(?:final|abstract|sealed|private|protected|case)(?:\\[[^\\]]*\\])?[ \\t]+)*)(class|trait|object)[ \\t]+([A-Za-z_][A-Za-z0-9_]*)"
  )
  private val Parent = Pattern.compile("\\b(?:extends|with)\\s+([A-Za-z_][A-Za-z0-9_.]*)")

  def scan(repo: Repo, modules: Map[String, String]): TestSources = {
    val roots = modules.collect {
      case (module, dir) if dir != "." => (dir.stripSuffix("/") + "/src/test/scala/", module)
    }
    TestSources(repo.files(".scala").flatMap { path =>
      val relative = repo.relative(path)
      roots
        .collectFirst { case (root, module) if relative.startsWith(root) => module }.toVector
        .flatMap(module => declarations(module, repo.read(path)))
    })
  }

  def declarations(module: String, source: String): Vector[Declaration] = {
    val matcher = Declared.matcher(source)
    val result  = Vector.newBuilder[Declaration]
    while (matcher.find()) {
      if (matcher.group(2) != "object") {
        val parents = Vector.newBuilder[String]
        val parent  = Parent.matcher(headerAt(source, matcher.end()))
        while (parent.find()) parents += parent.group(1).split('.').last
        result += Declaration(
          module,
          matcher.group(3),
          matcher.group(2) == "class" && !matcher.group(1).contains("abstract"),
          parents.result().toSet
        )
      }
    }
    result.result()
  }

  /** The text between a declared name and its body, without constructor arguments or type parameters. */
  private def headerAt(source: String, start: Int): String = {
    val header = new StringBuilder
    var depth  = 0
    var i      = start
    var done   = false
    while (!done && i < source.length) {
      val c = source.charAt(i)
      if (c == '(' || c == '[') depth += 1
      else if (c == ')' || c == ']') depth -= 1
      else if (depth == 0 && c == '{') done = true
      else if (depth == 0 && c == '\n') {
        val next = source.substring(i + 1).dropWhile(_.isWhitespace)
        if (next.startsWith("extends ") || next.startsWith("with ")) header += ' ' else done = true
      } else if (depth == 0) header += c
      i += 1
    }
    header.toString
  }
}

private object AgentsDoc {

  private val ModuleRow  = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|\\s*`([^`]+)`\\s*\\|")
  private val Backticked = Pattern.compile("`([^`]+)`")
  private val Count      = Pattern.compile("(\\d+) (?:concrete test classes|containers)\\b")
  private val TestOnly   = Pattern.compile("\"([A-Za-z0-9-]+)/testOnly ([^\" ]+)\"")

  private def section(source: String, heading: String): Vector[String] = {
    val lines = source.split("\n", -1).toVector
    val start = lines.indexWhere(_.trim == heading)
    require(start >= 0, s"AGENTS.md has no '$heading' section")
    lines.drop(start + 1).takeWhile(line => !line.startsWith("## "))
  }

  def moduleDirectories(source: String): Map[String, String] =
    section(source, "## Module map").flatMap { line =>
      val m = ModuleRow.matcher(line.trim)
      if (m.find()) Some(m.group(1) -> m.group(2)) else None
    }.toMap

  def dockerCostSuites(source: String): Vector[(String, String)] =
    section(source, "## Docker cost").filter(_.trim.startsWith("|")).flatMap { line =>
      val cells = line.trim.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim)
      if (cells.length != 2 || !cells(0).startsWith("`")) Vector.empty
      else {
        val module = cells(0).stripPrefix("`").stripSuffix("`")
        val names  = Backticked.matcher(cells(1))
        val found  = Vector.newBuilder[(String, String)]
        while (names.find()) found += (module -> names.group(1))
        found.result()
      }
    }

  def dockerCostCounts(source: String): Vector[Int] = {
    val m     = Count.matcher(section(source, "## Docker cost").mkString("\n"))
    val found = Vector.newBuilder[Int]
    while (m.find()) found += m.group(1).toInt
    found.result()
  }

  def testOnlyCommands(source: String, heading: String): Vector[(String, String)] = {
    val m     = TestOnly.matcher(section(source, heading).mkString("\n"))
    val found = Vector.newBuilder[(String, String)]
    while (m.find()) found += (m.group(1) -> m.group(2))
    found.result()
  }
}
