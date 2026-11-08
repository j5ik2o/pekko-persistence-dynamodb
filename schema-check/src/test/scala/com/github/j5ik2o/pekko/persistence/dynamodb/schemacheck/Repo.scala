package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, Files, Path, Paths, SimpleFileVisitor }
import scala.collection.mutable

final case class Repo(root: Path) {

  private val ExcludedDirectories = Set("target", ".git", ".bsp", ".bloop", ".metals", ".idea")

  def files(suffix: String): Seq[Path] = {
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
          if (file.getFileName.toString.endsWith(suffix)) collected += file
          FileVisitResult.CONTINUE
        }
      }
    )
    collected.toVector.sortBy(relative)
  }

  def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  def relative(path: Path): String =
    root.relativize(path).toString.replace('\\', '/')
}

object Repo {

  def locate(): Repo = {
    val configured = Option(System.getProperty("repo.root")).filter(_.nonEmpty)
    val start      = configured.map(Paths.get(_)).getOrElse(Paths.get(System.getProperty("user.dir")))
    Repo(walkUp(start.toAbsolutePath.normalize()))
  }

  private def walkUp(start: Path): Path = {
    @annotation.tailrec
    def loop(current: Path): Path = {
      if (current == null) {
        sys.error(s"cannot find build.sbt above $start")
      } else if (Files.isRegularFile(current.resolve("build.sbt"))) {
        current
      } else {
        loop(current.getParent)
      }
    }
    loop(start)
  }
}
