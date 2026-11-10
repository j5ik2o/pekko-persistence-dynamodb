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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.store

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.dao.SnapshotRow
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.schema.{
  SnapshotRowCodec,
  SnapshotSchema,
  SnapshotTableSpec
}
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.{
  PartitionKey,
  PartitionKeyResolver,
  SortKey,
  SortKeyResolver
}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

final class SnapshotStoreSpec extends AnyFreeSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  implicit val system: ActorSystem        = ActorSystem("SnapshotStoreSpec")
  implicit val materializer: Materializer = SystemMaterializer(system).materializer
  implicit val patience: PatienceConfig   = PatienceConfig(Span(10, Seconds), Span(100, Millis))

  private val schema = SnapshotSchema(
    partitionKey = AttributeName("pkey"),
    sortKey = AttributeName("skey"),
    persistenceId = AttributeName("persistence-id"),
    sequenceNumber = AttributeName("sequence-nr"),
    snapshot = AttributeName("snapshot"),
    created = AttributeName("created")
  )

  private val partitionKeyResolver = new PartitionKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(s"${persistenceId.asString}-${sequenceNumber.value % 4}")
  }

  private val sortKeyResolver = new SortKeyResolver {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey(s"${persistenceId.asString}-${sequenceNumber.value}")
  }

  private val sharded =
    SnapshotTableSpec.sharded(
      schema,
      TableName("Snapshot"),
      IndexName("GetSnapshotRowsIndex"),
      partitionKeyResolver,
      sortKeyResolver
    )

  private val legacy = SnapshotTableSpec.legacy(schema, TableName("Snapshot"))

  private val row = SnapshotRow(
    persistenceId = PersistenceId("sample"),
    sequenceNumber = SequenceNumber(5),
    created = 1000L,
    snapshot = Array[Byte](1, 2, 3)
  )

  private val shardedItem = AttributeMap.of(
    schema.partitionKey   -> DdbValue.Str("sample-1"),
    schema.sortKey        -> DdbValue.Str("sample-5"),
    schema.persistenceId  -> DdbValue.Str("sample"),
    schema.sequenceNumber -> DdbValue.Num(BigDecimal(5)),
    schema.snapshot       -> DdbValue.Bin(Vector[Byte](1, 2, 3)),
    schema.created        -> DdbValue.Num(BigDecimal(1000))
  )

  private val legacyItem = AttributeMap.of(
    schema.persistenceId  -> DdbValue.Str("sample"),
    schema.sequenceNumber -> DdbValue.Num(BigDecimal(5)),
    schema.snapshot       -> DdbValue.Bin(Vector[Byte](1, 2, 3)),
    schema.created        -> DdbValue.Num(BigDecimal(1000))
  )

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

  private def adapterReturning(rows: Vector[AttributeMap]): RecordingAdapter =
    new RecordingAdapter {
      override def respond(operation: Operation): OperationResult = operation match {
        case _: Operation.Query      => OperationResult.Items(rows, None)
        case _: Operation.Put        => OperationResult.Written(1)
        case _: Operation.BatchWrite => OperationResult.Written(1)
        case _: Operation.Delete     => OperationResult.Written(1)
        case other                   => fail(s"unexpected operation: $other")
      }
    }

  private final class UnprocessedBatchAdapter(rows: Vector[AttributeMap], failures: Int) extends DdbClientAdapter {
    private val attempts = new AtomicInteger(0)

    def batchAttempts: Int = attempts.get()

    override def capabilities: CapabilityProfile = CapabilityProfile.V2

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case _: Operation.Query => Source.single(OperationResult.Items(rows, None))
      case _: Operation.BatchWrite =>
        if (attempts.incrementAndGet() <= failures) {
          Source.failed(new IllegalStateException("BatchWriteItem left 1 unprocessed items"))
        } else {
          Source.single(OperationResult.Written(1))
        }
      case other => Source.failed(new IllegalArgumentException(s"unexpected operation: $other"))
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = Source.empty

    override def close(): Unit = ()
  }

  "SnapshotStore" - {
    "writes the key and the row columns" in {
      val adapter = adapterReturning(Vector.empty)
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25)

      store.save(row).runWith(Sink.ignore).futureValue

      adapter.operations should have size 1
      val put = adapter.operations.head.asInstanceOf[Operation.Put]
      put.table shouldBe TableName("Snapshot")
      put.item.get(schema.partitionKey) shouldBe Some(DdbValue.Str("sample-1"))
      put.item.get(schema.snapshot) shouldBe Some(DdbValue.Bin(Vector[Byte](1, 2, 3)))
    }

    "reads the latest row through the configured index" in {
      val adapter = adapterReturning(Vector(shardedItem))
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25)

      val result = store.latest(PersistenceId("sample")).runWith(Sink.head).futureValue

      adapter.operations.head shouldBe Operation.Query(
        table = TableName("Snapshot"),
        index = Some(IndexName("GetSnapshotRowsIndex")),
        hashKey = (schema.persistenceId, DdbValue.Str("sample")),
        rangeKey = Some(
          (
            schema.sequenceNumber,
            SortKeyCondition.Between(DdbValue.Num(BigDecimal(0)), DdbValue.Num(BigDecimal(Long.MaxValue)))
          )
        ),
        limit = Some(1),
        scanForward = false
      )
      result.map(_.persistenceId) shouldBe Some(row.persistenceId)
      result.map(_.sequenceNumber) shouldBe Some(row.sequenceNumber)
    }

    "returns no row when the query returns none" in {
      val adapter = adapterReturning(Vector.empty)
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25)

      store.latest(PersistenceId("sample")).runWith(Sink.head).futureValue shouldBe None
    }

    "filters by the created column for a maximum timestamp" in {
      val adapter = adapterReturning(Vector.empty)
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25)

      store.forMaxTimestamp(PersistenceId("sample"), 1000L).runWith(Sink.head).futureValue shouldBe None

      val query = adapter.operations.head.asInstanceOf[Operation.Query]
      query.filter shouldBe Some(
        Condition.Compare(Operand.Column(schema.created), CompareOp.Le, Operand.Literal(DdbValue.Num(BigDecimal(1000))))
      )
    }

    "deletes the found rows by their sharded key" in {
      val adapter = adapterReturning(Vector(shardedItem))
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25)

      store.delete(PersistenceId("sample"), SequenceNumber(5)).runWith(Sink.ignore).futureValue

      adapter.operations should have size 2
      adapter.operations(1) shouldBe Operation.BatchWrite(
        TableName("Snapshot"),
        Vector.empty,
        Vector(
          RowKey(
            TableName("Snapshot"),
            AttributeMap.of(schema.partitionKey -> DdbValue.Str("sample-1"), schema.sortKey -> DdbValue.Str("sample-5"))
          )
        )
      )
    }

    "deletes a single row by its identity key in the legacy format" in {
      val adapter = adapterReturning(Vector.empty)
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, legacy), consistentRead = false, 25)

      store.delete(PersistenceId("sample"), SequenceNumber(5)).runWith(Sink.ignore).futureValue

      adapter.operations shouldBe Vector(
        Operation.Delete(
          TableName("Snapshot"),
          RowKey(
            TableName("Snapshot"),
            AttributeMap.of(
              schema.persistenceId  -> DdbValue.Str("sample"),
              schema.sequenceNumber -> DdbValue.Num(BigDecimal(5))
            )
          ),
          None
        )
      )
    }

    "deletes the found rows by their identity key in the legacy format" in {
      val adapter = adapterReturning(Vector(legacyItem))
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, legacy), consistentRead = false, 25)

      store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.operations(1) shouldBe Operation.BatchWrite(
        TableName("Snapshot"),
        Vector.empty,
        Vector(
          RowKey(
            TableName("Snapshot"),
            AttributeMap.of(
              schema.persistenceId  -> DdbValue.Str("sample"),
              schema.sequenceNumber -> DdbValue.Num(BigDecimal(5))
            )
          )
        )
      )
    }

    "retries a batch delete that DynamoDB leaves unprocessed" in {
      val adapter = new UnprocessedBatchAdapter(Vector(legacyItem), failures = 2)
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, legacy), consistentRead = false, 25)

      store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.batchAttempts shouldBe 3
    }

    "writes nothing when the delete query returns no rows" in {
      val adapter = adapterReturning(Vector.empty)
      val store   = new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25)

      store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.operations should have size 1
      adapter.operations.head shouldBe a[Operation.Query]
    }
  }
}
