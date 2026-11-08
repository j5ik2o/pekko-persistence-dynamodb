/*
 * Copyright 2019 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config

import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig._
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.DynamoDBClientConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.config.{ BackoffConfig, CommonConfigParser, PluginConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.{
  PartitionKeyResolver,
  PartitionKeyResolverProvider,
  SortKeyResolver,
  SortKeyResolverProvider
}
import net.ceedubs.ficus.Ficus._
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ClassCheckUtils, LoggingSupport }
import com.typesafe.config.{ Config, ConfigFactory }

object SnapshotPluginConfig extends LoggingSupport {

  val legacyConfigFormatKey = "legacy-config-format"
  val legacyTableFormatKey  = "legacy-table-format"
  val tableNameKey          = "table-name"
  val columnsDefKey         = "columns-def"
  val consistentReadKey     = "consistent-read"

  val getSnapshotRowsIndexNameKey = "get-snapshot-rows-index-name"

  val dynamoDbClientKey = "dynamo-db-client"
  val writeBackoffKey   = "write-backoff"
  val readBackoffKey    = "read-backoff"

  val shardCountKey                    = "shard-count"
  val partitionKeyResolverClassNameKey = "partition-key-resolver-class-name"
  val partitionKeyResolverProviderClassNameKey =
    "partition-key-resolver-provider-class-name"
  val sortKeyResolverClassNameKey = "sort-key-resolver-class-name"
  val sortKeyResolverProviderClassNameKey =
    "sort-key-resolver-provider-class-name"

  def fromConfig(config: Config): SnapshotPluginConfig = {
    logger.debug("config = {}", config)
    val legacyConfigFormat = config.as[Boolean](legacyConfigFormatKey)
    logger.debug("legacy-config-format = {}", legacyConfigFormat)
    val legacyTableFormat = config.as[Boolean](legacyTableFormatKey)
    logger.debug("legacy-table-format = {}", legacyTableFormat)
    val clientConfig = CommonConfigParser.dynamoDbClientConfig(config, legacyConfigFormat)
    val clientFactories =
      CommonConfigParser.clientFactories(
        config,
        clientConfig.clientVersion,
        CommonConfigParser.FactoryValidation.V2DaxUnderV2
      )
    val reporters = CommonConfigParser.reporters(config)
    val backoffs  = CommonConfigParser.backoffs(config)
    val result = SnapshotPluginConfig(
      sourceConfig = config,
      plugInLifecycleHandlerFactoryClassName = {
        val className = config.as[String](plugInLifecycleHandlerFactoryClassNameKey)
        ClassCheckUtils.requireClassByName(PlugInLifecycleHandlerFactoryClassName, className)
      },
      plugInLifecycleHandlerClassName = {
        val className = config.as[String](plugInLifecycleHandlerClassNameKey)
        ClassCheckUtils.requireClassByName(PlugInLifecycleHandlerClassName, className)
      },
      v1AsyncClientFactoryClassName = clientFactories.v1AsyncClientFactoryClassName,
      v1SyncClientFactoryClassName = clientFactories.v1SyncClientFactoryClassName,
      v1DaxAsyncClientFactoryClassName = clientFactories.v1DaxAsyncClientFactoryClassName,
      v1DaxSyncClientFactoryClassName = clientFactories.v1DaxSyncClientFactoryClassName,
      v2AsyncClientFactoryClassName = clientFactories.v2AsyncClientFactoryClassName,
      v2SyncClientFactoryClassName = clientFactories.v2SyncClientFactoryClassName,
      v2DaxAsyncClientFactoryClassName = clientFactories.v2DaxAsyncClientFactoryClassName,
      v2DaxSyncClientFactoryClassName = clientFactories.v2DaxSyncClientFactoryClassName,
      legacyConfigFormat = legacyConfigFormat,
      legacyTableFormat = legacyTableFormat,
      tableName = config.as[String](tableNameKey),
      columnsDefConfig =
        SnapshotColumnsDefConfig.fromConfig(config.getAs[Config](columnsDefKey).getOrElse(ConfigFactory.empty())),
      getSnapshotRowsIndexName = config.as[String](getSnapshotRowsIndexNameKey),
      consistentRead = config.as[Boolean](consistentReadKey),
      shardCount = config.as[Int](shardCountKey),
      // ---
      partitionKeyResolverClassName = {
        val className = config.as[String](partitionKeyResolverClassNameKey)
        ClassCheckUtils.requireClass(classOf[PartitionKeyResolver], className)
      },
      partitionKeyResolverProviderClassName = {
        val className = config.as[String](partitionKeyResolverProviderClassNameKey)
        ClassCheckUtils.requireClass(classOf[PartitionKeyResolverProvider], className)
      },
      sortKeyResolverClassName = {
        val className = config.as[String](sortKeyResolverClassNameKey)
        ClassCheckUtils.requireClass(classOf[SortKeyResolver], className)
      },
      sortKeyResolverProviderClassName = {
        val className = config.as[String](sortKeyResolverProviderClassNameKey)
        ClassCheckUtils.requireClass(classOf[SortKeyResolverProvider], className)
      },
      // ---
      metricsReporterProviderClassName = reporters.metricsReporterProviderClassName,
      metricsReporterClassName = reporters.metricsReporterClassName,
      traceReporterProviderClassName = reporters.traceReporterProviderClassName,
      traceReporterClassName = reporters.traceReporterClassName,
      writeBackoffConfig = backoffs.write,
      readBackoffConfig = backoffs.read,
      clientConfig = clientConfig
    )
    logger.debug("result = {}", result)
    result
  }

}

final case class SnapshotPluginConfig(
    sourceConfig: Config,
    plugInLifecycleHandlerFactoryClassName: String,
    plugInLifecycleHandlerClassName: String,
    v1AsyncClientFactoryClassName: String,
    v1SyncClientFactoryClassName: String,
    v1DaxAsyncClientFactoryClassName: String,
    v1DaxSyncClientFactoryClassName: String,
    v2AsyncClientFactoryClassName: String,
    v2SyncClientFactoryClassName: String,
    v2DaxAsyncClientFactoryClassName: String,
    v2DaxSyncClientFactoryClassName: String,
    legacyConfigFormat: Boolean,
    legacyTableFormat: Boolean,
    tableName: String,
    columnsDefConfig: SnapshotColumnsDefConfig,
    getSnapshotRowsIndexName: String,
    consistentRead: Boolean,
    partitionKeyResolverClassName: String,
    sortKeyResolverClassName: String,
    partitionKeyResolverProviderClassName: String,
    sortKeyResolverProviderClassName: String,
    shardCount: Int,
    metricsReporterProviderClassName: String,
    metricsReporterClassName: Option[String],
    writeBackoffConfig: BackoffConfig,
    traceReporterProviderClassName: String,
    traceReporterClassName: Option[String],
    readBackoffConfig: BackoffConfig,
    clientConfig: DynamoDBClientConfig
) extends PluginConfig {
  override val configRootPath: String = "j5ik2o.dynamo-db-snapshot"
}
