package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class SchemaDriftSpec extends AnyFreeSpec with Matchers {

  "reference.conf, the table JSON, the docs, and DynamoDBContainerHelper agree" in {
    val repo     = Repo.locate()
    val problems = SchemaDrift.check(repo)

    problems.foreach(problem => info(s"known problem: ${problem.id}\n  ${problem.detail}"))

    val allowlist = Allowlist.load(repo.root.resolve("schema-check/known-problems.allowlist"))
    Allowlist.verify("schema-drift", problems, allowlist)
  }
}
