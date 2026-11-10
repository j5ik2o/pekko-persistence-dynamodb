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

/** Base class of the failures the journal write queue raises before a write reaches its row driver.
  *
  * The queue is a bounded buffer, so it can refuse a write without sending anything to DynamoDB. Each subclass is a
  * distinct reason, so that a caller can tell "the buffer is full" apart from "the queue is closed" or "too many offers
  * are waiting". Pekko still fails the write and stops the actor through `onPersistFailure`; the type is available to
  * the log and to that callback.
  */
sealed abstract class JournalWriteQueueException(message: String, cause: Throwable)
    extends RuntimeException(message, cause)

/** The write was refused because the queue buffer was full.
  *
  * Nothing was sent to DynamoDB. With a drop overflow strategy the queue keeps running, so a retry after the buffer
  * drains can succeed. With the default fail strategy the queue stops, so the plugin must restart.
  */
final class JournalWriteQueueFullException(val bufferSize: Int, cause: Throwable = null)
    extends JournalWriteQueueException(
      s"Failed to enqueue journal row batch write, the queue buffer was full ($bufferSize elements); " +
      "check the queue-buffer-size and queue-overflow-strategy settings",
      cause
    )

/** The write was refused because the queue is closed.
  *
  * The queue was disposed, or its stream terminated abnormally. The cause holds the `StreamDetachedException` when the
  * queue was detached. The write succeeds only after the plugin restarts.
  */
final class JournalWriteQueueClosedException(cause: Throwable = null)
    extends JournalWriteQueueException("Failed to enqueue journal row batch write, the queue was closed", cause)

/** The write was refused because too many writes were already waiting for the queue to accept them.
  *
  * With the backpressure overflow strategy a full buffer holds an offer back instead of refusing it, and the queue
  * accepts only a bounded number of such concurrent offers. Nothing was sent to DynamoDB and the queue keeps running,
  * so a retry after the in-flight writes drain can succeed. The cause holds the `IllegalStateException` from Pekko.
  */
final class JournalWriteQueueBusyException(cause: Throwable = null)
    extends JournalWriteQueueException(
      "Failed to enqueue journal row batch write, too many writes were waiting for the queue (backpressure); " +
      "retry after the in-flight writes complete",
      cause
    )
