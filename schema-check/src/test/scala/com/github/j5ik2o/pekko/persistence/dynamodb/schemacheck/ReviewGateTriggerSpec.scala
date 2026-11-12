package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.funsuite.AnyFunSuite

final class ReviewGateTriggerSpec extends AnyFunSuite {

  test("review-gate reruns unresolved-review-threads when a pull request is marked ready") {
    val repo  = Repo.locate()
    val lines = repo.read(repo.root.resolve(".github/workflows/review-gate.yml")).split("\n", -1).toVector

    val pullRequest = lines.indexWhere(_.matches("^  pull_request:\\s*(?:#.*)?$"))
    assert(pullRequest >= 0, "review-gate.yml must trigger on pull_request")
    val types = lines
      .drop(pullRequest + 1).takeWhile(line =>
        !line.matches("^  [A-Za-z_-]+:\\s*(?:#.*)?$") && !line.matches("^\\S.*$")
      )
      .collectFirst {
        case line if line.matches("^    types:\\s*\\[.*\\]\\s*(?:#.*)?$") =>
          line.substring(line.indexOf('[') + 1, line.indexOf(']')).split(",").map(_.trim).toSet
      }
      .getOrElse(Set.empty[String])

    // tools/merge-approved.sh requires an unresolved-review-threads run started after the latest ready_for_review.
    assert(
      types.contains("ready_for_review"),
      s"review-gate.yml pull_request types must include ready_for_review, found: ${types.mkString(", ")}"
    )
    assert(lines.exists(_.matches("^  unresolved-review-threads:\\s*(?:#.*)?$")))
  }
}
