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
package com.github.j5ik2o.akka.persistence.dynamodb.utils

import akka.actor.DynamicAccess
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.github.j5ik2o.akka.persistence.dynamodb.config.PluginConfig

trait V1DaxAsyncClientFactory {

  def create(dynamicAccess: DynamicAccess, pluginConfig: PluginConfig): AmazonDynamoDBAsync

}

object V1DaxAsyncClientFactory {

  class Default extends V1DaxAsyncClientFactory {
    override def create(dynamicAccess: DynamicAccess, pluginConfig: PluginConfig): AmazonDynamoDBAsync = {
      V1ClientUtils.createV1DaxAsyncClient(dynamicAccess, pluginConfig)
    }
  }

}