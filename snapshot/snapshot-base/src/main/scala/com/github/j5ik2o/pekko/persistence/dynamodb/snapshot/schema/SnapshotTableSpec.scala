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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.{ PartitionKeyResolver, SortKeyResolver }

sealed trait SnapshotKeyDerivation

object SnapshotKeyDerivation {

  final case class Sharded(
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ) extends SnapshotKeyDerivation

  case object Identity extends SnapshotKeyDerivation
}

final case class SnapshotTableSpec(
    table: TableSpec,
    readIndex: Option[IndexName],
    keyDerivation: SnapshotKeyDerivation
) {

  def keyOf(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): RowKey =
    keyDerivation match {
      case SnapshotKeyDerivation.Sharded(partitionKeyResolver, sortKeyResolver) =>
        val partitionKey = DdbValue.Str(partitionKeyResolver.resolve(persistenceId, sequenceNumber).asString)
        val sortKey      = DdbValue.Str(sortKeyResolver.resolve(persistenceId, sequenceNumber).asString)
        RowKey(table.table, keyFrom(partitionKey, sortKey))
      case SnapshotKeyDerivation.Identity =>
        RowKey(
          table.table,
          keyFrom(DdbValue.Str(persistenceId.asString), DdbValue.Num(BigDecimal(sequenceNumber.value)))
        )
    }

  /** The key that releases up to 1.2.0 gave the row, when it differs from `keyOf`.
    *
    * Those releases split the persistence id of the default resolvers differently (issue #1009), so a snapshot they
    * wrote can be stored under another key than the one `keyOf` gives now. It is none when the key did not change, when
    * the resolvers are not the default ones, and when those releases could not write a row for the id. The key can hold
    * the snapshot of another persistence id that shared it, so an operation on it must check the persistence id and the
    * sequence number of the row.
    */
  private[snapshot] def keyBeforeFixOf(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): Option[RowKey] =
    keyDerivation match {
      case SnapshotKeyDerivation.Identity => None
      case SnapshotKeyDerivation.Sharded(partitionKeyResolver, sortKeyResolver) =>
        val partitionKey = partitionKeyResolver.resolve(persistenceId, sequenceNumber)
        val sortKey      = sortKeyResolver.resolve(persistenceId, sequenceNumber)
        val partitionKeyBeforeFix = partitionKeyResolver match {
          case resolver: PartitionKeyResolver.PersistenceIdBased => resolver.resolveBeforeFix(persistenceId)
          case _                                                 => Some(partitionKey)
        }
        val sortKeyBeforeFix = sortKeyResolver match {
          case resolver: SortKeyResolver.PersistenceIdWithSeqNr =>
            resolver.resolveBeforeFix(persistenceId, sequenceNumber)
          case _ => Some(sortKey)
        }
        for {
          oldPartitionKey <- partitionKeyBeforeFix
          oldSortKey <- sortKeyBeforeFix
          if oldPartitionKey != partitionKey || oldSortKey != sortKey
        } yield RowKey(
          table.table,
          keyFrom(DdbValue.Str(oldPartitionKey.asString), DdbValue.Str(oldSortKey.asString))
        )
    }

  private def keyFrom(partitionKey: DdbValue, sortKey: DdbValue): AttributeMap = {
    val partitionEntry = Vector(table.primaryKey.hash -> partitionKey)
    val sortEntry      = table.primaryKey.range.toVector.map(_ -> sortKey)
    AttributeMap(partitionEntry ++ sortEntry)
  }
}

object SnapshotTableSpec {

  def sharded(
      schema: SnapshotSchema,
      tableName: TableName,
      indexName: IndexName,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): SnapshotTableSpec =
    SnapshotTableSpec(
      table = TableSpec(
        table = tableName,
        columns = Vector(
          Column(schema.partitionKey, AttributeKind.S, Presence.Required),
          Column(schema.sortKey, AttributeKind.S, Presence.Required),
          Column(schema.persistenceId, AttributeKind.S, Presence.Required),
          Column(schema.sequenceNumber, AttributeKind.N, Presence.Required),
          Column(schema.snapshot, AttributeKind.B, Presence.Required),
          Column(schema.created, AttributeKind.N, Presence.Required)
        ),
        primaryKey = PrimaryKey(schema.partitionKey, Some(schema.sortKey)),
        indexes = Vector(
          IndexSpec(indexName, schema.persistenceId, Some(schema.sequenceNumber), Projection.All)
        ),
        stream = None
      ),
      readIndex = Some(indexName),
      keyDerivation = SnapshotKeyDerivation.Sharded(partitionKeyResolver, sortKeyResolver)
    )

  def legacy(schema: SnapshotSchema, tableName: TableName): SnapshotTableSpec =
    SnapshotTableSpec(
      table = TableSpec(
        table = tableName,
        columns = Vector(
          Column(schema.persistenceId, AttributeKind.S, Presence.Required),
          Column(schema.sequenceNumber, AttributeKind.N, Presence.Required),
          Column(schema.snapshot, AttributeKind.B, Presence.Required),
          Column(schema.created, AttributeKind.N, Presence.Required)
        ),
        primaryKey = PrimaryKey(schema.persistenceId, Some(schema.sequenceNumber)),
        indexes = Vector.empty,
        stream = None
      ),
      readIndex = None,
      keyDerivation = SnapshotKeyDerivation.Identity
    )

  def fromConfig(
      schema: SnapshotSchema,
      config: SnapshotPluginConfig,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): SnapshotTableSpec =
    if (config.legacyTableFormat) {
      legacy(schema, TableName(config.tableName))
    } else {
      sharded(
        schema,
        TableName(config.tableName),
        IndexName(config.getSnapshotRowsIndexName),
        partitionKeyResolver,
        sortKeyResolver
      )
    }
}
