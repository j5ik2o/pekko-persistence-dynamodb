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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.head

import com.github.j5ik2o.pekko.persistence.dynamodb.model.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JournalHeadMigrationSelectionSpec extends AnyWordSpec with Matchers {
  "Migration selection" should {
    "keep all IDs by default and deduplicate exact values without normalization" in {
      JournalHeadMigration.resolvePersistenceIds(Array.empty) shouldBe None
      val values = Seq("p", "p", " P ", " ", "é", "e\u0301", "x" * 2048)
      JournalHeadMigration.resolvePersistenceIds(values.flatMap(v => Seq("--persistence-id", v)).toArray) shouldBe
      Some(values.map(PersistenceId(_)).toSet)
    }

    "reject invalid arguments before main can create an actor system or client" in {
      val invalid = Seq(
        Array("--persistence-id"),
        Array("--persistence-id", ""),
        Array("--persistence-id", "x" * 2049),
        Array("--persistence-id", "--execute"),
        Array("--persistence-id=p"),
        Array("--persistance-id", "p"),
        Array("extra"),
        Array("--execute", "extra"),
        Array("--progress"),
        Array("--snapshot-table"),
        Array("--snapshot-table", "--execute")
      )
      invalid.foreach { args =>
        intercept[IllegalArgumentException](JournalHeadMigration.main(args))
      }
    }

    "leave snapshot overrides and their existing precedence to the snapshot resolver" in {
      val args = Array(
        "--execute",
        "--snapshot-table",
        "first",
        "--snapshot-table",
        "second",
        "--snapshot-persistence-id-column",
        "pid",
        "--snapshot-sequence-nr-column",
        "seq",
        "--persistence-id",
        "--not-a-value"
      )
      intercept[IllegalArgumentException](JournalHeadMigration.resolvePersistenceIds(args))
      val valid = args.dropRight(2) ++ Array("--persistence-id", "p")
      JournalHeadMigration.resolvePersistenceIds(valid) shouldBe Some(Set(PersistenceId("p")))
      JournalHeadMigration.resolveSnapshotTable(valid, ConfigFactory.empty()) shouldBe
      Some(JournalHeadMigration.SnapshotTable("first", "pid", "seq"))
      JournalHeadMigration.resolvePersistenceIds(valid ++ Array("--without-snapshots")) shouldBe Some(
        Set(PersistenceId("p"))
      )
      JournalHeadMigration.resolveSnapshotTable(
        valid ++ Array("--without-snapshots"),
        ConfigFactory.empty()
      ) shouldBe None
    }
  }
}
