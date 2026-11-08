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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{ OperationCapabilities, OperationConformanceCases }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class OperationCapabilitiesSpec extends AnyFreeSpec with Matchers {

  "a non-transactional variant rejects a transaction" in {
    OperationCapabilities.validate(CapabilityProfile.V1, OperationConformanceCases.transaction).isLeft shouldBe true
    OperationCapabilities.validate(CapabilityProfile.V1Dax, OperationConformanceCases.transaction).isLeft shouldBe true
    OperationCapabilities.validate(CapabilityProfile.V2Dax, OperationConformanceCases.transaction).isLeft shouldBe true
  }

  "a transactional variant accepts the conformance transaction" in {
    OperationCapabilities.validate(CapabilityProfile.V2, OperationConformanceCases.transaction).isRight shouldBe true
  }

  "a transaction over the item limit is rejected" in {
    val items = Vector.fill(CapabilityProfile.V2.transactItemLimit + 1)(
      TransactItem.Put(OperationConformanceCases.table, AttributeMap.empty)
    )

    OperationCapabilities.validate(CapabilityProfile.V2, Operation.TransactWrite(items)).isLeft shouldBe true
  }

  "a batch write over the limit is rejected" in {
    val deletes = Vector.fill(CapabilityProfile.V1.batchWriteLimit + 1)(OperationConformanceCases.rowKey)

    OperationCapabilities
      .validate(
        CapabilityProfile.V1,
        Operation.BatchWrite(OperationConformanceCases.table, Vector.empty, deletes)
      )
      .isLeft shouldBe true
  }

  "a batch write at the limit is accepted" in {
    val deletes = Vector.fill(CapabilityProfile.V1.batchWriteLimit)(OperationConformanceCases.rowKey)

    OperationCapabilities
      .validate(
        CapabilityProfile.V1,
        Operation.BatchWrite(OperationConformanceCases.table, Vector.empty, deletes)
      )
      .isRight shouldBe true
  }
}
