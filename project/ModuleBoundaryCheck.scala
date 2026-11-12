package moduleboundary

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, Files, Path, SimpleFileVisitor }
import scala.collection.mutable
import scala.io.{ Codec, Source }
import scala.util.matching.Regex

// Compiled twice: by the sbt meta-build for checkModuleBoundaries, and by the schema-check tests on every Scala
// version in the cross build, so it uses only the standard library.
object ModuleBoundaryCheck {

  sealed trait ModuleKind
  object ModuleKind {

    /** Wraps the SDK. Its files import the SDK without an allowlist entry. */
    case object Sdk extends ModuleKind

    /** Already holds SDK imports. A file may import the SDK with an allowlist entry that gives the reason. */
    case object SdkHolding extends ModuleKind

    /** Must not import the SDK, with or without an allowlist entry. */
    case object Core extends ModuleKind
  }

  val Modules: Seq[(String, ModuleKind)] = Seq(
    "base/base-v1"         -> ModuleKind.Sdk,
    "base/base-v2"         -> ModuleKind.Sdk,
    "journal/journal-v1"   -> ModuleKind.SdkHolding,
    "journal/journal-v2"   -> ModuleKind.SdkHolding,
    "snapshot/snapshot-v1" -> ModuleKind.SdkHolding,
    "snapshot/snapshot-v2" -> ModuleKind.SdkHolding,
    "state/state-v1"       -> ModuleKind.SdkHolding,
    "state/state-v2"       -> ModuleKind.SdkHolding,
    "test"                 -> ModuleKind.SdkHolding,
    "example"              -> ModuleKind.SdkHolding,
    "metrics/metrics-v2"   -> ModuleKind.SdkHolding
  )

  def kindOf(path: String): ModuleKind =
    Modules.collectFirst { case (module, kind) if path.startsWith(module + "/") => kind }.getOrElse(ModuleKind.Core)

  final case class Entry(path: String, reason: String, line: Int)

  final case class Allowlist(entries: Seq[Entry], errors: Seq[String])

  final case class Result(errors: Seq[String], sdkFiles: Int, allowlisted: Int)

  private val SdkImport: Regex =
    """^\s*import\s+(com\.amazonaws|software\.amazon\.awssdk|software\.amazon\.dax)\b""".r

  private val ExcludedDirectories: Set[String] = Set("target", ".git", ".bsp", ".bloop", ".metals", ".idea")

  def run(base: Path, allowlistFile: Path): Result = {
    if (!Files.isRegularFile(allowlistFile)) {
      Result(Seq(s"module boundary allowlist not found: $allowlistFile"), 0, 0)
    } else {
      val text    = new String(Files.readAllBytes(allowlistFile), StandardCharsets.UTF_8)
      val result  = check(scalaFiles(base).filter(hasSdkImport).map(path => relative(base, path)).toSet, parse(text))
      val missing = Modules.map(_._1).filterNot(module => Files.isDirectory(base.resolve(module)))
      if (missing.isEmpty) result
      else
        result.copy(errors =
          result.errors ++ missing.map(module => s"ModuleBoundaryCheck.Modules names $module, which does not exist.")
        )
    }
  }

  def parse(text: String): Allowlist = {
    val errors  = mutable.ListBuffer.empty[String]
    val entries = mutable.ListBuffer.empty[Entry]
    text.split("\n", -1).zipWithIndex.foreach { case (raw, index) =>
      val line = raw.trim
      if (line.nonEmpty && !line.startsWith("#")) {
        val hash   = line.indexOf('#')
        val path   = if (hash < 0) line else line.substring(0, hash).trim
        val reason = if (hash < 0) "" else line.substring(hash + 1).trim
        if (reason.isEmpty) {
          errors += s"line ${index + 1}: $path has no reason. Write it as `<path>  # <why this file imports the SDK>`."
        }
        entries += Entry(path, reason, index + 1)
      }
    }
    entries.groupBy(_.path).collect { case (path, dups) if dups.size > 1 => path }.toSeq.sorted.foreach { path =>
      errors += s"$path is listed more than once."
    }
    Allowlist(entries.toVector, errors.toVector)
  }

  def check(sdkFiles: Set[String], allowlist: Allowlist): Result = {
    val entries = allowlist.entries
    val listed  = entries.map(_.path).toSet

    val core       = sdkFiles.filter(kindOf(_) == ModuleKind.Core).toSeq.sorted
    val unlisted   = sdkFiles.filter(path => kindOf(path) == ModuleKind.SdkHolding && !listed(path)).toSeq.sorted
    val needless   = entries.map(_.path).filter(kindOf(_) == ModuleKind.Sdk).sorted
    val stale      = entries.map(_.path).filter(path => kindOf(path) != ModuleKind.Sdk && !sdkFiles(path)).sorted
    val coreListed = core.filter(listed)

    val lines = mutable.ListBuffer.empty[String]
    if (allowlist.errors.nonEmpty) {
      lines += "Malformed entries in project/module-boundaries.allowlist:"
      lines ++= allowlist.errors.map(error => "  ! " + error)
    }
    if (core.nonEmpty) {
      lines += "Files that import an AWS SDK in a module that must not hold one. Move the SDK code to base-v1, " +
      "base-v2, or a binding module; an allowlist entry does not admit these:"
      lines ++= core.map(path => if (coreListed.contains(path)) s"  + $path (allowlisted)" else "  + " + path)
    }
    if (unlisted.nonEmpty) {
      lines += "Files that import an AWS SDK but are not in project/module-boundaries.allowlist. This module " +
      "already holds SDK imports, so add each file as `<path>  # <reason>` and give the reason in the PR too:"
      lines ++= unlisted.map(path => "  + " + path)
    }
    if (needless.nonEmpty) {
      lines += "Allowlist entries in base-v1 or base-v2, which import the SDK without an entry. Remove them:"
      lines ++= needless.map(path => "  - " + path)
    }
    if (stale.nonEmpty) {
      lines += "Allowlist entries that no longer import an AWS SDK. Remove them, because an entry must reproduce:"
      lines ++= stale.map(path => "  - " + path)
    }
    Result(lines.toVector, sdkFiles.size, entries.size)
  }

  private def scalaFiles(base: Path): Seq[Path] = {
    val collected = mutable.ListBuffer.empty[Path]
    Files.walkFileTree(
      base,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult = {
          if (directory != base && ExcludedDirectories.contains(directory.getFileName.toString)) {
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
    collected.toVector
  }

  private def hasSdkImport(path: Path): Boolean = {
    val source = Source.fromFile(path.toFile)(Codec.UTF8)
    try {
      source.getLines().exists(line => SdkImport.findFirstIn(line).isDefined)
    } finally {
      source.close()
    }
  }

  private def relative(base: Path, path: Path): String =
    base.relativize(path).toString.replace('\\', '/')
}
