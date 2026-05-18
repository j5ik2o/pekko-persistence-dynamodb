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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

final class SortKeyResolverSpec extends AnyFreeSpec with Matchers {

  "PersistenceIdWithSeqNr" - {
    "keeps the existing body-based key when persistence-id contains the separator" in {
      SortKeyResolver.persistenceIdWithSeqNrSKey(
        PersistenceId("counter-875e6ce0425e4d2b8203f3b44b9b531a"),
        SequenceNumber(1),
        PersistenceId.Separator
      ) shouldBe "875e6ce0425e4d2b8203f3b44b9b531a-0000000000000000001"
    }

    "includes the full persistence-id when persistence-id does not contain the separator" in {
      val sequenceNumber = SequenceNumber(1)

      SortKeyResolver.persistenceIdWithSeqNrSKey(
        PersistenceId("01HZX6K5VJAS2Y0P7FNKX5A2A1"),
        sequenceNumber,
        PersistenceId.Separator
      ) shouldBe "01HZX6K5VJAS2Y0P7FNKX5A2A1-0000000000000000001"

      SortKeyResolver.persistenceIdWithSeqNrSKey(
        PersistenceId("01HZX6K5VJAS2Y0P7FNKX5A2A2"),
        sequenceNumber,
        PersistenceId.Separator
      ) shouldBe "01HZX6K5VJAS2Y0P7FNKX5A2A2-0000000000000000001"
    }
  }

}
