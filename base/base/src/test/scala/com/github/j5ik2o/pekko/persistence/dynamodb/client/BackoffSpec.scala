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

import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

final class BackoffSpec extends AnyFreeSpec with Matchers {

  private val config: BackoffConfig = BackoffConfig(
    sourceConfig = ConfigFactory.empty(),
    enabled = true,
    minBackoff = 100.millis,
    maxBackoff = 10.seconds,
    randomFactor = 0.0,
    maxRestarts = 3
  )

  "backoffDelay" - {
    "doubles min-backoff for each earlier attempt" in {
      Backoff.backoffDelay(config, 1) shouldBe 100.millis
      Backoff.backoffDelay(config, 2) shouldBe 200.millis
      Backoff.backoffDelay(config, 3) shouldBe 400.millis
      Backoff.backoffDelay(config, 4) shouldBe 800.millis
    }

    "stops at max-backoff" in {
      Backoff.backoffDelay(config, 8) shouldBe 10.seconds
      Backoff.backoffDelay(config, 9) shouldBe 10.seconds
    }

    "stays between min-backoff and max-backoff for any attempt number, even when the doubling would overflow a Long" in {
      val jittered = config.copy(minBackoff = 3.seconds, maxBackoff = 1.minute, randomFactor = 0.2)

      Seq(1, 2, 5, 20, 43, 53, 54, 64, 65, 100, 1000, Int.MaxValue).foreach { attempt =>
        withClue(s"attempt=$attempt: ") {
          val delay = Backoff.backoffDelay(jittered, attempt)
          delay should be >= jittered.minBackoff
          delay should be <= jittered.maxBackoff
        }
      }
    }

    "never exceeds max-backoff when random-factor adds jitter" in {
      val jittered = config.copy(randomFactor = 1.0)

      (1 to 200).foreach { _ =>
        Backoff.backoffDelay(jittered, 20) should be <= jittered.maxBackoff
      }
    }

    "treats an attempt number below one as the first attempt" in {
      Backoff.backoffDelay(config, 0) shouldBe 100.millis
      Backoff.backoffDelay(config, -5) shouldBe 100.millis
    }
  }
}
