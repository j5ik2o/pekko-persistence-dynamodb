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
package com.github.j5ik2o.pekko.persistence.dynamodb.client

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Flow, Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

final class StreamSupportSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("stream-support-spec")

  private object Support extends StreamSupport

  private val backoff: BackoffConfig = BackoffConfig(
    sourceConfig = ConfigFactory.empty(),
    enabled = true,
    minBackoff = 20.millis,
    maxBackoff = 30.millis,
    randomFactor = 0.0,
    maxRestarts = 2
  )

  /** Counts each materialization and fails after `attemptDuration`. */
  private def failingFlow(started: AtomicInteger, attemptDuration: FiniteDuration): Flow[Int, Int, NotUsed] =
    Flow[Int]
      .flatMapConcat(_ =>
        Source
          .single(()).initialDelay(attemptDuration).flatMapConcat(_ =>
            Source.failed[Int](new IllegalStateException("slow"))
          )
      )
      .mapMaterializedValue { _ =>
        started.incrementAndGet()
        NotUsed
      }

  private def runUntilDone(flow: Flow[Int, Int, NotUsed], timeout: FiniteDuration): Boolean = {
    val done = Source.repeat(1).via(flow).runWith(Sink.ignore)
    Try(Await.ready(done, timeout)).isSuccess
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds)
    super.afterAll()
  }

  "flowWithBackoffSettings" - {
    "restarts a failing flow at most max-restarts times when each attempt is faster than min-backoff" in {
      val started = new AtomicInteger(0)

      val finished = runUntilDone(Support.flowWithBackoffSettings(backoff, failingFlow(started, 1.milli)), 10.seconds)

      finished shouldBe true
      started.get shouldBe 3
    }

    "restarts a failing flow at most max-restarts times when each attempt is slower than min-backoff" in {
      val started = new AtomicInteger(0)

      val finished = runUntilDone(Support.flowWithBackoffSettings(backoff, failingFlow(started, 150.millis)), 5.seconds)

      finished shouldBe true
      started.get shouldBe 3
    }

    "does not restart the flow when the backoff is disabled" in {
      val started = new AtomicInteger(0)

      val finished =
        runUntilDone(
          Support.flowWithBackoffSettings(backoff.copy(enabled = false), failingFlow(started, 1.milli)),
          10.seconds
        )

      finished shouldBe true
      started.get shouldBe 1
    }
  }
}
