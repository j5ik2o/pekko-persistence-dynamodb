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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ DdbValue, IndexSpec, PrimaryKey, Projection, StreamView }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class JournalKeyCodecSpec extends AnyFreeSpec with Matchers {

  import JournalTestSupport._

  private val codec          = new JournalKeyCodec(schema)
  private val rowCodec       = new JournalRowCodec(schema)
  private val persistenceId  = PersistenceId("account-1")
  private val sequenceNumber = SequenceNumber(7)

  "the delete key equals the write key for every shard" in {
    (1 to 8).foreach { shardCount =>
      val resolver = new ShardedPartitionKeyResolver(shardCount)
      val rowKey   = codec.keyOf(persistenceId, sequenceNumber, resolver, sortKeyResolver).toOption.get
      val item = rowCodec.encode(
        JournalTestSupport.row(persistenceId, sequenceNumber.value, "payload"),
        rowKey
      )

      schema.tableSpec.keyOf(item) shouldBe Right(rowKey)
    }
  }

  "the key uses the schema key columns and a string type" in {
    val resolver = new ShardedPartitionKeyResolver(2)
    val rowKey   = codec.keyOf(persistenceId, sequenceNumber, resolver, sortKeyResolver).toOption.get

    rowKey.table shouldBe schema.table
    rowKey.get(schema.partitionKey) shouldBe Some(DdbValue.Str("account-1-1"))
    rowKey.get(schema.sortKey) shouldBe Some(DdbValue.Str("0000000000000000007"))
  }

  "the table spec declares the key, the journal rows index, and the new image stream" in {
    val table = schema.tableSpec

    table.table shouldBe schema.table
    table.columns.map(_.name) shouldBe Vector(
      schema.partitionKey,
      schema.sortKey,
      schema.persistenceId,
      schema.sequenceNr,
      schema.tags
    )
    table.primaryKey shouldBe PrimaryKey(schema.partitionKey, Some(schema.sortKey))
    table.indexes shouldBe Vector(
      IndexSpec(schema.journalRowsIndex, schema.persistenceId, Some(schema.sequenceNr), Projection.All)
    )
    table.stream shouldBe Some(StreamView.NewImage)
  }
}
