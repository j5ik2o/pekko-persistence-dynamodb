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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.stream.scaladsl.Sink
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, PutItemRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.DockerAssignedPort

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object JournalHeadSpec extends DockerAssignedPort

final class JournalHeadSpec extends JournalHeadSpecSupport {

  import JournalHeadSpecSupport._

  override lazy val dynamoDBPort: Int = JournalHeadSpec.dynamoDBPort

  override lazy val controller = JournalHeadSpec.controller

  "JournalHead" should {
    "not write any JournalHead item when head.enabled is false" in {
      val pid = "head-disabled-1"
      persistOk(headDisabledSystem, pid, "e1")
      persistAllOk(headDisabledSystem, pid, immutable.Seq("e2", "e3"))

      headItem(pid) shouldBe None
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
    }

    "write the journal row and the JournalHead item for persist" in {
      val pid = "head-enabled-persist-1"
      persistOk(headEnabledSystem, pid, "e1")

      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(1L)
    }

    "write the journal rows and the JournalHead item for persistAll" in {
      val pid = "head-enabled-persist-all-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
      headSequenceNr(pid) shouldBe Some(3L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(1L, 2L, 3L)
    }

    "store only the last sequence number and the events of the write in the JournalHead item" in {
      val pid = "head-enabled-attributes-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2"))

      val item = headItem(pid).get
      item.keySet shouldBe Set("persistence-id", "sequence-nr", "events")
      item("events").getL.asScala.foreach { event =>
        event.getM.keySet.asScala shouldBe Set("sequence-nr", "message")
      }
      val headMessages = item("events").getL.asScala
        .map(e => (e.getM.get("sequence-nr").getN.toLong, e.getM.get("message").getB.array().toSeq)).toMap
      val journalMessages = journalRows(pid)
        .map(row => (row("sequence-nr").getN.toLong, row("message").getB.array().toSeq)).toMap
      headMessages shouldBe journalMessages
    }

    "accept a write whose sequence numbers have a gap" in {
      val pid = "head-gap-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))
      headSequenceNr(pid) shouldBe Some(3L)

      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        Await.result(dao.putMessages(Seq(journalRow(headEnabledSystem, pid, 5L, "e5"))).runWith(Sink.head), 60.seconds)
      } finally dao.dispose()

      // a gap is accepted (a write that Pekko itself refuses, such as a serialization failure, leaves one), and the head moves to the written seqNr
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L, 5L)
      headSequenceNr(pid) shouldBe Some(5L)
    }

    "reject a write whose JournalHead item is ahead" in {
      val pid = "head-ahead-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      // another writer got ahead of this one
      val headAheadItem = new com.amazonaws.services.dynamodbv2.model.PutItemRequest()
        .withTableName(journalHeadTableName)
        .withItem(
          Map(
            "persistence-id" -> new AttributeValue().withS(pid),
            "sequence-nr"    -> new AttributeValue().withN("10"),
            "events"         -> new AttributeValue().withL(java.util.Collections.emptyList[AttributeValue]())
          ).asJava
        )
      dynamoDBClient.putItem(headAheadItem)

      val dao       = newWriteJournalDao(headEnabledSystem)
      val exception = failedWith(dao, Seq(journalRow(headEnabledSystem, pid, 4L, "e4")))
      dao.dispose()

      exception shouldBe a[JournalHeadSequenceMismatchException]
      exception.asInstanceOf[JournalHeadSequenceMismatchException].expectedPreviousSequenceNr shouldBe 3L
      exception.asInstanceOf[JournalHeadSequenceMismatchException].actualSequenceNr shouldBe 10L
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L, 3L)
      headSequenceNr(pid) shouldBe Some(10L)
    }

    "reject a duplicate write and write nothing" in {
      val pid = "head-duplicate-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2"))

      val dao = newWriteJournalDao(headEnabledSystem)
      val exception = failedWith(
        dao,
        Seq(journalRow(headEnabledSystem, pid, 1L, "e1"), journalRow(headEnabledSystem, pid, 2L, "e2"))
      )

      exception shouldBe a[DuplicateJournalEventsException]
      exception.asInstanceOf[DuplicateJournalEventsException].sequenceNrs shouldBe Seq(1L, 2L)
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
    }

    "keep the order of two persist calls in one command handler" in {
      val pid = "head-two-persists-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistTwice("e1", "e2"), probe.ref)
        probe.expectMsg(60.seconds, PersistedSecond("e2"))
      }

      // both writes of one asyncWriteMessages call reach DynamoDB in the order the actor produced them
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(2L)
    }

    "stop the actor when a write exceeds the event limit and continue without a gap after a restart" in {
      val pid = "head-over-limit-then-persist-1"
      persistOk(headEnabledSystem, pid, "e1")
      headSequenceNr(pid) shouldBe Some(1L)

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistRejectedThenPersist(100, "after"), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadTooManyEventsException]
      }

      // nothing of the call was written, so there is no gap and the sequence number 2 is still free
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)

      persistOk(headEnabledSystem, pid, "e2")
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
      headItem(pid).get("events").getL.asScala.map(_.getM.get("sequence-nr").getN.toLong) shouldBe Seq(2L)
    }

    "stop the actor when the first write of a new aggregate exceeds the event limit, and write from 1 after a restart" in {
      val pid = "head-new-aggregate-rejected-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistAllPayloads((1 to 100).map(index => s"e$index").toVector), probe.ref)
        val cause = expectPersistFailed(probe, actor)
        cause shouldBe a[JournalHeadTooManyEventsException]
        cause.asInstanceOf[JournalHeadTooManyEventsException].eventCount shouldBe 100
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      // after the restart, recovery finds nothing, so the aggregate starts at sequence number 1 again
      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
    }

    "stop the actor when the first write of a new aggregate exceeds the item size, and write from 1 after a restart" in {
      val pid = "head-new-aggregate-rejected-size-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(Persist("x" * 410000), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadItemTooLargeException]
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
      headSequenceNr(pid) shouldBe Some(1L)
    }

    "write nothing of a batch when its second write exceeds a limit" in {
      val pid = "head-batch-partial-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistThenOverLimit("e1", 100), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadTooManyEventsException]
      }

      // every write of the call is checked before the first one is sent, so the first write is not durable either
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None

      persistOk(headEnabledSystem, pid, "e1")
      journalSequenceNrs(pid) shouldBe Seq(1L)
    }

    "stop the actor when a journal row exceeds 400 KB and continue without a gap after a restart" in {
      val pid = "head-row-size-over-1"
      persistOk(headEnabledSystem, pid, "e1")

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        // The tag makes the journal row exceed 400 KB while the JournalHead item, which does not carry tags, stays
        // small. The failure must happen before DynamoDB is called.
        actor.tell(PersistTaggedAll(1, 410000), probe.ref)
        val cause = expectPersistFailed(probe, actor)
        cause shouldBe a[JournalHeadJournalRowTooLargeException]
        cause.asInstanceOf[JournalHeadJournalRowTooLargeException].limitBytes shouldBe
        JournalHeadLimits.MaxItemSizeBytes
      }
      journalSequenceNrs(pid) shouldBe Seq(1L)

      persistOk(headEnabledSystem, pid, "e2")
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headSequenceNr(pid) shouldBe Some(2L)
    }

    "stop the actor when a journal row exceeds 400 KB and head.enabled is false, and continue without a gap after a restart" in {
      val pid = "head-disabled-row-size-over-1"
      persistOk(headDisabledSystem, pid, "e1")

      withActor(headDisabledSystem, pid) { (actor, probe) =>
        // The head-less path has no JournalHead item to check, but the row itself still must fail before DynamoDB
        // sees it, so onPersistFailure runs and the actor stops.
        actor.tell(PersistTaggedAll(1, 410000), probe.ref)
        val cause = expectPersistFailed(probe, actor)
        cause shouldBe a[JournalHeadJournalRowTooLargeException]
        cause.asInstanceOf[JournalHeadJournalRowTooLargeException].limitBytes shouldBe
        JournalHeadLimits.MaxItemSizeBytes
      }
      journalSequenceNrs(pid) shouldBe Seq(1L)

      persistOk(headDisabledSystem, pid, "e2")
      journalSequenceNrs(pid) shouldBe Seq(1L, 2L)
      headItem(pid) shouldBe None
    }

    "reject only the write whose event cannot be serialized, keep the actor alive, and accept the next write when head.enabled is true" in {
      val pid = "head-serialization-rejected-1"
      persistOk(headEnabledSystem, pid, "e1")

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistUnserializable(), probe.ref)
        val rejected = probe.expectMsgType[PersistRejected](60.seconds)
        rejected.cause should not be a[JournalHeadException]
        probe.expectNoMessage(500.millis)

        // the actor is the same one and still alive; Pekko does not hand back the rejected sequence number
        actor.tell(Persist("after"), probe.ref)
        probe.expectMsg(60.seconds, Persisted("after"))
      }

      info(s"observed journal sequence numbers: ${journalSequenceNrs(pid)}, head: ${headSequenceNr(pid)}")
      journalSequenceNrs(pid) shouldBe Seq(1L, 3L)
      headSequenceNr(pid) shouldBe Some(3L)
    }

    "reject only the write whose event cannot be serialized, keep the actor alive, and accept the next write when head.enabled is false" in {
      val pid = "head-disabled-serialization-rejected-1"
      persistOk(headDisabledSystem, pid, "e1")

      withActor(headDisabledSystem, pid) { (actor, probe) =>
        actor.tell(PersistUnserializable(), probe.ref)
        val rejected = probe.expectMsgType[PersistRejected](60.seconds)
        rejected.cause should not be a[JournalHeadException]
        probe.expectNoMessage(500.millis)

        actor.tell(Persist("after"), probe.ref)
        probe.expectMsg(60.seconds, Persisted("after"))
      }

      info(s"observed journal sequence numbers: ${journalSequenceNrs(pid)}")
      journalSequenceNrs(pid) shouldBe Seq(1L, 3L)
      headItem(pid) shouldBe None
    }

    "fail a write whose transaction would exceed 4 MB and stop the actor" in {
      val pid = "head-4mb-1"
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        // 99 events with a 43,000 character tag each: the journal rows alone exceed 4 MB, the head item stays small
        actor.tell(PersistTaggedAll(99, 43000), probe.ref)
        expectPersistFailed(probe, actor) shouldBe a[JournalHeadTransactionTooLargeException]
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe None
    }

    "keep the JournalHead item and its stream unchanged on a soft delete" in {
      val pid = "head-soft-delete-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))
      val headBefore = headItem(pid)
      headStreamRecords(pid, 1).size shouldBe 1

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }

      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(true, true, true)
      headItem(pid) shouldBe headBefore
      headStreamRecords(pid, 1).size shouldBe 1
    }

    "not write the JournalHead item on a soft delete when the head is ahead" in {
      val pid = "head-soft-delete-ahead-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      // A soft delete that went through the JournalHead transaction would fail here: the journal rows already exist,
      // so the row condition rejects the write, and the head is ahead of the first sequence number.
      val headAheadItem = new PutItemRequest()
        .withTableName(journalHeadTableName)
        .withItem(
          Map(
            "persistence-id" -> new AttributeValue().withS(pid),
            "sequence-nr"    -> new AttributeValue().withN("10"),
            "events"         -> new AttributeValue().withL(java.util.Collections.emptyList[AttributeValue]())
          ).asJava
        )
      dynamoDBClient.putItem(headAheadItem)

      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }

      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(true, true, true)
      headSequenceNr(pid) shouldBe Some(10L)
    }

    "keep the JournalHead item and its stream unchanged on a hard delete" in {
      val pid = "head-hard-delete-1"
      persistAllOk(headEnabledHardDeleteSystem, pid, immutable.Seq("e1", "e2", "e3"))
      val headBefore = headItem(pid)
      headStreamRecords(pid, 1).size shouldBe 1

      withActor(headEnabledHardDeleteSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }

      journalSequenceNrs(pid) shouldBe Seq.empty
      headItem(pid) shouldBe headBefore
      headStreamRecords(pid, 1).size shouldBe 1
    }

    "keep writing above the JournalHead item after every event was hard deleted" in {
      val pid = "head-hard-delete-write-1"
      persistAllOk(headEnabledHardDeleteSystem, pid, immutable.Seq("e1", "e2", "e3"))
      withActor(headEnabledHardDeleteSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(3L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }
      journalSequenceNrs(pid) shouldBe Seq.empty
      headSequenceNr(pid) shouldBe Some(3L)

      // the journal is empty, but the JournalHead item is the lower bound of the highest sequence number
      persistOk(headEnabledHardDeleteSystem, pid, "e4")
      journalSequenceNrs(pid) shouldBe Seq(4L)
      headSequenceNr(pid) shouldBe Some(4L)
    }

    "reject an invalid write as a value so that the write queue keeps working" in {
      val pid = "head-invalid-write-1"
      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        val exception = failedWith(
          dao,
          Seq(journalRow(headEnabledSystem, pid, 1L, "e1"), journalRow(headEnabledSystem, pid, 3L, "e3"))
        )
        exception shouldBe a[JournalHeadInvalidWriteException]

        // the queue of the same dao still writes: the exception did not abort the stream
        Await.result(dao.putMessages(Seq(journalRow(headEnabledSystem, pid, 1L, "e1"))).runWith(Sink.head), 60.seconds)
        headSequenceNr(pid) shouldBe Some(1L)
      } finally dao.dispose()
    }

    "accept 99 events in one persistAll and fail 100 events" in {
      val acceptedPid = "head-99-events-1"
      persistAllOk(headEnabledSystem, acceptedPid, (1 to 99).map(i => s"e$i").toVector) shouldBe PersistedAll(99)
      journalSequenceNrs(acceptedPid) shouldBe (1L to 99L).toSeq
      headSequenceNr(acceptedPid) shouldBe Some(99L)

      val rejectedPid = "head-100-events-1"
      val failure     = persistFails(headEnabledSystem, rejectedPid, (1 to 100).map(i => s"e$i").toVector)
      failure shouldBe a[JournalHeadTooManyEventsException]
      failure.asInstanceOf[JournalHeadTooManyEventsException].eventCount shouldBe 100
      journalSequenceNrs(rejectedPid) shouldBe Seq.empty
      headItem(rejectedPid) shouldBe None
    }

    "accept an event that keeps the JournalHead item under the size limit and fail one that exceeds it" in {
      val acceptedPid = "head-size-under-1"
      persistOk(headEnabledSystem, acceptedPid, "x" * 406000)
      journalSequenceNrs(acceptedPid) shouldBe Seq(1L)
      headSequenceNr(acceptedPid) shouldBe Some(1L)

      val rejectedPid = "head-size-over-1"
      val failure     = persistFails(headEnabledSystem, rejectedPid, immutable.Seq("x" * 410000))
      failure shouldBe a[JournalHeadItemTooLargeException]
      journalSequenceNrs(rejectedPid) shouldBe Seq.empty
      headItem(rejectedPid) shouldBe None
    }

    "accept a JournalHead item of exactly 409600 bytes and reject 409601 bytes on the same queue" in {
      // Two events keep each journal row under 400 KB while the head item reaches the exact item size limit. One
      // event at the head limit would put the journal row itself over the limit, and DynamoDB would refuse it first.
      val acceptedPid                     = "head-size-boundary-accepted-1"
      val rejectedPid                     = "head-size-boundary-rejected-1"
      val (acceptedFirst, acceptedSecond) = messageLengthsAtItemSize(acceptedPid, JournalHeadLimits.MaxItemSizeBytes)
      val (rejectedFirst, rejectedSecond) =
        messageLengthsAtItemSize(rejectedPid, JournalHeadLimits.MaxItemSizeBytes + 1)

      headItemSize(acceptedPid, acceptedFirst, acceptedSecond) shouldBe JournalHeadLimits.MaxItemSizeBytes
      headItemSize(rejectedPid, rejectedFirst, rejectedSecond) shouldBe JournalHeadLimits.MaxItemSizeBytes + 1

      val dao = newWriteJournalDao(headEnabledSystem)
      try {
        implicit val mat: Materializer = SystemMaterializer(headEnabledSystem).materializer
        Await.result(
          dao
            .putMessages(
              Seq(
                journalRowWithMessage(acceptedPid, 1L, acceptedFirst),
                journalRowWithMessage(acceptedPid, 2L, acceptedSecond)
              )
            ).runWith(Sink.head),
          120.seconds
        ) shouldBe 2L
        journalSequenceNrs(acceptedPid) shouldBe Seq(1L, 2L)
        headSequenceNr(acceptedPid) shouldBe Some(2L)

        val exception = failedWith(
          dao,
          Seq(
            journalRowWithMessage(rejectedPid, 1L, rejectedFirst),
            journalRowWithMessage(rejectedPid, 2L, rejectedSecond)
          )
        )
        exception shouldBe a[JournalHeadItemTooLargeException]
        exception.asInstanceOf[JournalHeadItemTooLargeException].itemSizeBytes shouldBe
        JournalHeadLimits.MaxItemSizeBytes + 1
        journalSequenceNrs(rejectedPid) shouldBe Seq.empty
        headItem(rejectedPid) shouldBe None

        // the rejection did not abort the queue of the same dao
        Await.result(
          dao.putMessages(Seq(journalRowWithMessage(rejectedPid, 1L, 1))).runWith(Sink.head),
          60.seconds
        ) shouldBe 1L
        headSequenceNr(rejectedPid) shouldBe Some(1L)
      } finally dao.dispose()
    }

    "deliver the JournalHead stream records in sequence number order" in {
      val pid = "head-stream-1"
      persistOk(headEnabledSystem, pid, "e1")
      persistOk(headEnabledSystem, pid, "e2")
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e3", "e4"))

      val records = headStreamRecords(pid, 3)

      val headSequenceNrs = records
        .filter(r => r.dynamodb().newImage().get("persistence-id").s() == pid)
        .map(r => r.dynamodb().newImage().get("sequence-nr").n().toLong)
      // three writes: seq 1, seq 2, and the write of seq 3 and 4 (the head jumps to 4)
      headSequenceNrs shouldBe Seq(1L, 2L, 4L)

      val streamEvents = records
        .filter(r => r.dynamodb().newImage().get("persistence-id").s() == pid)
        .flatMap(_.dynamodb().newImage().get("events").l().asScala)
        .map(e => (e.m().get("sequence-nr").n().toLong, e.m().get("message").b().asByteArray.toSeq))
      streamEvents.map(_._1) shouldBe Seq(1L, 2L, 3L, 4L)

      val journalMessages = journalRows(pid)
        .map(row => (row("sequence-nr").getN.toLong, row("message").getB.array().toSeq))
      streamEvents shouldBe journalMessages
    }

  }
}
