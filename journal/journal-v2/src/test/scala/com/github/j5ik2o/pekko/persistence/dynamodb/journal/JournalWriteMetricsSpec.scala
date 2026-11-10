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
import org.apache.pekko.actor.{ Actor, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, IndirectActorProducer }
import org.apache.pekko.persistence.{ AtomicWrite, Persistence, PersistentRepr }
import org.apache.pekko.serialization.Serializer
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.testkit.TestProbe
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.{ JournalRowWriteDriver, WriteJournalDaoQueueSpec }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  JournalHeadJournalRowTooLargeException,
  JournalHeadTooManyEventsException,
  JournalHeadWriteRequest
}
import com.github.j5ik2o.pekko.persistence.dynamodb.metrics.{ MetricsReporter, MetricsReporterProvider }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.Context
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.io.NotSerializableException
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

object JournalWriteMetricsSpec {
  final case class Callback(operation: String, result: Try[Unit])

  final class Recording(system: ExtendedActorSystem) extends Extension with MetricsReporter {
    val journal: Promise[DynamoDBJournal] = Promise[DynamoDBJournal]()
    val callbacks: TestProbe              = TestProbe()(system)
    val writes                            = new ConcurrentLinkedQueue[Seq[JournalRow]]()
    override def beforeJournalAsyncWriteMessages(context: Context): Context = {
      callbacks.ref ! Callback("write.before", Success(()))
      context
    }
    override def afterJournalAsyncWriteMessages(context: Context): Unit =
      callbacks.ref ! Callback("write", Success(()))
    override def errorJournalAsyncWriteMessages(context: Context, ex: Throwable): Unit =
      callbacks.ref ! Callback("write", Failure(ex))
    override def afterJournalSerializeJournal(context: Context): Unit =
      callbacks.ref ! Callback("serialize", Success(()))
    override def errorJournalSerializeJournal(context: Context, ex: Throwable): Unit =
      callbacks.ref ! Callback("serialize", Failure(ex))
  }

  object Recording extends ExtensionId[Recording] {
    override def createExtension(system: ExtendedActorSystem): Recording = new Recording(system)
  }

  // Pekko must register the plugin actor before AsyncWriteJournal reads its configuration.
  final class JournalProducer(config: Config) extends IndirectActorProducer {
    override def actorClass: Class[_ <: Actor] = classOf[DynamoDBJournal]
    override def produce(): Actor = {
      val journal = new DynamoDBJournal(config)
      Recording(journal.system).journal.success(journal)
      journal
    }
  }

  final class ReporterProvider(context: JournalPluginContext) extends MetricsReporterProvider {
    override def create: Option[MetricsReporter] = Some(Recording(context.system))
  }

  final class DriverFactory(context: JournalPluginContext) extends JournalRowWriteDriverFactory {
    override def create: JournalRowWriteDriver =
      new WriteJournalDaoQueueSpec.NoopJournalRowWriteDriver(context.system) {
        private val recording = Recording(context.system)
        override def singlePutJournalRowFlow: Flow[JournalRow, Long, NotUsed] =
          Flow[JournalRow].map { row =>
            recording.writes.add(Seq(row))
            1L
          }
        override def multiPutJournalRowsFlow: Flow[Seq[JournalRow], Long, NotUsed] =
          Flow[Seq[JournalRow]].map { rows =>
            recording.writes.add(rows)
            rows.size.toLong
          }
        override def putJournalRowsWithHeadFlow: Flow[JournalHeadWriteRequest, Long, NotUsed] =
          Flow[JournalHeadWriteRequest].map { request =>
            recording.writes.add(request.rows)
            request.rows.size.toLong
          }
      }
  }

  final class UnserializableEvent
  final class RejectingSerializer extends Serializer {
    override def identifier: Int                      = 846
    override def includeManifest: Boolean             = false
    override def toBinary(value: AnyRef): Array[Byte] = throw new NotSerializableException("metrics test event")
    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef =
      throw new UnsupportedOperationException("write-only test serializer")
  }
}

final class JournalWriteMetricsSpec extends AnyFreeSpec with Matchers {
  import JournalWriteMetricsSpec._

  info(s"Observed test JVM ${System.getProperty("java.version")}")

  private def withJournal(headEnabled: Boolean)(test: (DynamoDBJournal, Recording) => Unit): Unit = {
    val config = ConfigFactory
      .parseString(s"""
      pekko.persistence.journal.auto-start-journals = ["j5ik2o.dynamo-db-journal"]
      pekko.persistence.snapshot-store.plugin = ""
      pekko.actor.serializers.metrics-rejection = "${classOf[RejectingSerializer].getName}"
      pekko.actor.serialization-bindings."${classOf[UnserializableEvent].getName}" = metrics-rejection
      j5ik2o.dynamo-db-journal {
        class = "${classOf[JournalProducer].getName}"
        dynamo-db-client { client-version = v2, client-type = sync }
        head { enabled = $headEnabled, table-name = metrics-head }
        v2-journal-row-write-driver-factory-class-name = "${classOf[DriverFactory].getName}"
        metrics-reporter-provider-class-name = "${classOf[ReporterProvider].getName}"
      }
    """).withFallback(ConfigFactory.load("journal-reference")).resolve()
    implicit val system: ActorSystem = ActorSystem("journal-write-metrics-spec", config)
    try {
      val recording = Recording(system)
      Persistence(system)
      test(Await.result(recording.journal.future, 10.seconds), recording)
    } finally Await.result(system.terminate(), 30.seconds)
  }

  private def write(payloads: Any*): AtomicWrite =
    AtomicWrite(payloads.zipWithIndex.map { case (payload, index) =>
      PersistentRepr(payload, index.toLong + 1, "metrics-pid")
    }.toVector)

  private def expectCallbacks(recording: Recording, serialized: Seq[Try[Unit]], written: Try[Unit]): Unit = {
    val expected = Seq(Callback("write.before", Success(())), Callback("write", written)) ++
      serialized.map(Callback("serialize", _))
    recording.callbacks.receiveN(expected.size, 10.seconds) should contain theSameElementsAs expected
  }

  "a valid head write reports completion and reaches the head driver" in withJournal(headEnabled = true) {
    (journal, recording) =>
      Await.result(journal.asyncWriteMessages(Vector(write("event"))), 10.seconds) shouldBe Vector(Success(()))
      expectCallbacks(recording, Seq(Success(())), Success(()))
      recording.writes.size() shouldBe 1
      recording.writes.peek().map(_.sequenceNumber.value) shouldBe Seq(1L)
  }

  "a head preflight limit fails the outer call and reports write.error before sending any rows" in withJournal(
    headEnabled = true
  ) { (journal, recording) =>
    val result  = journal.asyncWriteMessages(Vector(write("valid first"), write(Vector.fill(101)("event"): _*)))
    val failure = Await.result(result.failed, 10.seconds)
    failure shouldBe a[JournalHeadTooManyEventsException]
    expectCallbacks(recording, Vector.fill(102)(Success(())), Failure(failure))
    recording.writes.isEmpty shouldBe true
  }

  "serialization rejection reports serialize.error and write.after with an inner Failure" in withJournal(headEnabled =
    true
  ) { (journal, recording) =>
    val result = Await.result(journal.asyncWriteMessages(Vector(write(new UnserializableEvent))), 10.seconds)
    result should have size 1
    val failure = result.head.failed.get
    failure shouldBe a[NotSerializableException]
    failure.getMessage shouldBe "metrics test event"
    expectCallbacks(recording, Seq(Failure(failure)), Success(()))
    recording.writes.isEmpty shouldBe true
  }

  "an oversized row without head mode reports write.error and sends no rows" in withJournal(headEnabled = false) {
    (journal, recording) =>
      val result  = journal.asyncWriteMessages(Vector(write("valid first"), write("x" * (401 * 1024))))
      val failure = Await.result(result.failed, 10.seconds)
      failure shouldBe a[JournalHeadJournalRowTooLargeException]
      expectCallbacks(recording, Seq(Success(()), Success(())), Failure(failure))
      recording.writes.isEmpty shouldBe true
  }

  "a valid write without head mode reaches the row driver and reports write.after" in withJournal(headEnabled = false) {
    (journal, recording) =>
      Await.result(journal.asyncWriteMessages(Vector(write("event"))), 10.seconds) shouldBe Vector(Success(()))
      expectCallbacks(recording, Seq(Success(())), Success(()))
      recording.writes.size() shouldBe 1
  }
}
