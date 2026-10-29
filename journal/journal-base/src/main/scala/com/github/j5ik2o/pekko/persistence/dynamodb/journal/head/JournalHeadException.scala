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

/** Base class of the failures that belong to the JournalHead write path.
  *
  * Every subclass is a distinct failure reason, so that callers and operators can tell "the write was rejected before
  * it reached DynamoDB", "the JournalHead item has not been migrated yet" and "the sequence number did not continue the
  * head" apart from network or service errors.
  */
sealed abstract class JournalHeadException(message: String, cause: Throwable) extends RuntimeException(message, cause) {
  def persistenceId: String
}

/** The write was rejected by the plugin before anything was sent to DynamoDB. */
sealed abstract class JournalHeadWriteRejectedException(message: String) extends JournalHeadException(message, null)

/** The rows of a single write do not form one transaction: they are empty, belong to more than one persistence id, or
  * their sequence numbers are not contiguous.
  *
  * This is a validation failure, not a DynamoDB failure. It is returned as a value so that it never aborts the write
  * queue of the journal plugin.
  */
final class JournalHeadInvalidWriteException(val persistenceId: String, val detail: String)
    extends JournalHeadException(s"persistenceId=[$persistenceId]: $detail", null)

/** The write contains more events than a single TransactWriteItems request can carry.
  *
  * The transaction contains one item per journal row plus one JournalHead item, and DynamoDB accepts at most 100 items
  * per transaction.
  */
final class JournalHeadTooManyEventsException(val persistenceId: String, val eventCount: Int, val limit: Int)
    extends JournalHeadWriteRejectedException(
      s"persistenceId=[$persistenceId]: a single write of $eventCount events exceeds the limit of $limit events" +
      " (the JournalHead item is part of the same transaction, so the transaction would contain more than 100 items)"
    )

/** The JournalHead item would be larger than the DynamoDB item size limit.
  *
  * The item is not split and the write does not fall back to a non-transactional write.
  */
final class JournalHeadItemTooLargeException(val persistenceId: String, val itemSizeBytes: Int, val limitBytes: Int)
    extends JournalHeadWriteRejectedException(
      s"persistenceId=[$persistenceId]: the JournalHead item would be $itemSizeBytes bytes, which exceeds the limit of $limitBytes bytes"
    )

/** The transaction as a whole would be larger than the DynamoDB transaction size limit. */
final class JournalHeadTransactionTooLargeException(
    val persistenceId: String,
    val transactionSizeBytes: Long,
    val limitBytes: Long
) extends JournalHeadWriteRejectedException(
      s"persistenceId=[$persistenceId]: the transaction would be $transactionSizeBytes bytes, which exceeds the limit of $limitBytes bytes"
    )

/** The JournalHead item does not exist although the aggregate already has events.
  *
  * This means the migration tool has not been run for this persistence id yet. It is a distinct failure so that it
  * cannot be confused with a sequence number mismatch.
  */
final class JournalHeadMissingException(val persistenceId: String, val firstSequenceNr: Long)
    extends JournalHeadException(
      s"persistenceId=[$persistenceId]: the JournalHead item does not exist, but the write starts at sequence number $firstSequenceNr." +
      " Run the JournalHead migration tool before enabling head.enabled.",
      null
    )

/** The JournalHead item is ahead of this write.
  *
  * The head must be lower than the first sequence number of the write. A head that is equal or higher means a rewind,
  * or another writer that already wrote these sequence numbers. A head that is lower than the first sequence number by
  * more than one is accepted on purpose: Pekko does not rewind the sequence number of a rejected write, so the write
  * after a rejection starts above the head by more than one.
  *
  * This is a safety check that the assumption "there is only one writer per aggregate" (kept by Cluster Sharding and
  * Split Brain Resolver) still holds. It is not optimistic locking.
  */
final class JournalHeadSequenceMismatchException(
    val persistenceId: String,
    val expectedPreviousSequenceNr: Long,
    val actualSequenceNr: Long
) extends JournalHeadException(
      s"persistenceId=[$persistenceId]: expected the JournalHead sequence number to be lower than the first sequence" +
      s" number of the write ($expectedPreviousSequenceNr) but it was $actualSequenceNr",
      null
    )

/** The journal already contains some of the rows of this write. */
final class DuplicateJournalEventsException(val persistenceId: String, val sequenceNrs: Seq[Long])
    extends JournalHeadException(
      s"persistenceId=[$persistenceId]: the journal already contains the events at sequence numbers [${sequenceNrs.mkString(",")}]",
      null
    )
