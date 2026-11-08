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
package com.github.j5ik2o.pekko.persistence.dynamodb.state.config

import com.github.j5ik2o.pekko.persistence.dynamodb.config.PluginConfig._
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.DynamoDBClientConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.config.{ BackoffConfig, CommonConfigParser, PluginConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.state._
import net.ceedubs.ficus.Ficus._
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ClassCheckUtils, LoggingSupport }
import com.typesafe.config.{ Config, ConfigFactory }

object StatePluginConfig extends LoggingSupport {

  val tableNameKey                             = "table-name"
  val columnsDefKey                            = "columns-def"
  val tagSeparatorKey                          = "tag-separator"
  val consistentReadKey                        = "consistent-read"
  val shardCountKey                            = "shard-count"
  val partitionKeyResolverClassNameKey         = "partition-key-resolver-class-name"
  val partitionKeyResolverProviderClassNameKey = "partition-key-resolver-provider-class-name"
  val sortKeyResolverClassNameKey              = "sort-key-resolver-class-name"
  val sortKeyResolverProviderClassNameKey      = "sort-key-resolver-provider-class-name"
  val tableNameResolverClassNameKey            = "table-name-resolver-class-name"
  val tableNameResolverProviderClassNameKey    = "table-name-resolver-provider-class-name"
  val writeBackoffKey                          = "write-backoff"
  val readBackoffKey                           = "read-backoff"
  val metricsReporterClassNameKey              = "metrics-reporter-class-name"
  val metricsReporterProviderClassNameKey      = "metrics-reporter-provider-class-name"
  val traceReporterClassNameKey                = "trace-reporter-class-name"
  val traceReporterProviderClassNameKey        = "trace-reporter-provider-class-name"
  val dynamoCbClientKey                        = "dynamo-db-client"

  def fromConfig(config: Config): StatePluginConfig = {
    logger.debug("config = {}", config)
    val clientConfig = CommonConfigParser.dynamoDbClientConfig(config, legacyConfigFormat = false)
    val clientFactories =
      CommonConfigParser.clientFactories(
        config,
        clientConfig.clientVersion,
        CommonConfigParser.FactoryValidation.PerVariant
      )
    val reporters = CommonConfigParser.reporters(config)
    val backoffs  = CommonConfigParser.backoffs(config)
    val result = new StatePluginConfig(
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
      tableName = config.as[String](tableNameKey),
      columnsDefConfig =
        StateColumnsDefConfig.fromConfig(config.getAs[Config](columnsDefKey).getOrElse(ConfigFactory.empty())),
      tagSeparator = config.as[String](tagSeparatorKey),
      consistentRead = config.as[Boolean](consistentReadKey),
      shardCount = config.as[Int](shardCountKey),
      tableNameResolverClassName = {
        val className = config.as[String](tableNameResolverClassNameKey)
        ClassCheckUtils.requireClass(classOf[TableNameResolver], className)
      },
      tableNameResolverProviderClassName = {
        val className = config.as[String](tableNameResolverProviderClassNameKey)
        ClassCheckUtils.requireClass(classOf[TableNameResolverProvider], className)
      },
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
      metricsReporterClassName = reporters.metricsReporterClassName,
      metricsReporterProviderClassName = reporters.metricsReporterProviderClassName,
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

final case class StatePluginConfig(
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
    tableName: String,
    columnsDefConfig: StateColumnsDefConfig,
    tableNameResolverClassName: String,
    tableNameResolverProviderClassName: String,
    tagSeparator: String,
    consistentRead: Boolean,
    shardCount: Int,
    partitionKeyResolverClassName: String,
    partitionKeyResolverProviderClassName: String,
    sortKeyResolverClassName: String,
    sortKeyResolverProviderClassName: String,
    metricsReporterProviderClassName: String,
    metricsReporterClassName: Option[String],
    traceReporterProviderClassName: String,
    traceReporterClassName: Option[String],
    writeBackoffConfig: BackoffConfig,
    readBackoffConfig: BackoffConfig,
    clientConfig: DynamoDBClientConfig
) extends PluginConfig {
  override val configRootPath: String = DynamoDBDurableStateStoreProvider.Identifier

}
