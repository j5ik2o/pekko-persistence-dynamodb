package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class VersionAlignmentSpec extends AnyFreeSpec with Matchers {

  "the sbt declarations and the documented Java versions agree with the build and CI" in {
    val repo     = Repo.locate()
    val problems = VersionDeclarations.check(repo)

    problems.foreach(problem => info(s"version problem: ${problem.id}\n  ${problem.detail}"))

    withClue(s"${problems.size} version declaration problem(s):\n") {
      problems shouldBe empty
    }
  }
}
