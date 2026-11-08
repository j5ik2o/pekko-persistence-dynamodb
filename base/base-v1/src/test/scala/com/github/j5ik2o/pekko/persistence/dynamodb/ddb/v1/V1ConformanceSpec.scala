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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{ OperationConformanceCases, OperationPlanner }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class V1ConformanceSpec extends AnyFreeSpec with Matchers {

  "the v1 renderer preserves the neutral plan for every conformance case" in {
    OperationConformanceCases.operations.foreach { case (name, operation) =>
      withClue(name) {
        val expected = OperationPlanner.plan(operation).map(_.normalized)
        val actual   = V1OperationRenderer.render(operation).map(V1RequestNormalizer.normalize).map(_.normalized)
        actual shouldBe expected
      }
    }
  }
}
