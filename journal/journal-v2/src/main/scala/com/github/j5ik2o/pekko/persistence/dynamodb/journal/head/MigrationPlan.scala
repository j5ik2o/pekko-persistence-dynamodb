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

/** Where the target sequence number of one persistence id comes from.
  *
  * The journal table and the snapshot table are a union, not alternatives: a persistence id appears when at least one
  * of them holds a row. `Both` keeps the two versions instead of collapsing them, so a plan says where its target came
  * from without another lookup.
  */
sealed trait MigrationSource {

  /** The highest sequence number the sources hold for the persistence id. */
  def target: SequenceNumber
}

object MigrationSource {

  final case class Journal(sequenceNr: SequenceNumber) extends MigrationSource {
    override def target: SequenceNumber = sequenceNr
  }

  final case class Snapshot(sequenceNr: SequenceNumber) extends MigrationSource {
    override def target: SequenceNumber = sequenceNr
  }

  final case class Both(journal: SequenceNumber, snapshot: SequenceNumber) extends MigrationSource {
    override def target: SequenceNumber = if (journal >= snapshot) journal else snapshot
  }

  /** The union member for one persistence id, or None when neither table holds a row for it. */
  def from(journal: Option[SequenceNumber], snapshot: Option[SequenceNumber]): Option[MigrationSource] =
    (journal, snapshot) match {
      case (Some(journalSequenceNr), Some(snapshotSequenceNr)) =>
        Some(Both(journalSequenceNr, snapshotSequenceNr))
      case (Some(journalSequenceNr), None)  => Some(Journal(journalSequenceNr))
      case (None, Some(snapshotSequenceNr)) => Some(Snapshot(snapshotSequenceNr))
      case (None, None)                     => None
    }

}

/** The difference between the current JournalHead table and the target the two migration sources describe.
  *
  * The plan is the only input of both the dry-run report and the executor, so the two cannot disagree about which
  * persistence ids need a create, a raise, or nothing.
  */
final case class MigrationPlan(entries: Vector[MigrationPlan.Entry], snapshotTableScanned: Boolean) {

  def scannedPersistenceIds: Int = entries.size

  def createdJournalHeads: Int = entries.count(_.needsCreate)

  def updatedJournalHeads: Int = entries.count(_.needsRaise)

  def existingJournalHeads: Int = entries.count(entry => !entry.needsCreate && !entry.needsRaise)

  def raisedPersistenceIds: Seq[PersistenceId] =
    entries.filter(_.needsRaise).map(_.persistenceId).sortBy(_.asString)

}

object MigrationPlan {

  /** One persistence id of the union and the work its target requires.
    *
    * `currentHead` is the head that existed when the plan was built. The executor still writes conditionally, so a plan
    * built before a concurrent write stays safe: a create that now finds an item falls through to a raise, and a raise
    * that now finds the target unchanged reports the item as existing.
    */
  final case class Entry(
      persistenceId: PersistenceId,
      source: MigrationSource,
      target: SequenceNumber,
      currentHead: Option[SequenceNumber]
  ) {

    def needsCreate: Boolean = currentHead.isEmpty

    def needsRaise: Boolean = currentHead.exists(_ < target)

  }

}
