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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.head

/** Limits of the JournalHead write path.
  *
  * The limits are fixed on purpose. A write that exceeds them is rejected with a reason; it is neither split into
  * several transactions nor written without a transaction, because both would weaken the ordering guarantee the
  * JournalHead table exists for.
  */
object JournalHeadLimits {

  /** DynamoDB accepts at most 100 items per TransactWriteItems request. One item is the JournalHead item, so a write
    * may carry at most 99 events.
    */
  val MaxEventsPerTransaction: Int = 99

  /** Maximum size of a DynamoDB item (400 KB). */
  val MaxItemSizeBytes: Int = 400 * 1024

  /** Maximum size of a TransactWriteItems request (4 MB). */
  val MaxTransactionSizeBytes: Long = 4L * 1024 * 1024

}
