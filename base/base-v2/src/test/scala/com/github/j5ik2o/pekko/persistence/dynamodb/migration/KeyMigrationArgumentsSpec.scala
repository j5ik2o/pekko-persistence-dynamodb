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

  // 2147483648 does not fit an Int; 1000000 is the most segments that a DynamoDB Scan takes (#1041)
  private val outOfRange    = Seq("0", "-1", "1001", "1000000", "1000001", "2147483648", "-2147483649")
  private val notANumber    = Seq("abc", "1.5", "1e3", "")
  private val countsInRange = Seq("1", "4", "999", "1000")

  "--segments" should {
    "be refused below 1, above 1000, and when it is not a whole number, for the moves and for --verify (#1041)" in {
      (outOfRange ++ notANumber).foreach { value =>
        withClue(value) {
          an[IllegalArgumentException] should be thrownBy MigrationOptions.parse(Seq("--segments", value))
          an[IllegalArgumentException] should be thrownBy MigrationOptions.parse(Seq("--verify", "--segments", value))
        }
      }
      Seq(0, -1, 1001, Int.MaxValue).foreach { value =>
        withClue(value) {
          an[IllegalArgumentException] should be thrownBy MigrationSettings(execute = false, segments = value)
          an[IllegalArgumentException] should be thrownBy VerifySettings(segments = value)
        }
      }
    }

    "say the range it accepts" in {
      val e = the[IllegalArgumentException] thrownBy MigrationOptions.parse(Seq("--segments", "1001"))
      e.getMessage should include("from 1 to 1000")
    }

    "be accepted from 1 to 1000" in {
      countsInRange.foreach { value =>
        withClue(value) {
          MigrationOptions.parse(Seq("--segments", value)).settings.segments shouldBe value.toInt
          MigrationOptions.parse(Seq("--verify", "--segments", value)).verify.map(_.segments) shouldBe Some(value.toInt)
        }
      }
    }
  }

  "--parallelism" should {
    "be refused below 1, above 1000, and when it is not a whole number (#1041)" in {
      (outOfRange ++ notANumber).foreach { value =>
        withClue(value) {
          an[IllegalArgumentException] should be thrownBy MigrationOptions.parse(Seq("--parallelism", value))
        }
      }
      Seq(0, -1, 1001, Int.MaxValue).foreach { value =>
        withClue(value) {
          an[IllegalArgumentException] should be thrownBy MigrationSettings(execute = false, parallelism = value)
        }
      }
    }

    "say the range it accepts" in {
      val e = the[IllegalArgumentException] thrownBy MigrationOptions.parse(Seq("--parallelism", "1001"))
      e.getMessage should include("from 1 to 1000")
    }

    "be accepted from 1 to 1000" in {
      countsInRange.foreach { value =>
        withClue(value) {
          MigrationOptions.parse(Seq("--parallelism", value)).settings.parallelism shouldBe value.toInt
        }
      }
    }
  }

  "the reason of a row under an unknown key" should {
    "name the default format locale as a possible cause (#1028)" in {
      val thai = Locale.forLanguageTag("th-TH-u-nu-thai")
      // the key of issue #831 that an application with Thai digits wrote
      val written = "%019d".formatLocal(thai, 1L)
      // The plan reads the key with the default format locale of the JVM, so the test runs with ASCII digits whatever
      // the locale of the JVM that runs it.
      val default = Locale.getDefault(Locale.Category.FORMAT)
      Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT)
      try {
        written should not be "%019d".format(1L)
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
      } finally Locale.setDefault(Locale.Category.FORMAT, default)
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
