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

import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.ClientVariant
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class ClientVariantRegistrySpec extends AnyFreeSpec with Matchers {

  "the registry" - {
    "lists one entry for each of the four client versions, in order" in {
      ClientVariantRegistry.all.map(_.clientVersion) shouldBe Vector(
        ClientVersion.V1,
        ClientVersion.V1Dax,
        ClientVersion.V2,
        ClientVersion.V2Dax
      )
    }

    "maps each client version to its variant" in {
      ClientVariantRegistry.variant(ClientVersion.V1) shouldBe ClientVariant.V1
      ClientVariantRegistry.variant(ClientVersion.V1Dax) shouldBe ClientVariant.V1Dax
      ClientVariantRegistry.variant(ClientVersion.V2) shouldBe ClientVariant.V2
      ClientVariantRegistry.variant(ClientVersion.V2Dax) shouldBe ClientVariant.V2Dax
    }

    "keeps the factory keys and expected classes in the entry" in {
      val entry = ClientVariantRegistry.entry(ClientVersion.V2Dax)
      entry.asyncClientFactoryKey shouldBe PluginConfig.v2DaxAsyncClientFactoryClassNameKey
      entry.syncClientFactoryKey shouldBe PluginConfig.v2DaxSyncClientFactoryClassNameKey
      entry.expectedAsyncClientFactoryClassName shouldBe PluginConfig.V2DaxAsyncClientFactoryClassName
      entry.expectedSyncClientFactoryClassName shouldBe PluginConfig.V2DaxSyncClientFactoryClassName
    }
  }
}
