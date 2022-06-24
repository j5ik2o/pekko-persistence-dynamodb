/*
 * Copyright 2022 Junichi Kato
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
package com.github.j5ik2o.akka.persistence.dynamodb.state.scaladsl

import com.github.j5ik2o.akka.persistence.dynamodb.config.client.ClientType
import com.github.j5ik2o.akka.persistence.dynamodb.exception.PluginException
import com.github.j5ik2o.akka.persistence.dynamodb.state.StatePluginContext
import com.github.j5ik2o.akka.persistence.dynamodb.utils.{ V2DaxAsyncClientFactory, V2DaxSyncClientFactory }

import scala.collection.immutable
import scala.util.{ Failure, Success }

final class V2DaxScalaDurableStateUpdateStoreFactory(pluginContext: StatePluginContext)
    extends ScalaDurableStateUpdateStoreFactory {
  override def create[A]: ScalaDurableStateUpdateStore[A] = {
    import pluginContext._
    val (maybeV2SyncClient, maybeV2AsyncClient) = pluginConfig.clientConfig.clientType match {
      case ClientType.Sync =>
        val f = dynamicAccess
          .createInstanceFor[V2DaxSyncClientFactory](
            pluginConfig.v2DaxSyncClientFactoryClassName,
            immutable.Seq.empty
          ) match {
          case Success(value) => value
          case Failure(ex)    => throw new PluginException("Failed to initialize V2DaxSyncClientFactory", Some(ex))
        }
        val client = f.create
        (Some(client), None)
      case ClientType.Async =>
        val f = dynamicAccess
          .createInstanceFor[V2DaxAsyncClientFactory](
            pluginConfig.v2DaxAsyncClientFactoryClassName,
            immutable.Seq.empty
          ) match {
          case Success(value) => value
          case Failure(ex)    => throw new PluginException("Failed to initialize V2DaxAsyncClientFactory", Some(ex))
        }
        val client = f.create
        (None, Some(client))
    }
    new DynamoDBDurableStateStoreV2(
      pluginContext,
      maybeV2AsyncClient,
      maybeV2SyncClient
    )
  }
}
