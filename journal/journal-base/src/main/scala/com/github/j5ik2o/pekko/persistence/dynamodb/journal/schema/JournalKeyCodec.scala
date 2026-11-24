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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, DdbValue, KeyError, RowKey }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ PartitionKeyResolver, SortKeyResolver }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, PersistenceIdKeyParts, SequenceNumber }

final class JournalKeyCodec(schema: JournalSchema) {

  def keyOf(
      persistenceId: PersistenceId,
      sequenceNumber: SequenceNumber,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Either[KeyError, RowKey] =
    rowKey(
      partitionKeyResolver.resolve(persistenceId, sequenceNumber).asString,
      sortKeyResolver.resolve(persistenceId, sequenceNumber).asString
    )

  /** The key that releases up to 1.3.0 gave the row, when it differs from `keyOf`.
    *
    * Those releases split the persistence id of the default resolvers differently (issue #1009), so a row they wrote
    * can be stored under another key than the one `keyOf` gives now. It is none when the key did not change, when
    * neither resolver is a default one, when those releases could not write a row for the id, and when
    * `legacy-keys-enabled` is false. The key can hold the row of another persistence id that shared it, so an operation
    * on it must check the persistence id and the sequence number of the row.
    */
  private[journal] def legacyKeyOf(
      persistenceId: PersistenceId,
      sequenceNumber: SequenceNumber,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): Option[RowKey] = {
    val partitionKey = partitionKeyResolver.resolve(persistenceId, sequenceNumber)
    val sortKey      = sortKeyResolver.resolve(persistenceId, sequenceNumber)
    val legacyPartitionKey = partitionKeyResolver match {
      case resolver: PartitionKeyResolver.PersistenceIdBased => resolver.resolveLegacy(persistenceId)
      case _                                                 => Some(partitionKey)
    }
    val legacySortKey = sortKeyResolver match {
      case resolver: SortKeyResolver.PersistenceIdWithSeqNr => resolver.resolveLegacy(persistenceId, sequenceNumber)
      case _                                                => Some(sortKey)
    }
    for {
      oldPartitionKey <- legacyPartitionKey
      oldSortKey <- legacySortKey
      if oldPartitionKey != partitionKey || oldSortKey != sortKey
      if PersistenceIdKeyParts.fitsDynamoDB(oldPartitionKey.asString, oldSortKey.asString)
      key <- rowKey(oldPartitionKey.asString, oldSortKey.asString).toOption
    } yield key
  }

  private def rowKey(partitionKey: String, sortKey: String): Either[KeyError, RowKey] =
    schema.tableSpec.keyOf(
      AttributeMap.of(schema.partitionKey -> DdbValue.Str(partitionKey), schema.sortKey -> DdbValue.Str(sortKey))
    )
}
