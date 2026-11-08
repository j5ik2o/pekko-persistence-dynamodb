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

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

final class JournalHeadMigrationPlannerSpec extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  private val persistenceId = PersistenceId("p-1")

  /** The two conditional writes of the real table, without DynamoDB. A put wins only for a missing item, and a raise
    * wins only for a head below the target, so the writer has the same convergence rules as the storage layer.
    */
  private final class InMemoryHeadWriter(initial: Map[PersistenceId, Long]) extends MigrationExecutor.HeadWriter {

    private val heads =
      mutable.Map(initial.toSeq.map { case (id, sequenceNr) => id -> SequenceNumber(sequenceNr) }: _*)

    def head(id: PersistenceId): Option[SequenceNumber] = heads.get(id)

    override def putIfAbsent(id: PersistenceId, target: SequenceNumber): Boolean =
      if (heads.contains(id)) false
      else {
        heads.update(id, target)
        true
      }

    override def raiseIfBehind(id: PersistenceId, target: SequenceNumber): Boolean =
      heads.get(id) match {
        case Some(current) if current < target =>
          heads.update(id, target)
          true
        case _ => false
      }

  }

  private def plan(
      journal: Option[Long],
      snapshot: Option[Long],
      head: Option[Long]
  ): MigrationPlan =
    MigrationPlanner.plan(
      journal.map(sequenceNr => persistenceId -> SequenceNumber(sequenceNr)).toMap,
      snapshot.map(sequenceNr => Map(persistenceId -> SequenceNumber(sequenceNr))),
      head.map(sequenceNr => persistenceId -> SequenceNumber(sequenceNr)).toMap
    )

  "MigrationPlanner" should {

    "compute the target version from the union of the journal and snapshot sources" in {
      val cases =
        Table[String, Option[Long], Option[Long], Option[Long], Long, MigrationSource, Int, Int, Int](
          ("case", "journal", "snapshot", "head", "target", "source", "created", "updated", "existing"),
          (
            "journal only",
            Some(3L),
            None,
            None,
            3L,
            MigrationSource.Journal(SequenceNumber(3)),
            1,
            0,
            0
          ),
          (
            "snapshot only",
            None,
            Some(5L),
            None,
            5L,
            MigrationSource.Snapshot(SequenceNumber(5)),
            1,
            0,
            0
          ),
          (
            "both, journal ahead",
            Some(7L),
            Some(5L),
            None,
            7L,
            MigrationSource.Both(SequenceNumber(7), SequenceNumber(5)),
            1,
            0,
            0
          ),
          (
            "both, snapshot ahead",
            Some(4L),
            Some(9L),
            None,
            9L,
            MigrationSource.Both(SequenceNumber(4), SequenceNumber(9)),
            1,
            0,
            0
          ),
          (
            "stale head",
            Some(6L),
            None,
            Some(3L),
            6L,
            MigrationSource.Journal(SequenceNumber(6)),
            0,
            1,
            0
          ),
          (
            "head at the target",
            Some(6L),
            None,
            Some(6L),
            6L,
            MigrationSource.Journal(SequenceNumber(6)),
            0,
            0,
            1
          ),
          (
            "head ahead of the target",
            None,
            Some(6L),
            Some(9L),
            6L,
            MigrationSource.Snapshot(SequenceNumber(6)),
            0,
            0,
            1
          ),
          (
            "rerun after execute",
            Some(6L),
            Some(6L),
            Some(6L),
            6L,
            MigrationSource.Both(SequenceNumber(6), SequenceNumber(6)),
            0,
            0,
            1
          )
        )

      forAll(cases) { (caseName, journal, snapshot, head, target, source, created, updated, existing) =>
        withClue(caseName) {
          val planned = plan(journal, snapshot, head)
          planned.entries.map(_.persistenceId) shouldBe Seq(persistenceId)
          planned.entries.map(_.target) shouldBe Seq(SequenceNumber(target))
          planned.entries.map(_.source) shouldBe Seq(source)
          planned.createdJournalHeads shouldBe created
          planned.updatedJournalHeads shouldBe updated
          planned.existingJournalHeads shouldBe existing
          planned.raisedPersistenceIds shouldBe (if (updated == 1) Seq(persistenceId) else Seq.empty)
          planned.snapshotTableScanned shouldBe snapshot.isDefined
        }
      }
    }

    "plan nothing for a persistence id that neither table holds" in {
      val planned = MigrationPlanner.plan(
        Map.empty,
        None,
        Map(persistenceId -> SequenceNumber(9))
      )

      planned.entries shouldBe empty
      planned.scannedPersistenceIds shouldBe 0
      planned.createdJournalHeads shouldBe 0
      planned.updatedJournalHeads shouldBe 0
      planned.existingJournalHeads shouldBe 0
      planned.raisedPersistenceIds shouldBe Seq.empty
      planned.snapshotTableScanned shouldBe false
    }

    "plan one entry per persistence id present in either table" in {
      val journalOnly  = PersistenceId("a-journal-only")
      val both         = PersistenceId("b-both")
      val snapshotOnly = PersistenceId("c-snapshot-only")

      val planned = MigrationPlanner.plan(
        Map(journalOnly -> SequenceNumber(3), both -> SequenceNumber(1)),
        Some(Map(both -> SequenceNumber(4), snapshotOnly -> SequenceNumber(7))),
        Map.empty
      )

      planned.entries.map(entry => entry.persistenceId -> entry.target) shouldBe Seq(
        journalOnly  -> SequenceNumber(3),
        both         -> SequenceNumber(4),
        snapshotOnly -> SequenceNumber(7)
      )
      planned.scannedPersistenceIds shouldBe 3
      planned.createdJournalHeads shouldBe 3
      planned.updatedJournalHeads shouldBe 0
      planned.existingJournalHeads shouldBe 0
      planned.snapshotTableScanned shouldBe true
    }

    "mark the snapshot table as not scanned when the caller has no snapshot source" in {
      val planned = plan(Some(3L), None, None)

      planned.snapshotTableScanned shouldBe false
    }

  }

  "MigrationExecutor" should {

    "converge when the same plan is executed twice" in {
      val writer  = new InMemoryHeadWriter(Map.empty)
      val planned = plan(Some(3L), None, None)

      val first = MigrationExecutor.execute(planned, writer)
      first.createdJournalHeads shouldBe 1
      first.updatedJournalHeads shouldBe 0
      first.existingJournalHeads shouldBe 0
      writer.head(persistenceId) shouldBe Some(SequenceNumber(3))

      val second = MigrationExecutor.execute(planned, writer)
      second.createdJournalHeads shouldBe 0
      second.updatedJournalHeads shouldBe 0
      second.existingJournalHeads shouldBe 1
      second.raisedPersistenceIds shouldBe Seq.empty
      writer.head(persistenceId) shouldBe Some(SequenceNumber(3))
    }

    "fall through from a planned create to a raise when the head appeared after the plan" in {
      val writer  = new InMemoryHeadWriter(Map(persistenceId -> 1L))
      val planned = plan(Some(3L), None, None)

      val result = MigrationExecutor.execute(planned, writer)

      result.createdJournalHeads shouldBe 0
      result.updatedJournalHeads shouldBe 1
      result.raisedPersistenceIds shouldBe Seq(persistenceId)
      writer.head(persistenceId) shouldBe Some(SequenceNumber(3))
    }

    "leave a head that is already ahead alone" in {
      val writer  = new InMemoryHeadWriter(Map(persistenceId -> 5L))
      val planned = plan(Some(3L), None, None)

      val result = MigrationExecutor.execute(planned, writer)

      result.existingJournalHeads shouldBe 1
      writer.head(persistenceId) shouldBe Some(SequenceNumber(5))
    }

    "raise a stale head once and report it as existing on the second execution" in {
      val writer  = new InMemoryHeadWriter(Map(persistenceId -> 3L))
      val planned = plan(Some(6L), None, Some(3L))

      val first = MigrationExecutor.execute(planned, writer)
      first.updatedJournalHeads shouldBe 1
      first.raisedPersistenceIds shouldBe Seq(persistenceId)
      writer.head(persistenceId) shouldBe Some(SequenceNumber(6))

      val second = MigrationExecutor.execute(planned, writer)
      second.updatedJournalHeads shouldBe 0
      second.existingJournalHeads shouldBe 1
      writer.head(persistenceId) shouldBe Some(SequenceNumber(6))
    }

    "never lower a head that is ahead of the target" in {
      val writer  = new InMemoryHeadWriter(Map(persistenceId -> 9L))
      val planned = plan(Some(6L), None, Some(3L))

      val result = MigrationExecutor.execute(planned, writer)

      result.existingJournalHeads shouldBe 1
      writer.head(persistenceId) shouldBe Some(SequenceNumber(9))
    }

  }

}
