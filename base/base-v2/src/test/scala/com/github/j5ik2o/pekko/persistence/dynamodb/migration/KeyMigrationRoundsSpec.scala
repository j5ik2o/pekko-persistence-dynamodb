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
package com.github.j5ik2o.pekko.persistence.dynamodb.migration

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable
import scala.util.Random

/** `KeyMigration.Rounds` gives the rounds of `execute` and of the dry-run (#1028, review of #1044). */
final class KeyMigrationRoundsSpec extends AnyWordSpec with Matchers {

  private def key(n: Int): StoredKey = StoredKey(s"p$n", s"s$n")

  /** The rounds as `execute` computed them up to 47ee84ce: the whole of `pending`, grouped by target, each round. */
  private def reference(
      pending: mutable.LinkedHashMap[StoredKey, Candidate],
      reserved: collection.Set[StoredKey]
  ): Vector[Candidate] =
    pending.values
      .groupBy(_.to)
      .collect { case (to, group) if !reserved.contains(to) => group }
      .map(_.minBy(c => (c.from.partitionKey, c.from.sortKey)))
      .filterNot(c => pending.contains(c.to))
      .toVector
      .sortBy(c => (c.from.partitionKey, c.from.sortKey))

  private def random(seed: Long): Random = new Random(new java.util.SplittableRandom(seed).nextLong())

  "the rounds" should {
    "be those of the whole pending set, round by round, with targets reserved on the way (#1028)" in {
      var rounds = 0
      (0 until 2000).foreach { i =>
        val rnd   = random(0x10440000L + i)
        val size  = 2 + rnd.nextInt(14)
        val froms = rnd.shuffle((1 to size).toVector).take(1 + rnd.nextInt(size))
        val candidates =
          froms
            .map(f => Candidate(s"id$f", 1L, key(f), key(1 + rnd.nextInt(size)), KeyForm.Release130)).filter(c =>
              c.from != c.to
            )
        val pending  = mutable.LinkedHashMap(candidates.map(c => c.from -> c): _*)
        val reserved = mutable.HashSet.empty[StoredKey]
        val tested   = new KeyMigration.Rounds(candidates)
        var more     = true
        while (more) {
          val expected = reference(pending, reserved)
          val actual   = tested.next(reserved.contains)
          withClue(s"case $i, round $rounds, candidates ${candidates.map(_.describe)}: ")(actual shouldBe expected)
          // a row of the round may keep its target for itself (Failed, Gone in execute)
          expected.foreach { c =>
            if (rnd.nextInt(4) == 0) reserved += c.to
            pending.remove(c.from)
            tested.done(c)
          }
          tested.pending shouldBe pending
          rounds += 1
          more = expected.nonEmpty
        }
      }
      rounds should be >= 5000
    }

    "cost about one step per row when many rows need one target, and along a chain (review of #1044)" in {
      val n = 100000
      // every row needs key 0: one round per row, as in a --reverse of ids that share their key of 1.3.0
      val shared = (1 to n).map(i => Candidate(s"id$i", 1L, key(i), key(0), KeyForm.PlainText))
      // row i needs the key of row i + 1: one round per row
      val chain = (1 to n).map(i => Candidate(s"id$i", 1L, key(i), key(i + 1), KeyForm.Release130))
      Seq("shared" -> shared, "chain" -> chain).foreach { case (name, candidates) =>
        val started = System.nanoTime()
        val tested  = new KeyMigration.Rounds(candidates)
        var tried   = 0
        var ready   = tested.next(_ => false)
        while (ready.nonEmpty) {
          ready.foreach { c => tested.done(c); tried += 1 }
          ready = tested.next(_ => false)
        }
        val millis = (System.nanoTime() - started) / 1000000L
        info(s"$name: $n rows in $millis ms")
        tried shouldBe n
        // the whole pending set grouped each round took minutes for this many rows
        millis should be < 20000L
      }
    }
  }
}
