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
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, DdbValue, TableName }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.{ JournalHeadColumnsDefConfig, JournalHeadConfig }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.PersistenceIdWithSeqNr
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

    an[IllegalArgumentException] should be thrownBy store.write(rows)
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
}
