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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class JournalHeadPreflightSpec extends AnyFreeSpec with Matchers {

  import JournalHeadPreflight._

  private val head    = TableStream("JournalHead", enabled = true, viewType = Some("NEW_IMAGE"))
  private val journal = TableStream("Journal", enabled = false, viewType = None)

  "reports no problem when the head stream carries the new image and the journal has no stream" in {
    problems(head, journal) shouldBe empty
  }

  "accepts NEW_AND_OLD_IMAGES on the head stream" in {
    problems(head.copy(viewType = Some("NEW_AND_OLD_IMAGES")), journal) shouldBe empty
  }

  "reports a disabled head stream" in {
    problems(head.copy(enabled = false), journal) shouldBe Vector(Problem.HeadStreamDisabled("JournalHead"))
  }

  "reports a head stream view type that does not carry the new image" in {
    problems(head.copy(viewType = Some("KEYS_ONLY")), journal) shouldBe
    Vector(Problem.HeadStreamViewTypeUnsupported("JournalHead", Some("KEYS_ONLY")))
  }

  "reports an enabled journal stream" in {
    problems(head, journal.copy(enabled = true)) shouldBe Vector(Problem.JournalStreamEnabled("Journal"))
  }

  "reports both mismatches at once" in {
    problems(head.copy(enabled = false), journal.copy(enabled = true)) shouldBe Vector(
      Problem.HeadStreamDisabled("JournalHead"),
      Problem.JournalStreamEnabled("Journal")
    )
  }

}
