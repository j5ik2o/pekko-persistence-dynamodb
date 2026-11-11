package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.funsuite.AnyFunSuite

final class WorkflowTimeoutSpec extends AnyFunSuite {

  test("every workflow job has a timeout") {
    val repo      = Repo.locate()
    val workflows = repo.root.resolve(".github/workflows")
    val files     = Files.list(workflows)
    val paths =
      try {
        val iterator = files.iterator()
        val found    = scala.collection.mutable.ListBuffer.empty[Path]
        while (iterator.hasNext) {
          val path = iterator.next()
          if (path.getFileName.toString.endsWith(".yml")) found += path
        }
        found.toVector.sortBy(repo.relative)
      } finally files.close()

    val jobs = "^  ([A-Za-z0-9_-]+):\\s*(?:#.*)?$".r
    val missingTimeouts = paths.flatMap { path =>
      val lines      = repo.read(path).split("\n", -1).toVector
      val jobsHeader = lines.indexWhere(_.matches("^jobs:\\s*(?:#.*)?$"))
      if (jobsHeader < 0) Vector.empty
      else {
        val jobLines = lines.drop(jobsHeader + 1)
        val starts = jobLines.zipWithIndex.collect { case (jobs(id), index) =>
          id -> index
        }
        starts.zipWithIndex.collect {
          case ((id, start), index) if {
                val end        = starts.lift(index + 1).map(_._2).getOrElse(jobLines.length)
                val body       = jobLines.slice(start + 1, end)
                val reusable   = body.exists(_.matches("^    uses:\\s*.+$"))
                val hasTimeout = body.exists(_.matches("^    timeout-minutes:\\s*.+$"))
                !reusable && !hasTimeout
              } =>
            s"${repo.relative(path)}: $id"
        }
      }
    }

    // Reusable workflow calls declare `uses:` at job level. GitHub does not allow
    // `timeout-minutes` on those caller jobs, so only executable jobs are checked.
    assert(missingTimeouts.isEmpty, s"Jobs missing timeout-minutes:\n${missingTimeouts.mkString("\n")}")
  }
}
