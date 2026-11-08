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

/** Applies a [[MigrationPlan]] through a [[MigrationExecutor.HeadWriter]].
  *
  * The executor owns the convergence rule, not the storage calls. Each entry is applied by destination, not by the
  * action the plan classified: a planned create becomes a conditional create, and a create that finds the item already
  * there falls through to a conditional raise. A second run of the same plan therefore finds every condition already
  * satisfied and reports the entries as existing.
  *
  * The storage calls sit behind a trait so that this file, and the planner, stay free of the AWS SDK. That keeps the
  * module boundary allowlist from growing and lets the convergence rule run as a pure unit test.
  */
object MigrationExecutor {

  /** The two conditional writes of the JournalHead migration.
    *
    * Both methods are conditional at the storage layer and return whether this call changed the item. They return
    * false, rather than throw, when the condition no longer holds, because a lost race with another migration run is
    * the expected state and not an error.
    */
  trait HeadWriter {

    def putIfAbsent(persistenceId: PersistenceId, target: SequenceNumber): Boolean

    def raiseIfBehind(persistenceId: PersistenceId, target: SequenceNumber): Boolean

  }

  final case class Result(
      createdJournalHeads: Int,
      updatedJournalHeads: Int,
      existingJournalHeads: Int,
      raisedPersistenceIds: Seq[PersistenceId]
  )

  def execute(plan: MigrationPlan, writer: HeadWriter): Result = {
    var created  = 0
    var updated  = 0
    var existing = 0
    val raised   = Vector.newBuilder[PersistenceId]
    plan.entries.foreach { entry =>
      entry.currentHead match {
        case None =>
          if (writer.putIfAbsent(entry.persistenceId, entry.target)) {
            created += 1
          } else if (writer.raiseIfBehind(entry.persistenceId, entry.target)) {
            updated += 1
            raised += entry.persistenceId
          } else {
            existing += 1
          }
        case Some(current) if current < entry.target =>
          if (writer.raiseIfBehind(entry.persistenceId, entry.target)) {
            updated += 1
            raised += entry.persistenceId
          } else {
            existing += 1
          }
        case Some(_) =>
          existing += 1
      }
    }
    Result(created, updated, existing, raised.result().sortBy(_.asString))
  }

}
