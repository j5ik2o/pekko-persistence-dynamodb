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
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.PersistenceId
import com.github.j5ik2o.pekko.persistence.dynamodb.state.schema.{
  StateRow,
  StateRowCodec,
  StateSchema,
  StateTableSpec
}
import com.github.j5ik2o.pekko.persistence.dynamodb.state.{
  PartitionKey,
  PartitionKeyResolver,
  SortKey,
  SortKeyResolver,
  TableName => StateTableName,
  TableNameResolver
}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

final class StateStoreSpec extends AnyFreeSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  implicit val system: ActorSystem        = ActorSystem("StateStoreSpec")
  implicit val materializer: Materializer = SystemMaterializer(system).materializer
  implicit val patience: PatienceConfig   = PatienceConfig(Span(10, Seconds), Span(100, Millis))

  private val schema = StateSchema(
    partitionKey = AttributeName("pkey"),
    sortKey = AttributeName("skey"),
    persistenceId = AttributeName("persistence-id"),
    revision = AttributeName("revision-nr"),
    deleted = AttributeName("deleted"),
    payload = AttributeName("payload"),
    serializerId = AttributeName("serializer-id"),
    serializerManifest = AttributeName("serializer-manifest"),
    ordering = AttributeName("ordering"),
    tags = AttributeName("tags")
  )

  private val tableNameResolver = new TableNameResolver {
    override def resolve(persistenceId: PersistenceId): StateTableName = StateTableName(persistenceId.asString)
  }

  private val partitionKeyResolver = new PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId): PartitionKey = PartitionKey("partition")
  }

  private val sortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId): SortKey = SortKey(persistenceId.asString)
  }

  private val tableSpec =
    StateTableSpec.create(schema, TableName("State"), tableNameResolver, partitionKeyResolver, sortKeyResolver)

  private val codec = new StateRowCodec(schema, tableSpec)

  private val row = StateRow(
    persistenceId = "sample",
    revision = 3L,
    payload = Array[Byte](1, 2, 3),
    serializerId = 7,
    serializerManifest = Some("manifest"),
    ordering = 1000L,
    tag = Some("tag")
  )

  private val item = codec.encode(row)

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  private class RecordingAdapter extends DdbClientAdapter {
    private var recorded = Vector.empty[Operation]

    def operations: Vector[Operation] = synchronized(recorded)

    def respond(operation: Operation): OperationResult = fail(s"unexpected operation: $operation")

    override def capabilities: CapabilityProfile = CapabilityProfile.V2

    override def run(operation: Operation): Source[OperationResult, NotUsed] = {
      synchronized { recorded :+= operation }
      Source.single(respond(operation))
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = Source.empty

    override def close(): Unit = ()
  }

  private def adapterReturning(result: OperationResult): RecordingAdapter =
    new RecordingAdapter {
      override def respond(operation: Operation): OperationResult = result
    }

  "StateStore" - {
    "reads a row from the table that the resolver names" in {
      val adapter = adapterReturning(OperationResult.Item(Some(item)))
      val store   = new StateStore(adapter, codec, consistentRead = true)

      val result = store.get(PersistenceId("sample")).runWith(Sink.head).futureValue

      adapter.operations.head shouldBe Operation.Get(
        TableName("sample"),
        RowKey(
          TableName("sample"),
          AttributeMap.of(schema.partitionKey -> DdbValue.Str("partition"), schema.sortKey -> DdbValue.Str("sample"))
        ),
        consistentRead = true
      )
      result.map(decoded => (decoded.persistenceId, decoded.revision, decoded.payload.toVector, decoded.tag)) shouldBe
      Some((row.persistenceId, row.revision, row.payload.toVector, row.tag))
    }

    "returns no row when the item is missing" in {
      val adapter = adapterReturning(OperationResult.Item(None))
      val store   = new StateStore(adapter, codec, consistentRead = false)

      store.get(PersistenceId("sample")).runWith(Sink.head).futureValue shouldBe None
    }

    "writes the row with its key" in {
      val adapter = adapterReturning(OperationResult.Written(1))
      val store   = new StateStore(adapter, codec, consistentRead = false)

      store.upsert(row).runWith(Sink.ignore).futureValue

      adapter.operations.head shouldBe Operation.Put(TableName("sample"), item, None)
    }

    "deletes the row by its key" in {
      val adapter = adapterReturning(OperationResult.Written(1))
      val store   = new StateStore(adapter, codec, consistentRead = false)

      store.delete(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.operations.head shouldBe Operation.Delete(
        TableName("sample"),
        RowKey(
          TableName("sample"),
          AttributeMap.of(schema.partitionKey -> DdbValue.Str("partition"), schema.sortKey -> DdbValue.Str("sample"))
        ),
        None
      )
    }
  }
}
