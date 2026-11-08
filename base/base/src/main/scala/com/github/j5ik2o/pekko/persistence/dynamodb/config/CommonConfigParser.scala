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
package com.github.j5ik2o.pekko.persistence.dynamodb.config

import com.github.j5ik2o.pekko.persistence.dynamodb.client.{ ClientVariantEntry, ClientVariantRegistry }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{
  ClientVersion,
  CommonConfigKeys,
  DynamoDBClientConfig
}
import com.github.j5ik2o.pekko.persistence.dynamodb.metrics.{ MetricsReporter, MetricsReporterProvider }
import com.github.j5ik2o.pekko.persistence.dynamodb.trace.{ TraceReporter, TraceReporterProvider }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.ClassCheckUtils
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

/** Parsers shared by the journal, snapshot, and state plugin configs.
  *
  * A key is read in one place, so a change to a client factory key, a reporter key, or a backoff key does not have to
  * be repeated in the three configs.
  */
object CommonConfigParser {

  val dynamoDbClientKey = "dynamo-db-client"
  val writeBackoffKey   = "write-backoff"
  val readBackoffKey    = "read-backoff"

  final case class ClientFactories(
      v1AsyncClientFactoryClassName: String,
      v1SyncClientFactoryClassName: String,
      v1DaxAsyncClientFactoryClassName: String,
      v1DaxSyncClientFactoryClassName: String,
      v2AsyncClientFactoryClassName: String,
      v2SyncClientFactoryClassName: String,
      v2DaxAsyncClientFactoryClassName: String,
      v2DaxSyncClientFactoryClassName: String
  )

  final case class Reporters(
      metricsReporterProviderClassName: String,
      metricsReporterClassName: Option[String],
      traceReporterProviderClassName: String,
      traceReporterClassName: Option[String]
  )

  final case class Backoffs(write: BackoffConfig, read: BackoffConfig)

  def dynamoDbClientConfig(config: Config, legacyConfigFormat: Boolean): DynamoDBClientConfig =
    DynamoDBClientConfig.fromConfig(
      config.getAs[Config](dynamoDbClientKey).getOrElse(ConfigFactory.empty()),
      legacyConfigFormat
    )

  def clientFactories(config: Config, selected: ClientVersion.Value): ClientFactories = {
    val v1    = readClientFactories(config, ClientVariantRegistry.V1, selected)
    val v1Dax = readClientFactories(config, ClientVariantRegistry.V1Dax, selected)
    val v2    = readClientFactories(config, ClientVariantRegistry.V2, selected)
    val v2Dax = readClientFactories(config, ClientVariantRegistry.V2Dax, selected)
    ClientFactories(
      v1AsyncClientFactoryClassName = v1._1,
      v1SyncClientFactoryClassName = v1._2,
      v1DaxAsyncClientFactoryClassName = v1Dax._1,
      v1DaxSyncClientFactoryClassName = v1Dax._2,
      v2AsyncClientFactoryClassName = v2._1,
      v2SyncClientFactoryClassName = v2._2,
      v2DaxAsyncClientFactoryClassName = v2Dax._1,
      v2DaxSyncClientFactoryClassName = v2Dax._2
    )
  }

  def reporters(config: Config): Reporters =
    Reporters(
      metricsReporterProviderClassName = ClassCheckUtils.requireClass(
        classOf[MetricsReporterProvider],
        config.as[String](CommonConfigKeys.metricsReporterProviderClassNameKey)
      ),
      metricsReporterClassName = ClassCheckUtils.requireClass(
        classOf[MetricsReporter],
        config.getAs[String](CommonConfigKeys.metricsReporterClassNameKey)
      ),
      traceReporterProviderClassName = ClassCheckUtils.requireClass(
        classOf[TraceReporterProvider],
        config.as[String](CommonConfigKeys.traceReporterProviderClassNameKey)
      ),
      traceReporterClassName = ClassCheckUtils.requireClass(
        classOf[TraceReporter],
        config.getAs[String](CommonConfigKeys.traceReporterClassNameKey)
      )
    )

  def backoffs(config: Config): Backoffs =
    Backoffs(
      write = BackoffConfig.fromConfig(
        config.getAs[Config](writeBackoffKey).getOrElse(ConfigFactory.empty())
      ),
      read = BackoffConfig.fromConfig(
        config.getAs[Config](readBackoffKey).getOrElse(ConfigFactory.empty())
      )
    )

  private[config] def validates(entry: ClientVariantEntry, selected: ClientVersion.Value): Boolean =
    entry.clientVersion == selected

  private def readClientFactories(
      config: Config,
      entry: ClientVariantEntry,
      selected: ClientVersion.Value
  ): (String, String) = {
    val enabled = validates(entry, selected)
    (
      ClassCheckUtils.requireClassByName(
        entry.expectedAsyncClientFactoryClassName,
        config.as[String](entry.asyncClientFactoryKey),
        enabled
      ),
      ClassCheckUtils.requireClassByName(
        entry.expectedSyncClientFactoryClassName,
        config.as[String](entry.syncClientFactoryKey),
        enabled
      )
    )
  }
}
