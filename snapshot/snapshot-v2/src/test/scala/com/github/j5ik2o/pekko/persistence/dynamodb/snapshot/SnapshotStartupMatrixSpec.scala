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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.ClientVariant
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.dao.{ SnapshotDaoFactory, V2DaxSnapshotDaoFactory }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{
  V2AsyncClientFactory,
  V2DaxAsyncClientFactory,
  V2DaxSyncClientFactory,
  V2SyncClientFactory
}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/** Proves that the snapshot plugin resolves its executor, its AWS SDK client factories, and its DAO factory for the v2
  * client variants. The factories are constructed, not invoked, so no DynamoDB or DAX connection is opened.
  */
final class SnapshotStartupMatrixSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  private var system: ActorSystem = _

  override def beforeAll(): Unit = {
    system = ActorSystem("snapshot-startup-matrix-v2")
  }

  override def afterAll(): Unit = {
    if (system != null) Await.result(system.terminate(), 30.seconds)
  }

  private def pluginConfig(version: ClientVersion.Value): SnapshotPluginConfig = {
    val config = ConfigFactory
      .parseString(
        s"""
           |j5ik2o.dynamo-db-snapshot.dynamo-db-client {
           |  client-version = "${version.toString}"
           |  client-type = "sync"
           |}
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load("snapshot-reference"))
      .resolve()
    SnapshotPluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-snapshot"))
  }

  private def context(version: ClientVersion.Value): SnapshotPluginContext = {
    val config = pluginConfig(version)
    config.clientVariant shouldBe expectedVariant(version)
    val pluginContext = SnapshotPluginContext(system, config)
    pluginContext.pluginExecutor should not be null
    pluginContext
  }

  private def expectedVariant(version: ClientVersion.Value): ClientVariant =
    version match {
      case ClientVersion.V2    => ClientVariant.V2
      case ClientVersion.V2Dax => ClientVariant.V2Dax
      case other               => fail(s"this spec covers the v2 variants, not $other")
    }

  "the snapshot plugin starts for v2" in {
    val pluginContext = context(ClientVersion.V2)
    pluginContext
      .newDynamicAccessor[V2SyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2SyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[V2AsyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2AsyncClientFactoryClassName) should not be null
    val daoFactory = pluginContext
      .newDynamicAccessor[SnapshotDaoFactory]()
      .createThrow(DynamoDBSnapshotStore.snapshotDaoFactoryClassName(ClientVersion.V2))
    daoFactory shouldBe a[SnapshotDaoFactory]
  }

  "the snapshot plugin starts for v2-dax" in {
    val pluginContext = context(ClientVersion.V2Dax)
    pluginContext
      .newDynamicAccessor[V2DaxSyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2DaxSyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[V2DaxAsyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2DaxAsyncClientFactoryClassName) should not be null
    val daoFactory = pluginContext
      .newDynamicAccessor[SnapshotDaoFactory]()
      .createThrow(DynamoDBSnapshotStore.snapshotDaoFactoryClassName(ClientVersion.V2Dax))
    daoFactory shouldBe a[V2DaxSnapshotDaoFactory]
  }

}
