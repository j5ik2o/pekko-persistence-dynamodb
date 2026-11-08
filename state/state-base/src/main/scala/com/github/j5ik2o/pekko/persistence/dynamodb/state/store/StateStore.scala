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
package com.github.j5ik2o.pekko.persistence.dynamodb.state.store

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.PersistenceId
import com.github.j5ik2o.pekko.persistence.dynamodb.state.schema.{ StateRow, StateRowCodec }

final class StateStore(
    adapter: DdbClientAdapter,
    private val codec: StateRowCodec,
    consistentRead: Boolean
) {

  def get(persistenceId: PersistenceId): Source[Option[StateRow], NotUsed] = {
    val key = codec.key(persistenceId)
    adapter.run(Operation.Get(key.table, key, consistentRead)).flatMapConcat {
      case OperationResult.Item(None)      => Source.single(None)
      case OperationResult.Item(Some(row)) => decoded(row)
      case other                           => Source.failed(new IllegalStateException(s"unexpected get result: $other"))
    }
  }

  def upsert(row: StateRow): Source[Unit, NotUsed] = {
    val key = codec.key(PersistenceId(row.persistenceId))
    written(Operation.Put(key.table, codec.encode(row)))
  }

  def delete(persistenceId: PersistenceId): Source[Unit, NotUsed] = {
    val key = codec.key(persistenceId)
    written(Operation.Delete(key.table, key))
  }

  private def decoded(values: AttributeMap): Source[Option[StateRow], NotUsed] =
    codec.decode(values) match {
      case Right(row)  => Source.single(Some(row))
      case Left(error) => Source.failed(new IllegalArgumentException(s"cannot decode the state row: $error"))
    }

  private def written(operation: Operation): Source[Unit, NotUsed] =
    adapter.run(operation).flatMapConcat {
      case OperationResult.Written(_) => Source.single(())
      case other                      => Source.failed(new IllegalStateException(s"unexpected write result: $other"))
    }
}
