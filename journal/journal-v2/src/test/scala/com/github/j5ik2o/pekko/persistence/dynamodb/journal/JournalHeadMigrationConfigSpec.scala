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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadMigration
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class JournalHeadMigrationConfigSpec extends AnyWordSpec with Matchers {

  "JournalHeadMigration" should {
    "resolve the snapshot source from the config or the command line, and fail when neither is given" in {
      val configured = ConfigFactory.parseString(
        """
          |j5ik2o.dynamo-db-snapshot {
          |  table-name = "ConfiguredSnapshot"
          |  columns-def {
          |    persistence-id-column-name = "pid"
          |    sequence-nr-column-name = "seq"
          |  }
          |}
          |""".stripMargin
      )

      JournalHeadMigration.resolveSnapshotTable(Array.empty, configured) shouldBe
      Some(JournalHeadMigration.SnapshotTable("ConfiguredSnapshot", "pid", "seq"))

      JournalHeadMigration.resolveSnapshotTable(
        Array(
          "--snapshot-table",
          "CustomSnapshot",
          "--snapshot-persistence-id-column",
          "p",
          "--snapshot-sequence-nr-column",
          "s"
        ),
        configured
      ) shouldBe Some(JournalHeadMigration.SnapshotTable("CustomSnapshot", "p", "s"))

      JournalHeadMigration.resolveSnapshotTable(Array("--without-snapshots"), configured) shouldBe None

      // Without the config and without a named table the tool must not silently scan the default `Snapshot` table.
      val missingSource = intercept[IllegalArgumentException] {
        JournalHeadMigration.resolveSnapshotTable(Array.empty, ConfigFactory.empty())
      }
      missingSource.getMessage should include("--snapshot-table")
      missingSource.getMessage should include("--without-snapshots")

      // A named table is a source on its own, so a deployment without the snapshot plugin can name the table.
      JournalHeadMigration.resolveSnapshotTable(
        Array("--snapshot-table", "CustomSnapshot"),
        ConfigFactory.empty()
      ) shouldBe
      Some(JournalHeadMigration.SnapshotTable("CustomSnapshot", "persistence-id", "sequence-nr"))

      Seq(
        "--snapshot-table",
        "--snapshot-persistence-id-column",
        "--snapshot-sequence-nr-column"
      ).foreach { option =>
        val failure = intercept[IllegalArgumentException] {
          JournalHeadMigration.resolveSnapshotTable(Array(option), configured)
        }
        failure.getMessage should include(option)
      }
    }
  }

}
