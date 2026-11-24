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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters._

/** Counts the requests that the AWS SDK v2 clients of a test system send, by operation (`PutItem`, `Query`, ...).
  *
  * It is also a no-op `MetricPublisher`, because `execution-interceptor-class-names` checks each class name against
  * `MetricPublisher` instead of `ExecutionInterceptor` (issue #1021).
  */
final class RequestCounter extends ExecutionInterceptor with MetricPublisher {
  override def publish(metricCollection: MetricCollection): Unit = ()
  override def close(): Unit                                     = ()

  override def beforeExecution(context: Context.BeforeExecution, executionAttributes: ExecutionAttributes): Unit =
    RequestCounter.record(context.request().getClass.getSimpleName.stripSuffix("Request"))
}

object RequestCounter {
  private val counts = new ConcurrentHashMap[String, AtomicLong]()

  private def record(operation: String): Unit =
    counts.computeIfAbsent(operation, _ => new AtomicLong()).incrementAndGet()

  def current(): Map[String, Long] = counts.asScala.map { case (k, v) => k -> v.get() }.toMap

  /** The requests that `action` sent, by operation. */
  def during[T](action: => T): (T, Map[String, Long]) = {
    val before = current()
    val result = action
    val after  = current()
    val diff = after
      .map { case (operation, count) => operation -> (count - before.getOrElse(operation, 0L)) }
      .filter(_._2 != 0L)
    (result, diff)
  }
}
