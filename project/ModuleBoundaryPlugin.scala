import sbt._
import sbt.Keys._

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, Files, Path => NioPath, SimpleFileVisitor }
import scala.collection.mutable
import scala.io.{ Codec, Source }

object ModuleBoundaryPlugin extends AutoPlugin {

  object autoImport {
    val checkModuleBoundaries: TaskKey[Unit] =
      taskKey[Unit]("Fail when a file imports an AWS SDK outside project/module-boundaries.allowlist.")
    val moduleBoundariesAllowlist: SettingKey[File] =
      settingKey[File]("The file that lists the files allowed to import the AWS SDKs.")
  }

  import autoImport._

  override def trigger: PluginTrigger = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    moduleBoundariesAllowlist := repoRoot(baseDirectory.value) / "project" / "module-boundaries.allowlist",
    checkModuleBoundaries / aggregate := false,
    checkModuleBoundaries := {
      ModuleBoundaryCheck.run(
        repoRoot(baseDirectory.value).toPath,
        moduleBoundariesAllowlist.value.toPath,
        streams.value.log
      )
    }
  )

  private def repoRoot(start: File): File = {
    @annotation.tailrec
    def loop(current: File): File = {
      if (current == null) {
        sys.error(s"cannot find build.sbt above $start")
      } else if ((current / "build.sbt").isFile) {
        current
      } else {
        loop(current.getParentFile)
      }
    }
    loop(start.getAbsoluteFile)
  }
}

private object ModuleBoundaryCheck {

  private val SdkImport: scala.util.matching.Regex =
    """^\s*import\s+(com\.amazonaws|software\.amazon\.awssdk|software\.amazon\.dax)\b""".r

  private val ExcludedDirectories: Set[String] = Set("target", ".git", ".bsp", ".bloop", ".metals", ".idea")

  def run(base: NioPath, allowlistFile: NioPath, log: sbt.util.Logger): Unit = {
    val allowlist = readAllowlist(allowlistFile)
    val offenders = scalaFiles(base).filter(hasSdkImport).map(path => relative(base, path)).toSet
    val added     = (offenders -- allowlist).toSeq.sorted
    val removed   = (allowlist -- offenders).toSeq.sorted

    if (added.nonEmpty || removed.nonEmpty) {
      val lines = mutable.ListBuffer.empty[String]
      if (added.nonEmpty) {
        lines += "Files that import an AWS SDK but are not in project/module-boundaries.allowlist:"
        lines ++= added.map(path => "  + " + path)
      }
      if (removed.nonEmpty) {
        lines += "Allowlist entries that no longer import an AWS SDK. Remove them, because the list only shrinks:"
        lines ++= removed.map(path => "  - " + path)
      }
      sys.error(lines.mkString("\n"))
    }

    log.info(s"checkModuleBoundaries: ${offenders.size} allowlisted files import an AWS SDK; no new imports.")
  }

  private def readAllowlist(path: NioPath): Set[String] = {
    if (!Files.isRegularFile(path)) {
      sys.error(s"module boundary allowlist not found: $path")
    }
    val source = Source.fromFile(path.toFile)(Codec.UTF8)
    try {
      source.getLines().map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).toSet
    } finally {
      source.close()
    }
  }

  private def scalaFiles(base: NioPath): Seq[NioPath] = {
    val collected = mutable.ListBuffer.empty[NioPath]
    Files.walkFileTree(
      base,
      new SimpleFileVisitor[NioPath] {
        override def preVisitDirectory(directory: NioPath, attributes: BasicFileAttributes): FileVisitResult = {
          if (directory != base && ExcludedDirectories.contains(directory.getFileName.toString)) {
            FileVisitResult.SKIP_SUBTREE
          } else {
            FileVisitResult.CONTINUE
          }
        }

        override def visitFile(file: NioPath, attributes: BasicFileAttributes): FileVisitResult = {
          if (file.getFileName.toString.endsWith(".scala")) collected += file
          FileVisitResult.CONTINUE
        }
      }
    )
    collected.toVector
  }

  private def hasSdkImport(path: NioPath): Boolean = {
    val source = Source.fromFile(path.toFile)(Codec.UTF8)
    try {
      source.getLines().exists(line => SdkImport.findFirstIn(line).isDefined)
    } finally {
      source.close()
    }
  }

  private def relative(base: NioPath, path: NioPath): String =
    base.relativize(path).toString.replace('\\', '/')
}
