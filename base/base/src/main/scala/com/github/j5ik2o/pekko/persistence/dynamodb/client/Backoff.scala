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

import org.apache.pekko.stream.RestartSettings
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig

import java.util.concurrent.{ ThreadLocalRandom, TimeUnit }
import scala.concurrent.duration._

/** The wait between retries, shared by every store and driver that retries a DynamoDB call. */
private[dynamodb] object Backoff {

  /** The period in which a restart count of `write-backoff.max-restarts` accumulates. Pekko resets the count when this
    * period has passed since the last reset, so a window as short as `min-backoff` resets it after every wait and
    * `max-restarts` never takes effect. The window is far longer than any attempt and its waits, so the restarts of one
    * run are bounded by `max-restarts`, however long each attempt takes.
    */
  val RestartWindow: FiniteDuration = 365.days

  /** The restart settings of one run: `max-restarts` is a hard bound on the restarts of that run. */
  def restartSettings(config: BackoffConfig): RestartSettings =
    RestartSettings(config.minBackoff, config.maxBackoff, config.randomFactor)
      .withMaxRestarts(config.maxRestarts, RestartWindow)

  /** The wait before the attempt after `attempt`: `min-backoff` doubled for each earlier attempt, never above
    * `max-backoff`, and spread by `random-factor`. The doubling is done in floating point and capped, so a large
    * attempt number cannot overflow into a negative or zero wait.
    */
  def backoffDelay(config: BackoffConfig, attempt: Int): FiniteDuration = {
    val maxMillis = config.maxBackoff.toMillis
    val doubled   = config.minBackoff.toMillis.toDouble * math.pow(2.0, math.min(math.max(attempt - 1, 0), 62).toDouble)
    val base      = math.min(maxMillis.toDouble, doubled)
    val jitter    = 1.0 + ThreadLocalRandom.current().nextDouble() * config.randomFactor
    FiniteDuration(math.min(maxMillis, (base * jitter).toLong), TimeUnit.MILLISECONDS)
  }
}
