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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.migration

import com.github.j5ik2o.pekko.persistence.dynamodb.migration.{ KeyForm, Placement, ResolverKeyPlan, StoredKey }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.migration.SnapshotKeyMigration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The keys that published releases wrote, as fixed values (review of #1025, F1 and F8).
  *
  * They were computed from the functions of the tags, not from this code base: `v1.0.2` to `v1.0.59` and `v1.3.0` (`git
  * show <tag>:journal/journal-base/.../journal/SortKeyResolver.scala`, `PartitionKeyResolver.scala`, and the snapshot
  * ones), with the default separator `-`, shard counts 1024 (journal, as in the test configuration) and 64 (snapshot,
  * the default). The shard is `md5(persistenceId.reverse) % shardCount + 1` in 40 digits in every release.
  *
  *   - Up to 1.0.59 (before issue #831), an id without the separator got `skey = "%019d".format(sequenceNr)`.
  *   - From 1.0.60 to 1.3.0, it got `${persistenceId}-${"%019d".format(sequenceNr)}`; an id with the separator got
  *     `${second part of the split}-${"%019d"}` and `pkey = ${first part}-${shard}`.
  */
final class ReleasedKeysSpec extends AnyWordSpec with Matchers {

  private val journalShard831    = "0000000000000000000000000000000000000389" // Plain123, 1024 shards
  private val snapshotShard831   = "0000000000000000000000000000000000000005" // Plain123, 64 shards
  private val journalShardOrder  = "0000000000000000000000000000000000000937" // Order-part-1, 1024 shards
  private val snapshotShardOrder = "0000000000000000000000000000000000000041" // Order-part-1, 64 shards

  private def plans(
      release130: (String, Long) => Option[StoredKey],
      plainText: (String, Long) => Option[StoredKey]
  ) = Seq(
    new ResolverKeyPlan(release130, plainText, KeyForm.PlainText, reverse = false),
    new ResolverKeyPlan(release130, plainText, KeyForm.Release130, reverse = false),
    new ResolverKeyPlan(release130, plainText, KeyForm.PlainText, reverse = true)
  )

  private val journal = (
    JournalKeyMigration.release130Key("-", 1024) _,
    JournalKeyMigration.plainTextKey("-", 1024) _,
    journalShard831,
    journalShardOrder
  )
  private val snapshot = (
    SnapshotKeyMigration.release130Key("-", 64) _,
    SnapshotKeyMigration.plainTextKey("-", 64) _,
    snapshotShard831,
    snapshotShardOrder
  )

  "the keys of 1.3.0" should {
    "be the keys that 1.3.0 wrote" in {
      Seq(journal, snapshot).foreach { case (release130, plainText, shard831, shardOrder) =>
        release130("Plain123", 1) shouldBe Some(StoredKey(shard831, "Plain123-0000000000000000001"))
        release130("Order-part-1", 1) shouldBe Some(StoredKey(s"Order-$shardOrder", "part-0000000000000000001"))
        plainText("Order-part-1", 1) shouldBe Some(StoredKey(s"Order-$shardOrder", "part-1-0000000000000000001"))
        plans(release130, plainText).head.placement(
          "Order-part-1",
          1,
          StoredKey(s"Order-$shardOrder", "part-0000000000000000001")
        ) shouldBe Placement.Move(StoredKey(s"Order-$shardOrder", "part-1-0000000000000000001"), KeyForm.Release130)
      }
    }
  }

  "the rows of issue #831, as 1.0.59 wrote them" should {
    "move to the key that both formats give, in both directions, also with the resolvers of 1.3.0" in {
      Seq(journal, snapshot).foreach { case (release130, plainText, shard831, _) =>
        Seq(1L -> "0000000000000000001", 2L -> "0000000000000000002").foreach { case (seq, written) =>
          val stored = StoredKey(shard831, written)
          val target = StoredKey(shard831, s"Plain123-$written")
          plans(release130, plainText).foreach { plan =>
            plan.placement("Plain123", seq, stored) shouldBe Placement.Move(target, KeyForm.Issue831)
          }
        }
      }
    }

    "not take the sequence number without zeros, which no default resolver wrote" in {
      Seq(journal, snapshot).foreach { case (release130, plainText, shard831, _) =>
        plans(release130, plainText).foreach { plan =>
          plan.placement("Plain123", 1, StoredKey(shard831, "1")) shouldBe a[Placement.Unmovable]
        }
      }
    }
  }
}
