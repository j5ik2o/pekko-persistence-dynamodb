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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import org.apache.pekko.persistence.CapabilityFlag
import org.apache.pekko.persistence.journal.JournalSpec
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DynamoDBSpecSupport, RandomPortUtil }
import org.scalatest.concurrent.ScalaFutures
import org.testcontainers.DockerClientFactory

import scala.concurrent.duration._

object DynamoDBJournalV1SyncSpec {
  val dynamoDBHost: String       = DockerClientFactory.instance().dockerHostIpAddress()
  val dynamoDBPort: Int          = RandomPortUtil.temporaryServerPort()
  val legacyJournalMode: Boolean = false

}

final class DynamoDBJournalV1SyncSpec
    extends JournalSpec(
      ConfigHelper
        .config(
          Some("journal-reference"),
          legacyConfigFormat = false,
          legacyJournalMode = DynamoDBJournalV1SyncSpec.legacyJournalMode,
          dynamoDBHost = DynamoDBJournalV1SyncSpec.dynamoDBHost,
          dynamoDBPort = DynamoDBJournalV1SyncSpec.dynamoDBPort,
          clientVersion = ClientVersion.V1.toString,
          clientType = ClientType.Sync.toString
        )
    )
    with ScalaFutures
    with DynamoDBSpecSupport {

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.on()

  implicit val pc: PatienceConfig = PatienceConfig(30.seconds, 1.seconds)

  override protected lazy val dynamoDBPort: Int = DynamoDBJournalV1SyncSpec.dynamoDBPort

  override val legacyJournalTable: Boolean = DynamoDBJournalV1SyncSpec.legacyJournalMode

  override def beforeAll(): Unit = {
    super.beforeAll()
    createTable()
  }

  override def afterAll(): Unit = {
    deleteTable()
    super.afterAll()
  }

}