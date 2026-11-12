package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.RenderedOperation

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, Files, Path, SimpleFileVisitor }
import scala.collection.mutable
import scala.util.matching.Regex

final case class ApiDeclaration(module: String, path: String, owner: String, header: String) {
  def render: String = s"$module\t$path\t$owner\t$header"
}

object ApiDeclaration {
  def parse(line: String): Option[ApiDeclaration] = {
    val fields = line.split("\t", -1)
    if (fields.length == 4) Some(ApiDeclaration(fields(0), fields(1), fields(2), fields(3))) else None
  }
}

/** Extracts the public declaration surface of the published modules from their Scala sources.
  *
  * The gate protects the declarations that existed before a change. A declaration that disappears from a module is a
  * potential compatibility break for callers. New declarations must also be registered so subsequent removals cannot
  * escape the gate.
  */
object PublicApiSurface {

  val Modules: Vector[(String, String)] = Vector(
    "base"          -> "base/base/src/main",
    "base-v1"       -> "base/base-v1/src/main",
    "base-v2"       -> "base/base-v2/src/main",
    "journal-base"  -> "journal/journal-base/src/main",
    "journal-v1"    -> "journal/journal-v1/src/main",
    "journal-v2"    -> "journal/journal-v2/src/main",
    "snapshot-base" -> "snapshot/snapshot-base/src/main",
    "snapshot-v1"   -> "snapshot/snapshot-v1/src/main",
    "snapshot-v2"   -> "snapshot/snapshot-v2/src/main",
    "state-base"    -> "state/state-base/src/main",
    "state-v1"      -> "state/state-v1/src/main",
    "state-v2"      -> "state/state-v2/src/main"
  )

  /** Packages whose declarations stay outside the gate, each with the reason. A file is excluded when its package is a
    * listed package or a subpackage of one. Each package is named through one of its types, so moving the package
    * breaks the build instead of silently excluding nothing.
    */
  val ExcludedPackages: Map[String, String] = Map(
    classOf[RenderedOperation].getPackage.getName ->
    "Renderer input that base, base-v1, and base-v2 use. It is internal and has no compatibility guarantee (#862)."
  )

  private val ExcludedDirectories = Set("target", ".git", ".bsp", ".bloop", ".metals", ".idea")

  private val PackageClause: Regex = """(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;?\s*$""".r

  private val StartPattern: Regex =
    """(?:package\s+)?((?:(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?|sealed|final|abstract|implicit|override|lazy|case|transient|private(?:\[[^\]]*\])?|protected(?:\[[^\]]*\])?)\s+)*)(class|trait|object|def|val|var|type)\b""".r

  private final case class Start(keyword: String, name: String, isPrivate: Boolean, isType: Boolean)

  private final case class Owner(name: String, bodyDepth: Int, isPrivate: Boolean, isType: Boolean)

  private final case class Captured(header: String, nextIndex: Int, bodyOpen: Boolean)

  def of(root: Path): Vector[ApiDeclaration] = {
    val declarations = Vector.newBuilder[ApiDeclaration]
    Modules.foreach { case (module, relativeRoot) =>
      val moduleRoot = root.resolve(relativeRoot)
      if (Files.isDirectory(moduleRoot)) {
        scalaFiles(moduleRoot).foreach { file =>
          val text     = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
          val relative = root.relativize(file).toString.replace('\\', '/')
          if (!isExcludedPackage(packageOf(text))) declarations ++= scan(module, relative, text)
        }
      }
    }
    declarations
      .result().distinct.sortBy(declaration =>
        (declaration.module, declaration.path, declaration.owner, declaration.header)
      )
  }

  def parse(lines: Iterator[String]): Vector[ApiDeclaration] =
    lines.map(_.trim).filter(_.nonEmpty).filterNot(_.startsWith("#")).flatMap(ApiDeclaration.parse).toVector

  def render(declarations: Vector[ApiDeclaration]): String = {
    val header =
      "# Public declarations of the published modules. The check fails on removed or unregistered declarations.\n" +
      "# Format: module<TAB>path<TAB>owner<TAB>declaration\n" +
      "# Regenerate with: sbt \"schema-check/runMain com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck.ApiBaseline <repo-root> schema-check/public-api-baseline.txt\"\n"
    header + declarations.map(_.render).mkString("\n") + "\n"
  }

  def missing(baseline: Vector[ApiDeclaration], current: Vector[ApiDeclaration]): Vector[ApiDeclaration] = {
    val currentSet = current.toSet
    baseline.filterNot(currentSet.contains)
  }

  def added(baseline: Vector[ApiDeclaration], current: Vector[ApiDeclaration]): Vector[ApiDeclaration] = {
    val baselineSet = baseline.toSet
    current.filterNot(baselineSet.contains)
  }

  private def packageOf(text: String): String =
    PackageClause.findAllMatchIn(stripComments(text)).map(_.group(1)).mkString(".")

  private def isExcludedPackage(name: String): Boolean =
    ExcludedPackages.keys.exists(excluded => name == excluded || name.startsWith(excluded + "."))

  private def scan(module: String, path: String, text: String): Vector[ApiDeclaration] = {
    val clean        = stripComments(text)
    val declarations = Vector.newBuilder[ApiDeclaration]
    val owners       = mutable.ArrayBuffer.empty[Owner]
    var depth        = 0
    var index        = 0
    var atLineStart  = true

    def popClosedOwners(): Unit = {
      while (owners.nonEmpty && depth < owners.last.bodyDepth) {
        owners.remove(owners.length - 1)
      }
    }

    def consumeChar(): Unit = {
      val c = clean.charAt(index)
      if (c == '"') {
        index = skipString(clean, index)
      } else if (c == '\'' && isCharLiteral(clean, index)) {
        index = skipCharLiteral(clean, index)
      } else {
        if (c == '{') {
          depth += 1
        } else if (c == '}') {
          depth -= 1
          popClosedOwners()
        }
        index += 1
      }
    }

    while (index < clean.length) {
      val c = clean.charAt(index)
      if (c == '\n') {
        index += 1
        atLineStart = true
      } else if (c == ' ' || c == '\t' || c == '\r') {
        index += 1
      } else if (atLineStart) {
        startAt(clean, index) match {
          case Some(start) =>
            val captured    = capture(clean, index, start.keyword)
            val header      = normalize(captured.header)
            val ownerIsType = owners.forall(_.isType)
            val ownerHidden = owners.exists(_.isPrivate)
            val recordable  = depth == 0 || (owners.nonEmpty && ownerIsType && !ownerHidden)
            if (recordable && !start.isPrivate && header.nonEmpty) {
              declarations += ApiDeclaration(module, path, owners.map(_.name).mkString("."), header)
            }
            if (captured.bodyOpen) {
              owners += Owner(start.name, depth + 1, start.isPrivate, start.isType)
              depth += 1
            }
            index = captured.nextIndex
            atLineStart = index == clean.length || clean.charAt(index) == '\n'
          case None =>
            atLineStart = false
            consumeChar()
        }
      } else {
        consumeChar()
      }
    }
    declarations.result()
  }

  private def startAt(text: String, index: Int): Option[Start] = {
    val end   = lineEnd(text, index)
    val line  = text.substring(index, end)
    val found = StartPattern.findPrefixMatchOf(line)
    found.map { matched =>
      val modifiers = matched.group(1)
      val keyword   = matched.group(2)
      val rest      = line.substring(matched.end)
      val name      = identifier(rest).getOrElse(keyword)
      Start(
        keyword = keyword,
        name = name,
        isPrivate = modifiers.contains("private") || modifiers.contains("protected"),
        isType = keyword == "class" || keyword == "trait" || keyword == "object"
      )
    }
  }

  private def identifier(rest: String): Option[String] = {
    val trimmed = rest.dropWhile(character => character == ' ' || character == '\t')
    val end =
      trimmed.indexWhere(character => !(Character.isLetterOrDigit(character) || character == '_' || character == '$'))
    if (end == 0) None else Some(trimmed.substring(0, if (end < 0) trimmed.length else end))
  }

  private def capture(text: String, start: Int, keyword: String): Captured = {
    val header = new StringBuilder
    var index  = start
    var paren  = 0
    var square = 0
    var done   = false
    var body   = false
    val isType = keyword == "class" || keyword == "trait" || keyword == "object"

    while (index < text.length && !done) {
      val c = text.charAt(index)
      c match {
        case '"' =>
          val next = skipString(text, index)
          header.append(text.substring(index, next))
          index = next
        case '\'' if isCharLiteral(text, index) =>
          val next = skipCharLiteral(text, index)
          header.append(text.substring(index, next))
          index = next
        case '(' =>
          paren += 1
          header.append(c)
          index += 1
        case ')' =>
          paren -= 1
          header.append(c)
          index += 1
        case '[' =>
          square += 1
          header.append(c)
          index += 1
        case ']' =>
          square -= 1
          header.append(c)
          index += 1
        case '{' if paren == 0 && square == 0 =>
          body = true
          done = true
          index += 1
        case '=' if paren == 0 && square == 0 && !isArrow(text, index) =>
          if (isType) {
            done = true
          } else {
            return Captured(header.toString, implementationEnd(text, index + 1), bodyOpen = false)
          }
          index += 1
        case '\n' if paren == 0 && square == 0 =>
          if (continues(header.toString) || (isType && typeHeaderContinues(text, index + 1))) {
            header.append(' ')
            index += 1
          } else {
            done = true
          }
        case _ =>
          header.append(c)
          index += 1
      }
    }
    Captured(header.toString, index, body)
  }

  private def typeHeaderContinues(text: String, start: Int): Boolean = {
    val next = text.substring(start).dropWhile(_.isWhitespace)
    next.startsWith("{") || next.startsWith("(") || next.startsWith("[") ||
    next.matches("(?s)^(extends|with)\\b.*")
  }

  private def implementationEnd(text: String, start: Int): Int = {
    var index               = start
    var nesting             = 0
    var started             = false
    var expressionLineStart = start
    while (index < text.length) {
      val c = text.charAt(index)
      c match {
        case '"' =>
          index = skipString(text, index)
          started = true
        case '\'' if isCharLiteral(text, index) =>
          index = skipCharLiteral(text, index)
          started = true
        case '(' | '[' | '{' =>
          nesting += 1
          started = true
          index += 1
        case '}' if nesting == 0 => return index
        case ')' | ']' | '}' =>
          nesting -= 1
          index += 1
        case ';' if nesting == 0 => return index + 1
        case '\n' =>
          var next = index + 1
          while (next < text.length && text.charAt(next).isWhitespace) next += 1
          val tail     = text.substring(next)
          val previous = text.substring(expressionLineStart, index).trim
          val continuation = tail.startsWith(".") || tail.startsWith("{") ||
            tail.matches("(?s)^(else|catch|finally|yield|match)\\b.*") || continues(previous)
          if (nesting == 0 && started && !continuation) return index
          expressionLineStart = index + 1
          index += 1
        case _ =>
          if (!c.isWhitespace) started = true
          index += 1
      }
    }
    index
  }

  private def continues(header: String): Boolean = {
    val trimmed = header.trim
    trimmed.endsWith(",") ||
    trimmed.endsWith("(") ||
    trimmed.endsWith("[") ||
    trimmed.endsWith("=") ||
    trimmed.endsWith("=>") ||
    trimmed.endsWith("extends") ||
    trimmed.endsWith("with") ||
    trimmed.endsWith(":")
  }

  private def isArrow(text: String, index: Int): Boolean =
    index + 1 < text.length && text.charAt(index + 1) == '>'

  private def isCharLiteral(text: String, index: Int): Boolean = {
    if (index + 2 >= text.length) {
      false
    } else if (text.charAt(index + 1) == '\\') {
      index + 3 < text.length && text.charAt(index + 3) == '\''
    } else {
      text.charAt(index + 2) == '\''
    }
  }

  private def skipString(text: String, start: Int): Int = {
    val triple = start + 2 < text.length && text.charAt(start + 1) == '"' && text.charAt(start + 2) == '"'
    if (triple) {
      var index = start + 3
      while (
        index + 2 < text.length && !(text.charAt(index) == '"' && text
          .charAt(index + 1) == '"' && text.charAt(index + 2) == '"')
      ) {
        index += 1
      }
      math.min(text.length, if (index + 2 < text.length) index + 3 else text.length)
    } else {
      var index = start + 1
      var done  = false
      while (index < text.length && !done) {
        val c = text.charAt(index)
        if (c == '\\' && index + 1 < text.length) {
          index += 2
        } else if (c == '"') {
          done = true
          index += 1
        } else {
          index += 1
        }
      }
      index
    }
  }

  private def skipCharLiteral(text: String, start: Int): Int = {
    val end = if (start + 1 < text.length && text.charAt(start + 1) == '\\') start + 4 else start + 3
    math.min(text.length, end)
  }

  private def lineEnd(text: String, start: Int): Int = {
    val end = text.indexOf('\n', start)
    if (end < 0) text.length else end
  }

  private def normalize(header: String): String =
    header.split("\\s+").filter(_.nonEmpty).mkString(" ").trim

  private def stripComments(text: String): String = {
    val out    = new StringBuilder(text.length)
    var index  = 0
    var block  = false
    var line   = false
    var string = false
    var triple = false

    while (index < text.length) {
      val c     = text.charAt(index)
      val next  = if (index + 1 < text.length) text.charAt(index + 1) else '\u0000'
      val third = if (index + 2 < text.length) text.charAt(index + 2) else '\u0000'
      if (block) {
        if (c == '*' && next == '/') {
          block = false
          index += 2
        } else {
          if (c == '\n') out.append('\n')
          index += 1
        }
      } else if (line) {
        if (c == '\n') {
          line = false
          out.append('\n')
        }
        index += 1
      } else if (triple) {
        if (c == '"' && next == '"' && third == '"') {
          out.append("\"\"\"")
          triple = false
          string = false
          index += 3
        } else {
          out.append(c)
          index += 1
        }
      } else if (string) {
        if (c == '"') {
          out.append(c)
          string = false
          index += 1
        } else if (c == '\\' && index + 1 < text.length) {
          out.append(c)
          out.append(text.charAt(index + 1))
          index += 2
        } else {
          out.append(c)
          index += 1
        }
      } else if (c == '/' && next == '/') {
        line = true
        index += 2
      } else if (c == '/' && next == '*') {
        block = true
        index += 2
      } else if (c == '"' && next == '"' && third == '"') {
        out.append("\"\"\"")
        triple = true
        string = true
        index += 3
      } else {
        out.append(c)
        if (c == '"') string = true
        index += 1
      }
    }
    out.toString
  }

  private def scalaFiles(root: Path): Vector[Path] = {
    val collected = mutable.ListBuffer.empty[Path]
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult = {
          if (directory != root && ExcludedDirectories.contains(directory.getFileName.toString)) {
            FileVisitResult.SKIP_SUBTREE
          } else {
            FileVisitResult.CONTINUE
          }
        }

        override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
          if (file.getFileName.toString.endsWith(".scala")) collected += file
          FileVisitResult.CONTINUE
        }
      }
    )
    collected.toVector.sortBy(_.toString)
  }
}
