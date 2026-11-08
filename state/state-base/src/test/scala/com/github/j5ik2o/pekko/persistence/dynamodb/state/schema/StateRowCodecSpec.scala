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
import com.github.j5ik2o.pekko.persistence.dynamodb.state.{
  PartitionKey,
  PartitionKeyResolver,
  SortKey,
  SortKeyResolver,
  TableName => StateTableName,
  TableNameResolver
}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class StateRowCodecSpec extends AnyFreeSpec with Matchers {

  private val schema = StateSchema(
    partitionKey = AttributeName("pkey"),
    sortKey = AttributeName("skey"),
    persistenceId = AttributeName("persistence-id"),
    revision = AttributeName("revision-nr"),
    deleted = AttributeName("deleted"),
    payload = AttributeName("payload"),
    serializerId = AttributeName("serializer-id"),
    serializerManifest = AttributeName("serializer-manifest"),
    ordering = AttributeName("ordering"),
    tags = AttributeName("tags")
  )

  private val tableNameResolver = new TableNameResolver {
    override def resolve(persistenceId: PersistenceId): StateTableName = StateTableName("State")
  }

  private val partitionKeyResolver = new PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId): PartitionKey = PartitionKey("partition")
  }

  private val sortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId): SortKey = SortKey(persistenceId.asString)
  }

  private val tableSpec =
    StateTableSpec.create(schema, TableName("State"), tableNameResolver, partitionKeyResolver, sortKeyResolver)

  private val codec = new StateRowCodec(schema, tableSpec)

  private val row = StateRow(
    persistenceId = "sample",
    revision = 3L,
    payload = Array[Byte](1, 2, 3),
    serializerId = 7,
    serializerManifest = Some("manifest"),
    ordering = 1000L,
    tag = Some("tag")
  )

  "StateRowCodec" - {
    "round-trips a row" in {
      val decoded = codec.decode(codec.encode(row))

      decoded.map(_.persistenceId) shouldBe Right(row.persistenceId)
      decoded.map(_.revision) shouldBe Right(row.revision)
      decoded.map(_.payload.toVector) shouldBe Right(row.payload.toVector)
      decoded.map(_.serializerId) shouldBe Right(row.serializerId)
      decoded.map(_.serializerManifest) shouldBe Right(row.serializerManifest)
      decoded.map(_.ordering) shouldBe Right(row.ordering)
      decoded.map(_.tag) shouldBe Right(row.tag)
    }

    "writes the key and the required columns" in {
      val values = codec.encode(row)

      values.get(schema.partitionKey) shouldBe Some(DdbValue.Str("partition"))
      values.get(schema.sortKey) shouldBe Some(DdbValue.Str("sample"))
      values.get(schema.persistenceId) shouldBe Some(DdbValue.Str("sample"))
      values.get(schema.revision) shouldBe Some(DdbValue.Num(BigDecimal(3)))
      values.get(schema.payload) shouldBe Some(DdbValue.Bin(Vector[Byte](1, 2, 3)))
      values.get(schema.serializerId) shouldBe Some(DdbValue.Num(BigDecimal(7)))
      values.get(schema.ordering) shouldBe Some(DdbValue.Num(BigDecimal(1000)))
    }

    "omits the optional columns when the row has no manifest or tag" in {
      val values = codec.encode(row.copy(serializerManifest = None, tag = None))

      values.get(schema.serializerManifest) shouldBe None
      values.get(schema.tags) shouldBe None
    }

    "omits the tag column when the tag is empty" in {
      val values = codec.encode(row.copy(tag = Some("")))

      values.get(schema.tags) shouldBe None
    }

    "reports a missing column" in {
      codec.decode(AttributeMap.empty) shouldBe Left(DecodeError.Missing(schema.persistenceId))
    }

    "reports a column of the wrong type" in {
      val values = codec
        .encode(row)
        .updated(schema.revision, DdbValue.Str("three"))

      codec.decode(values) shouldBe Left(DecodeError.WrongType(schema.revision, AttributeKind.N))
    }
  }
}
