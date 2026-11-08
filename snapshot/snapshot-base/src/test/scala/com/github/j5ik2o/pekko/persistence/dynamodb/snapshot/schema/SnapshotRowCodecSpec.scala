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
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.dao.SnapshotRow
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.{
  PartitionKey,
  PartitionKeyResolver,
  SortKey,
  SortKeyResolver
}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class SnapshotRowCodecSpec extends AnyFreeSpec with Matchers {

  private val schema = SnapshotSchema(
    partitionKey = AttributeName("pkey"),
    sortKey = AttributeName("skey"),
    persistenceId = AttributeName("persistence-id"),
    sequenceNumber = AttributeName("sequence-nr"),
    snapshot = AttributeName("snapshot"),
    created = AttributeName("created")
  )

  private val partitionKeyResolver = new PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(s"${persistenceId.asString}-${sequenceNumber.value % 4}")
  }

  private val sortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey(s"${persistenceId.asString}-${sequenceNumber.value}")
  }

  private val sharded =
    SnapshotTableSpec.sharded(
      schema,
      TableName("Snapshot"),
      IndexName("GetSnapshotRowsIndex"),
      partitionKeyResolver,
      sortKeyResolver
    )

  private val legacy = SnapshotTableSpec.legacy(schema, TableName("Snapshot"))

  private val row = SnapshotRow(
    persistenceId = PersistenceId("sample"),
    sequenceNumber = SequenceNumber(5),
    created = 1000L,
    snapshot = Array[Byte](1, 2, 3)
  )

  "SnapshotRowCodec" - {
    "round-trips a row through the sharded table" in {
      val codec   = new SnapshotRowCodec(schema, sharded)
      val decoded = codec.decode(codec.encode(row))

      decoded.map(_.persistenceId) shouldBe Right(row.persistenceId)
      decoded.map(_.sequenceNumber) shouldBe Right(row.sequenceNumber)
      decoded.map(_.created) shouldBe Right(row.created)
      decoded.map(_.snapshot.toVector) shouldBe Right(row.snapshot.toVector)
    }

    "writes the sharded partition and sort key columns" in {
      val codec = new SnapshotRowCodec(schema, sharded)
      val item  = codec.encode(row)

      item.get(schema.partitionKey) shouldBe Some(DdbValue.Str("sample-1"))
      item.get(schema.sortKey) shouldBe Some(DdbValue.Str("sample-5"))
      item.get(schema.persistenceId) shouldBe Some(DdbValue.Str("sample"))
      item.get(schema.sequenceNumber) shouldBe Some(DdbValue.Num(BigDecimal(5)))
      item.get(schema.snapshot) shouldBe Some(DdbValue.Bin(Vector[Byte](1, 2, 3)))
      item.get(schema.created) shouldBe Some(DdbValue.Num(BigDecimal(1000)))
    }

    "writes only the identity key columns for the legacy table" in {
      val codec = new SnapshotRowCodec(schema, legacy)
      val item  = codec.encode(row)

      item.get(schema.partitionKey) shouldBe None
      item.get(schema.sortKey) shouldBe None
      item.get(schema.persistenceId) shouldBe Some(DdbValue.Str("sample"))
      item.get(schema.sequenceNumber) shouldBe Some(DdbValue.Num(BigDecimal(5)))
    }

    "uses the same key for the row and for the encoded item" in {
      val codec = new SnapshotRowCodec(schema, sharded)

      sharded.table.keyOf(codec.encode(row)) shouldBe Right(codec.key(row))
    }

    "reports a missing column" in {
      val codec = new SnapshotRowCodec(schema, sharded)

      codec.decode(AttributeMap.empty) shouldBe Left(DecodeError.Missing(schema.persistenceId))
    }

    "reports a column of the wrong type" in {
      val codec = new SnapshotRowCodec(schema, sharded)
      val item  = codec.encode(row).updated(schema.sequenceNumber, DdbValue.Str("five"))

      codec.decode(item) shouldBe Left(DecodeError.WrongType(schema.sequenceNumber, AttributeKind.N))
    }
  }
}
