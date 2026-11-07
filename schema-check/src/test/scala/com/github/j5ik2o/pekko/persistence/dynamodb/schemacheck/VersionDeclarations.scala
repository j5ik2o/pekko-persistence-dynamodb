package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import scala.util.matching.Regex

final case class VersionProblem(id: String, detail: String)

object VersionDeclarations {

  private val SbtToolVersion: Regex   = """(?m)^sbt\s+(\S+)\s*$""".r
  private val SbtBuildProperty: Regex = """(?m)^sbt\.version\s*=\s*(\S+)\s*$""".r
  private val CiJdkMatrix: Regex      = """(?m)^\s*jdk:\s*\[([^\]]*)\]\s*$""".r

  private val DocumentedJavaClaims = Seq("README.md", "docs/src/sphinx/overview.md")

  def check(repo: Repo): Seq[VersionProblem] = {
    val testedJdks = jdkMatrix(repo.read(repo.root.resolve(".github/workflows/ci.yml")))
    val ciProblems =
      if (testedJdks.isEmpty) {
        Seq(
          VersionProblem("ci-jdk-matrix-missing", ".github/workflows/ci.yml has no 'jdk: [ ... ]' test matrix")
        )
      } else {
        DocumentedJavaClaims.flatMap { path =>
          javaProblemsFor(path, repo.read(repo.root.resolve(path)), testedJdks)
        }
      }

    sbtProblems(
      repo.read(repo.root.resolve(".tool-versions")),
      repo.read(repo.root.resolve("project/build.properties"))
    ) ++ ciProblems
  }

  private def sbtProblems(toolVersions: String, buildProperties: String): Seq[VersionProblem] = {
    val fromToolVersions    = SbtToolVersion.findFirstMatchIn(toolVersions).map(_.group(1))
    val fromBuildProperties = SbtBuildProperty.findFirstMatchIn(buildProperties).map(_.group(1))
    (fromToolVersions, fromBuildProperties) match {
      case (Some(toolVersion), Some(buildVersion)) if toolVersion == buildVersion =>
        Seq.empty
      case (toolVersion, buildVersion) =>
        Seq(
          VersionProblem(
            "sbt-version-mismatch",
            s"project/build.properties declares sbt ${buildVersion.getOrElse("<missing>")} and .tool-versions declares sbt ${toolVersion.getOrElse("<missing>")}. Set both to the same version."
          )
        )
    }
  }

  private def javaProblemsFor(source: String, text: String, testedJdks: Seq[Int]): Seq[VersionProblem] =
    javaClaim(text) match {
      case None =>
        Seq(
          VersionProblem(
            s"java-version-missing:$source",
            s"$source has no line that starts with 'Java' and names the supported Java versions"
          )
        )
      case Some(claimed) if claimed == testedJdks.toSet =>
        Seq.empty
      case Some(claimed) =>
        Seq(
          VersionProblem(
            s"java-version-mismatch:$source",
            s"$source claims Java ${claimed.toSeq.sorted.mkString(", ")}, but .github/workflows/ci.yml tests Java ${testedJdks.sorted.mkString(", ")}"
          )
        )
    }

  private def jdkMatrix(ci: String): Seq[Int] =
    CiJdkMatrix
      .findFirstMatchIn(ci)
      .map(_.group(1).split(",").toSeq.map(_.trim).filter(_.nonEmpty).map(_.toInt))
      .getOrElse(Seq.empty)

  private def javaClaim(text: String): Option[Set[Int]] =
    text.linesIterator
      .map(_.trim)
      .find(_.startsWith("Java"))
      .map { line =>
        """\d+""".r.findAllIn(line.split("\\bScala\\b").head).map(_.toInt).toSet
      }
}
