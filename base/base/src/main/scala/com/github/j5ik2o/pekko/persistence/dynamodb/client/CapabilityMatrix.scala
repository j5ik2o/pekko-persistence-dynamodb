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

/** A capability a client variant may or may not offer. */
sealed trait ClientCapability

object ClientCapability {
  case object Sync extends ClientCapability
  case object Async extends ClientCapability
  case object Dax extends ClientCapability
  case object TransactWrite extends ClientCapability
  case object Streams extends ClientCapability
}

/** The single place that answers whether a client variant supports a capability.
  *
  * The parser and the startup checks ask this object instead of comparing client versions. The profile itself comes
  * from [[ClientVariantRegistry]], so a capability is declared once per variant.
  */
object CapabilityMatrix {

  def supports(clientVersion: ClientVersion.Value, capability: ClientCapability): Boolean = {
    val profile = ClientVariantRegistry.entry(clientVersion).capabilities
    capability match {
      case ClientCapability.Sync          => profile.sync
      case ClientCapability.Async         => profile.async
      case ClientCapability.Dax           => profile.dax
      case ClientCapability.TransactWrite => profile.transactWrite
      case ClientCapability.Streams       => profile.streams
    }
  }

  def require(clientVersion: ClientVersion.Value, capability: ClientCapability, setting: String): Unit = {
    if (!supports(clientVersion, capability)) {
      throw new IllegalArgumentException(
        s"$setting requires a client variant that supports ${describe(capability)}, but client-version is $clientVersion"
      )
    }
  }

  def supportsTransactionalWrites(clientVersion: ClientVersion.Value): Boolean =
    supports(clientVersion, ClientCapability.TransactWrite)

  def requireTransactionalWrites(clientVersion: ClientVersion.Value, setting: String): Unit =
    require(clientVersion, ClientCapability.TransactWrite, setting)

  private def describe(capability: ClientCapability): String = capability match {
    case ClientCapability.Sync          => "sync requests"
    case ClientCapability.Async         => "async requests"
    case ClientCapability.Dax           => "DAX"
    case ClientCapability.TransactWrite => "transactional writes"
    case ClientCapability.Streams       => "DynamoDB Streams"
  }
}
