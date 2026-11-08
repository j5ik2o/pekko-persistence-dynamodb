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

/** Builds a [[MigrationPlan]] from the versions the journal and the snapshot table hold and the heads that exist now.
  *
  * The function is pure: the caller scans the tables and reads the heads, and this object only computes the difference.
  * Reading the heads here would make the planner depend on DynamoDB, and the dry-run report and the executor would no
  * longer be able to share one plan.
  */
object MigrationPlanner {

  def plan(
      journal: Map[PersistenceId, SequenceNumber],
      snapshot: Option[Map[PersistenceId, SequenceNumber]],
      heads: Map[PersistenceId, SequenceNumber]
  ): MigrationPlan = {
    val snapshotVersions = snapshot.getOrElse(Map.empty[PersistenceId, SequenceNumber])
    val entries =
      unionSources(journal, snapshotVersions).toVector.sortBy(_._1.asString).map { case (persistenceId, source) =>
        MigrationPlan.Entry(persistenceId, source, source.target, heads.get(persistenceId))
      }
    MigrationPlan(entries, snapshot.isDefined)
  }

  /** Joins the two tables on the persistence id. A persistence id that appears in neither table is not a union member,
    * so it never reaches the plan even when a head for it exists.
    */
  private def unionSources(
      journal: Map[PersistenceId, SequenceNumber],
      snapshot: Map[PersistenceId, SequenceNumber]
  ): Map[PersistenceId, MigrationSource] = {
    val persistenceIds = journal.keySet ++ snapshot.keySet
    persistenceIds.flatMap { persistenceId =>
      MigrationSource
        .from(journal.get(persistenceId), snapshot.get(persistenceId))
        .map(source => persistenceId -> source)
    }.toMap
  }

}
