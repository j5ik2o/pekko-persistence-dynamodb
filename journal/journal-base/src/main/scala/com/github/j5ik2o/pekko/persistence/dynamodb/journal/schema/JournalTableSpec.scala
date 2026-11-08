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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeKind,
  Column,
  IndexSpec,
  Presence,
  PrimaryKey,
  Projection,
  StreamView,
  TableSpec
}

/** The declared attributes, key, index, and stream of the journal table.
  *
  * The columns are the attributes DynamoDB declares for the key and the index. The message, ordering, and deleted
  * columns are item attributes, so the table does not declare them.
  */
object JournalTableSpec {

  def of(schema: JournalSchema): TableSpec = TableSpec(
    table = schema.table,
    columns = Vector(
      Column(schema.partitionKey, AttributeKind.S, Presence.Required),
      Column(schema.sortKey, AttributeKind.S, Presence.Required),
      Column(schema.persistenceId, AttributeKind.S, Presence.Required),
      Column(schema.sequenceNr, AttributeKind.N, Presence.Required),
      Column(schema.tags, AttributeKind.S, Presence.Optional)
    ),
    primaryKey = PrimaryKey(schema.partitionKey, Some(schema.sortKey)),
    indexes = Vector(
      IndexSpec(
        schema.journalRowsIndex,
        schema.persistenceId,
        Some(schema.sequenceNr),
        Projection.All
      )
    ),
    stream = Some(StreamView.NewImage)
  )
}
