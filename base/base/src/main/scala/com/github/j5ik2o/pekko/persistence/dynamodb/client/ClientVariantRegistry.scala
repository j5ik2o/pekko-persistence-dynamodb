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
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ CapabilityProfile, ClientVariant }

/** One client variant and the configuration keys that select its AWS SDK client factories.
  *
  * The registry is the only place that lists the variants and their factory keys. A new variant is added here, and the
  * three plugin configs pick it up through the shared config parser.
  */
final case class ClientVariantEntry(
    variant: ClientVariant,
    asyncClientFactoryKey: String,
    syncClientFactoryKey: String,
    expectedAsyncClientFactoryClassName: String,
    expectedSyncClientFactoryClassName: String
) {
  def clientVersion: ClientVersion.Value = variant.clientVersion
  def capabilities: CapabilityProfile    = variant.capabilities
}

object ClientVariantRegistry {

  val V1: ClientVariantEntry = ClientVariantEntry(
    variant = ClientVariant.V1,
    asyncClientFactoryKey = PluginConfig.v1AsyncClientFactoryClassNameKey,
    syncClientFactoryKey = PluginConfig.v1SyncClientFactoryClassNameKey,
    expectedAsyncClientFactoryClassName = PluginConfig.V1AsyncClientFactoryClassName,
    expectedSyncClientFactoryClassName = PluginConfig.V1SyncClientFactoryClassName
  )

  val V1Dax: ClientVariantEntry = ClientVariantEntry(
    variant = ClientVariant.V1Dax,
    asyncClientFactoryKey = PluginConfig.v1DaxAsyncClientFactoryClassNameKey,
    syncClientFactoryKey = PluginConfig.v1DaxSyncClientFactoryClassNameKey,
    expectedAsyncClientFactoryClassName = PluginConfig.V1DaxAsyncClientFactoryClassName,
    expectedSyncClientFactoryClassName = PluginConfig.V1DaxSyncClientFactoryClassName
  )

  val V2: ClientVariantEntry = ClientVariantEntry(
    variant = ClientVariant.V2,
    asyncClientFactoryKey = PluginConfig.v2AsyncClientFactoryClassNameKey,
    syncClientFactoryKey = PluginConfig.v2SyncClientFactoryClassNameKey,
    expectedAsyncClientFactoryClassName = PluginConfig.V2AsyncClientFactoryClassName,
    expectedSyncClientFactoryClassName = PluginConfig.V2SyncClientFactoryClassName
  )

  val V2Dax: ClientVariantEntry = ClientVariantEntry(
    variant = ClientVariant.V2Dax,
    asyncClientFactoryKey = PluginConfig.v2DaxAsyncClientFactoryClassNameKey,
    syncClientFactoryKey = PluginConfig.v2DaxSyncClientFactoryClassNameKey,
    expectedAsyncClientFactoryClassName = PluginConfig.V2DaxAsyncClientFactoryClassName,
    expectedSyncClientFactoryClassName = PluginConfig.V2DaxSyncClientFactoryClassName
  )

  val all: Vector[ClientVariantEntry] = Vector(V1, V1Dax, V2, V2Dax)

  def entry(clientVersion: ClientVersion.Value): ClientVariantEntry =
    all.find(_.clientVersion == clientVersion).getOrElse {
      throw new IllegalArgumentException(s"unknown client version: $clientVersion")
    }

  def variant(clientVersion: ClientVersion.Value): ClientVariant = entry(clientVersion).variant
}
