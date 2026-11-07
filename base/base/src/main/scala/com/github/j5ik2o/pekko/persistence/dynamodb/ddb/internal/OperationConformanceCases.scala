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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._

/** The operations that the v1 and v2 conformance specs run against both renderers.
  *
  * The table lives here, rather than in one SDK module, so the two specs cannot drift apart.
  */
private[dynamodb] object OperationConformanceCases {

  val table: TableName = TableName("Conformance")

  val spec: TableSpec = TableSpec(
    table = table,
    columns = Vector(
      Column(AttributeName("pkey"), AttributeKind.S, Presence.Required),
      Column(AttributeName("skey"), AttributeKind.S, Presence.Required),
      Column(AttributeName("persistence-id"), AttributeKind.S, Presence.Required),
      Column(AttributeName("sequence-nr"), AttributeKind.N, Presence.Required),
      Column(AttributeName("tags"), AttributeKind.S, Presence.Optional)
    ),
    primaryKey = PrimaryKey(AttributeName("pkey"), Some(AttributeName("skey"))),
    indexes = Vector(
      IndexSpec(
        IndexName("ConformanceIndex"),
        AttributeName("persistence-id"),
        Some(AttributeName("sequence-nr")),
        Projection.All
      )
    ),
    stream = Some(StreamView.NewImage)
  )

  val rowKey: RowKey = RowKey(
    table,
    AttributeMap.of(
      AttributeName("pkey") -> DdbValue.Str("p"),
      AttributeName("skey") -> DdbValue.Str("s")
    )
  )

  val operations: Vector[(String, Operation)] = Vector(
    "put with condition" -> Operation.Put(
      table,
      AttributeMap.of(
        AttributeName("pkey")    -> DdbValue.Str("p"),
        AttributeName("skey")    -> DdbValue.Str("s"),
        AttributeName("message") -> DdbValue.Bin(Vector[Byte](1, 2, 3))
      ),
      Some(
        Condition.And(
          Condition.AttributeNotExists(AttributeName("deleted")),
          Condition.Compare(
            Operand.Column(AttributeName("sequence-nr")),
            CompareOp.Ge,
            Operand.Literal(DdbValue.Num(1))
          )
        )
      )
    ),
    "get with consistent read" -> Operation.Get(table, rowKey, consistentRead = true),
    "delete with condition" -> Operation.Delete(
      table,
      rowKey,
      Some(Condition.AttributeExists(AttributeName("pkey")))
    ),
    "update with set and remove" -> Operation.Update(
      table,
      rowKey,
      Vector(
        AttributeUpdate.Set(AttributeName("tags"), DdbValue.StrSet(Set("a", "b"))),
        AttributeUpdate.Remove(AttributeName("deleted"))
      ),
      Some(Condition.AttributeNotExists(AttributeName("locked")))
    ),
    "query with index and paging" -> Operation.Query(
      table = table,
      index = Some(IndexName("ConformanceIndex")),
      hashKey = AttributeName("persistence-id") -> DdbValue.Str("p"),
      rangeKey = Some(AttributeName("sequence-nr") -> SortKeyCondition.Between(DdbValue.Num(1), DdbValue.Num(10))),
      filter = Some(
        Condition.Compare(
          Operand.Column(AttributeName("tags")),
          CompareOp.BeginsWith,
          Operand.Literal(DdbValue.Str("t"))
        )
      ),
      limit = Some(25),
      consistentRead = false,
      scanForward = false,
      exclusiveStartKey = Some(rowKey.values)
    ),
    "batch write" -> Operation.BatchWrite(
      table,
      puts = Vector(
        AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("p"), AttributeName("skey") -> DdbValue.Str("s"))
      ),
      deletes = Vector(rowKey)
    ),
    "transact write" -> Operation.TransactWrite(
      Vector(
        TransactItem.Put(
          table,
          AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("p"), AttributeName("skey") -> DdbValue.Str("s")),
          Some(Condition.AttributeNotExists(AttributeName("pkey")))
        ),
        TransactItem.Delete(table, rowKey, Some(Condition.AttributeExists(AttributeName("pkey")))),
        TransactItem.Update(
          table,
          rowKey,
          Vector(AttributeUpdate.Set(AttributeName("tags"), DdbValue.Str("t")))
        )
      )
    )
  )
}
