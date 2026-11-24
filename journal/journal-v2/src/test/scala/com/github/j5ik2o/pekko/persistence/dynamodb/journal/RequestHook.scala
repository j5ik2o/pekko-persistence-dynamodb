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

import software.amazon.awssdk.core.interceptor.{ Context, ExecutionAttributes, ExecutionInterceptor }
import software.amazon.awssdk.metrics.{ MetricCollection, MetricPublisher }

import java.util.concurrent.atomic.AtomicReference

/** Runs a test action once, right after the first request of the AWS SDK v2 clients of a test system that matches, so a
  * test can put another writer between two requests of the plugin (issue #1014).
  *
  * It is also a no-op `MetricPublisher`, because `execution-interceptor-class-names` checks each class name against
  * `MetricPublisher` instead of `ExecutionInterceptor` (issue #1021).
  */
final class RequestHook extends ExecutionInterceptor with MetricPublisher {
  override def publish(metricCollection: MetricCollection): Unit = ()
  override def close(): Unit                                     = ()

  override def afterExecution(context: Context.AfterExecution, executionAttributes: ExecutionAttributes): Unit =
    RequestHook.fire(context.request())
}

object RequestHook {
  private val armed = new AtomicReference[Option[(AnyRef => Boolean, () => Unit)]](None)

  /** Runs `action` after the next request for which `matches` holds. */
  def arm(matches: AnyRef => Boolean)(action: => Unit): Unit = armed.set(Some((matches, () => action)))

  def disarm(): Unit = armed.set(None)

  private def fire(request: AnyRef): Unit = {
    val current = armed.get()
    current.foreach { case (matches, action) =>
      if (matches(request) && armed.compareAndSet(current, None)) action()
    }
  }
}
