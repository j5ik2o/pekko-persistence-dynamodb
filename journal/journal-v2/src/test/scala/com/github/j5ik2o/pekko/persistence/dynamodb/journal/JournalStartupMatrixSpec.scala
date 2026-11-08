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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Flow, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.ClientVariant
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.{ JournalRowWriteDriver, PersistenceIdWithSeqNr }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v2.{
  V2DaxJournalRowWriteDriverFactory,
  V2JournalRowWriteDriverFactory
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{
  V2AsyncClientFactory,
  V2DaxAsyncClientFactory,
  V2DaxSyncClientFactory,
  V2SyncClientFactory
}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/** A driver that never reaches a client, so the startup matrix can build a plugin context for a DAX variant without
  * opening a DAX connection.
  */
final class StartupMatrixJournalRowWriteDriver(actorSystem: ActorSystem) extends JournalRowWriteDriver {
  override def system: ActorSystem = actorSystem
  override def dispose(): Unit     = ()

  override def getJournalRows(
      persistenceId: PersistenceId,
      toSequenceNr: SequenceNumber,
      deleted: Boolean
  ): Source[Seq[JournalRow], NotUsed] = Source.empty

  override def getJournalRows(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber,
      toSequenceNr: SequenceNumber,
      max: Long,
      deleted: Option[Boolean]
  ): Source[JournalRow, NotUsed] = Source.empty

  override def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber],
      deleted: Option[Boolean]
  ): Source[Option[Long], NotUsed] = Source.single(None)

  override def singlePutJournalRowFlow: Flow[JournalRow, Long, NotUsed] =
    Flow[JournalRow].map(_ => 0L)

  override def multiPutJournalRowsFlow: Flow[Seq[JournalRow], Long, NotUsed] =
    Flow[Seq[JournalRow]].map(_ => 0L)

  override def updateMessage(journalRow: JournalRow): Source[Unit, NotUsed] = Source.empty

  override def singleDeleteJournalRowFlow: Flow[PersistenceIdWithSeqNr, Long, NotUsed] =
    Flow[PersistenceIdWithSeqNr].map(_ => 0L)

  override def multiDeleteJournalRowsFlow: Flow[Seq[PersistenceIdWithSeqNr], Long, NotUsed] =
    Flow[Seq[PersistenceIdWithSeqNr]].map(_ => 0L)
}

final class StartupMatrixJournalRowWriteDriverFactory(pluginContext: JournalPluginContext)
    extends JournalRowWriteDriverFactory {
  override def create: JournalRowWriteDriver = new StartupMatrixJournalRowWriteDriver(pluginContext.system)
}

/** Proves that the journal plugin resolves its executor, its AWS SDK client factories, and its row write driver factory
  * for the v2 client variants. The client factories and the real driver factory are constructed, not invoked, so no
  * DynamoDB or DAX connection is opened. The configured driver factory is a local test double, because a DAX driver
  * factory opens its client when it creates the driver.
  */
final class JournalStartupMatrixSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  private var system: ActorSystem = _

  override def beforeAll(): Unit = {
    system = ActorSystem("journal-startup-matrix-v2")
  }

  override def afterAll(): Unit = {
    if (system != null) Await.result(system.terminate(), 30.seconds)
  }

  private val testDriverFactoryClassName = classOf[StartupMatrixJournalRowWriteDriverFactory].getName

  private def pluginConfig(version: ClientVersion.Value): JournalPluginConfig = {
    val config = ConfigFactory
      .parseString(
        s"""
           |j5ik2o.dynamo-db-journal {
           |  dynamo-db-client {
           |    client-version = "${version.toString}"
           |    client-type = "sync"
           |  }
           |  v1-journal-row-write-driver-factory-class-name = "$testDriverFactoryClassName"
           |  v1-dax-journal-row-write-driver-factory-class-name = "$testDriverFactoryClassName"
           |  v2-journal-row-write-driver-factory-class-name = "$testDriverFactoryClassName"
           |  v2-dax-journal-row-write-driver-factory-class-name = "$testDriverFactoryClassName"
           |}
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load("journal-reference"))
      .resolve()
    JournalPluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-journal"))
  }

  private def context(version: ClientVersion.Value): JournalPluginContext = {
    val config = pluginConfig(version)
    config.clientVariant shouldBe expectedVariant(version)
    val pluginContext = JournalPluginContext(system, config)
    pluginContext.pluginExecutor should not be null
    pluginContext
  }

  private def expectedVariant(version: ClientVersion.Value): ClientVariant =
    version match {
      case ClientVersion.V2    => ClientVariant.V2
      case ClientVersion.V2Dax => ClientVariant.V2Dax
      case other               => fail(s"this spec covers the v2 variants, not $other")
    }

  "the journal plugin starts for v2" in {
    val pluginContext = context(ClientVersion.V2)
    pluginContext.pluginConfig.v2JournalRowWriteDriverFactoryClassName shouldBe testDriverFactoryClassName
    pluginContext
      .newDynamicAccessor[V2SyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2SyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[V2AsyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2AsyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[JournalRowWriteDriverFactory]()
      .createThrow(classOf[V2JournalRowWriteDriverFactory].getName) shouldBe a[V2JournalRowWriteDriverFactory]
  }

  "the journal plugin starts for v2-dax" in {
    val pluginContext = context(ClientVersion.V2Dax)
    pluginContext.pluginConfig.v2DaxJournalRowWriteDriverFactoryClassName shouldBe testDriverFactoryClassName
    pluginContext
      .newDynamicAccessor[V2DaxSyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2DaxSyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[V2DaxAsyncClientFactory]()
      .createThrow(pluginContext.pluginConfig.v2DaxAsyncClientFactoryClassName) should not be null
    pluginContext
      .newDynamicAccessor[JournalRowWriteDriverFactory]()
      .createThrow(classOf[V2DaxJournalRowWriteDriverFactory].getName) shouldBe a[V2DaxJournalRowWriteDriverFactory]
  }

}
