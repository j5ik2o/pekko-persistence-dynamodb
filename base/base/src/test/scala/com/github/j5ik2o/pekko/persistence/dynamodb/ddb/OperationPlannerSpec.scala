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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{ OperationPlanner, RenderedOperation }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class OperationPlannerSpec extends AnyFreeSpec with Matchers {

  private val table = TableName("Journal")
  private val spec = TableSpec(
    table = table,
    columns = Vector(
      Column(AttributeName("pkey"), AttributeKind.S, Presence.Required),
      Column(AttributeName("skey"), AttributeKind.S, Presence.Required)
    ),
    primaryKey = PrimaryKey(AttributeName("pkey"), Some(AttributeName("skey"))),
    indexes = Vector.empty,
    stream = None
  )
  private val rowKey = spec
    .keyOf(
      AttributeMap.of(
        AttributeName("pkey") -> DdbValue.Str("partition"),
        AttributeName("skey") -> DdbValue.Str("sort")
      )
    )
    .toOption
    .get

  "OperationPlanner renders a put condition with stable placeholders" in {
    val condition = Condition.And(
      Condition.Compare(
        Operand.Column(AttributeName("persistence-id")),
        CompareOp.Eq,
        Operand.Literal(DdbValue.Str("p"))
      ),
      Condition.AttributeExists(AttributeName("sequence-nr"))
    )
    val operation =
      Operation.Put(table, AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("partition")), Some(condition))

    val rendered = OperationPlanner.plan(operation) match {
      case Right(put: RenderedOperation.Put) => put
      case other                             => fail(s"expected a put plan, got $other")
    }

    rendered.condition.map(_.expression) shouldBe Some("(#n0 = :v0) AND (attribute_exists(#n1))")
    rendered.condition.map(_.names) shouldBe Some(
      Vector("#n0" -> AttributeName("persistence-id"), "#n1" -> AttributeName("sequence-nr"))
    )
    rendered.condition.map(_.values) shouldBe Some(Vector(":v0" -> DdbValue.Str("p")))
  }

  "OperationPlanner renders an update and its condition against one placeholder set" in {
    val updates = Vector(
      AttributeUpdate.Set(AttributeName("a"), DdbValue.Str("x")),
      AttributeUpdate.Remove(AttributeName("b"))
    )
    val operation = Operation.Update(table, rowKey, updates, Some(Condition.AttributeNotExists(AttributeName("c"))))

    val rendered = OperationPlanner.plan(operation) match {
      case Right(update: RenderedOperation.Update) => update
      case other                                   => fail(s"expected an update plan, got $other")
    }

    rendered.update.expression shouldBe "SET #n0 = :v0 REMOVE #n1"
    rendered.condition.map(_.expression) shouldBe Some("attribute_not_exists(#n2)")
    rendered.update.names shouldBe Vector(
      "#n0" -> AttributeName("a"),
      "#n1" -> AttributeName("b"),
      "#n2" -> AttributeName("c")
    )
    rendered.condition.map(_.names) shouldBe Some(rendered.update.names)
  }

  "OperationPlanner renders a query key condition and filter against one placeholder set" in {
    val query = Operation.Query(
      table = table,
      index = Some(IndexName("GetJournalRowsIndex")),
      hashKey = AttributeName("persistence-id") -> DdbValue.Str("p"),
      rangeKey = Some(AttributeName("sequence-nr") -> SortKeyCondition.Between(DdbValue.Num(1), DdbValue.Num(10))),
      filter = Some(
        Condition.Compare(
          Operand.Column(AttributeName("persistence-id")),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Str("p"))
        )
      ),
      limit = Some(50),
      consistentRead = true,
      scanForward = false,
      exclusiveStartKey = Some(AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("start")))
    )

    val rendered = OperationPlanner.plan(query) match {
      case Right(query: RenderedOperation.Query) => query
      case other                                 => fail(s"expected a query plan, got $other")
    }

    rendered.keyCondition.expression shouldBe "#n0 = :v0 AND #n1 BETWEEN :v1 AND :v2"
    rendered.filter.map(_.expression) shouldBe Some("#n0 = :v0")
    rendered.keyCondition.values shouldBe Vector(
      ":v0" -> DdbValue.Str("p"),
      ":v1" -> DdbValue.Num(1),
      ":v2" -> DdbValue.Num(10)
    )
    rendered.limit shouldBe Some(50)
    rendered.consistentRead shouldBe true
    rendered.scanForward shouldBe false
    rendered.exclusiveStartKey shouldBe Some(AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("start")))
  }

  "OperationPlanner renders a batch write and a transaction" in {
    val batch = Operation.BatchWrite(
      table,
      puts = Vector(AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("p"))),
      deletes = Vector(rowKey)
    )
    val transact = Operation.TransactWrite(
      Vector(
        TransactItem.Put(table, AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("p"))),
        TransactItem.Delete(table, rowKey),
        TransactItem.Update(table, rowKey, Vector(AttributeUpdate.Set(AttributeName("a"), DdbValue.Num(1))))
      )
    )

    val renderedBatch = OperationPlanner.plan(batch) match {
      case Right(batch: RenderedOperation.BatchWrite) => batch
      case other                                      => fail(s"expected a batch plan, got $other")
    }
    val renderedTransact = OperationPlanner.plan(transact) match {
      case Right(transact: RenderedOperation.TransactWrite) => transact
      case other                                            => fail(s"expected a transaction plan, got $other")
    }

    renderedBatch.puts should have size 1
    renderedBatch.deletes shouldBe Vector(rowKey.values)
    renderedTransact.items should have size 3
  }

  "OperationPlanner rejects an empty update, batch write, and transaction" in {
    OperationPlanner.plan(Operation.Update(table, rowKey, Vector.empty)).isLeft shouldBe true
    OperationPlanner.plan(Operation.BatchWrite(table, Vector.empty, Vector.empty)).isLeft shouldBe true
    OperationPlanner.plan(Operation.TransactWrite(Vector.empty)).isLeft shouldBe true
  }

  "OperationPlanner renders every operation case" in {
    val operations = Vector[Operation](
      Operation.Put(table, AttributeMap.empty),
      Operation.Get(table, rowKey),
      Operation.Delete(table, rowKey),
      Operation.Update(table, rowKey, Vector(AttributeUpdate.Remove(AttributeName("a")))),
      Operation.Query(table, None, AttributeName("pkey") -> DdbValue.Str("p")),
      Operation.BatchWrite(table, Vector(AttributeMap.empty), Vector.empty),
      Operation.TransactWrite(Vector(TransactItem.Delete(table, rowKey)))
    )

    operations.foreach(operation => OperationPlanner.plan(operation).isRight shouldBe true)
  }
}
