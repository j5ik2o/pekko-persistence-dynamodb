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

import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalColumnsDefConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalRow,
  PartitionKey,
  PartitionKeyResolver,
  SortKey,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.typesafe.config.ConfigFactory

import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

object JournalTestSupport {

  val schema: JournalSchema = JournalSchema(
    tableName = "Journal",
    journalRowsIndexName = "GetJournalRowsIndex",
    columns = JournalColumnsDefConfig(
      sourceConfig = ConfigFactory.empty(),
      partitionKeyColumnName = "pkey",
      sortKeyColumnName = "skey",
      persistenceIdColumnName = "persistence-id",
      sequenceNrColumnName = "sequence-nr",
      deletedColumnName = "deleted",
      messageColumnName = "message",
      orderingColumnName = "ordering",
      tagsColumnName = "tags"
    )
  )

  val sortKeyResolver: SortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey("%019d".format(sequenceNumber.value))
  }

  final class ShardedPartitionKeyResolver(shardCount: Int) extends PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(s"${persistenceId.asString}-${sequenceNumber.value % shardCount}")
  }

  val disabledBackoff: BackoffConfig = BackoffConfig(
    sourceConfig = ConfigFactory.empty(),
    enabled = false,
    minBackoff = 1.second,
    maxBackoff = 1.second,
    randomFactor = 0.2,
    maxRestarts = 3
  )

  def row(
      persistenceId: PersistenceId,
      sequenceNumber: Long,
      message: String,
      deleted: Boolean = false,
      ordering: Long = 0L,
      tags: Option[String] = None
  ): JournalRow = JournalRow(
    persistenceId = persistenceId,
    sequenceNumber = SequenceNumber(sequenceNumber),
    deleted = deleted,
    message = message.getBytes(StandardCharsets.UTF_8),
    ordering = ordering,
    tags = tags
  )
}
