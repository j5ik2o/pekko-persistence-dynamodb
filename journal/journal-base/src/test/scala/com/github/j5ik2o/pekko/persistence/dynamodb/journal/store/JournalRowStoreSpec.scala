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

  "retry an unprocessed batch until the adapter accepts it" in {
    val fixture = newFixture()
    val store   = storeWith(new FlakyBatchAdapter(fixture.adapter, failures = 2), fastBackoff)
    val rows    = (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

    run(store.write(rows)) shouldBe Vector(3L)
    run(store.read(persistenceId, None, SequenceNumber(3), None, None))
      .map(_.sequenceNumber.value) shouldBe Vector(1L, 2L, 3L)
  }

  "do not retry a batch failure that is not the unprocessed-items signal" in {
    val fixture = newFixture()
    val adapter = new FlakyBatchAdapter(
      fixture.adapter,
      failures = 2,
      failure = () => new IllegalStateException("unexpected result for a write")
    )
    val store = storeWith(adapter, disabledBackoff)
    val rows  = (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

    val failure = Await.result(store.write(rows).runWith(Sink.ignore).failed, 30.seconds)

    failure shouldBe an[IllegalStateException]
    adapter.batchAttempts shouldBe 1
  }

  "stop retrying an unprocessed batch after the configured attempts" in {
    val fixture = newFixture()
    val adapter = new FlakyBatchAdapter(fixture.adapter, failures = 2)
    val store   = storeWith(adapter, fastBackoff.copy(maxRestarts = 0))
    val rows    = (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

    val failure = Await.result(store.write(rows).runWith(Sink.ignore).failed, 30.seconds)

    failure shouldBe a[JournalRowStoreException.BatchWriteRetriesExhausted]
    failure.asInstanceOf[JournalRowStoreException.BatchWriteRetriesExhausted].attempts shouldBe 1
    adapter.batchAttempts shouldBe 1
  }

  "fail with the retryable failure when an unprocessed batch keeps failing" in {
    val fixture     = newFixture()
    val unprocessed = new BatchWriteUnprocessedItemsException(1)
    val adapter =
      new FlakyBatchAdapter(fixture.adapter, failures = Int.MaxValue, failure = () => unprocessed)
    val store = storeWith(adapter, fastBackoff)
    val rows  = (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

    val failure = Await.result(store.write(rows).runWith(Sink.ignore).failed, 30.seconds)

    failure shouldBe a[JournalRowStoreException.BatchWriteRetriesExhausted]
    val exhausted = failure.asInstanceOf[JournalRowStoreException.BatchWriteRetriesExhausted]
    exhausted.attempts shouldBe 4
    exhausted.table shouldBe TableName("Journal")
    exhausted.getCause should be theSameInstanceAs unprocessed
    adapter.batchAttempts shouldBe 4
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
        Condition.AttributeExists(schema.partitionKey),
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
}
