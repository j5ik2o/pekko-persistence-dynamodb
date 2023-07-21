package com.github.j5ik2o.pekko.persistence.dynamodb.utils

import com.github.j5ik2o.pekko.persistence.dynamodb.context.PluginContext
import software.amazon.dax.{ ClusterDaxAsyncClient, ClusterDaxClient }

object V2DaxClientBuilderUtils {

  def setupAsync(
      pluginContext: PluginContext
  ): ClusterDaxAsyncClient.Builder = {
    val builder = ClusterDaxAsyncClient.builder()
    val configuration = V2DaxOverrideConfigurationBuilderUtils
      .setup(pluginContext)
      .build()
    builder.overrideConfiguration(configuration)
    builder
  }

  def setupSync(
      pluginContext: PluginContext
  ): ClusterDaxClient.Builder = {
    val builder = ClusterDaxClient.builder()
    val configuration = V2DaxOverrideConfigurationBuilderUtils
      .setup(pluginContext)
      .build()
    builder.overrideConfiguration(configuration)
    builder
  }

}
