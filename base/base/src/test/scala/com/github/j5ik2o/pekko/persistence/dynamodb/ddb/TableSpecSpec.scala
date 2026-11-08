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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class TableSpecSpec extends AnyFreeSpec with Matchers {

  private val journal = TableSpec(
    table = TableName("Journal"),
    columns = Vector(
      Column(AttributeName("pkey"), AttributeKind.S, Presence.Required),
      Column(AttributeName("skey"), AttributeKind.S, Presence.Required),
      Column(AttributeName("persistence-id"), AttributeKind.S, Presence.Required),
      Column(AttributeName("sequence-nr"), AttributeKind.N, Presence.Required)
    ),
    primaryKey = PrimaryKey(AttributeName("pkey"), Some(AttributeName("skey"))),
    indexes = Vector(
      IndexSpec(
        IndexName("GetJournalRowsIndex"),
        AttributeName("persistence-id"),
        Some(AttributeName("sequence-nr")),
        Projection.All
      )
    ),
    stream = Some(StreamView.NewImage)
  )

  "TableSpec.withColumns returns a new value" in {
    val extra   = Column(AttributeName("tags"), AttributeKind.S, Presence.Optional)
    val derived = journal.withColumns(journal.columns :+ extra)

    derived.columns should contain(extra)
    derived.columns should have size 5
    journal.columns should have size 4
  }

  "TableSpec.keyOf extracts the hash and range key" in {
    val rowKey = journal
      .keyOf(
        AttributeMap.of(
          AttributeName("pkey")           -> DdbValue.Str("partition"),
          AttributeName("skey")           -> DdbValue.Str("sort"),
          AttributeName("persistence-id") -> DdbValue.Str("ignored")
        )
      )
      .toOption
      .get

    rowKey.table shouldBe TableName("Journal")
    rowKey.values shouldBe AttributeMap.of(
      AttributeName("pkey") -> DdbValue.Str("partition"),
      AttributeName("skey") -> DdbValue.Str("sort")
    )
  }

  "TableSpec.keyOf rejects a missing key attribute" in {
    val result = journal.keyOf(AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("partition")))

    result shouldBe Left(KeyError.Missing(AttributeName("skey")))
  }

  "TableSpec.keyOf rejects a key attribute of the wrong type" in {
    val result = journal.keyOf(
      AttributeMap.of(
        AttributeName("pkey") -> DdbValue.Str("partition"),
        AttributeName("skey") -> DdbValue.Num(1)
      )
    )

    result shouldBe Left(KeyError.WrongType(AttributeName("skey"), AttributeKind.S))
  }

  "TableSpec.keyOf reports an undefined key column before a missing attribute" in {
    val broken = journal.copy(primaryKey = PrimaryKey(AttributeName("pkey"), Some(AttributeName("undefined"))))

    broken.keyOf(AttributeMap.of(AttributeName("pkey") -> DdbValue.Str("partition"))) shouldBe
    Left(KeyError.UndefinedColumn(AttributeName("undefined")))
  }

  "AttributeMap keeps the last value for a name and merges in order" in {
    val first = AttributeMap.of(
      AttributeName("a") -> DdbValue.Str("1"),
      AttributeName("b") -> DdbValue.Str("2")
    )
    val merged = first.merge(AttributeMap.of(AttributeName("a") -> DdbValue.Str("3")))

    merged.get(AttributeName("a")) shouldBe Some(DdbValue.Str("3"))
    merged.names shouldBe Vector(AttributeName("b"), AttributeName("a"))
    merged.removed(AttributeName("b")).names shouldBe Vector(AttributeName("a"))
  }

  "AttributeMap keeps one value per name when constructed from duplicates" in {
    val map = AttributeMap(
      Vector(
        AttributeName("a") -> DdbValue.Str("1"),
        AttributeName("b") -> DdbValue.Str("2"),
        AttributeName("a") -> DdbValue.Str("3")
      )
    )

    map.size shouldBe 2
    map.get(AttributeName("a")) shouldBe Some(DdbValue.Str("3"))
    map.entries shouldBe Vector(AttributeName("b") -> DdbValue.Str("2"), AttributeName("a") -> DdbValue.Str("3"))
  }
}
