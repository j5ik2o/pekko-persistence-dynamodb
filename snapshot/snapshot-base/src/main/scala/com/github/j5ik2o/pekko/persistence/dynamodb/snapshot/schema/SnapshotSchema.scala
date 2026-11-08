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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.AttributeName
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotColumnsDefConfig

/** The snapshot row fields and the columns that store them.
  *
  * The row codec and the table spec both read these names, so a column rename changes one value.
  */
final case class SnapshotSchema(
    partitionKey: AttributeName,
    sortKey: AttributeName,
    persistenceId: AttributeName,
    sequenceNumber: AttributeName,
    snapshot: AttributeName,
    created: AttributeName
)

object SnapshotSchema {

  def fromColumns(columns: SnapshotColumnsDefConfig): SnapshotSchema =
    SnapshotSchema(
      partitionKey = AttributeName(columns.partitionKeyColumnName),
      sortKey = AttributeName(columns.sortKeyColumnName),
      persistenceId = AttributeName(columns.persistenceIdColumnName),
      sequenceNumber = AttributeName(columns.sequenceNrColumnName),
      snapshot = AttributeName(columns.snapshotColumnName),
      created = AttributeName(columns.createdColumnName)
    )
}
