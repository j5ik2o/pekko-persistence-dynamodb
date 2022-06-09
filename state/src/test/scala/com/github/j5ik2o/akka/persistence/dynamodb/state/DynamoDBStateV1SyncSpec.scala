package com.github.j5ik2o.akka.persistence.dynamodb.state

import akka.persistence.state.DurableStateStoreRegistry
import com.github.j5ik2o.akka.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.akka.persistence.dynamodb.state.scaladsl.{ DynamoDBDurableStateStoreV1, StateSpecBase }
import com.github.j5ik2o.akka.persistence.dynamodb.utils.{ ConfigHelper, DynamoDBSpecSupport, RandomPortUtil }
import org.scalatest.concurrent.ScalaFutures
import org.testcontainers.DockerClientFactory

import java.util.UUID
import scala.concurrent.duration.DurationInt

object DynamoDBStateV1SyncSpec {
  val dynamoDBHost: String = DockerClientFactory.instance().dockerHostIpAddress()
  val dynamoDBPort: Int    = RandomPortUtil.temporaryServerPort()
}

class DynamoDBStateV1SyncSpec
    extends StateSpecBase(
      ConfigHelper
        .config(
          Some("state-reference"),
          legacyConfigFormat = false,
          legacyJournalMode = false,
          dynamoDBHost = DynamoDBStateV1SyncSpec.dynamoDBHost,
          dynamoDBPort = DynamoDBStateV1SyncSpec.dynamoDBPort,
          clientVersion = ClientVersion.V1.toString,
          clientType = ClientType.Sync.toString
        )
    )
    with ScalaFutures
    with DynamoDBSpecSupport {

  implicit val pc: PatienceConfig = PatienceConfig(30.seconds, 1.seconds)

  override protected lazy val dynamoDBPort: Int = DynamoDBStateV1SyncSpec.dynamoDBPort

  "A durable state store plugin" - {
    "instantiate a JdbcDurableDataStore successfully" in {
      val store = DurableStateStoreRegistry
        .get(system)
        .durableStateStoreFor[DynamoDBDurableStateStoreV1[String]](DynamoDBDurableStateStoreProvider.Identifier)

      {
        val id       = UUID.randomUUID().toString
        val revision = 1
        val data     = "abc"
        val tag      = ""
        store.upsertObject(id, revision, data, tag).futureValue()
        val result = store.getObject(id).futureValue()
        result.value shouldBe Some(data)
      }
      {
        val id       = UUID.randomUUID().toString
        val revision = 1
        val data     = "def"
        val tag      = UUID.randomUUID().toString
        store.upsertObject(id, revision, data, tag).futureValue()
        val result = store.getRawObject(id).futureValue()
        result match {
          case just: GetRawObjectResult.Just[String] =>
            just.value shouldBe Some(data)
            just.tag shouldBe Some(tag)
          case _ =>
            fail()
        }
      }

      store shouldBe a[DynamoDBDurableStateStoreV1[_]]
      store.system.settings.config shouldBe system.settings.config
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    createTable()
  }

  override def afterAll(): Unit = {
    deleteTable()
    super.afterAll()
  }

}
