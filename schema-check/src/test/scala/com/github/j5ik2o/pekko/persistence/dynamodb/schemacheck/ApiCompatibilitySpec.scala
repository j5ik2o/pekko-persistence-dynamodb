/*
 * Copyright 2026 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class ApiCompatibilitySpec extends AnyFreeSpec with Matchers {

  "the baseline records every public declaration of the published modules" in {
    val repo         = Repo.locate()
    val baselinePath = repo.root.resolve("schema-check/public-api-baseline.txt")
    if (!Files.isRegularFile(baselinePath)) {
      val command =
        "sbt \"" +
        "schema-check/runMain com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck.ApiBaseline " +
        repo.root.toString + " " + baselinePath.toString +
        "\""
      sys.error(s"public API baseline not found at $baselinePath. Regenerate it with $command")
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
    withClue("Unregistered public declarations. Regenerate the baseline with ApiBaseline: ") {
      added shouldBe empty
    }
  }
}
