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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.store

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.config.BackoffConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeMap,
  AttributeName,
  AttributeUpdate,
  BatchWriteUnprocessedItemsException,
  CapabilityProfile,
  CompareOp,
  Condition,
  ConditionFailure,
  DdbClientAdapter,
  DdbValue,
  Operand,
  Operation,
  OperationResult,
  TableName,
  TransactItem
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalHeadColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  JournalHeadAsyncUpdateEventUnsupportedException,
  JournalHeadConcurrentUpdateException,
  JournalHeadInvalidWriteException,
  JournalHeadItemTooLargeException,
  JournalHeadJournalRowTooLargeException,
  JournalHeadMissingException,
  JournalHeadRowUpdateRejectedException,
  JournalRowUpdateRejection
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema.JournalTestSupport
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ PartitionKey, PartitionKeyResolver }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._

final class JournalRowStoreSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  import JournalTestSupport._

  private implicit val system: ActorSystem        = ActorSystem("journal-row-store-spec")
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val persistenceId = PersistenceId("account-1")

  private final case class Fixture(adapter: InMemoryDdbAdapter, store: JournalRowStore)

  /** Fails the first `failures` batch writes with `failure`, then delegates. */
  private final class FlakyBatchAdapter(
      delegate: InMemoryDdbAdapter,
      failures: Int,
      failure: () => Throwable = () => new BatchWriteUnprocessedItemsException(1)
  ) extends DdbClientAdapter {
    private val attempts = new AtomicInteger(0)

    def batchAttempts: Int = attempts.get()

    override val capabilities: CapabilityProfile = delegate.capabilities

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case _: Operation.BatchWrite =>
        if (attempts.incrementAndGet() <= failures) Source.failed(failure()) else delegate.run(operation)
      case _ =>
        delegate.run(operation)
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = delegate.queryAll(query)

    override def close(): Unit = delegate.close()
  }

  /** Answers every transactional write with the next scripted result and records the transactions it received.
    * `onTransaction` runs after a transaction is recorded, so a test can change the data the way a concurrent writer
    * would before the store reads again.
    */
  private final class ScriptedTransactionAdapter(
      delegate: InMemoryDdbAdapter,
      results: Seq[OperationResult],
      onTransaction: Int => Unit
  ) extends DdbClientAdapter {
    private val pending = scala.collection.mutable.Queue(results: _*)
    val transactions: scala.collection.mutable.ArrayBuffer[Operation.TransactWrite] =
      scala.collection.mutable.ArrayBuffer.empty

    override val capabilities: CapabilityProfile = delegate.capabilities

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case transaction: Operation.TransactWrite =>
        transactions += transaction
        onTransaction(transactions.size)
        Source.single(pending.dequeue())
      case _ =>
        delegate.run(operation)
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = delegate.queryAll(query)

    override def close(): Unit = delegate.close()
  }

  private def newFixture(
      queryBatchSize: Int = 2,
      headConfig: Option[JournalHeadConfig] = None
  ): Fixture = {
    val keyColumns = headConfig match {
      case None => Map(schema.table -> Vector(schema.partitionKey, schema.sortKey))
      case Some(head) =>
        Map(
          schema.table              -> Vector(schema.partitionKey, schema.sortKey),
          TableName(head.tableName) -> Vector(AttributeName(head.columnsDefConfig.persistenceIdColumnName))
        )
    }
    val adapter = new InMemoryDdbAdapter(keyColumns)
    val store = new JournalRowStore(
      schema = schema,
      partitionKeyResolver = new ShardedPartitionKeyResolver(4),
      sortKeyResolver = sortKeyResolver,
      adapter = adapter,
      queryBatchSize = queryBatchSize,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = disabledBackoff,
      headConfig = headConfig
    )
    Fixture(adapter, store)
  }

  private def headModeConfig: JournalHeadConfig = JournalHeadConfig(
    enabled = true,
    tableName = "JournalHead",
    columnsDefConfig = JournalHeadColumnsDefConfig(
      persistenceIdColumnName = "persistence-id",
      sequenceNrColumnName = "sequence-nr",
      eventsColumnName = "events",
      eventSequenceNrColumnName = "sequence-nr",
      eventMessageColumnName = "message"
    )
  )

  private val fastBackoff: BackoffConfig =
    disabledBackoff.copy(minBackoff = 1.millis, maxBackoff = 2.millis)

  private def storeWith(adapter: DdbClientAdapter, writeBackoffConfig: BackoffConfig): JournalRowStore =
    new JournalRowStore(
      schema = schema,
      partitionKeyResolver = new ShardedPartitionKeyResolver(4),
      sortKeyResolver = sortKeyResolver,
      adapter = adapter,
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = writeBackoffConfig,
      headConfig = None
    )

  private def run[T](source: Source[T, NotUsed]): Vector[T] =
    Await.result(
      source.runWith(Sink.fold[Vector[T], T](Vector.empty[T])((accumulated, element) => accumulated :+ element)),
      30.seconds
    )

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds)
    super.afterAll()
  }

  "write reads back the row it stored" in {
    val fixture = newFixture()
    val row = JournalTestSupport.row(
      persistenceId,
      1L,
      "payload",
      ordering = 5L,
      tags = Some("tag")
    )

    run(fixture.store.write(Seq(row))) shouldBe Vector(1L)

    val decoded = run(fixture.store.read(persistenceId, None, SequenceNumber(3), None, Some(false)))
    decoded.map(_.sequenceNumber.value) shouldBe Vector(1L)
    decoded.head.message.toSeq shouldBe row.message.toSeq
    decoded.head.ordering shouldBe 5L
    decoded.head.tags shouldBe Some("tag")
  }

  "read pages through the rows and honors the maximum" in {
    val fixture = newFixture(queryBatchSize = 2)
    run(
      fixture.store.write(
        (1L to 5L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))
      )
    )

    run(fixture.store.read(persistenceId, None, SequenceNumber(5), None, None))
      .map(_.sequenceNumber.value) shouldBe (1L to 5L).toVector

    run(fixture.store.read(persistenceId, Some(SequenceNumber(2)), SequenceNumber(5), Some(2), None))
      .map(_.sequenceNumber.value) shouldBe Vector(2L, 3L)
  }

  "highestSequenceNr returns the last sequence number" in {
    val fixture = newFixture()
    run(
      fixture.store.write(
        (1L to 5L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))
      )
    )

    run(fixture.store.highestSequenceNr(persistenceId, None, None)) shouldBe Vector(Some(5L))
    run(fixture.store.highestSequenceNr(persistenceId, Some(SequenceNumber(4)), None)) shouldBe Vector(Some(5L))
    run(fixture.store.highestSequenceNr(persistenceId, Some(SequenceNumber(6)), None)) shouldBe Vector(None)
  }

  "delete removes the row of the given key" in {
    val fixture = newFixture()
    run(
      fixture.store.write(
        (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))
      )
    )

    run(fixture.store.delete(Seq(PersistenceIdWithSeqNr(persistenceId, SequenceNumber(2))))) shouldBe Vector(1L)

    run(fixture.store.read(persistenceId, None, SequenceNumber(3), None, None))
      .map(_.sequenceNumber.value) shouldBe Vector(1L, 3L)
  }

  "update changes the message, the ordering, the deleted flag, and the tags" in {
    val fixture = newFixture()
    run(fixture.store.write(Seq(JournalTestSupport.row(persistenceId, 1L, "old", ordering = 1L))))

    run(
      fixture.store.update(
        JournalTestSupport.row(
          persistenceId,
          1L,
          "new",
          deleted = true,
          ordering = 9L,
          tags = Some("tag")
        )
      )
    )

    val decoded = run(fixture.store.read(persistenceId, None, SequenceNumber(1), None, None)).head
    decoded.message.toSeq shouldBe JournalTestSupport.row(persistenceId, 1L, "new").message.toSeq
    decoded.ordering shouldBe 9L
    decoded.deleted shouldBe true
    decoded.tags shouldBe Some("tag")
  }

  "update without head mode refuses a row that does not exist and does not create it" in {
    val fixture = newFixture()

    val failure = updateFailure(fixture.store)

    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure
      .asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDoesNotExist
    run(fixture.store.read(persistenceId, None, SequenceNumber(2), None, None)) shouldBe empty
  }

  "update without head mode refuses a soft deleted row and keeps it deleted and unchanged" in {
    val fixture = newFixture()
    run(fixture.store.write(Seq(JournalTestSupport.row(persistenceId, 2L, "old2", deleted = true, ordering = 1L))))

    val failure = updateFailure(fixture.store)

    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDeleted
    val stored = run(fixture.store.read(persistenceId, None, SequenceNumber(2), None, None)).head
    stored.deleted shouldBe true
    stored.message.toSeq shouldBe JournalTestSupport.row(persistenceId, 2L, "old2").message.toSeq
  }

  "update without head mode sends the update under the condition that the row exists, is of the persistence id, and is not deleted" in {
    val recorded = scala.collection.mutable.ArrayBuffer.empty[Operation.Update]
    val inMemory = newFixture()
    val recording = new DdbClientAdapter {
      override val capabilities: CapabilityProfile = inMemory.adapter.capabilities
      override def run(operation: Operation): Source[OperationResult, NotUsed] = {
        operation match { case update: Operation.Update => recorded += update; case _ => () }
        inMemory.adapter.run(operation)
      }
      override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = inMemory.adapter.queryAll(query)
      override def close(): Unit                                                   = ()
    }
    val store = storeWith(recording, disabledBackoff)
    run(store.write(Seq(JournalTestSupport.row(persistenceId, 2L, "old2", ordering = 1L))))

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    recorded.map(_.condition) shouldBe Vector(
      Some(
        Condition.And(
          Condition.And(
            Condition.AttributeExists(schema.partitionKey),
            Condition.Compare(
              Operand.Column(schema.persistenceId),
              CompareOp.Eq,
              Operand.Literal(DdbValue.Str(persistenceId.asString))
            )
          ),
          Condition.Not(
            Condition.Compare(Operand.Column(schema.deleted), CompareOp.Eq, Operand.Literal(DdbValue.Bool(true)))
          )
        )
      )
    )
  }

  "update without head mode tries again when the condition failed but the row is there and not deleted" in {
    val inMemory   = newFixture()
    val rejections = new AtomicInteger(1)
    val racing = new DdbClientAdapter {
      override val capabilities: CapabilityProfile = inMemory.adapter.capabilities
      override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
        case _: Operation.Update if rejections.getAndDecrement() > 0 =>
          Source.single(OperationResult.ConditionRejected(Vector(ConditionFailure(0, "ConditionalCheckFailed"))))
        case _ => inMemory.adapter.run(operation)
      }
      override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = inMemory.adapter.queryAll(query)
      override def close(): Unit                                                   = ()
    }
    val store = storeWith(racing, disabledBackoff)
    run(store.write(Seq(JournalTestSupport.row(persistenceId, 2L, "old2", ordering = 1L))))

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    val stored = run(store.read(persistenceId, None, SequenceNumber(2), None, None)).head
    stored.message.toSeq shouldBe JournalTestSupport.row(persistenceId, 2L, "new2").message.toSeq
  }

  "update without head mode fails with a typed conflict when the row keeps changing" in {
    val inMemory = newFixture()
    val attempts = new AtomicInteger(0)
    val racing = new DdbClientAdapter {
      override val capabilities: CapabilityProfile = inMemory.adapter.capabilities
      override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
        case _: Operation.Update =>
          attempts.incrementAndGet()
          Source.single(OperationResult.ConditionRejected(Vector(ConditionFailure(0, "ConditionalCheckFailed"))))
        case _ => inMemory.adapter.run(operation)
      }
      override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = inMemory.adapter.queryAll(query)
      override def close(): Unit                                                   = ()
    }
    val store = storeWith(racing, disabledBackoff)
    run(store.write(Seq(JournalTestSupport.row(persistenceId, 2L, "old2", ordering = 1L))))

    updateFailure(store) shouldBe a[JournalHeadConcurrentUpdateException]
    attempts.get() shouldBe 3
    val stored = run(store.read(persistenceId, None, SequenceNumber(2), None, None)).head
    stored.message.toSeq shouldBe JournalTestSupport.row(persistenceId, 2L, "old2").message.toSeq
  }

  private def rows3 = (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

  /** Records every operation and fails the first `failures` PutItem requests with `failure`. */
  private final class RecordingAdapter(
      delegate: InMemoryDdbAdapter,
      failures: Int = 0,
      failure: () => Throwable = () => new IllegalStateException("a service error")
  ) extends DdbClientAdapter {
    private val putAttempts = new AtomicInteger(0)
    val operations: java.util.concurrent.ConcurrentLinkedQueue[Operation] =
      new java.util.concurrent.ConcurrentLinkedQueue[Operation]()

    def puts: Int = putAttempts.get()

    override val capabilities: CapabilityProfile = delegate.capabilities

    override def run(operation: Operation): Source[OperationResult, NotUsed] = {
      operations.add(operation)
      operation match {
        case _: Operation.Put if putAttempts.incrementAndGet() <= failures => Source.failed(failure())
        case _                                                             => delegate.run(operation)
      }
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = delegate.queryAll(query)

    override def close(): Unit = delegate.close()
  }

  private def sameRowOrNone(id: PersistenceId): Condition =
    Condition.Or(
      Condition.AttributeNotExists(schema.partitionKey),
      Condition.Compare(Operand.Column(schema.persistenceId), CompareOp.Eq, Operand.Literal(DdbValue.Str(id.asString)))
    )

  /** A store whose partition key is the same for every persistence id, so `account-a` and `account-b` share a key at
    * each sequence number, as two ids do in issue #1009.
    */
  private def sharedKeyStore(adapter: DdbClientAdapter): JournalRowStore =
    new JournalRowStore(
      schema = schema,
      partitionKeyResolver = new PartitionKeyResolver {
        override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
          PartitionKey("shared")
      },
      sortKeyResolver = sortKeyResolver,
      adapter = adapter,
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = disabledBackoff,
      headConfig = None
    )

  private val idA = PersistenceId("account-a")
  private val idB = PersistenceId("account-b")

  "write sends one conditional PutItem per row and no BatchWriteItem" in {
    val adapter = new RecordingAdapter(newFixture().adapter)
    val store   = storeWith(adapter, disabledBackoff)
    val rows    = (1L to 30L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

    run(store.write(rows)) shouldBe Vector(30L)

    val operations = adapter.operations.toArray.toVector
    operations.collect { case batch: Operation.BatchWrite => batch } shouldBe empty
    val puts = operations.collect { case put: Operation.Put => put }
    puts.size shouldBe 30
    puts.map(_.condition).distinct shouldBe Vector(Some(sameRowOrNone(persistenceId)))
    run(store.read(persistenceId, None, SequenceNumber(30), None, None)).map(_.sequenceNumber.value) shouldBe
    (1L to 30L).toVector
  }

  "write refuses a row whose key holds an event of another persistence id, and keeps that event" in {
    val fixture = newFixture()
    val store   = sharedKeyStore(fixture.adapter)
    run(store.write(Seq(JournalTestSupport.row(idA, 1L, "a1"))))

    val failure =
      Await.result(store.write(Seq(JournalTestSupport.row(idB, 1L, "b1"))).runWith(Sink.ignore).failed, 30.seconds)

    failure shouldBe a[JournalRowStoreException.KeyConflict]
    val conflict = failure.asInstanceOf[JournalRowStoreException.KeyConflict]
    conflict.persistenceId shouldBe "account-b"
    conflict.sequenceNr shouldBe 1L
    conflict.conflictingPersistenceId shouldBe Some("account-a")
    conflict.getMessage should include("issues/1009")
    run(store.read(idA, None, SequenceNumber(1), None, None)).map(r => new String(r.message, "UTF-8")) shouldBe
    Vector("a1")
    run(store.read(idB, None, SequenceNumber(1), None, None)) shouldBe empty
  }

  "write refuses a multi-row write when one row conflicts, and leaves none of its new rows" in {
    val fixture = newFixture()
    val store   = sharedKeyStore(fixture.adapter)
    run(store.write(Seq(JournalTestSupport.row(idA, 2L, "a2"))))

    val failure = Await.result(
      store.write((1L to 3L).map(n => JournalTestSupport.row(idB, n, s"b$n"))).runWith(Sink.ignore).failed,
      30.seconds
    )

    failure shouldBe a[JournalRowStoreException.KeyConflict]
    failure.asInstanceOf[JournalRowStoreException.KeyConflict].sequenceNr shouldBe 2L
    run(store.read(idA, None, SequenceNumber(3), None, None)).map(_.sequenceNumber.value) shouldBe Vector(2L)
    run(store.read(idB, None, SequenceNumber(3), None, None)) shouldBe empty
    run(store.highestSequenceNr(idB, None, None)) shouldBe Vector(None)
  }

  "write accepts a rewrite of an event of the same persistence id, such as a soft delete" in {
    val fixture = newFixture()
    val store   = sharedKeyStore(fixture.adapter)
    run(store.write(Seq(JournalTestSupport.row(idA, 1L, "a1"))))

    run(store.write(Seq(JournalTestSupport.row(idA, 1L, "a1", deleted = true)))) shouldBe Vector(1L)

    run(store.read(idA, None, SequenceNumber(1), None, None)).map(_.deleted) shouldBe Vector(true)
  }

  "delete leaves a key that holds the row of another persistence id" in {
    val fixture = newFixture()
    val store   = sharedKeyStore(fixture.adapter)
    run(store.write(Seq(JournalTestSupport.row(idA, 1L, "a1"), JournalTestSupport.row(idA, 2L, "a2"))))

    run(store.delete(Seq(PersistenceIdWithSeqNr(idB, SequenceNumber(1)))))
    run(
      store.delete(Seq(PersistenceIdWithSeqNr(idB, SequenceNumber(1)), PersistenceIdWithSeqNr(idB, SequenceNumber(2))))
    )

    run(store.read(idA, None, SequenceNumber(2), None, None)).map(_.sequenceNumber.value) shouldBe Vector(1L, 2L)
  }

  "update without head mode refuses a key that holds the row of another persistence id, and keeps that row" in {
    val fixture = newFixture()
    val store   = sharedKeyStore(fixture.adapter)
    run(store.write(Seq(JournalTestSupport.row(idA, 1L, "a1"))))

    val failure =
      Await.result(store.update(JournalTestSupport.row(idB, 1L, "b1")).runWith(Sink.head).failed, 30.seconds)

    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure.getMessage should include("does not exist")
    run(store.read(idA, None, SequenceNumber(1), None, None)).map(r => new String(r.message, "UTF-8")) shouldBe
    Vector("a1")
  }

  "do not retry a row write that failed when the backoff is disabled" in {
    val adapter = new RecordingAdapter(newFixture().adapter, failures = 1)
    val store   = storeWith(adapter, disabledBackoff)

    val failure = Await.result(store.write(Seq(rows3.head)).runWith(Sink.ignore).failed, 30.seconds)

    failure shouldBe an[IllegalStateException]
    adapter.puts shouldBe 1
  }

  "restart a row write that failed for any reason when the backoff is enabled" in {
    val fixture = newFixture()
    val adapter = new RecordingAdapter(fixture.adapter, failures = 2)
    val store   = storeWith(adapter, fastBackoff.copy(enabled = true))

    run(store.write(rows3)) shouldBe Vector(3L)
    adapter.puts shouldBe 5
    run(store.read(persistenceId, None, SequenceNumber(3), None, None)).map(_.sequenceNumber.value) shouldBe
    Vector(1L, 2L, 3L)
  }

  /** Fails every single-row write after `attemptDuration`, which is longer than `min-backoff`. */
  private final class SlowFailingWriteAdapter(delegate: InMemoryDdbAdapter, attemptDuration: FiniteDuration)
      extends DdbClientAdapter {
    private val attempts = new AtomicInteger(0)

    def writeAttempts: Int = attempts.get()

    override val capabilities: CapabilityProfile = delegate.capabilities

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case _: Operation.Put | _: Operation.Delete =>
        attempts.incrementAndGet()
        Source
          .single(()).initialDelay(attemptDuration).flatMapConcat(_ => Source.failed(new IllegalStateException("slow")))
      case _ => delegate.run(operation)
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = delegate.queryAll(query)

    override def close(): Unit = delegate.close()
  }

  private def slowBackoffOf(maxRestarts: Int): BackoffConfig =
    fastBackoff.copy(
      enabled = true,
      minBackoff = 20.millis,
      maxBackoff = 30.millis,
      randomFactor = 0.0,
      maxRestarts = maxRestarts
    )

  "stop a single-row write after the configured restarts when each attempt is slower than the minimum backoff" in {
    val adapter = new SlowFailingWriteAdapter(newFixture().adapter, 150.millis)
    val store   = storeWith(adapter, slowBackoffOf(maxRestarts = 2))

    val failure = Await.result(
      store.write(Seq(JournalTestSupport.row(persistenceId, 1L, "e1"))).runWith(Sink.ignore).failed,
      10.seconds
    )

    failure shouldBe an[IllegalStateException]
    adapter.writeAttempts shouldBe 3
  }

  "stop a single-row delete after the configured restarts when each attempt is slower than the minimum backoff" in {
    val adapter = new SlowFailingWriteAdapter(newFixture().adapter, 150.millis)
    val store   = storeWith(adapter, slowBackoffOf(maxRestarts = 2))

    val failure = Await.result(
      store.delete(Seq(PersistenceIdWithSeqNr(persistenceId, SequenceNumber(1)))).runWith(Sink.ignore).failed,
      10.seconds
    )

    failure shouldBe an[IllegalStateException]
    adapter.writeAttempts shouldBe 3
  }

  "reject two rows that resolve to the same key" in {
    val fixture = newFixture()
    val constantResolver: PartitionKeyResolver = new PartitionKeyResolver {
      override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
        PartitionKey("same")
    }
    val store = new JournalRowStore(
      schema = schema,
      partitionKeyResolver = constantResolver,
      sortKeyResolver = sortKeyResolver,
      adapter = fixture.adapter,
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = disabledBackoff,
      headConfig = None
    )
    val rows = Seq(
      JournalTestSupport.row(PersistenceId("account-a"), 1L, "e1"),
      JournalTestSupport.row(PersistenceId("account-b"), 1L, "e2")
    )

    val failure = Await.result(store.write(rows).runWith(Sink.head).failed, 30.seconds)
    failure shouldBe a[JournalHeadInvalidWriteException]
  }

  "headSequenceNr reads the last sequence number from the JournalHead item" in {
    val fixture = newFixture(headConfig = Some(headModeConfig))
    fixture.adapter.seed(
      TableName("JournalHead"),
      AttributeMap.of(
        AttributeName("persistence-id") -> DdbValue.Str(persistenceId.asString),
        AttributeName("sequence-nr")    -> DdbValue.Num(BigDecimal(7))
      )
    )

    run(fixture.store.headSequenceNr(persistenceId)) shouldBe Vector(Some(7L))
    run(fixture.store.headSequenceNr(PersistenceId("other"))) shouldBe Vector(None)
  }

  "headSequenceNr returns none when head mode is disabled" in {
    val fixture = newFixture(headConfig = None)

    run(fixture.store.headSequenceNr(persistenceId)) shouldBe Vector(None)
  }

  private def headEvent(sequenceNr: Long, message: String): DdbValue =
    DdbValue.M(
      Vector(
        "sequence-nr" -> DdbValue.Num(BigDecimal(sequenceNr)),
        "message"     -> DdbValue.Bin(message.getBytes("UTF-8").toVector)
      )
    )

  private def headItem(
      sequenceNr: Long = 2,
      revision: Option[Long] = None,
      events: Vector[DdbValue] = Vector(headEvent(1, "old1"), headEvent(2, "old2"))
  ): AttributeMap =
    AttributeMap(
      Vector[(AttributeName, DdbValue)](
        AttributeName("persistence-id") -> DdbValue.Str(persistenceId.asString),
        AttributeName("sequence-nr")    -> DdbValue.Num(BigDecimal(sequenceNr)),
        AttributeName("events")         -> DdbValue.L(events)
      ) ++ revision.map(value => AttributeName("revision") -> (DdbValue.Num(BigDecimal(value)): DdbValue))
    )

  private val headTableName = TableName("JournalHead")

  private def conditionFailedAt(index: Int, code: String = "ConditionalCheckFailed"): OperationResult =
    OperationResult.TransactionRejected(
      (0 to 1).toVector.map(i => if (i == index) ConditionFailure(i, code) else ConditionFailure(i, "None"))
    )

  private val headChanged: OperationResult         = conditionFailedAt(1)
  private val rowConditionFailed: OperationResult  = conditionFailedAt(0)
  private val validationError: OperationResult     = conditionFailedAt(0, "ValidationError")
  private val transactionConflict: OperationResult = conditionFailedAt(1, "TransactionConflict")
  private val throttled: OperationResult           = conditionFailedAt(0, "ThrottlingError")
  private val committed: OperationResult           = OperationResult.TransactionCommitted(2)

  private val testBackoff = disabledBackoff.copy(minBackoff = 10.millis, maxBackoff = 20.millis)

  private val slowBackoff =
    disabledBackoff.copy(enabled = true, minBackoff = 300.millis, maxBackoff = 300.millis, randomFactor = 0.0)

  /** What the store finds in the tables before the scripted transactions run. `row` is the `deleted` flag of the
    * journal row at sequence number 2, if the row exists.
    */
  private final case class Script(
      head: Option[AttributeMap] = Some(headItem()),
      row: Option[Boolean] = Some(false),
      backoff: BackoffConfig = testBackoff,
      onTransaction: (Int, InMemoryDdbAdapter) => Unit = (_, _) => ()
  )

  private def scriptedStore(
      script: Script,
      results: OperationResult*
  ): (ScriptedTransactionAdapter, JournalRowStore) = {
    val inMemory = newFixture(headConfig = Some(headModeConfig))
    script.head.foreach(item => inMemory.adapter.seed(headTableName, item))
    script.row.foreach { deleted =>
      run(inMemory.store.write(Seq(JournalTestSupport.row(persistenceId, 2L, "old2", deleted = deleted))))
    }
    val adapter =
      new ScriptedTransactionAdapter(inMemory.adapter, results, i => script.onTransaction(i, inMemory.adapter))
    val store = new JournalRowStore(
      schema = schema,
      partitionKeyResolver = new ShardedPartitionKeyResolver(4),
      sortKeyResolver = sortKeyResolver,
      adapter = adapter,
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = script.backoff,
      headConfig = Some(headModeConfig)
    )
    (adapter, store)
  }

  private def updateFailure(store: JournalRowStore, sequenceNr: Long = 2L, message: String = "new2"): Throwable =
    Await.result(
      store.update(JournalTestSupport.row(persistenceId, sequenceNr, message, ordering = 2L)).runWith(Sink.head).failed,
      30.seconds
    )

  "update with head mode sends the row update and the head replacement in one transaction" in {
    val (adapter, store) = scriptedStore(Script(), committed)

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    adapter.transactions.size shouldBe 1
    val Vector(rowUpdate: TransactItem.Update, headPut: TransactItem.Put) = adapter.transactions.head.items
    rowUpdate.updates.collect { case AttributeUpdate.Set(name, _) => name } should not contain schema.deleted
    rowUpdate.condition shouldBe Some(
      Condition.And(
        Condition.And(
          Condition.AttributeExists(schema.partitionKey),
          Condition.Compare(
            Operand.Column(schema.persistenceId),
            CompareOp.Eq,
            Operand.Literal(DdbValue.Str(persistenceId.asString))
          )
        ),
        Condition.Not(
          Condition.Compare(Operand.Column(schema.deleted), CompareOp.Eq, Operand.Literal(DdbValue.Bool(true)))
        )
      )
    )
    headPut.table shouldBe headTableName
    headPut.item.get(AttributeName("events")) shouldBe Some(
      DdbValue.L(Vector(headEvent(1, "old1"), headEvent(2, "new2")))
    )
    headPut.item.get(AttributeName("revision")) shouldBe Some(DdbValue.Num(BigDecimal(1)))
    headPut.condition shouldBe Some(
      Condition.And(
        Condition.Compare(
          Operand.Column(AttributeName("sequence-nr")),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Num(BigDecimal(2)))
        ),
        Condition.AttributeNotExists(AttributeName("revision"))
      )
    )
  }

  "update with head mode raises the revision that it read and conditions the head on it" in {
    val (adapter, store) = scriptedStore(Script(head = Some(headItem(revision = Some(3)))), committed)

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    val Vector(_, headPut: TransactItem.Put) = adapter.transactions.head.items
    headPut.item.get(AttributeName("revision")) shouldBe Some(DdbValue.Num(BigDecimal(4)))
    headPut.condition shouldBe Some(
      Condition.And(
        Condition.Compare(
          Operand.Column(AttributeName("sequence-nr")),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Num(BigDecimal(2)))
        ),
        Condition.Compare(
          Operand.Column(AttributeName("revision")),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Num(BigDecimal(3)))
        )
      )
    )
  }

  "update with head mode reads the head again and retries when the head changed before the transaction" in {
    val (adapter, store) = scriptedStore(Script(), headChanged, committed)

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    adapter.transactions.size shouldBe 2
  }

  "update with head mode returns a recoverable failure after three attempts while the head keeps changing" in {
    val (adapter, store) = scriptedStore(Script(), headChanged, headChanged, headChanged)

    val failure = updateFailure(store)

    failure shouldBe a[JournalHeadConcurrentUpdateException]
    val concurrent = failure.asInstanceOf[JournalHeadConcurrentUpdateException]
    concurrent.persistenceId shouldBe persistenceId.asString
    concurrent.sequenceNr shouldBe 2L
    concurrent.attempts shouldBe 3
    adapter.transactions.size shouldBe 3
  }

  "update with head mode retries a transaction conflict and returns a recoverable failure when it persists" in {
    val (retried, retryingStore) = scriptedStore(Script(), transactionConflict, committed)
    run(retryingStore.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))
    retried.transactions.size shouldBe 2

    val (adapter, store) = scriptedStore(Script(), transactionConflict, transactionConflict, transactionConflict)
    updateFailure(store) shouldBe a[JournalHeadConcurrentUpdateException]
    adapter.transactions.size shouldBe 3
  }

  "update with head mode retries throttling and returns a recoverable failure when it persists" in {
    val (retried, retryingStore) = scriptedStore(Script(), throttled, committed)
    run(retryingStore.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))
    retried.transactions.size shouldBe 2

    val (adapter, store) = scriptedStore(Script(), throttled, throttled, throttled)
    updateFailure(store) shouldBe a[JournalHeadConcurrentUpdateException]
    adapter.transactions.size shouldBe 3
  }

  "update with head mode refuses an update whose journal row does not exist and does not retry" in {
    val (adapter, store) = scriptedStore(Script(row = None), rowConditionFailed)

    val failure = updateFailure(store)

    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure
      .asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDoesNotExist
    adapter.transactions.size shouldBe 1
  }

  "update with head mode refuses an update whose journal row is soft deleted and does not retry" in {
    val (adapter, store) = scriptedStore(Script(row = Some(true)), rowConditionFailed)

    val failure = updateFailure(store)

    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDeleted
    adapter.transactions.size shouldBe 1
  }

  "update with head mode retries when the row condition failed but the row is there and not deleted" in {
    val (adapter, store) = scriptedStore(Script(), rowConditionFailed, committed)

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    adapter.transactions.size shouldBe 2
  }

  "update with head mode fails with an illegal state when the head sequence number goes backwards between attempts" in {
    val lower = headItem(sequenceNr = 1, events = Vector(headEvent(1, "old1"), headEvent(2, "old2")))
    val (adapter, store) = scriptedStore(
      Script(onTransaction = (_, memory) => memory.seed(headTableName, lower)),
      headChanged,
      committed
    )

    updateFailure(store) shouldBe an[IllegalStateException]
    adapter.transactions.size shouldBe 1
  }

  "update with head mode fails with an illegal state when the head revision goes backwards between attempts" in {
    val lower = headItem(revision = Some(1))
    val (adapter, store) = scriptedStore(
      Script(
        head = Some(headItem(revision = Some(2))),
        onTransaction = (_, memory) => memory.seed(headTableName, lower)
      ),
      headChanged,
      committed
    )

    updateFailure(store) shouldBe an[IllegalStateException]
    adapter.transactions.size shouldBe 1
  }

  "update with head mode fails with an illegal state for a malformed JournalHead item and sends no transaction" in {
    val noSequenceNr = AttributeMap.of(
      AttributeName("persistence-id") -> DdbValue.Str(persistenceId.asString),
      AttributeName("events")         -> DdbValue.L(Vector(headEvent(2, "old2")))
    )
    val eventsNotAList = AttributeMap.of(
      AttributeName("persistence-id") -> DdbValue.Str(persistenceId.asString),
      AttributeName("sequence-nr")    -> DdbValue.Num(BigDecimal(2)),
      AttributeName("events")         -> DdbValue.Str("broken")
    )
    val eventWithoutSequenceNr = headItem(events = Vector(DdbValue.M(Vector("message" -> DdbValue.Bin(Vector.empty)))))
    Seq(noSequenceNr, eventsNotAList, eventWithoutSequenceNr).foreach { malformed =>
      val (adapter, store) = scriptedStore(Script(head = Some(malformed)), committed)

      updateFailure(store) shouldBe an[IllegalStateException]
      adapter.transactions shouldBe empty
    }
  }

  "update with head mode fails with an illegal state for a cancellation reason it does not know and does not retry" in {
    val (adapter, store) = scriptedStore(Script(), validationError)

    val failure = updateFailure(store)

    failure shouldBe an[IllegalStateException]
    failure.getMessage should include("ValidationError")
    adapter.transactions.size shouldBe 1
  }

  "update with head mode fails with an illegal state for a result it does not expect" in {
    val (_, store) = scriptedStore(Script(), OperationResult.Written(1))

    updateFailure(store) shouldBe an[IllegalStateException]
  }

  "update with head mode refuses a journal row over 400 KB before it reads or writes anything" in {
    val (adapter, store) = scriptedStore(Script(), committed)

    val failure = updateFailure(store, message = "x" * 410000)

    failure shouldBe a[JournalHeadJournalRowTooLargeException]
    adapter.transactions shouldBe empty
  }

  "update with head mode sends no transaction for an event outside the latest head write" in {
    val (adapter, store) = scriptedStore(Script())

    updateFailure(store, sequenceNr = 9L, message = "new9") shouldBe a[JournalHeadAsyncUpdateEventUnsupportedException]
    adapter.transactions shouldBe empty
  }

  "update with head mode sends no transaction when the JournalHead item does not exist" in {
    val (adapter, store) = scriptedStore(Script(head = None))

    updateFailure(store, sequenceNr = 1L, message = "new1") shouldBe a[JournalHeadMissingException]
    adapter.transactions shouldBe empty
  }

  "update with head mode refuses a head item over 400 KB with the head too large rejection" in {
    val big              = "x" * 130000
    val bigEvents        = Vector(headEvent(1, big), headEvent(2, big), headEvent(3, big))
    val (adapter, store) = scriptedStore(Script(head = Some(headItem(sequenceNr = 3, events = bigEvents))), committed)

    updateFailure(store, sequenceNr = 3L, message = "y" * 160000) shouldBe a[JournalHeadItemTooLargeException]
    adapter.transactions shouldBe empty
  }

  private def elapsedMillis(action: => Unit): Long = {
    val started = System.nanoTime()
    action
    (System.nanoTime() - started) / 1000000
  }

  "update with head mode waits for the backoff before it tries a throttled update again" in {
    val (_, store) = scriptedStore(Script(backoff = slowBackoff), throttled, committed)

    elapsedMillis(run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))) should be >= 250L
  }

  "update with head mode does not wait when the backoff is disabled" in {
    val (_, store) = scriptedStore(Script(backoff = slowBackoff.copy(enabled = false)), throttled, committed)

    elapsedMillis(run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))) should be < 200L
  }

  "update with head mode reads the head again without waiting when the head changed" in {
    val (_, store) = scriptedStore(Script(backoff = slowBackoff), headChanged, committed)

    elapsedMillis(run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))) should be < 200L
  }

  "update with head mode retries ProvisionedThroughputExceeded like throttling" in {
    val (adapter, store) = scriptedStore(Script(), conditionFailedAt(0, "ProvisionedThroughputExceeded"), committed)

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    adapter.transactions.size shouldBe 2
  }

  "update with head mode checks the journal row first when the row and the head conditions both failed" in {
    val bothFailed =
      OperationResult.TransactionRejected(
        Vector(ConditionFailure(0, "ConditionalCheckFailed"), ConditionFailure(1, "ConditionalCheckFailed"))
      )
    val (adapter, store) = scriptedStore(Script(row = Some(true)), bothFailed)

    val failure = updateFailure(store)

    failure shouldBe a[JournalHeadRowUpdateRejectedException]
    failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDeleted
    adapter.transactions.size shouldBe 1
  }

  "update with head mode returns a recoverable failure when the row keeps changing during the check" in {
    val (adapter, store) = scriptedStore(Script(), rowConditionFailed, rowConditionFailed, rowConditionFailed)

    updateFailure(store) shouldBe a[JournalHeadConcurrentUpdateException]
    adapter.transactions.size shouldBe 3
  }

  "update with head mode builds the retry from the head it read again" in {
    val newer = headItem(revision = Some(5))
    val (adapter, store) = scriptedStore(
      Script(onTransaction = (_, memory) => memory.seed(headTableName, newer)),
      headChanged,
      committed
    )

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    val Vector(_, retried: TransactItem.Put) = adapter.transactions(1).items
    retried.item.get(AttributeName("revision")) shouldBe Some(DdbValue.Num(BigDecimal(6)))
    retried.condition shouldBe Some(
      Condition.And(
        Condition.Compare(
          Operand.Column(AttributeName("sequence-nr")),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Num(BigDecimal(2)))
        ),
        Condition.Compare(
          Operand.Column(AttributeName("revision")),
          CompareOp.Eq,
          Operand.Literal(DdbValue.Num(BigDecimal(5)))
        )
      )
    )
  }

  "update with head mode fails with an illegal state for every other shape of a malformed JournalHead item" in {
    def item(entries: (String, DdbValue)*): AttributeMap =
      AttributeMap(entries.toVector.map { case (name, value) => AttributeName(name) -> value })
    val id     = "persistence-id" -> DdbValue.Str(persistenceId.asString)
    val events = "events"         -> DdbValue.L(Vector(headEvent(2, "old2")))
    val malformed = Seq(
      item(id, "sequence-nr" -> DdbValue.Str("2"), events),
      item(id, "sequence-nr" -> DdbValue.Num(BigDecimal(2)), "revision" -> DdbValue.Str("1"), events),
      item(id, "sequence-nr" -> DdbValue.Num(BigDecimal(2)), "events"   -> DdbValue.L(Vector(DdbValue.Str("event")))),
      item(
        id,
        "sequence-nr" -> DdbValue.Num(BigDecimal(2)),
        "events"      -> DdbValue.L(Vector(DdbValue.M(Vector("sequence-nr" -> DdbValue.Num(BigDecimal(2))))))
      ),
      item(id, "sequence-nr" -> DdbValue.Num(BigDecimal(2)))
    )
    malformed.foreach { head =>
      val (adapter, store) = scriptedStore(Script(head = Some(head)), committed)

      updateFailure(store) shouldBe an[IllegalStateException]
      adapter.transactions shouldBe empty
    }
  }

  "update with head mode fails with an illegal state when DynamoDB cancels the transaction without a reason" in {
    val (adapter, store) = scriptedStore(Script(), OperationResult.TransactionRejected(Vector.empty))

    updateFailure(store) shouldBe an[IllegalStateException]
    adapter.transactions.size shouldBe 1
  }

  // --- keys of releases up to 1.3.0 (issue #1014) -------------------------------------------------------------------

  /** A partition key resolver whose ids that start with `account` had another key in 1.3.0: the id without its last
    * character. So `account-1` and `account-2` shared their 1.3.0 key `k-account-`, and the 1.3.0 key of `account-1z`
    * is the current key of `account-1`. `currentPad` and `legacyPad` lengthen the two keys.
    */
  private final class LegacyAwarePartitionKeyResolver(currentPad: String = "", legacyPad: String = "")
      extends PartitionKeyResolver
      with PartitionKeyResolver.LegacyKeys {
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(s"k-${persistenceId.asString}$currentPad")
    override def resolveLegacy(persistenceId: PersistenceId): Option[PartitionKey] =
      if (persistenceId.asString.startsWith("account"))
        Some(PartitionKey(s"k-${persistenceId.asString.dropRight(1)}$legacyPad"))
      else None
  }

  /** Writes rows under the 1.3.0 keys of `LegacyAwarePartitionKeyResolver`, as 1.3.0 did. */
  private final class AsIn130PartitionKeyResolver(legacyPad: String = "") extends PartitionKeyResolver {
    private val aware = new LegacyAwarePartitionKeyResolver(legacyPad = legacyPad)
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      aware.resolveLegacy(persistenceId).getOrElse(aware.resolve(persistenceId, sequenceNumber))
  }

  private def legacyStores(
      adapter: DdbClientAdapter,
      currentPad: String = "",
      legacyPad: String = "",
      headConfig: Option[JournalHeadConfig] = None
  ): (JournalRowStore, JournalRowStore) = {
    def store(resolver: PartitionKeyResolver) = new JournalRowStore(
      schema = schema,
      partitionKeyResolver = resolver,
      sortKeyResolver = sortKeyResolver,
      adapter = adapter,
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = disabledBackoff,
      headConfig = headConfig
    )
    (
      store(new AsIn130PartitionKeyResolver(legacyPad)),
      store(new LegacyAwarePartitionKeyResolver(currentPad, legacyPad))
    )
  }

  private def storedAt(adapter: InMemoryDdbAdapter, partitionKey: String, sequenceNr: Long): Option[AttributeMap] =
    run(
      adapter.run(
        Operation.Get(
          schema.table,
          com.github.j5ik2o.pekko.persistence.dynamodb.ddb.RowKey(
            schema.table,
            AttributeMap.of(
              schema.partitionKey -> DdbValue.Str(partitionKey),
              schema.sortKey      -> DdbValue.Str("%019d".format(sequenceNr))
            )
          ),
          consistentRead = true
        )
      )
    ).head match {
      case OperationResult.Item(item) => item
      case other                      => fail(s"unexpected result: $other")
    }

  private val accountX = PersistenceId("account-1")
  private val accountY = PersistenceId("account-1z") // its 1.3.0 key is the current key of account-1
  private val accountZ = PersistenceId("account-2")  // shares the 1.3.0 key of account-1

  "a soft delete marks an event under its 1.3.0 key deleted there, even when the current key holds another id's event" in {
    val adapter        = new InMemoryDdbAdapter(Map(schema.table -> Vector(schema.partitionKey, schema.sortKey)))
    val (in130, store) = legacyStores(adapter)
    run(in130.write(Seq(JournalTestSupport.row(accountX, 1L, "x1"))))
    run(in130.write(Seq(JournalTestSupport.row(accountY, 1L, "y1"))))

    run(store.write(Seq(JournalTestSupport.row(accountX, 1L, "x1", deleted = true)))) shouldBe Vector(1L)

    storedAt(adapter, "k-account-", 1L).flatMap(_.get(schema.deleted)) shouldBe Some(DdbValue.Bool(true))
    val other = storedAt(adapter, "k-account-1", 1L).get
    other.get(schema.persistenceId) shouldBe Some(DdbValue.Str(accountY.asString))
    other.get(schema.deleted) shouldBe Some(DdbValue.Bool(false))
    run(store.read(accountX, None, SequenceNumber(10), None, Some(false))) shouldBe empty
    run(store.read(accountY, None, SequenceNumber(10), None, Some(false))).map(_.sequenceNumber.value) shouldBe Vector(
      1L
    )
  }

  "a soft delete of an event under its current key writes it there" in {
    val adapter    = new InMemoryDdbAdapter(Map(schema.table -> Vector(schema.partitionKey, schema.sortKey)))
    val (_, store) = legacyStores(adapter)
    run(store.write(Seq(JournalTestSupport.row(accountX, 2L, "x2"))))
    run(store.write(Seq(JournalTestSupport.row(accountX, 2L, "x2", deleted = true)))) shouldBe Vector(1L)
    storedAt(adapter, "k-account-1", 2L).flatMap(_.get(schema.deleted)) shouldBe Some(DdbValue.Bool(true))
    storedAt(adapter, "k-account-", 2L) shouldBe None
  }

  "a hard delete removes the event under its 1.3.0 key only while that key holds the same id and sequence number" in {
    val adapter        = new InMemoryDdbAdapter(Map(schema.table -> Vector(schema.partitionKey, schema.sortKey)))
    val (in130, store) = legacyStores(adapter)
    run(in130.write(Seq(JournalTestSupport.row(accountZ, 1L, "z1")))) // the shared 1.3.0 key holds account-2
    run(in130.write(Seq(JournalTestSupport.row(accountX, 2L, "x2"))))

    run(
      store.delete(
        Seq(PersistenceIdWithSeqNr(accountX, SequenceNumber(1)), PersistenceIdWithSeqNr(accountX, SequenceNumber(2)))
      )
    )

    storedAt(adapter, "k-account-", 1L).flatMap(_.get(schema.persistenceId)) shouldBe Some(
      DdbValue.Str(accountZ.asString)
    )
    storedAt(adapter, "k-account-", 2L) shouldBe None
  }

  "an update falls back to the 1.3.0 key, and refuses a 1.3.0 key that holds another id's event" in {
    val adapter        = new InMemoryDdbAdapter(Map(schema.table -> Vector(schema.partitionKey, schema.sortKey)))
    val (in130, store) = legacyStores(adapter)
    run(in130.write(Seq(JournalTestSupport.row(accountZ, 1L, "z1"))))
    run(in130.write(Seq(JournalTestSupport.row(accountX, 3L, "x3"))))

    run(store.update(JournalTestSupport.row(accountX, 3L, "x3-new", ordering = 3L)))
    storedAt(adapter, "k-account-", 3L).flatMap(_.get(schema.message)) shouldBe Some(
      DdbValue.Bin("x3-new".getBytes("UTF-8").toVector)
    )
    storedAt(adapter, "k-account-1", 3L) shouldBe None

    val refused = Await.result(
      store.update(JournalTestSupport.row(accountX, 1L, "must-not-be-written")).runWith(Sink.head).failed,
      30.seconds
    )
    refused shouldBe a[JournalHeadRowUpdateRejectedException]
    storedAt(adapter, "k-account-", 1L).flatMap(_.get(schema.message)) shouldBe Some(
      DdbValue.Bin("z1".getBytes("UTF-8").toVector)
    )
  }

  "an update with head mode checks the size of the row with the key that the row is stored under" in {
    val columns = com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalColumnsDefConfig(
      sourceConfig = com.typesafe.config.ConfigFactory.empty(),
      partitionKeyColumnName = "pkey",
      sortKeyColumnName = "skey",
      persistenceIdColumnName = "persistence-id",
      sequenceNrColumnName = "sequence-nr",
      deletedColumnName = "deleted",
      messageColumnName = "message",
      orderingColumnName = "ordering",
      tagsColumnName = "tags"
    )
    val pad = "p" * 1000
    def sizeOf(message: String, partitionKey: String) =
      com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.DynamoDBItemSizeCalculator.journalItemSize(
        JournalTestSupport.row(accountX, 2L, message, ordering = 2L),
        columns,
        partitionKey,
        "%019d".format(2L)
      )
    val limit = com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadLimits.MaxItemSizeBytes
    // fits under the shorter key and not under the key that is 1,000 bytes longer
    val message = "x" * (limit - 100 - sizeOf("", "k-account-"))
    sizeOf(message, "k-account-") should be <= limit
    sizeOf(message, s"k-account-1$pad") should be > limit

    def scenario(currentPad: String, legacyPad: String): (ScriptedTransactionAdapter, Either[Throwable, Unit]) = {
      val inMemory = new InMemoryDdbAdapter(
        Map(
          schema.table  -> Vector(schema.partitionKey, schema.sortKey),
          headTableName -> Vector(AttributeName("persistence-id"))
        )
      )
      inMemory.seed(headTableName, headItem())
      val (in130, _) = legacyStores(inMemory, currentPad, legacyPad)
      run(in130.write(Seq(JournalTestSupport.row(accountX, 2L, "old2"))))
      val adapter    = new ScriptedTransactionAdapter(inMemory, Seq(committed), _ => ())
      val (_, store) = legacyStores(adapter, currentPad, legacyPad, Some(headModeConfig))
      val result = Await
        .ready(
          store.update(JournalTestSupport.row(accountX, 2L, message, ordering = 2L)).runWith(Sink.head),
          30.seconds
        ).value.get.toEither.map(_ => ())
      (adapter, result)
    }

    // The row is under the short 1.3.0 key: the update fits and goes to that key.
    val (fits, fitsResult) = scenario(currentPad = pad, legacyPad = "")
    fitsResult shouldBe Right(())
    fits.transactions.flatMap(_.items).collect { case TransactItem.Update(_, key, _, _) =>
      key.values.get(schema.partitionKey)
    } shouldBe Vector(Some(DdbValue.Str("k-account-")))

    // The row is under the long 1.3.0 key: the update is refused before any transaction.
    val (tooLarge, tooLargeResult) = scenario(currentPad = "", legacyPad = pad)
    tooLargeResult.left.toOption.get shouldBe a[JournalHeadJournalRowTooLargeException]
    tooLarge.transactions shouldBe empty
  }
}
