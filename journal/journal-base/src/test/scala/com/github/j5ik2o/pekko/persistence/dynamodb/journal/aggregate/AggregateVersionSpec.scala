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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.aggregate

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._

final class AggregateVersionSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem        = ActorSystem("aggregate-version-spec")
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val persistenceId = PersistenceId("account-1")

  private def run[T](source: Source[T, NotUsed]): Vector[T] =
    Await.result(
      source.runWith(Sink.fold[Vector[T], T](Vector.empty[T])((accumulated, element) => accumulated :+ element)),
      30.seconds
    )

  private def journalHighest(value: Option[Long]): (PersistenceId, SequenceNumber) => Source[Option[Long], NotUsed] =
    (_: PersistenceId, _: SequenceNumber) => Source.single(value)

  private def headHighest(value: Option[Long]): PersistenceId => Source[Option[Long], NotUsed] =
    (_: PersistenceId) => Source.single(value)

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds)
    super.afterAll()
  }

  "AggregateVersion" should {

    "derive the next sequence number from the highest one" in {
      AggregateVersion(7L).highestSequenceNr shouldBe 7L
      AggregateVersion(7L).nextSequenceNr shouldBe 8L
      AggregateVersion.Zero.highestSequenceNr shouldBe 0L
      AggregateVersion.Zero.nextSequenceNr shouldBe 1L
    }

    "treat an absent highest sequence number as zero" in {
      AggregateVersion.fromHighestSequenceNr(None) shouldBe AggregateVersion.Zero
      AggregateVersion.fromHighestSequenceNr(Some(4L)) shouldBe AggregateVersion(4L)
    }

    "keep the higher of two versions" in {
      AggregateVersion.highestOf(AggregateVersion(3L), AggregateVersion(7L)) shouldBe AggregateVersion(7L)
      AggregateVersion.highestOf(AggregateVersion(7L), AggregateVersion(3L)) shouldBe AggregateVersion(7L)
      AggregateVersion.highestOf(AggregateVersion(7L), AggregateVersion(7L)) shouldBe AggregateVersion(7L)
    }

  }

  "JournalAggregateStore" should {

    "take the higher of the journal and the head sequence numbers" in {
      val store = new JournalAggregateStore(
        journalHighest(Some(3L)),
        headHighest(Some(7L)),
        headEnabled = true
      )

      run(store.highestSequenceNr(persistenceId, SequenceNumber(1))) shouldBe Vector(AggregateVersion(7L))
    }

    "keep the journal sequence number when it is ahead of the head" in {
      val store = new JournalAggregateStore(
        journalHighest(Some(7L)),
        headHighest(Some(3L)),
        headEnabled = true
      )

      run(store.highestSequenceNr(persistenceId, SequenceNumber(1))) shouldBe Vector(AggregateVersion(7L))
    }

    "use the head when every journal row was hard deleted" in {
      val store = new JournalAggregateStore(
        journalHighest(None),
        headHighest(Some(3L)),
        headEnabled = true
      )

      run(store.highestSequenceNr(persistenceId, SequenceNumber(1))) shouldBe Vector(AggregateVersion(3L))
    }

    "ignore the head when head mode is disabled" in {
      val store = new JournalAggregateStore(
        journalHighest(Some(5L)),
        (_: PersistenceId) => Source.failed(new IllegalStateException("the head must not be read")),
        headEnabled = false
      )

      run(store.highestSequenceNr(persistenceId, SequenceNumber(1))) shouldBe Vector(AggregateVersion(5L))
    }

    "report zero for an aggregate with no event and no head" in {
      val store = new JournalAggregateStore(
        journalHighest(None),
        headHighest(None),
        headEnabled = true
      )

      run(store.highestSequenceNr(persistenceId, SequenceNumber(1))) shouldBe Vector(AggregateVersion.Zero)
    }

  }

}
