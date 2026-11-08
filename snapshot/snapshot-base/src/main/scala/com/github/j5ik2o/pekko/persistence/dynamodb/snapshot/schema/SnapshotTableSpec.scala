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

/** How a snapshot row maps to its primary key.
  *
  * `Sharded` stores the row under the configured partition and sort key resolvers. `Identity` uses the persistence id
  * and the sequence number as the key, which is the legacy table format.
  */
sealed trait SnapshotKeyDerivation

object SnapshotKeyDerivation {

  final case class Sharded(
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ) extends SnapshotKeyDerivation

  case object Identity extends SnapshotKeyDerivation
}

/** The physical snapshot table and the way a row maps to its key.
  *
  * The default format reads the snapshot rows through the configured index. The legacy format uses the persistence id
  * and the sequence number as the table key and has no index.
  */
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
