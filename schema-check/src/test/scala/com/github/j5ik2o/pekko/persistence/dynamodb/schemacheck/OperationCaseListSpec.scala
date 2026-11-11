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
package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ Operation, TransactItem }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal.{ RenderedOperation, RenderedTransactItem }
import java.lang.reflect.Modifier
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Checks the closed sets in each root's compiled artifact. Known leaves must remain final so descendants cannot escape
  * that artifact. This does not enumerate arbitrary classes elsewhere in the JVM.
  */
final class OperationCaseListSpec extends AnyFreeSpec with Matchers {
  private def check(root: Class[_], known: Set[Class[_]]): Unit = {
    CompiledSubtypes.of(root) shouldBe known
    known.foreach { leaf =>
      withClue(s"${leaf.getName} must remain final: ") {
        Modifier.isFinal(leaf.getModifiers) shouldBe true
      }
    }
  }

  "the Operation set has exactly the known cases" in {
    check(
      classOf[Operation],
      Set[Class[_]](
        classOf[Operation.Put],
        classOf[Operation.Get],
        classOf[Operation.Delete],
        classOf[Operation.Update],
        classOf[Operation.Query],
        classOf[Operation.BatchWrite],
        classOf[Operation.TransactWrite]
      )
    )
  }

  "the TransactItem set has exactly the known cases" in {
    check(
      classOf[TransactItem],
      Set[Class[_]](classOf[TransactItem.Put], classOf[TransactItem.Delete], classOf[TransactItem.Update])
    )
  }

  "the RenderedOperation set has exactly the known cases" in {
    check(
      classOf[RenderedOperation],
      Set[Class[_]](
        classOf[RenderedOperation.Put],
        classOf[RenderedOperation.Get],
        classOf[RenderedOperation.Delete],
        classOf[RenderedOperation.Update],
        classOf[RenderedOperation.Query],
        classOf[RenderedOperation.BatchWrite],
        classOf[RenderedOperation.TransactWrite]
      )
    )
  }

  "the RenderedTransactItem set has exactly the known cases" in {
    check(
      classOf[RenderedTransactItem],
      Set[Class[_]](
        classOf[RenderedTransactItem.Put],
        classOf[RenderedTransactItem.Delete],
        classOf[RenderedTransactItem.Update]
      )
    )
  }
}
