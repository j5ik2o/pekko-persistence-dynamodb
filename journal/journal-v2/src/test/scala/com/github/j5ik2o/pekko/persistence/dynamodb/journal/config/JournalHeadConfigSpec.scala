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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.config

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class JournalHeadConfigSpec extends AnyFreeSpec with Matchers {

  private def pluginConfig(clientVersion: ClientVersion.Value, headEnabled: Boolean) =
    ConfigFactory
      .parseString(
        s"""
           |j5ik2o.dynamo-db-journal.head.enabled = $headEnabled
           |j5ik2o.dynamo-db-journal.dynamo-db-client.client-version = "${clientVersion.toString}"
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
      .resolve()
      .getConfig("j5ik2o.dynamo-db-journal")

  "head.enabled" - {
    "is accepted for the transactional variant" in {
      noException should be thrownBy {
        JournalPluginConfig.fromConfig(pluginConfig(ClientVersion.V2, headEnabled = true))
      }
    }

    "is rejected for v2-dax" in {
      // v1 and v1-dax are rejected by the same CapabilityMatrix check, which CapabilityMatrixSpec covers.
      // This module has no base-v1 classes, so the v1 client config cannot be parsed here.
      val error = the[IllegalArgumentException] thrownBy {
        JournalPluginConfig.fromConfig(pluginConfig(ClientVersion.V2Dax, headEnabled = true))
      }

      error.getMessage should include("head.enabled")
    }

    "does not require transactional writes when disabled" in {
      noException should be thrownBy {
        JournalPluginConfig.fromConfig(pluginConfig(ClientVersion.V2Dax, headEnabled = false))
      }
    }
  }
}
