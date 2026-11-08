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
package com.github.j5ik2o.pekko.persistence.dynamodb.state

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.ClientVariant
import com.github.j5ik2o.pekko.persistence.dynamodb.state.config.StatePluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.state.scaladsl.{
  ScalaDurableStateUpdateStoreFactory,
  V1DaxScalaDurableStateUpdateStoreFactory,
  V1ScalaDurableStateUpdateStoreFactory
}
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{
  V1AsyncClientFactory,
  V1DaxAsyncClientFactory,
  V1DaxSyncClientFactory,
  V1SyncClientFactory
}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/** Proves that the durable state plugin resolves its executor, its AWS SDK client factories, and its store factory for
  * the v1 client variants. The factories are constructed, not invoked, so no DynamoDB or DAX connection is opened.
  */
final class StateStartupMatrixSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  private var system: ActorSystem = _

  override def beforeAll(): Unit = {
    system = ActorSystem("state-startup-matrix-v1")
  }

  override def afterAll(): Unit = {
    if (system != null) Await.result(system.terminate(), 30.seconds)
  }

  private def pluginConfig(version: ClientVersion.Value): StatePluginConfig = {
    val config = ConfigFactory
      .parseString(
        s"""
           |j5ik2o.dynamo-db-state.dynamo-db-client {
           |  client-version = "${version.toString}"
           |  client-type = "sync"
           |}
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load("state-reference"))
      .resolve()
    StatePluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-state"))
  }

  private def context(version: ClientVersion.Value): StatePluginContext = {
    val config = pluginConfig(version)
    config.clientVariant shouldBe expectedVariant(version)
    val pluginContext = StatePluginContext(system, config)
    pluginContext.pluginExecutor should not be null
    pluginContext
  }

  private def expectedVariant(version: ClientVersion.Value): ClientVariant =
    version match {
      case ClientVersion.V1    => ClientVariant.V1
      case ClientVersion.V1Dax => ClientVariant.V1Dax
      case other               => fail(s"this spec covers the v1 variants, not $other")
    }

  "the durable state plugin starts for v1" in {
    val pluginContext = context(ClientVersion.V1)
    pluginContext
      .newDynamicAccessor[V1SyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v1SyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[V1AsyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v1AsyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[ScalaDurableStateUpdateStoreFactory]()
      .createThrow(classOf[V1ScalaDurableStateUpdateStoreFactory].getName) shouldBe
    a[V1ScalaDurableStateUpdateStoreFactory]
  }

  "the durable state plugin starts for v1-dax" in {
    val pluginContext = context(ClientVersion.V1Dax)
    pluginContext
      .newDynamicAccessor[V1DaxSyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v1DaxSyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[V1DaxAsyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v1DaxAsyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[ScalaDurableStateUpdateStoreFactory]()
      .createThrow(classOf[V1DaxScalaDurableStateUpdateStoreFactory].getName) shouldBe
    a[V1DaxScalaDurableStateUpdateStoreFactory]
  }

}
