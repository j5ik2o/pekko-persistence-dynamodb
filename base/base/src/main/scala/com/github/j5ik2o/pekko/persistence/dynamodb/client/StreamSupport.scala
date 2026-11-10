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
import org.apache.pekko.stream.scaladsl.{ Flow, RestartFlow }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig

trait StreamSupport {

  def flowWithBackoffSettings[In, Out](
      backoffConfig: BackoffConfig,
      flow: Flow[In, Out, NotUsed]
  ): Flow[In, Out, NotUsed] = {
    if (backoffConfig.enabled) {
      RestartFlow.withBackoff(Backoff.restartSettings(backoffConfig)) { () => flow }
    } else flow
  }
}
