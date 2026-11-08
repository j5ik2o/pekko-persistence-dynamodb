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
package com.github.j5ik2o.pekko.persistence.dynamodb.state.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.PersistenceId
import com.github.j5ik2o.pekko.persistence.dynamodb.state.config.StatePluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.state.{ PartitionKeyResolver, SortKeyResolver, TableNameResolver }

/** The durable state table and the way a persistence id maps to its key.
  *
  * The table name comes from the resolver on every call, so the prefix resolver keeps working.
  */
final case class StateTableSpec(
    table: TableSpec,
    tableNameResolver: TableNameResolver,
    partitionKeyResolver: PartitionKeyResolver,
    sortKeyResolver: SortKeyResolver
) {

  def keyOf(persistenceId: PersistenceId): RowKey = {
    val tableName      = TableName(tableNameResolver.resolve(persistenceId).asString)
    val partitionKey   = DdbValue.Str(partitionKeyResolver.resolve(persistenceId).asString)
    val sortKey        = DdbValue.Str(sortKeyResolver.resolve(persistenceId).asString)
    val partitionEntry = Vector(table.primaryKey.hash -> partitionKey)
    val sortEntry      = table.primaryKey.range.toVector.map(_ -> sortKey)
    RowKey(tableName, AttributeMap(partitionEntry ++ sortEntry))
  }
}

object StateTableSpec {

  def create(
      schema: StateSchema,
      tableName: TableName,
      tableNameResolver: TableNameResolver,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): StateTableSpec =
    StateTableSpec(
      table = TableSpec(
        table = tableName,
        columns = Vector(
          Column(schema.partitionKey, AttributeKind.S, Presence.Required),
          Column(schema.sortKey, AttributeKind.S, Presence.Required),
          Column(schema.persistenceId, AttributeKind.S, Presence.Required),
          Column(schema.revision, AttributeKind.N, Presence.Required),
          Column(schema.payload, AttributeKind.B, Presence.Required),
          Column(schema.serializerId, AttributeKind.N, Presence.Required),
          Column(schema.serializerManifest, AttributeKind.S, Presence.Optional),
          Column(schema.ordering, AttributeKind.N, Presence.Required),
          Column(schema.tags, AttributeKind.S, Presence.Optional)
        ),
        primaryKey = PrimaryKey(schema.partitionKey, Some(schema.sortKey)),
        indexes = Vector.empty,
        stream = None
      ),
      tableNameResolver = tableNameResolver,
      partitionKeyResolver = partitionKeyResolver,
      sortKeyResolver = sortKeyResolver
    )

  def fromConfig(
      schema: StateSchema,
      config: StatePluginConfig,
      tableNameResolver: TableNameResolver,
      partitionKeyResolver: PartitionKeyResolver,
      sortKeyResolver: SortKeyResolver
  ): StateTableSpec =
    create(
      schema,
      TableName(config.tableName),
      tableNameResolver,
      partitionKeyResolver,
      sortKeyResolver
    )
}
