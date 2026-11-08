package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class FactoryClassReferenceSpec extends AnyFreeSpec with Matchers {

  "every configuration class name resolves to a class, object, or trait" in {
    val repo        = Repo.locate()
    val definitions = FactoryClassReferences.definitions(repo)
    val references  = FactoryClassReferences.references(repo)
    val missing     = references.filterNot(reference => FactoryClassReferences.resolve(definitions, reference.fqcn))

    val problems = missing
      .groupBy(reference => problemId(reference.fqcn))
      .map { case (id, occurrences) =>
        KnownProblem(
          id,
          occurrences.map(reference => s"${reference.fqcn} <- ${reference.source}").distinct.sorted.mkString("; ")
        )
      }
      .toSeq
      .sortBy(_.id)

    info(s"checked ${references.map(_.fqcn).distinct.size} class names")
    problems.foreach(problem => info(s"known problem: ${problem.id}\n  ${problem.detail}"))

    val allowlist = Allowlist.load(repo.root.resolve("schema-check/known-problems.allowlist"))
    Allowlist.verify("factory-class", problems, allowlist)
  }

  private def problemId(fqcn: String): String =
    if (fqcn.endsWith(".snapshot.dao.V1DaxSnapshotDaoFactory")) "snapshot-v1-dax-factory-missing"
    else s"missing-factory-class:$fqcn"
}
