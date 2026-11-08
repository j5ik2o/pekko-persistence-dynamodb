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
package com.github.j5ik2o.pekko.persistence.dynamodb.client

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class CapabilityMatrixSpec extends AnyFreeSpec with Matchers {

  "the capability matrix" - {
    "gives every variant synchronous and asynchronous clients" in {
      ClientVersion.values.foreach { version =>
        CapabilityMatrix.supports(version, ClientCapability.Sync) shouldBe true
        CapabilityMatrix.supports(version, ClientCapability.Async) shouldBe true
      }
    }

    "marks only the DAX variants as DAX capable" in {
      ClientVersion.values.filter(CapabilityMatrix.supports(_, ClientCapability.Dax)).toSet shouldBe Set(
        ClientVersion.V1Dax,
        ClientVersion.V2Dax
      )
    }

    "marks only v2 as transactional" in {
      ClientVersion.values.filter(CapabilityMatrix.supportsTransactionalWrites).toSet shouldBe Set(ClientVersion.V2)
    }

    "marks only the non-DAX variants as stream capable" in {
      ClientVersion.values.filter(CapabilityMatrix.supports(_, ClientCapability.Streams)).toSet shouldBe Set(
        ClientVersion.V1,
        ClientVersion.V2
      )
    }
  }

  "requireTransactionalWrites" - {
    "accepts v2" in {
      noException should be thrownBy {
        CapabilityMatrix.requireTransactionalWrites(ClientVersion.V2, "head.enabled")
      }
    }

    "rejects v1, v1-dax, and v2-dax with the setting name" in {
      Seq(ClientVersion.V1, ClientVersion.V1Dax, ClientVersion.V2Dax).foreach { version =>
        val error = the[IllegalArgumentException] thrownBy {
          CapabilityMatrix.requireTransactionalWrites(version, "head.enabled")
        }
        error.getMessage should include("head.enabled")
        error.getMessage should include("transactional writes")
        error.getMessage should include(version.toString)
      }
    }
  }
}
