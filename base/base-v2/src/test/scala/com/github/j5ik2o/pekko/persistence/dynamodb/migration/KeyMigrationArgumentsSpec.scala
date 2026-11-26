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

import java.util.Locale

/** The argument checks and the texts of the key migration tools that need no table (issue #1028). */
final class KeyMigrationArgumentsSpec extends AnyWordSpec with Matchers {

  // 1e1000 is Infinity as a Double; 1e-11 made the interval of the rate limiter overflow (#1028)
  private val refused  = Seq("0", "-1", "1e-11", "0.000999", "Infinity", "-Infinity", "1e1000", "NaN")
  private val accepted = Seq("0.001", "0.5", "1e300")

  "--max-rate" should {
    "be refused below 0.001 and when it is not finite, for the moves and for --verify (#1028)" in {
      refused.foreach { value =>
        withClue(value) {
          an[IllegalArgumentException] should be thrownBy MigrationOptions.parse(Seq("--max-rate", value))
          an[IllegalArgumentException] should be thrownBy MigrationOptions.parse(Seq("--verify", "--max-rate", value))
          an[IllegalArgumentException] should be thrownBy MigrationSettings(
            execute = false,
            maxRate = Some(value.toDouble)
          )
          an[IllegalArgumentException] should be thrownBy VerifySettings(maxRate = Some(value.toDouble))
          an[IllegalArgumentException] should be thrownBy new KeyMigration.RateLimiter(value.toDouble)
        }
      }
    }

    "say the range it accepts" in {
      val e = the[IllegalArgumentException] thrownBy MigrationOptions.parse(Seq("--max-rate", "1e-11"))
      e.getMessage should include("at least 0.001")
    }

    "be accepted from 0.001 on while it is finite" in {
      accepted.foreach { value =>
        withClue(value) {
          MigrationOptions.parse(Seq("--max-rate", value)).settings.maxRate shouldBe Some(value.toDouble)
          MigrationOptions.parse(Seq("--verify", "--max-rate", value)).verify.flatMap(_.maxRate) shouldBe
          Some(value.toDouble)
          new KeyMigration.RateLimiter(value.toDouble)
        }
      }
    }

    "keep the next slot of the lowest rate a thousand seconds away" in {
      val limiter = new KeyMigration.RateLimiter(0.001)
      limiter.acquire()
      limiter.acquireBefore(System.nanoTime() + 1000000000L) shouldBe false
    }
  }

  "the reason of a row under an unknown key" should {
    "name the default format locale as a possible cause (#1028)" in {
      val thai = Locale.forLanguageTag("th-TH-u-nu-thai")
      // the key of issue #831 that an application with Thai digits wrote
      val written = "%019d".formatLocal(thai, 1L)
      written should not be "%019d".formatLocal(Locale.ROOT, 1L)
      val plan = new ResolverKeyPlan(
        (_, seq) => Some(StoredKey("pk", s"id-$seq")),
        (_, seq) => Some(StoredKey("pk", s"new-$seq")),
        KeyForm.PlainText,
        reverse = false
      )
      plan.placement("id", 1L, StoredKey("pk", written)) match {
        case Placement.Unmovable(reason) => reason should include("default format locale")
        case other                       => fail(s"expected Unmovable, got $other")
      }
    }
  }

  "the report of --verify" should {
    def report(shared: Seq[Seq[String]]): VerifyReport =
      VerifyReport(
        tableName = "journal",
        indexName = "rows",
        scannedRows = 1,
        notRows = 0,
        persistenceIds = 1,
        toMove = 0,
        notMovable = 0,
        examples = Seq.empty,
        indexProblems = Seq.empty,
        indexMismatch = Seq.empty,
        indexErrors = Seq.empty,
        stoppedBy = None,
        sharedKeyGroups = shared,
        usage = Usage(Map.empty, Map.empty),
        elapsedMillis = 0
      )
    val limit = "only the persistence ids that the table holds rows of"

    "say with --reverse that it checks the shared keys only for the ids with rows, matched or not (#1028)" in {
      Seq(report(Seq.empty), report(Seq(Seq("a", "b")))).foreach { r =>
        KeyVerification.render(r, "reverse", None).mkString("\n") should include(limit)
      }
    }

    "not say it without --reverse" in {
      KeyVerification.render(report(Seq.empty), "forward", None).mkString("\n") should not include limit
    }
  }
}
