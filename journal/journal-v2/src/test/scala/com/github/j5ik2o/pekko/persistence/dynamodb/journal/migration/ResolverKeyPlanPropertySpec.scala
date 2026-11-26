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

import scala.util.Random

/** The plans that the key migration tools build give each row one target, whatever key a copy is under (issue #1028).
  *
  * `JournalKeyMigration.plan` and `SnapshotKeyMigration.plan` are the only builders of a `KeyPlan` in the main code,
  * and both build a `ResolverKeyPlan` from `release130Key` and `plainTextKey`, functions of the persistence id, the
  * sequence number, the separator, and the shard count; `configuredForm` refuses every other key resolver. The property
  * P2 of `KeyMigrationDryRunPropertySpec` (design section 0.4) holds in full for such plans, so this spec checks the
  * two facts it rests on, on the real functions:
  *
  *   - the target of a row depends on its persistence id and sequence number only: every stored key that moves gives
  *     the same target;
  *   - no copy of a row is the target of another copy: under the target, the row is in place.
  */
final class ResolverKeyPlanPropertySpec extends AnyWordSpec with Matchers {

  private type KeyFunction = (String, Long) => Option[StoredKey]

  private final case class Builders(
      name: String,
      release130: (String, Int) => KeyFunction,
      plainText: (String, Int) => KeyFunction
  )

  private val builders = Seq(
    Builders(
      "journal",
      (separator, shards) => JournalKeyMigration.release130Key(separator, shards),
      (separator, shards) => JournalKeyMigration.plainTextKey(separator, shards)
    ),
    Builders(
      "snapshot",
      (separator, shards) => SnapshotKeyMigration.release130Key(separator, shards),
      (separator, shards) => SnapshotKeyMigration.plainTextKey(separator, shards)
    )
  )

  private val separators = Seq("-", "|", "::", ".", "-+")
  private val alphabet   = Seq("a", "b", "-", "|", ":", ".", "+", "0", "9", "é", "日", " ")

  private def random(seed: Long): Random = new Random(new java.util.SplittableRandom(seed).nextLong())

  "the plans of the key migration tools" should {
    "give one target per row, and never a target that another copy of the row is under (#1028)" in {
      var moves = 0
      (0 until 3000).foreach { i =>
        val rnd        = random(0x1028L + i)
        val build      = builders(rnd.nextInt(builders.size))
        val separator  = separators(rnd.nextInt(separators.size))
        val shards     = 1 + rnd.nextInt(4)
        val id         = Seq.fill(1 + rnd.nextInt(6))(alphabet(rnd.nextInt(alphabet.size))).mkString
        val seq        = Seq(0L, 1L, 2L, 99L, Long.MaxValue, rnd.nextLong() & Long.MaxValue)(rnd.nextInt(6))
        val release130 = build.release130(separator, shards)
        val plainText  = build.plainText(separator, shards)
        val configured = if (rnd.nextBoolean()) KeyForm.PlainText else KeyForm.Release130
        val reverse    = rnd.nextBoolean()
        val plan       = new ResolverKeyPlan(release130, plainText, configured, reverse)
        // the keys a copy of the row can be under: its own forms, the forms of other rows, and keys of no form
        val own        = Seq(release130(id, seq), plainText(id, seq)).flatten
        val ofIssue831 = own.map(k => StoredKey(k.partitionKey, "%019d".format(seq)))
        val others     = Seq(release130(id, seq + 1), plainText(id + "x", seq), release130(id + "x", seq)).flatten
        val unknown = Seq(StoredKey("other", "key"), StoredKey(own.headOption.fold("p")(_.partitionKey), s"$id-$seq"))
        val stored  = (own ++ ofIssue831 ++ others ++ unknown).distinct
        val targets = stored.flatMap { key =>
          plan.placement(id, seq, key) match {
            case Placement.Move(target, _) => Some(target)
            case _                         => None
          }
        }.distinct
        withClue(s"${build.name} separator=$separator shards=$shards id=$id seq=$seq $configured reverse=$reverse: ") {
          targets.size should be <= 1
          targets.foreach(target => plan.placement(id, seq, target) shouldBe Placement.InPlace)
        }
        moves += targets.size
      }
      // the cases reach rows that move
      moves should be >= 500
    }
  }
}
