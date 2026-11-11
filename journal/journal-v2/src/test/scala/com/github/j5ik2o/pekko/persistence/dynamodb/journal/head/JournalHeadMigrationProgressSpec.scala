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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

class JournalHeadMigrationProgressSpec extends AnyWordSpec with Matchers {
  import JournalHeadMigration._

  "Migration progress" should {
    "render dry-run findings for raised and snapshot-only persistence ids" in {
      val findings = MigrationFindings.fromPlan(
        MigrationPlan(
          Vector(
            MigrationPlan.Entry(
              PersistenceId("order-17"),
              MigrationSource.Journal(SequenceNumber(4)),
              SequenceNumber(4),
              Some(SequenceNumber(2))
            ),
            MigrationPlan.Entry(
              PersistenceId("order-42"),
              MigrationSource.Both(SequenceNumber(5), SequenceNumber(3)),
              SequenceNumber(5),
              Some(SequenceNumber(3))
            ),
            MigrationPlan.Entry(
              PersistenceId("order-93"),
              MigrationSource.Snapshot(SequenceNumber(7)),
              SequenceNumber(7),
              None
            )
          ),
          snapshotTableScanned = true
        )
      )

      renderFindings(findings) shouldBe Seq(
        "[dry-run] raised JournalHead items may indicate writes from nodes with head mode disabled; this is not " +
        "conclusive. A serialization rejection can leave a sequence-number gap without a journal row, and a " +
        "later head-enabled write can move the head across a gap so the migration no longer reports it",
        "[dry-run] snapshot-only migration sources: 1 persistence id (compare with hard-delete history; this is " +
        "not an anomaly):\n  - order-93"
      )
    }

    "render source observations without implying a migration total or success" in {
      renderProgress(ScanPage(Journal, 2L, 1L, 1L, false), execute = false) shouldBe
      "[dry-run] JournalHead migration progress phase=scan source=journal pages=2 rows-observed=1 ids-observed=1 source-exhausted=false"
      renderProgress(ScanPage(Snapshot, 1L, 2L, 2L, true), execute = true) shouldBe
      "JournalHead migration progress phase=scan source=snapshot pages=1 rows-observed=2 ids-observed=2 source-exhausted=true"
      renderProgress(SourceStarted(Journal), execute = true) should endWith("source=journal started")
      renderProgress(SourceStarted(Snapshot), execute = true) should endWith("source=snapshot started")
      renderProgress(SnapshotSkipped, execute = false) should endWith("source=snapshot skipped")
      renderProgress(SnapshotUnavailable, execute = false) should endWith("source=snapshot unavailable")
      renderProgress(PhaseStarted("read-heads"), execute = true) should endWith("phase=read-heads started")
    }

    "enable only flag-selected stderr output and preserve stdout" in {
      def capture(args: Array[String]): (String, String) = {
        val out = new java.io.ByteArrayOutputStream()
        val err = new java.io.ByteArrayOutputStream()
        Console.withOut(out) {
          Console.withErr(err) {
            notifyProgress(progressSink(args, execute = false), SourceStarted(Journal))
          }
        }
        (out.toString("UTF-8"), err.toString("UTF-8"))
      }
      capture(Array.empty) shouldBe (("", ""))
      val observed = capture(Array("--progress"))
      observed._1 shouldBe ""
      observed._2 shouldBe (renderProgress(SourceStarted(Journal), execute = false) + System.lineSeparator())
    }

    "isolate only NonFatal notification failures" in {
      noException should be thrownBy notifyProgress(_ => throw new IllegalStateException("sink"), SnapshotSkipped)
      val fatal = new LinkageError("fatal sink")
      intercept[LinkageError](notifyProgress(_ => throw fatal, SnapshotSkipped)) shouldBe fatal
    }
  }
}
