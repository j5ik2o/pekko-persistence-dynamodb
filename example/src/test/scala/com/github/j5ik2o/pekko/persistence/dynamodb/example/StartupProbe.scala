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
package com.github.j5ik2o.pekko.persistence.dynamodb.example

import org.apache.pekko.actor.ActorSystem
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.state.StatePluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.state.config.StatePluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{
  V1AsyncClientFactory,
  V1DaxAsyncClientFactory,
  V1DaxSyncClientFactory,
  V1SyncClientFactory,
  V2AsyncClientFactory,
  V2DaxAsyncClientFactory,
  V2DaxSyncClientFactory,
  V2SyncClientFactory
}
import com.typesafe.config.ConfigFactory

import scala.util.Try

/** Measures the plugin startup path for V1, V1Dax, V2, and V2Dax without opening a DynamoDB or DAX connection.
  *
  * One run prints one line per version. The caller runs it several times and takes the median. On the trunk before D2,
  * the DAX versions fail with a MatchError from the plugin executor; at the D2 head they resolve.
  */
object StartupProbe {

  private val versions =
    Vector(ClientVersion.V1, ClientVersion.V1Dax, ClientVersion.V2, ClientVersion.V2Dax)

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("startup-probe")
    try {
      versions.foreach { version =>
        val started = System.nanoTime()
        val result = Try {
          val snapshotConfig = SnapshotPluginConfig.fromConfig(
            clientConfig("j5ik2o.dynamo-db-snapshot", version)
          )
          val snapshotContext = SnapshotPluginContext(system, snapshotConfig)
          snapshotContext.pluginExecutor
          resolveClientFactories(snapshotContext, version)
          val stateConfig = StatePluginConfig.fromConfig(
            clientConfig("j5ik2o.dynamo-db-state", version)
          )
          val stateContext = StatePluginContext(system, stateConfig)
          stateContext.pluginExecutor
          ()
        }
        val elapsedMillis = (System.nanoTime() - started) / 1000000.0
        println(f"PROBE version=${version.toString}%-6s elapsedMs=$elapsedMillis%.3f ok=${result.isSuccess}")
        result.failed.foreach(exception =>
          println(
            s"PROBE_FAILURE version=${version.toString} ${exception.getClass.getSimpleName}: ${exception.getMessage}"
          )
        )
      }
    } finally system.terminate()
  }

  private def clientConfig(path: String, version: ClientVersion.Value) =
    ConfigFactory
      .parseString(
        s"""
           |$path.dynamo-db-client {
           |  client-version = "${version.toString}"
           |  client-type = "sync"
           |  endpoint = "http://127.0.0.1:1/"
           |  region = "ap-northeast-1"
           |  access-key-id = "x"
           |  secret-access-key = "x"
           |}
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
      .resolve()
      .getConfig(path)

  private def resolveClientFactories(
      context: SnapshotPluginContext,
      version: ClientVersion.Value
  ): Unit = {
    val config = context.pluginConfig
    version match {
      case ClientVersion.V1 =>
        context.newDynamicAccessor[V1SyncClientFactory]().createThrow(config.v1SyncClientFactoryClassName)
        context.newDynamicAccessor[V1AsyncClientFactory]().createThrow(config.v1AsyncClientFactoryClassName)
      case ClientVersion.V1Dax =>
        context.newDynamicAccessor[V1DaxSyncClientFactory]().createThrow(config.v1DaxSyncClientFactoryClassName)
        context.newDynamicAccessor[V1DaxAsyncClientFactory]().createThrow(config.v1DaxAsyncClientFactoryClassName)
      case ClientVersion.V2 =>
        context.newDynamicAccessor[V2SyncClientFactory]().createThrow(config.v2SyncClientFactoryClassName)
        context.newDynamicAccessor[V2AsyncClientFactory]().createThrow(config.v2AsyncClientFactoryClassName)
      case ClientVersion.V2Dax =>
        context.newDynamicAccessor[V2DaxSyncClientFactory]().createThrow(config.v2DaxSyncClientFactoryClassName)
        context.newDynamicAccessor[V2DaxAsyncClientFactory]().createThrow(config.v2DaxAsyncClientFactoryClassName)
    }
    ()
  }

}
