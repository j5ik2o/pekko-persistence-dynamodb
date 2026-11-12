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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v2.V2JournalRowWriteDriver
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.ConfigHelper
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue => V2AttributeValue,
  CancellationReason,
  GetItemRequest,
  GetItemResponse,
  TransactWriteItemsResponse,
  TransactionCanceledException
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient => V2DynamoDbAsyncClient }

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.jdk.CollectionConverters._

object V2JournalRowWriteDriverSpec {

  /** What the fake answers when the driver reads the JournalHead item after a failed head condition. */
  sealed trait FakeHead
  object FakeHead {

    /** The test does not expect a head read. */
    case object Unexpected extends FakeHead

    /** The JournalHead item does not exist. */
    case object Missing extends FakeHead

    /** The JournalHead item has this `sequence-nr`. */
    final case class At(sequenceNr: Long) extends FakeHead
  }

  /** A v2 async client whose `transactWriteItems` fails with a cancelled transaction `failures` times, then succeeds.
    * `failedItemIndex` is the transaction item whose cancellation reason carries `reasonCode`. `head` answers the head
    * read that follows a failed head condition.
    */
  final class FakeTransactWriteClient(
      failures: Int,
      reasonCode: String,
      failedItemIndex: Int = 1,
      head: FakeHead = FakeHead.Unexpected
  ) {
    val calls: AtomicInteger     = new AtomicInteger(0)
    val headReads: AtomicInteger = new AtomicInteger(0)
    private val remaining        = new AtomicInteger(failures)

    val client: V2DynamoDbAsyncClient = Proxy
      .newProxyInstance(
        classOf[V2DynamoDbAsyncClient].getClassLoader,
        Array(classOf[V2DynamoDbAsyncClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
            method.getName match {
              case "transactWriteItems" =>
                calls.incrementAndGet()
                if (remaining.getAndDecrement() > 0) failed(cancelled(reasonCode, failedItemIndex))
                else CompletableFuture.completedFuture(successfulResponse)
              case "getItem" =>
                headReads.incrementAndGet()
                head match {
                  case FakeHead.At(sequenceNr) =>
                    CompletableFuture.completedFuture(headItem(args(0).asInstanceOf[GetItemRequest], sequenceNr))
                  case FakeHead.Missing =>
                    CompletableFuture.completedFuture(GetItemResponse.builder().build())
                  case FakeHead.Unexpected => throw new UnsupportedOperationException("unexpected call: getItem")
                }
              case "close"       => null
              case "serviceName" => "dynamodb"
              case "toString"    => "FakeTransactWriteClient"
              case "hashCode"    => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"      => java.lang.Boolean.valueOf(proxy.asInstanceOf[AnyRef] eq args(0))
              case other         => throw new UnsupportedOperationException(s"unexpected call: $other")
            }
        }
      )
      .asInstanceOf[V2DynamoDbAsyncClient]

    private def successfulResponse: TransactWriteItemsResponse = {
      val builder = TransactWriteItemsResponse.builder()
      builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
      builder.build()
    }

    private def headItem(request: GetItemRequest, sequenceNr: Long): GetItemResponse = {
      val persistenceId = Option(request.key())
        .flatMap(key => Option(key.get("persistence-id")))
        .map(_.s())
        .getOrElse("")
      val item = Map(
        "persistence-id" -> V2AttributeValue.builder().s(persistenceId).build(),
        "sequence-nr"    -> V2AttributeValue.builder().n(sequenceNr.toString).build()
      ).asJava
      GetItemResponse.builder().item(item).build()
    }

    private def cancelled(code: String, failedItemIndex: Int): TransactionCanceledException = {
      val builder = TransactionCanceledException.builder()
      builder.message(s"Transaction cancelled: $code")
      builder.cancellationReasons(
        java.util.Arrays.asList(
          CancellationReason.builder().code(if (failedItemIndex == 0) code else "None").build(),
          CancellationReason.builder().code(if (failedItemIndex == 1) code else "None").build()
        )
      )
      builder.build().asInstanceOf[TransactionCanceledException]
    }

    private def failed(cause: Throwable): CompletableFuture[TransactWriteItemsResponse] = {
      val future = new CompletableFuture[TransactWriteItemsResponse]()
      future.completeExceptionally(cause)
      future
    }
  }

}

final class V2JournalRowWriteDriverSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import V2JournalRowWriteDriverSpec._

  private var system: ActorSystem = _
  private val contexts            = new java.util.concurrent.ConcurrentLinkedQueue[JournalPluginContext]()

  private def baseConfig(writeBackoff: String) = ConfigFactory
    .parseString(s"j5ik2o.dynamo-db-journal.write-backoff { $writeBackoff }")
    .withFallback(
      ConfigHelper.config(
        defaultResource = None,
        legacyConfigFormat = false,
        legacyJournalMode = false,
        dynamoDBHost = "localhost",
        dynamoDBPort = 1,
        clientVersion = ClientVersion.V2.toString,
        clientType = ClientType.Sync.toString,
        headEnabled = true
      )
    ).resolve()

  private val retryingBackoff =
    "enabled = true, min-backoff = 1ms, max-backoff = 2ms, random-factor = 0.0, max-restarts = 2"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    system = ActorSystem("v2-journal-row-write-driver-spec", baseConfig(retryingBackoff))
  }

  override protected def afterAll(): Unit = {
    contexts.forEach(context => context.journalRowWriteDriver.dispose())
    if (system != null) Await.result(system.terminate(), 60.seconds)
    super.afterAll()
  }

  private def write(
      client: V2DynamoDbAsyncClient,
      persistenceId: String,
      writeBackoff: String = retryingBackoff
  ): Future[Long] = {
    implicit val mat: Materializer = SystemMaterializer(system).materializer
    val pluginContext = JournalPluginContext(
      system,
      JournalPluginConfig.fromConfig(baseConfig(writeBackoff).getConfig("j5ik2o.dynamo-db-journal"))
    )
    contexts.add(pluginContext)
    val request = JournalHeadWriteRequest
      .of(
        Seq(
          JournalRow(
            PersistenceId(persistenceId),
            SequenceNumber(1L),
            deleted = false,
            message = Array[Byte](1, 2, 3),
            ordering = 1L
          )
        ),
        pluginContext.pluginConfig.headConfig,
        pluginContext.pluginConfig.columnsDefConfig,
        pluginContext.partitionKeyResolver,
        pluginContext.sortKeyResolver
      )
      .fold(cause => throw cause, identity)
    val driver = new V2JournalRowWriteDriver(pluginContext, Some(client), None)
    Source.single(request).via(driver.putJournalRowsWithHeadFlow).runWith(Sink.head)
  }

  private def await[A](future: Future[A]): A = Await.result(future, 30.seconds)

  "V2JournalRowWriteDriver" should {

    "retry a transaction conflict and complete the write once the conflict clears" in {
      val fake = new FakeTransactWriteClient(failures = 1, reasonCode = "TransactionConflict")

      await(write(fake.client, "conflict-then-success")) shouldBe 1L

      fake.calls.get() shouldBe 2
    }

    "retry throttling like a transaction conflict" in {
      val fake = new FakeTransactWriteClient(failures = 2, reasonCode = "ProvisionedThroughputExceeded")

      await(write(fake.client, "throttled-then-success")) shouldBe 1L

      fake.calls.get() shouldBe 3
    }

    "fail with JournalHeadConcurrentWriteException after the retries are exhausted" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "TransactionConflict")

      val failure = intercept[JournalHeadConcurrentWriteException](await(write(fake.client, "conflict-exhausted")))

      fake.calls.get() shouldBe 3
      failure.persistenceId shouldBe "conflict-exhausted"
      failure.attempts shouldBe 3
      failure.reason shouldBe "TransactionConflict"
    }

    "make a single attempt when max-restarts is 0" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "TransactionConflict")

      val failure = intercept[JournalHeadConcurrentWriteException](
        await(write(fake.client, "no-restarts", "enabled = false, max-restarts = 0"))
      )

      fake.calls.get() shouldBe 1
      failure.attempts shouldBe 1
    }

    "not wait between attempts when write-backoff is disabled" in {
      val fake    = new FakeTransactWriteClient(failures = 2, reasonCode = "TransactionConflict")
      val started = System.nanoTime()

      await(
        write(
          fake.client,
          "disabled-backoff",
          "enabled = false, min-backoff = 10s, max-backoff = 10s, random-factor = 0.0, max-restarts = 2"
        )
      ) shouldBe 1L

      fake.calls.get() shouldBe 3
      (System.nanoTime() - started).nanos should be < 5.seconds
    }

    "wait between attempts when write-backoff is enabled" in {
      val fake    = new FakeTransactWriteClient(failures = 2, reasonCode = "TransactionConflict")
      val started = System.nanoTime()

      await(
        write(
          fake.client,
          "enabled-backoff",
          "enabled = true, min-backoff = 200ms, max-backoff = 200ms, random-factor = 0.0, max-restarts = 2"
        )
      ) shouldBe 1L

      fake.calls.get() shouldBe 3
      (System.nanoTime() - started).nanos should be >= 400.millis
    }

    "fail with DuplicateJournalEventsException, without a retry, when a journal row exists" in {
      val fake = new FakeTransactWriteClient(failures = 1, reasonCode = "ConditionalCheckFailed", failedItemIndex = 0)

      val failure = intercept[DuplicateJournalEventsException](await(write(fake.client, "duplicate")))

      fake.calls.get() shouldBe 1
      failure.persistenceId shouldBe "duplicate"
    }

    "retry when the JournalHead item changed after the condition was checked" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.At(0L)
      )

      await(write(fake.client, "head-changed-then-success")) shouldBe 1L

      fake.calls.get() shouldBe 2
      fake.headReads.get() shouldBe 1
    }

    "fail with JournalHeadConcurrentWriteException when the JournalHead item keeps changing" in {
      val fake = new FakeTransactWriteClient(
        failures = 100,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.At(0L)
      )

      val failure = intercept[JournalHeadConcurrentWriteException](await(write(fake.client, "head-keeps-changing")))

      fake.calls.get() shouldBe 3
      fake.headReads.get() shouldBe 3
      failure.attempts shouldBe 3
    }

    "fail with JournalHeadMissingException, without a retry, when the JournalHead item does not exist" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.Missing
      )

      intercept[JournalHeadMissingException](await(write(fake.client, "head-missing")))

      fake.calls.get() shouldBe 1
      fake.headReads.get() shouldBe 1
    }

    "fail with JournalHeadSequenceMismatchException, without a retry, when the JournalHead item is ahead" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        head = FakeHead.At(5L)
      )

      intercept[JournalHeadSequenceMismatchException](await(write(fake.client, "head-ahead")))

      fake.calls.get() shouldBe 1
      fake.headReads.get() shouldBe 1
    }

    "fail with the SDK exception, without a retry, for a cancellation reason it does not recognize" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "ValidationError")

      val failure = intercept[TransactionCanceledException](await(write(fake.client, "unknown-reason")))

      fake.calls.get() shouldBe 1
      failure.cancellationReasons().asScala.map(_.code()) should contain("ValidationError")
    }
  }

}
