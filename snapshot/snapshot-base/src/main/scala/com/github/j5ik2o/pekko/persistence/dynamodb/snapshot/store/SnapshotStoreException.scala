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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.store

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.TableName

/** A snapshot store failure that a caller can tell apart from a failure of DynamoDB. */
sealed abstract class SnapshotStoreException(message: String, cause: Throwable) extends RuntimeException(message, cause)

object SnapshotStoreException {

  /** A batch delete failed after the configured attempts. The delete is idempotent, so the caller can retry it. */
  final class BatchWriteRetriesExhausted(val table: TableName, val attempts: Int, cause: Throwable)
      extends SnapshotStoreException(
        s"the batch write to ${table.value} failed after $attempts attempt(s)",
        cause
      )

  /** A save was refused because the key of the snapshot already holds a snapshot of another persistence id.
    *
    * The default key resolvers give two persistence ids the same key when they differ only after the second separator
    * (issue #1009). Earlier releases overwrote the other snapshot; the save now fails and leaves it as it is. Retrying
    * does not help: give the persistence id at most one separator, or see the compatibility note on the persistence id
    * separator in the documentation.
    */
  final class KeyConflict(
      val persistenceId: String,
      val sequenceNr: Long,
      val conflictingPersistenceId: Option[String],
      val key: String
  ) extends SnapshotStoreException(
        s"persistenceId=[$persistenceId]: the snapshot at sequence number $sequenceNr was not saved because its key ($key) already holds a snapshot of ${conflictingPersistenceId
            .fold("another persistence id")(id => s"persistenceId=[$id]")}. The two ids resolve to the same key; see https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1009 for the workaround.",
        null
      )
}
