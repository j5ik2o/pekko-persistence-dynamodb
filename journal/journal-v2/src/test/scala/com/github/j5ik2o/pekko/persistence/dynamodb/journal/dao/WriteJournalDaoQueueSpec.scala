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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.stream.scaladsl.{ Flow, Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalPluginContext,
  JournalRow,
  JournalRowWriteDriverFactory
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.serialization.ByteArrayJournalSerializer
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise, TimeoutException }
import scala.util.{ Failure, Try }

/** Exercises the two ways the bounded write queue can refuse a write without reaching a row driver: a full buffer and a
  * closed queue. The driver doubles keep the spec local to the plugin, so it starts no DynamoDB container.
  */
object WriteJournalDaoQueueSpec {

  /** Creates the driver that `JournalPluginContext` needs. The queue spec passes its own driver to the DAO. */
  final class NoopJournalRowWriteDriverFactory(pluginContext: JournalPluginContext)
      extends JournalRowWriteDriverFactory {
    override def create: JournalRowWriteDriver = new NoopJournalRowWriteDriver(pluginContext.system)
  }

  class NoopJournalRowWriteDriver(actorSystem: ActorSystem) extends JournalRowWriteDriver {
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
      Flow[JournalRow].map(_ => 1L)

    override def multiPutJournalRowsFlow: Flow[Seq[JournalRow], Long, NotUsed] =
      Flow[Seq[JournalRow]].map(_.size.toLong)

    override def updateMessage(journalRow: JournalRow): Source[Unit, NotUsed] = Source.empty

    override def singleDeleteJournalRowFlow: Flow[PersistenceIdWithSeqNr, Long, NotUsed] =
      Flow[PersistenceIdWithSeqNr].map(_ => 1L)

    override def multiDeleteJournalRowsFlow: Flow[Seq[PersistenceIdWithSeqNr], Long, NotUsed] =
      Flow[Seq[PersistenceIdWithSeqNr]].map(_.size.toLong)
  }

  /** Holds the single write of the queue in flight until the spec releases it, so the queue has no downstream demand.
    */
  final class BlockingJournalRowWriteDriver(actorSystem: ActorSystem) extends NoopJournalRowWriteDriver(actorSystem) {
    private implicit val ec: ExecutionContext = actorSystem.dispatcher
    private val startedPromise                = Promise[Unit]()
    private val releasePromise                = Promise[Unit]()

    def started: Future[Unit] = startedPromise.future
    def release(): Unit       = releasePromise.trySuccess(())

    override def singlePutJournalRowFlow: Flow[JournalRow, Long, NotUsed] =
      Flow[JournalRow].mapAsync(1) { _ =>
        startedPromise.trySuccess(())
        releasePromise.future.map(_ => 1L)
      }
  }

}

final class WriteJournalDaoQueueSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  import WriteJournalDaoQueueSpec._

  private implicit val system: ActorSystem  = ActorSystem("write-journal-dao-queue-spec")
  private implicit val ec: ExecutionContext = system.dispatcher

  private val testFactoryClassName = classOf[NoopJournalRowWriteDriverFactory].getName
  private val persistenceId        = PersistenceId("write-journal-dao-queue-spec")
  private val row =
    JournalRow(persistenceId, SequenceNumber(1L), deleted = false, message = Array.empty[Byte], ordering = 0L)

  override def afterAll(): Unit = {
    if (system != null) Await.result(system.terminate(), 30.seconds)
  }

  private def pluginConfig(bufferSize: Int, overflowStrategy: String): JournalPluginConfig = {
    val config = ConfigFactory
      .parseString(
        s"""
           |j5ik2o.dynamo-db-journal {
           |  dynamo-db-client {
           |    client-version = "v2"
           |    client-type = "sync"
           |  }
           |  queue-enable = true
           |  queue-buffer-size = $bufferSize
           |  queue-overflow-strategy = "$overflowStrategy"
           |  queue-parallelism = 1
           |  write-parallelism = 1
           |  v1-journal-row-write-driver-factory-class-name = "$testFactoryClassName"
           |  v1-dax-journal-row-write-driver-factory-class-name = "$testFactoryClassName"
           |  v2-journal-row-write-driver-factory-class-name = "$testFactoryClassName"
           |  v2-dax-journal-row-write-driver-factory-class-name = "$testFactoryClassName"
           |}
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load("journal-reference"))
      .resolve()
    JournalPluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-journal"))
  }

  private def newDao(
      driver: JournalRowWriteDriver,
      bufferSize: Int,
      overflowStrategy: String
  ): WriteJournalDaoImpl = {
    val config        = pluginConfig(bufferSize, overflowStrategy)
    val pluginContext = JournalPluginContext(system, config)
    val serializer = new ByteArrayJournalSerializer(
      SerializationExtension(system),
      config.tagSeparator,
      None,
      None
    )
    new WriteJournalDaoImpl(pluginContext, driver, serializer)(pluginContext.pluginExecutor, system)
  }

  /** Offers writes until the shut-down queue refuses one. A write accepted just before the queue completes is retried.
    */
  private def awaitClosedQueueFailure(dao: WriteJournalDaoImpl): Throwable = {
    val deadline = 10.seconds.fromNow
    @tailrec
    def loop(): Throwable =
      Try(Await.result(dao.putMessages(Seq(row)).runWith(Sink.head), 500.millis)) match {
        case Failure(_: TimeoutException) if deadline.hasTimeLeft() => Thread.sleep(50); loop()
        case Failure(cause)                                         => cause
        case _ if deadline.hasTimeLeft()                            => Thread.sleep(50); loop()
        case other                                                  => fail(s"the write queue stayed open: $other")
      }
    loop()
  }

  "a full write queue fails the write with a recoverable, typed failure" in {
    val driver = new BlockingJournalRowWriteDriver(system)
    val dao    = newDao(driver, bufferSize = 1, overflowStrategy = "dropnew")
    try {
      // The first write holds the single mapAsync slot, so the buffer is the only place the queue can put a write.
      val first = dao.putMessages(Seq(row)).runWith(Sink.head)
      Await.result(driver.started, 10.seconds)

      val overflow = Vector.fill(10)(dao.putMessages(Seq(row)).runWith(Sink.head))
      val failure  = Await.result(Future.firstCompletedOf(overflow.map(_.failed)), 10.seconds)

      failure shouldBe a[JournalWriteQueueFullException]
      failure.getMessage should include("queue-buffer-size")

      driver.release()
      Await.result(first, 10.seconds) shouldBe 1L
    } finally {
      driver.release()
      dao.dispose()
    }
  }

  "a closed write queue fails the write with a recoverable, typed failure" in {
    val dao = newDao(new NoopJournalRowWriteDriver(system), bufferSize = 1, overflowStrategy = "fail")
    try {
      dao.dispose()

      val failure = awaitClosedQueueFailure(dao)

      failure shouldBe a[JournalWriteQueueClosedException]
    } finally dao.dispose()
  }

}
