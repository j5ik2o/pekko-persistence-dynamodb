package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class ApiCompatibilitySpec extends AnyFreeSpec with Matchers {

  "no public declaration of the published modules disappears" in {
    val repo         = Repo.locate()
    val baselinePath = repo.root.resolve("schema-check/public-api-baseline.txt")
    if (!Files.isRegularFile(baselinePath)) {
      sys.error(
        s"public API baseline not found: $baselinePath. Generate it with sbt \"schema-check/runMain com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck.ApiBaseline ${repo.root} $baselinePath\""
      )
    }
    val baseline = PublicApiSurface.parse(
      Files.readAllLines(baselinePath, StandardCharsets.UTF_8).asScala.iterator
    )
    val current = PublicApiSurface.of(repo.root)
    val removed = PublicApiSurface.missing(baseline, current)
    val added   = PublicApiSurface.added(baseline, current)

    info(s"checked ${current.size} public declarations against ${baseline.size} baseline declarations")
    added.foreach(declaration =>
      info(s"new public declaration: ${declaration.path}:${declaration.owner}:${declaration.header}")
    )
    removed.foreach(declaration =>
      info(s"removed public declaration: ${declaration.path}:${declaration.owner}:${declaration.header}")
    )

    removed shouldBe empty
  }
}
