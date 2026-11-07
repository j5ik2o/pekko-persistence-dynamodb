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

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion

/** What one client variant can do. The config parser and the startup checks read this value, so a capability is
  * declared once.
  */
final case class CapabilityProfile(
    sync: Boolean,
    async: Boolean,
    dax: Boolean,
    transactWrite: Boolean,
    streams: Boolean,
    batchWriteLimit: Int,
    transactItemLimit: Int,
    itemSizeLimitBytes: Int,
    transactionSizeLimitBytes: Long
)

object CapabilityProfile {

  val V1: CapabilityProfile = CapabilityProfile(
    sync = true,
    async = true,
    dax = false,
    transactWrite = false,
    streams = true,
    batchWriteLimit = 25,
    transactItemLimit = ItemSize.MaxItemsPerTransaction,
    itemSizeLimitBytes = ItemSize.MaxItemSizeBytes,
    transactionSizeLimitBytes = ItemSize.MaxTransactionSizeBytes
  )

  val V1Dax: CapabilityProfile = V1.copy(dax = true, streams = false)

  val V2: CapabilityProfile = V1.copy(transactWrite = true)

  val V2Dax: CapabilityProfile = V2.copy(dax = true, transactWrite = false, streams = false)
}

/** One client variant: the config value it maps to and the capabilities it offers. */
sealed trait ClientVariant {
  def clientVersion: ClientVersion.Value
  def capabilities: CapabilityProfile
}

object ClientVariant {
  case object V1 extends ClientVariant {
    override val clientVersion: ClientVersion.Value = ClientVersion.V1
    override val capabilities: CapabilityProfile    = CapabilityProfile.V1
  }

  case object V1Dax extends ClientVariant {
    override val clientVersion: ClientVersion.Value = ClientVersion.V1Dax
    override val capabilities: CapabilityProfile    = CapabilityProfile.V1Dax
  }

  case object V2 extends ClientVariant {
    override val clientVersion: ClientVersion.Value = ClientVersion.V2
    override val capabilities: CapabilityProfile    = CapabilityProfile.V2
  }

  case object V2Dax extends ClientVariant {
    override val clientVersion: ClientVersion.Value = ClientVersion.V2Dax
    override val capabilities: CapabilityProfile    = CapabilityProfile.V2Dax
  }

  val all: Vector[ClientVariant] = Vector(V1, V1Dax, V2, V2Dax)

  def fromClientVersion(version: ClientVersion.Value): Option[ClientVariant] =
    all.find(_.clientVersion == version)
}
