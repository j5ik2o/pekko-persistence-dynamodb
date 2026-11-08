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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.AttributeName
import com.github.j5ik2o.pekko.persistence.dynamodb.state.config.StateColumnsDefConfig

final case class StateSchema(
    partitionKey: AttributeName,
    sortKey: AttributeName,
    persistenceId: AttributeName,
    revision: AttributeName,
    deleted: AttributeName,
    payload: AttributeName,
    serializerId: AttributeName,
    serializerManifest: AttributeName,
    ordering: AttributeName,
    tags: AttributeName
)

object StateSchema {

  def fromColumns(columns: StateColumnsDefConfig): StateSchema =
    StateSchema(
      partitionKey = AttributeName(columns.partitionKeyColumnName),
      sortKey = AttributeName(columns.sortKeyColumnName),
      persistenceId = AttributeName(columns.persistenceIdColumnName),
      revision = AttributeName(columns.revisionColumnName),
      deleted = AttributeName(columns.deletedColumnName),
      payload = AttributeName(columns.payloadColumnName),
      serializerId = AttributeName(columns.serializerIdColumnName),
      serializerManifest = AttributeName(columns.serializerManifestColumnName),
      ordering = AttributeName(columns.orderingColumnName),
      tags = AttributeName(columns.tagsColumnName)
    )
}
