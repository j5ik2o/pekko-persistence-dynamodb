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

/** Fails when a case is added to or removed from one of the closed sets.
  *
  * The build does not use `-Werror`, so a missing renderer or planner case is only a warning. This list makes the
  * change fail until the author updates the set and the conformance cases that exercise it.
  */
final class OperationCaseListSpec extends AnyFreeSpec with Matchers {

  private def caseNames(repo: Repo, file: String, owner: String): Set[String] =
    PublicApiSurface
      .of(repo.root)
      .filter(declaration => declaration.path.endsWith(file) && declaration.owner == owner)
      .filter(_.header.startsWith("final case class "))
      .map(_.header.stripPrefix("final case class ").takeWhile(_ != '('))
      .toSet

  "the Operation set has exactly the known cases" in {
    val repo = Repo.locate()

    caseNames(repo, "/ddb/Operation.scala", "Operation") shouldBe
    Set("Put", "Get", "Delete", "Update", "Query", "BatchWrite", "TransactWrite")
  }

  "the TransactItem set has exactly the known cases" in {
    val repo = Repo.locate()

    caseNames(repo, "/ddb/Operation.scala", "TransactItem") shouldBe Set("Put", "Delete", "Update")
  }

  "the RenderedOperation set has exactly the known cases" in {
    val repo = Repo.locate()

    caseNames(repo, "/ddb/internal/RenderedOperation.scala", "RenderedOperation") shouldBe
    Set("Put", "Get", "Delete", "Update", "Query", "BatchWrite", "TransactWrite")
  }

  "the RenderedTransactItem set has exactly the known cases" in {
    val repo = Repo.locate()

    caseNames(repo, "/ddb/internal/RenderedOperation.scala", "RenderedTransactItem") shouldBe
    Set("Put", "Delete", "Update")
  }
}
