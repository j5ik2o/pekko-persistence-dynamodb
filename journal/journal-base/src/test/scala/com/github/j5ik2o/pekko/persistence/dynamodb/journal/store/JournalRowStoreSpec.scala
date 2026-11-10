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
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeMap,
  AttributeName,
  CapabilityProfile,
  Condition,
  ConditionFailure,
  DdbClientAdapter,
  DdbValue,
  Operation,
  OperationResult,
  TableName,
  TransactItem
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalHeadColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.{
  JournalHeadAsyncUpdateEventUnsupportedException,
  JournalHeadInvalidWriteException,
  JournalHeadMissingException
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema.JournalTestSupport
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ PartitionKey, PartitionKeyResolver }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

final class JournalRowStoreSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  import JournalTestSupport._

  private implicit val system: ActorSystem        = ActorSystem("journal-row-store-spec")
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val persistenceId = PersistenceId("account-1")

  private final case class Fixture(adapter: InMemoryDdbAdapter, store: JournalRowStore)

  /** Fails the first `failures` batch writes with the error the adapter raises for unprocessed items, then delegates.
    */
  private final class FlakyBatchAdapter(delegate: InMemoryDdbAdapter, failures: Int) extends DdbClientAdapter {
    private var remaining: Int = failures

    override val capabilities: CapabilityProfile = delegate.capabilities

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case _: Operation.BatchWrite if remaining > 0 =>
        remaining -= 1
        Source.failed(new IllegalStateException("BatchWriteItem left 1 unprocessed items"))
      case _ =>
        delegate.run(operation)
    }

    override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = delegate.queryAll(query)

    override def close(): Unit = delegate.close()
  }

  /** Answers every transactional write with the next scripted result and records the transactions it received. */
  private final class ScriptedTransactionAdapter(delegate: InMemoryDdbAdapter, results: Seq[OperationResult])
      extends DdbClientAdapter {
    private val pending = scala.collection.mutable.Queue(results: _*)
    val transactions: scala.collection.mutable.ArrayBuffer[Operation.TransactWrite] =
      scala.collection.mutable.ArrayBuffer.empty

    override val capabilities: CapabilityProfile = delegate.capabilities

    override def run(operation: Operation): Source[OperationResult, NotUsed] = operation match {
      case transaction: Operation.TransactWrite =>
        transactions += transaction
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
    val store = new JournalRowStore(
      schema = schema,
      partitionKeyResolver = new ShardedPartitionKeyResolver(4),
      sortKeyResolver = sortKeyResolver,
      adapter = new FlakyBatchAdapter(fixture.adapter, failures = 2),
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = disabledBackoff,
      headConfig = None
    )
    val rows = (1L to 3L).map(sequenceNr => JournalTestSupport.row(persistenceId, sequenceNr, s"e$sequenceNr"))

    run(store.write(rows)) shouldBe Vector(3L)
    run(store.read(persistenceId, None, SequenceNumber(3), None, None))
      .map(_.sequenceNumber.value) shouldBe Vector(1L, 2L, 3L)
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

  private def seedHead(fixture: Fixture, events: DdbValue*): Unit =
    fixture.adapter.seed(
      TableName("JournalHead"),
      AttributeMap.of(
        AttributeName("persistence-id") -> DdbValue.Str(persistenceId.asString),
        AttributeName("sequence-nr")    -> DdbValue.Num(BigDecimal(2)),
        AttributeName("events")         -> DdbValue.L(events.toVector)
      )
    )

  private val headChanged: OperationResult = OperationResult.TransactionRejected(
    Vector(ConditionFailure(0, "None"), ConditionFailure(1, "ConditionalCheckFailed"))
  )
  private val rowMissing: OperationResult = OperationResult.TransactionRejected(
    Vector(ConditionFailure(0, "ConditionalCheckFailed"), ConditionFailure(1, "None"))
  )

  private val validationError: OperationResult = OperationResult.TransactionRejected(
    Vector(ConditionFailure(0, "ValidationError"), ConditionFailure(1, "None"))
  )
  private val transactionConflict: OperationResult = OperationResult.TransactionRejected(
    Vector(ConditionFailure(0, "None"), ConditionFailure(1, "TransactionConflict"))
  )

  private def scriptedStore(results: OperationResult*): (ScriptedTransactionAdapter, JournalRowStore) = {
    val inMemory = newFixture(headConfig = Some(headModeConfig))
    seedHead(inMemory, headEvent(1, "old1"), headEvent(2, "old2"))
    val adapter = new ScriptedTransactionAdapter(inMemory.adapter, results)
    val store = new JournalRowStore(
      schema = schema,
      partitionKeyResolver = new ShardedPartitionKeyResolver(4),
      sortKeyResolver = sortKeyResolver,
      adapter = adapter,
      queryBatchSize = 2,
      consistentRead = false,
      batchWriteLimit = 25,
      readBackoffConfig = disabledBackoff,
      writeBackoffConfig = disabledBackoff,
      headConfig = Some(headModeConfig)
    )
    (adapter, store)
  }

  "update with head mode sends the row update and the head replacement in one transaction" in {
    val (adapter, store) = scriptedStore(OperationResult.TransactionCommitted(2))

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    adapter.transactions.size shouldBe 1
    val Vector(rowUpdate: TransactItem.Update, headPut: TransactItem.Put) = adapter.transactions.head.items
    rowUpdate.condition shouldBe Some(Condition.AttributeExists(schema.partitionKey))
    headPut.table shouldBe TableName("JournalHead")
    headPut.item.get(AttributeName("events")) shouldBe Some(
      DdbValue.L(Vector(headEvent(1, "old1"), headEvent(2, "new2")))
    )
    headPut.condition.isDefined shouldBe true
  }

  "update with head mode reads the head again and retries when the head changed before the transaction" in {
    val (adapter, store) = scriptedStore(headChanged, OperationResult.TransactionCommitted(2))

    run(store.update(JournalTestSupport.row(persistenceId, 2L, "new2", ordering = 2L)))

    adapter.transactions.size shouldBe 2
  }

  "update with head mode fails after three attempts while the head keeps changing" in {
    val (adapter, store) = scriptedStore(headChanged, headChanged, headChanged)

    val failure =
      Await.result(
        store.update(JournalTestSupport.row(persistenceId, 2L, "new2")).runWith(Sink.head).failed,
        30.seconds
      )

    failure shouldBe an[IllegalStateException]
    adapter.transactions.size shouldBe 3
  }

  "update with head mode does not retry when the journal row does not exist" in {
    val (adapter, store) = scriptedStore(rowMissing)

    val failure =
      Await.result(
        store.update(JournalTestSupport.row(persistenceId, 2L, "new2")).runWith(Sink.head).failed,
        30.seconds
      )

    failure shouldBe an[IllegalStateException]
    adapter.transactions.size shouldBe 1
  }

  "update with head mode reports a cancellation that is not a failed condition without retrying" in {
    Seq(validationError, transactionConflict).foreach { rejection =>
      val (adapter, store) = scriptedStore(rejection)

      val failure =
        Await.result(
          store.update(JournalTestSupport.row(persistenceId, 2L, "new2")).runWith(Sink.head).failed,
          30.seconds
        )

      failure shouldBe an[IllegalStateException]
      failure.getMessage should (include("ValidationError").or(include("TransactionConflict")))
      adapter.transactions.size shouldBe 1
    }
  }

  "update with head mode sends no transaction for an event outside the latest head write" in {
    val (adapter, store) = scriptedStore()

    val failure =
      Await.result(
        store.update(JournalTestSupport.row(persistenceId, 9L, "new9")).runWith(Sink.head).failed,
        30.seconds
      )

    failure shouldBe a[JournalHeadAsyncUpdateEventUnsupportedException]
    adapter.transactions shouldBe empty
  }

  "update with head mode sends no transaction when the JournalHead item does not exist" in {
    val fixture = newFixture(headConfig = Some(headModeConfig))

    val failure =
      Await.result(
        fixture.store.update(JournalTestSupport.row(persistenceId, 1L, "new1")).runWith(Sink.head).failed,
        30.seconds
      )

    failure shouldBe a[JournalHeadMissingException]
  }
}
