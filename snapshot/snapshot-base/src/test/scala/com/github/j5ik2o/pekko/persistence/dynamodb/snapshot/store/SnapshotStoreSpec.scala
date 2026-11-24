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
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.duration.DurationInt

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

  private val writeBackoff: BackoffConfig =
    BackoffConfig(
      sourceConfig = ConfigFactory.empty(),
      enabled = false,
      minBackoff = 1.milli,
      maxBackoff = 5.millis,
      randomFactor = 0.0,
      maxRestarts = 3
    )

  private val noRestartBackoff: BackoffConfig = writeBackoff.copy(maxRestarts = 0)

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

  private final class StatefulDeleteAdapter(initialRows: Map[RowKey, AttributeMap]) extends RecordingAdapter {
    private var rows = initialRows

    def remainingRows: Map[RowKey, AttributeMap] = synchronized(rows)

    override def respond(operation: Operation): OperationResult = synchronized {
      operation match {
        case query: Operation.Query =>
          OperationResult.Items(rows.values.filter(_.get(query.hashKey._1).contains(query.hashKey._2)).toVector, None)
        case Operation.Delete(_, key, condition) =>
          if (condition.forall(holds(rows.get(key), _))) {
            rows -= key
            OperationResult.Written(1)
          } else OperationResult.ConditionRejected(Vector(ConditionFailure(0, "ConditionalCheckFailed")))
        case Operation.Put(table, item, condition) =>
          val key = RowKey(
            table,
            AttributeMap.of(
              schema.partitionKey -> item.get(schema.partitionKey).get,
              schema.sortKey      -> item.get(schema.sortKey).get
            )
          )
          if (condition.forall(holds(rows.get(key), _))) {
            rows += key -> item
            OperationResult.Written(1)
          } else OperationResult.ConditionRejected(Vector(ConditionFailure(0, "ConditionalCheckFailed")))
        case Operation.Get(_, key, _) => OperationResult.Item(rows.get(key))
        case other                    => fail(s"unexpected operation: $other")
      }
    }
  }

  /** Evaluates the conditions that the store sends: `attribute_not_exists`, equality, and `OR`. */
  private def holds(item: Option[AttributeMap], condition: Condition): Boolean = condition match {
    case Condition.AttributeNotExists(name) => item.forall(_.get(name).isEmpty)
    case Condition.Compare(Operand.Column(name), CompareOp.Eq, Operand.Literal(value)) =>
      item.flatMap(_.get(name)).contains(value)
    case Condition.Or(left, right) => holds(item, left) || holds(item, right)
    case other                     => fail(s"unexpected condition: $other")
  }

  private def sameRowOrNone(hash: AttributeName, persistenceId: String): Option[Condition] =
    Some(
      Condition.Or(
        Condition.AttributeNotExists(hash),
        Condition.Compare(
          Operand.Column(schema.persistenceId),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Str(persistenceId))
        )
      )
    )

  private final class FailingDeleteAdapter(rows: Vector[AttributeMap], failures: Int, failure: () => Throwable)
      extends DdbClientAdapter {
    private val attempts = new AtomicInteger(0)

    def deleteAttempts: Int = attempts.get()

    override def capabilities: CapabilityProfile = CapabilityProfile.V2

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case _: Operation.Query => Source.single(OperationResult.Items(rows, None))
      case _: Operation.Delete =>
        if (attempts.incrementAndGet() <= failures) {
          Source.failed(failure())
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
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

      store.save(row).runWith(Sink.ignore).futureValue

      adapter.operations should have size 1
      val put = adapter.operations.head.asInstanceOf[Operation.Put]
      put.table shouldBe TableName("Snapshot")
      put.item.get(schema.partitionKey) shouldBe Some(DdbValue.Str("sample-1"))
      put.item.get(schema.snapshot) shouldBe Some(DdbValue.Bin(Vector[Byte](1, 2, 3)))
    }

    "reads the latest row through the configured index" in {
      val adapter = adapterReturning(Vector(shardedItem))
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

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
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

      store.latest(PersistenceId("sample")).runWith(Sink.head).futureValue shouldBe None
    }

    "filters by the created column for a maximum timestamp" in {
      val adapter = adapterReturning(Vector.empty)
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

      store.forMaxTimestamp(PersistenceId("sample"), 1000L).runWith(Sink.head).futureValue shouldBe None

      val query = adapter.operations.head.asInstanceOf[Operation.Query]
      query.filter shouldBe Some(
        Condition.Compare(Operand.Column(schema.created), CompareOp.Le, Operand.Literal(DdbValue.Num(BigDecimal(1000))))
      )
    }

    Vector(
      ("deletes a found row when its stored key matches the current resolver", "sample-5", true),
      ("leaves an index-readable row with an old sort key until migration to the current resolver", "old-5", false)
    ).foreach { case (name, storedSortKey, deleted) =>
      name in {
        val storedItem = shardedItem.updated(schema.sortKey, DdbValue.Str(storedSortKey))
        val storedKey = RowKey(
          TableName("Snapshot"),
          AttributeMap.of(
            schema.partitionKey -> DdbValue.Str("sample-1"),
            schema.sortKey      -> DdbValue.Str(storedSortKey)
          )
        )
        val initialRows = Map(storedKey -> storedItem)
        val adapter     = new StatefulDeleteAdapter(initialRows)
        val store =
          new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

        val loaded = store.latest(PersistenceId("sample")).runWith(Sink.head).futureValue
        loaded.map(_.persistenceId) shouldBe Some(row.persistenceId)
        loaded.map(_.sequenceNumber) shouldBe Some(row.sequenceNumber)
        loaded.map(_.snapshot.toVector) shouldBe Some(row.snapshot.toVector)
        adapter.operations.head.asInstanceOf[Operation.Query].index shouldBe Some(IndexName("GetSnapshotRowsIndex"))

        store.delete(PersistenceId("sample"), SequenceNumber(5)).runWith(Sink.ignore).futureValue

        adapter.operations should have size 3
        adapter.operations.last shouldBe Operation.Delete(
          TableName("Snapshot"),
          RowKey(
            TableName("Snapshot"),
            AttributeMap.of(
              schema.partitionKey -> DdbValue.Str("sample-1"),
              schema.sortKey      -> DdbValue.Str("sample-5")
            )
          ),
          sameRowOrNone(schema.partitionKey, "sample")
        )
        adapter.remainingRows shouldBe (if (deleted) Map.empty[RowKey, AttributeMap] else initialRows)
      }
    }

    "deletes a single row by its identity key in the legacy format" in {
      val adapter = adapterReturning(Vector.empty)
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, legacy), consistentRead = false, 25, writeBackoff)

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
          sameRowOrNone(schema.persistenceId, "sample")
        )
      )
    }

    "deletes the found rows by their identity key in the legacy format" in {
      val adapter = adapterReturning(Vector(legacyItem))
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, legacy), consistentRead = false, 25, writeBackoff)

      store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.operations(1) shouldBe Operation.Delete(
        TableName("Snapshot"),
        RowKey(
          TableName("Snapshot"),
          AttributeMap.of(
            schema.persistenceId  -> DdbValue.Str("sample"),
            schema.sequenceNumber -> DdbValue.Num(BigDecimal(5))
          )
        ),
        sameRowOrNone(schema.persistenceId, "sample")
      )
    }

    "retries a failed delete with the backoff when the backoff is enabled" in {
      val adapter =
        new FailingDeleteAdapter(Vector(legacyItem), failures = 2, () => new IllegalStateException("a service error"))
      val store =
        new SnapshotStore(
          adapter,
          new SnapshotRowCodec(schema, legacy),
          consistentRead = false,
          25,
          writeBackoff.copy(enabled = true)
        )

      store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.deleteAttempts shouldBe 3
    }

    "does not retry a failed delete when the backoff is disabled" in {
      val permanent = new IllegalArgumentException("permanent")
      val adapter =
        new FailingDeleteAdapter(Vector(legacyItem), failures = Int.MaxValue, () => permanent)
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, legacy), consistentRead = false, 25, writeBackoff)

      val failure = store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).failed.futureValue

      failure should be theSameInstanceAs permanent
      adapter.deleteAttempts shouldBe 1
    }

    "stops retrying a failed delete after max-restarts when the backoff is enabled" in {
      val adapter =
        new FailingDeleteAdapter(Vector(legacyItem), failures = Int.MaxValue, () => new IllegalStateException("down"))
      val store =
        new SnapshotStore(
          adapter,
          new SnapshotRowCodec(schema, legacy),
          consistentRead = false,
          25,
          noRestartBackoff.copy(enabled = true)
        )

      store
        .deleteAll(PersistenceId("sample")).runWith(Sink.ignore).failed.futureValue shouldBe an[IllegalStateException]
      adapter.deleteAttempts shouldBe 1
    }

    "saves with a condition that the key holds no row or a row of the same persistence id" in {
      val adapter = adapterReturning(Vector.empty)
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

      store.save(row).runWith(Sink.ignore).futureValue

      adapter.operations.head.asInstanceOf[Operation.Put].condition shouldBe sameRowOrNone(
        schema.partitionKey,
        "sample"
      )
    }

    "refuses to save over the snapshot of another persistence id that shares the key, and keeps it" in {
      val sharedKey = new PartitionKeyResolver {
        override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
          PartitionKey("shared")
      }
      val seqOnly = new SortKeyResolver {
        override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
          SortKey(sequenceNumber.value.toString)
      }
      val spec =
        SnapshotTableSpec.sharded(schema, TableName("Snapshot"), IndexName("GetSnapshotRowsIndex"), sharedKey, seqOnly)
      val adapter = new StatefulDeleteAdapter(Map.empty)
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, spec), consistentRead = false, 25, writeBackoff)
      val other = row.copy(persistenceId = PersistenceId("other"), snapshot = Array[Byte](9))

      store.save(row).runWith(Sink.ignore).futureValue
      store.save(row.copy(snapshot = Array[Byte](4))).runWith(Sink.ignore).futureValue
      val failure = store.save(other).runWith(Sink.ignore).failed.futureValue

      failure shouldBe a[SnapshotStoreException.KeyConflict]
      val conflict = failure.asInstanceOf[SnapshotStoreException.KeyConflict]
      conflict.persistenceId shouldBe "other"
      conflict.conflictingPersistenceId shouldBe Some("sample")
      conflict.getMessage should include("issues/1009")
      adapter.remainingRows.values.map(_.get(schema.snapshot)) shouldBe Iterable(Some(DdbValue.Bin(Vector[Byte](4))))

      store.delete(PersistenceId("other"), SequenceNumber(5)).runWith(Sink.ignore).futureValue
      adapter.remainingRows should have size 1
    }

    "refuses to delete a row of another persistence id under the key it resolves to, and keeps that row" in {
      // The query for `sample` returns its own row, which an earlier release stored under another key, while the key
      // that the resolvers give `sample` now holds the snapshot of `other` (issue #1009).
      val keyOfSample = RowKey(
        TableName("Snapshot"),
        AttributeMap.of(schema.partitionKey -> DdbValue.Str("sample-1"), schema.sortKey -> DdbValue.Str("sample-5"))
      )
      val oldKeyOfSample = RowKey(
        TableName("Snapshot"),
        AttributeMap.of(schema.partitionKey -> DdbValue.Str("sample-1"), schema.sortKey -> DdbValue.Str("old-5"))
      )
      val rowOfOther  = shardedItem.updated(schema.persistenceId, DdbValue.Str("other"))
      val rowOfSample = shardedItem.updated(schema.sortKey, DdbValue.Str("old-5"))
      val adapter     = new StatefulDeleteAdapter(Map(keyOfSample -> rowOfOther, oldKeyOfSample -> rowOfSample))
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

      store.delete(PersistenceId("sample"), SequenceNumber(5)).runWith(Sink.ignore).futureValue

      adapter.operations.collect { case delete: Operation.Delete => delete } shouldBe Vector(
        Operation.Delete(TableName("Snapshot"), keyOfSample, sameRowOrNone(schema.partitionKey, "sample"))
      )
      adapter.remainingRows.get(keyOfSample) shouldBe Some(rowOfOther)
    }

    "writes nothing when the delete query returns no rows" in {
      val adapter = adapterReturning(Vector.empty)
      val store =
        new SnapshotStore(adapter, new SnapshotRowCodec(schema, sharded), consistentRead = false, 25, writeBackoff)

      store.deleteAll(PersistenceId("sample")).runWith(Sink.ignore).futureValue

      adapter.operations should have size 1
      adapter.operations.head shouldBe a[Operation.Query]
    }
  }
}
