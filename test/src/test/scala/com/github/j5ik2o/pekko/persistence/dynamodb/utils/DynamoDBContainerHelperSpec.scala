/*
 * Copyright 2020 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.utils

import com.github.dockerjava.api.model.ExposedPort
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

private object DynamoDBContainerHelperSpec extends DockerAssignedPort

class DynamoDBContainerHelperSpec extends AnyFreeSpec with Matchers with DynamoDBContainerHelper {

  override lazy val controller        = DynamoDBContainerHelperSpec.controller
  override lazy val dynamoDBPort: Int = DynamoDBContainerHelperSpec.dynamoDBPort

  private val configuredEndpoint               = DynamoDBContainerHelperSpec.dynamoDBEndpoint
  private var firstContainerId: Option[String] = None

  "use Docker's bound port to reach an isolated database" in {
    val binding = controller.inspectContainer().getNetworkSettings.getPorts.getBindings.get(ExposedPort.tcp(8000)).head
    dynamoDBPort shouldBe binding.getHostPortSpec.toInt
    dynamoDBPort should be > 0
    dynamoDBEndpoint shouldBe configuredEndpoint
    dynamoDBEndpoint should endWith(s":$dynamoDBPort")
    dynamoDBClient.listTables().getTableNames shouldBe empty
    createTable()
    dynamoDBClient.listTables().getTableNames should contain(journalTableName)
    firstContainerId = controller.containerId
    info(s"container=${controller.containerId.get} boundPort=$dynamoDBPort endpoint=$dynamoDBEndpoint")
  }

  "keep the same binding between tests until suite disposal" in {
    controller.containerId shouldBe firstContainerId
    dynamoDBClient.listTables().getTableNames should contain(journalTableName)
    deleteTable()
    dynamoDBClient.listTables().getTableNames shouldBe empty
  }
}
