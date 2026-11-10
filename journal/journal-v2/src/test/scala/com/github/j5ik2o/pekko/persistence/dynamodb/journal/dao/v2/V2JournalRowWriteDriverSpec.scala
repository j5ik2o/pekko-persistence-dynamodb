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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v2

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  DuplicateJournalEventsException,
  JournalHeadConcurrentWriteException,
  JournalHeadException,
  JournalHeadWriteRequest
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ JournalPluginContext, JournalRow }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.ConfigHelper
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  CancellationReason,
  GetItemRequest,
  GetItemResponse,
  TransactWriteItemsResponse,
  TransactionCanceledException
}
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient => JavaDynamoDbAsyncClient }

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.jdk.CollectionConverters._

object V2JournalRowWriteDriverSpec {

  /** A v2 async client whose `transactWriteItems` fails with a cancelled transaction `failures` times, then succeeds.
    * `failedItemIndex` is the transaction item whose cancellation reason carries `reasonCode`. `headSequenceNr` answers
    * the head read that follows a failed head condition.
    */
  final class FakeTransactWriteClient(
      failures: Int,
      reasonCode: String,
      failedItemIndex: Int = 1,
      headSequenceNr: Option[Long] = None
  ) {
    val calls: AtomicInteger     = new AtomicInteger(0)
    val headReads: AtomicInteger = new AtomicInteger(0)
    private val remaining        = new AtomicInteger(failures)

    val client: JavaDynamoDbAsyncClient = Proxy
      .newProxyInstance(
        classOf[JavaDynamoDbAsyncClient].getClassLoader,
        Array(classOf[JavaDynamoDbAsyncClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
            method.getName match {
              case "transactWriteItems" =>
                calls.incrementAndGet()
                if (remaining.getAndDecrement() > 0) failed(cancelled(reasonCode, failedItemIndex))
                else CompletableFuture.completedFuture(successfulResponse)
              case "getItem" =>
                headReads.incrementAndGet()
                headSequenceNr match {
                  case Some(sequenceNr) =>
                    CompletableFuture.completedFuture(headItem(args(0).asInstanceOf[GetItemRequest], sequenceNr))
                  case None => throw new UnsupportedOperationException("unexpected call: getItem")
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
      .asInstanceOf[JavaDynamoDbAsyncClient]

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
        "persistence-id" -> AttributeValue.builder().s(persistenceId).build(),
        "sequence-nr"    -> AttributeValue.builder().n(sequenceNr.toString).build()
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

  private var system: ActorSystem                 = _
  private var pluginContext: JournalPluginContext = _

  private val config = ConfigFactory
    .parseString(
      """
        |j5ik2o.dynamo-db-journal.write-backoff {
        |  min-backoff = 1ms
        |  max-backoff = 2ms
        |  random-factor = 0.0
        |  max-restarts = 2
        |}
        |""".stripMargin
    ).withFallback(
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

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    system = ActorSystem("v2-journal-row-write-driver-spec", config)
    pluginContext = JournalPluginContext(
      system,
      JournalPluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-journal"))
    )
  }

  override protected def afterAll(): Unit = {
    if (pluginContext != null) pluginContext.journalRowWriteDriver.dispose()
    if (system != null) Await.result(system.terminate(), 60.seconds)
    super.afterAll()
  }

  private def write(
      client: JavaDynamoDbAsyncClient,
      persistenceId: String
  ): Future[Long] = {
    implicit val mat: Materializer = SystemMaterializer(system).materializer
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

  "V2JournalRowWriteDriver" should {

    "retry a transaction conflict and complete the write once the conflict clears" in {
      val fake = new FakeTransactWriteClient(failures = 1, reasonCode = "TransactionConflict")

      val result = Await.result(write(fake.client, "conflict-then-success"), 30.seconds)

      result shouldBe 1L
      fake.calls.get() shouldBe 2
    }

    "retry throttling like a transaction conflict" in {
      val fake = new FakeTransactWriteClient(failures = 2, reasonCode = "ProvisionedThroughputExceeded")

      val result = Await.result(write(fake.client, "throttled-then-success"), 30.seconds)

      result shouldBe 1L
      fake.calls.get() shouldBe 3
    }

    "fail with a typed JournalHeadException after the retries are exhausted" in {
      val fake = new FakeTransactWriteClient(failures = 100, reasonCode = "TransactionConflict")

      val failure = intercept[JournalHeadException](Await.result(write(fake.client, "conflict-exhausted"), 30.seconds))

      fake.calls.get() shouldBe 3
      failure shouldBe a[JournalHeadConcurrentWriteException]
      val concurrent = failure.asInstanceOf[JournalHeadConcurrentWriteException]
      concurrent.attempts shouldBe 3
      concurrent.reason shouldBe "TransactionConflict"
    }

    "not retry a conditional check failure" in {
      val fake = new FakeTransactWriteClient(failures = 1, reasonCode = "ConditionalCheckFailed", failedItemIndex = 0)

      val failure = intercept[JournalHeadException](Await.result(write(fake.client, "duplicate"), 30.seconds))

      fake.calls.get() shouldBe 1
      failure shouldBe a[DuplicateJournalEventsException]
    }

    "retry when the JournalHead item changed after the condition was checked" in {
      val fake = new FakeTransactWriteClient(
        failures = 1,
        reasonCode = "ConditionalCheckFailed",
        failedItemIndex = 1,
        headSequenceNr = Some(0L)
      )

      val result = Await.result(write(fake.client, "head-changed-then-success"), 30.seconds)

      result shouldBe 1L
      fake.calls.get() shouldBe 2
      fake.headReads.get() shouldBe 1
    }
  }

}
