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

import org.apache.pekko.Done
import org.apache.pekko.actor.Status
import org.apache.pekko.persistence.dynamodbtest.JournalProtocolAccess
import org.apache.pekko.testkit.TestProbe
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  JournalHeadRowUpdateRejectedException,
  JournalRowUpdateRejection
}
import org.apache.pekko.persistence.CapabilityFlag
import org.apache.pekko.persistence.journal.JournalSpec
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ ConfigHelper, DynamoDBContainerHelper, RandomPortUtil }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object DynamoDBJournalV1AsyncSpec {
  val dynamoDBHost: String       = "localhost"
  val dynamoDBPort: Int          = RandomPortUtil.temporaryServerPort()
  val legacyJournalMode: Boolean = false

}

final class DynamoDBJournalV1AsyncSpec
    extends JournalSpec(
      ConfigHelper
        .config(
          Some("journal-reference"),
          legacyConfigFormat = false,
          legacyJournalMode = DynamoDBJournalV1AsyncSpec.legacyJournalMode,
          dynamoDBHost = DynamoDBJournalV1AsyncSpec.dynamoDBHost,
          dynamoDBPort = DynamoDBJournalV1AsyncSpec.dynamoDBPort,
          clientVersion = ClientVersion.V1.toString,
          clientType = ClientType.Async.toString
        )
    )
    with ScalaFutures
    with DynamoDBContainerHelper {

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.on()

  implicit val pc: PatienceConfig = PatienceConfig(30.seconds, 1.seconds)

  override lazy val dynamoDBPort: Int = DynamoDBJournalV1AsyncSpec.dynamoDBPort

  override val legacyJournalTable: Boolean = DynamoDBJournalV1AsyncSpec.legacyJournalMode

  "refuse an in-place event update of a row that does not exist and create no row" in {
    val pid      = "headless-in-place-update-missing"
    val receiver = TestProbe()
    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, "updated"), testActor)
    val failure = expectMsgType[Status.Failure](30.seconds).cause
    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure
      .asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDoesNotExist

    journal ! JournalProtocolAccess.replay(pid, receiver.ref)
    receiver.expectMsg(30.seconds, JournalProtocolAccess.recoverySuccess(0L))
  }

  "refuse an in-place event update of a soft deleted row and keep it deleted" in {
    val pid      = "headless-in-place-update-deleted"
    val receiver = TestProbe()
    val writer   = TestProbe()
    journal.tell(JournalProtocolAccess.write(pid, 1L, "original", writer.ref), writer.ref)
    writer.expectMsg(30.seconds, JournalProtocolAccess.writeSuccessful)
    journal ! JournalProtocolAccess.deleteTo(pid, 1L, receiver.ref)
    receiver.expectMsg(30.seconds, JournalProtocolAccess.deleteSuccess(1L))

    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, "updated"), testActor)
    val failure = expectMsgType[Status.Failure](30.seconds).cause
    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDeleted

    journal ! JournalProtocolAccess.replay(pid, receiver.ref)
    receiver.expectMsg(30.seconds, JournalProtocolAccess.recoverySuccess(1L))
  }

  "update the message of an existing row in place" in {
    val pid      = "headless-in-place-update-existing"
    val receiver = TestProbe()
    val writer   = TestProbe()
    journal.tell(JournalProtocolAccess.write(pid, 1L, "original", writer.ref), writer.ref)
    writer.expectMsg(30.seconds, JournalProtocolAccess.writeSuccessful)

    journal.tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, "updated"), testActor)
    expectMsg(30.seconds, Done)

    journal ! JournalProtocolAccess.replay(pid, receiver.ref)
    JournalProtocolAccess.replayedPayload(receiver.receiveOne(30.seconds)) shouldBe Some("updated")
    receiver.expectMsg(30.seconds, JournalProtocolAccess.recoverySuccess(1L))
  }

  override def afterStartContainers(): Unit = {
    super.afterStartContainers()
    createTable()
  }

  override def beforeStopContainers(): Unit = {
    deleteTable()
    super.beforeStopContainers()
  }

}
