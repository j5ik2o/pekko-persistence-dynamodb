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

import org.apache.pekko.Done
import org.apache.pekko.actor.Status
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.head._
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.DockerAssignedPort

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

object JournalHeadInPlaceUpdateSpec extends DockerAssignedPort

final class JournalHeadInPlaceUpdateSpec extends JournalHeadSpecSupport {

  import JournalHeadSpecSupport._

  override lazy val dynamoDBPort: Int = JournalHeadInPlaceUpdateSpec.dynamoDBPort

  override lazy val controller = JournalHeadInPlaceUpdateSpec.controller

  "JournalHead" should {
    "update the journal row and the JournalHead event of an in-place event update in one transaction when head mode is enabled" in {
      val pid = "head-in-place-update-1"
      persistOk(headEnabledSystem, pid, "e1")

      headItem(pid).get.contains("revision") shouldBe false

      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "updated")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "updated")
      headSequenceNr(pid) shouldBe Some(1L)
      headItem(pid).get("revision").getN shouldBe "1"
      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(false)
    }

    "raise the revision of the JournalHead item by one on every in-place event update and keep the sequence number" in {
      val pid = "head-in-place-update-revision-1"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2"))

      updateEvent(headEnabledSystem, pid, 1L, "first") shouldBe Done
      updateEvent(headEnabledSystem, pid, 2L, "second") shouldBe Done
      updateEvent(headEnabledSystem, pid, 2L, "third") shouldBe Done

      headItem(pid).get("revision").getN shouldBe "3"
      headSequenceNr(pid) shouldBe Some(2L)
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "first", 2L -> "third")
    }

    "write no revision when the next write replaces the JournalHead item after an in-place event update" in {
      val pid = "head-in-place-update-revision-2"
      persistOk(headEnabledSystem, pid, "e1")
      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done
      headItem(pid).get("revision").getN shouldBe "1"

      persistOk(headEnabledSystem, pid, "e2")

      headItem(pid).get.contains("revision") shouldBe false
      headSequenceNr(pid) shouldBe Some(2L)
    }

    "deliver an in-place event update as a stream record with the same sequence number and a higher revision" in {
      val pid = "head-in-place-update-stream-1"
      persistOk(headEnabledSystem, pid, "e1")
      updateEvent(headEnabledSystem, pid, 1L, "updated") shouldBe Done

      val images = headStreamRecords(pid, 2)
        .map(_.dynamodb().newImage())
        .filter(_.get("persistence-id").s() == pid)

      images.map(_.get("sequence-nr").n().toLong) shouldBe Seq(1L, 1L)
      images.map(image => Option(image.get("revision")).map(_.n().toLong)) shouldBe Seq(None, Some(1L))
      images.map(image =>
        payloadOf(headEnabledSystem, image.get("events").l().get(0).m().get("message").b().asByteArray)
      ) shouldBe Seq("e1", "updated")
    }

    "refuse an in-place update of a soft deleted event and keep it deleted" in {
      val pid = "head-in-place-update-deleted-1"
      persistOk(headEnabledSystem, pid, "e1")
      withActor(headEnabledSystem, pid) { (actor, probe) =>
        actor.tell(DeleteTo(1L), probe.ref)
        probe.expectMsg(60.seconds, Deleted())
      }
      val headBefore = headItem(pid)

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadRowUpdateRejectedException]
      failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe JournalRowUpdateRejection.RowDeleted

      journalRows(pid).map(_("deleted").getBOOL.booleanValue()) shouldBe Seq(true)
      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headItem(pid) shouldBe headBefore
    }

    "keep the other events of the JournalHead item on an in-place event update of one event of a persistAll write" in {
      val pid = "head-in-place-update-2"
      persistAllOk(headEnabledSystem, pid, immutable.Seq("e1", "e2", "e3"))

      updateEvent(headEnabledSystem, pid, 2L, "updated") shouldBe Done

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "updated", 3L -> "e3")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "updated", 3L -> "e3")
      headSequenceNr(pid) shouldBe Some(3L)
    }

    "refuse an in-place update of an event that is not in the latest write of the JournalHead item" in {
      val pid = "head-in-place-update-3"
      persistOk(headEnabledSystem, pid, "e1")
      persistOk(headEnabledSystem, pid, "e2")

      // The head holds only the events of the latest write, so the head stream has already delivered event 1 and
      // cannot be told about a change. The update is refused instead of changing only the journal row.
      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadAsyncUpdateEventUnsupportedException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1", 2L -> "e2")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(2L -> "e2")
    }

    "leave the journal and the JournalHead item unchanged when an in-place event update would make the head item exceed 400 KB" in {
      val pid     = "head-in-place-update-4"
      val payload = "x" * 125000
      persistAllOk(headEnabledSystem, pid, immutable.Seq(payload, payload, payload))

      // each journal row stays far below 400 KB, but the three events of the head would not fit with the new one
      val failure = updateEventFails(headEnabledSystem, pid, 3L, "y" * 160000)
      failure shouldBe a[JournalHeadItemTooLargeException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> payload, 2L -> payload, 3L -> payload)
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> payload, 2L -> payload, 3L -> payload)
      headItem(pid).get.contains("revision") shouldBe false
    }

    "refuse an in-place event update whose journal row would exceed 400 KB before it calls DynamoDB" in {
      val pid = "head-in-place-update-4b"
      persistOk(headEnabledSystem, pid, "e1")

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "x" * 410000)
      failure shouldBe a[JournalHeadJournalRowTooLargeException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
    }

    "end every in-place event update that races with persists in a typed outcome and keep the journal and the head in agreement" in {
      val pid      = "head-in-place-race-1"
      val count    = 60
      val progress = new java.util.concurrent.atomic.AtomicLong(0)
      // the journal plugin starts on its first use, and the updates go to the plugin directly
      persistOk(headEnabledSystem, "head-in-place-race-start-plugin", "e1")
      val journal = journalActor(headEnabledSystem)
      val probe   = newTestProbe(headEnabledSystem)
      val actor   = newActor(headEnabledSystem, pid, probe.ref)

      import org.apache.pekko.pattern.ask
      implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(120.seconds)

      actor.tell(PersistChain(count, progress), probe.ref)
      var updates                     = Vector.empty[scala.concurrent.Future[Any]]
      var persistOutcome: Option[Any] = None
      var lastSent                    = 0L
      val deadline                    = System.nanoTime() + 120.seconds.toNanos
      while (persistOutcome.isEmpty && System.nanoTime() < deadline) {
        // The actor persists the next event as soon as one is confirmed, so the updates of the event just confirmed
        // run at the same time as the write of the next one.
        val latest = progress.get()
        if (latest > lastSent) {
          // three updates of the same event also race with each other, on top of the next write
          updates ++= (1 to 3).map(n => journal ? DynamoDBJournal.InPlaceUpdateEvent(pid, latest, s"u$latest-$n"))
          lastSent = latest
        }
        persistOutcome = Option(probe.receiveOne(1.millis))
      }
      headEnabledSystem.stop(actor)

      val results = updates.map(update => Await.ready(update, 120.seconds).value.get)
      val outcomes = results.map {
        case scala.util.Success(_)  => "Done"
        case scala.util.Failure(ex) => ex.getClass.getSimpleName
      }
      val counts = outcomes.groupBy(identity).map { case (outcome, same) => outcome -> same.size }
      info(s"updates sent=${updates.size} outcomes=$counts")
      info(s"persist outcome=$persistOutcome persisted up to ${progress.get()} of $count")

      val typed = Set(
        "Done",
        classOf[JournalHeadAsyncUpdateEventUnsupportedException].getSimpleName,
        classOf[JournalHeadRowUpdateRejectedException].getSimpleName,
        classOf[JournalHeadConcurrentUpdateException].getSimpleName
      )
      outcomes.filterNot(typed.contains) shouldBe empty

      val journalBySeq = journalPayloads(headEnabledSystem, pid).toMap
      headPayloads(headEnabledSystem, pid).foreach { case (sequenceNr, payload) =>
        journalBySeq.get(sequenceNr) shouldBe Some(payload)
      }
    }

    "refuse an in-place event update whose journal row was hard deleted and create no journal row" in {
      val pid = "head-in-place-update-5"
      persistOk(headEnabledSystem, pid, "e1")
      // A hard delete removes the journal row and leaves the head alone, so the head still holds event 1.
      deleteJournalRows(pid)

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadRowUpdateRejectedException]
      failure.asInstanceOf[JournalHeadRowUpdateRejectedException].reason shouldBe
      JournalRowUpdateRejection.RowDoesNotExist

      journalSequenceNrs(pid) shouldBe Seq.empty
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
    }

    "refuse an in-place update when the aggregate has no JournalHead item" in {
      val pid = "head-in-place-update-6"
      persistOk(headDisabledSystem, pid, "e1")
      // Pekko starts the journal plugin of a system on its first use, and the update goes to the plugin directly.
      persistOk(headEnabledSystem, "head-in-place-update-6-start-plugin", "e1")

      val failure = updateEventFails(headEnabledSystem, pid, 1L, "updated")
      failure shouldBe a[JournalHeadMissingException]

      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headItem(pid) shouldBe None
    }

    "report an in-place update of an event that cannot be serialized as an illegal argument that keeps the cause" in {
      val pid = "head-in-place-update-7"
      persistOk(headEnabledSystem, pid, "e1")

      val probe = newTestProbe(headEnabledSystem)
      journalActor(headEnabledSystem)
        .tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, new Object), probe.ref)
      val failure = probe.expectMsgType[Status.Failure](60.seconds).cause

      failure shouldBe an[IllegalArgumentException]
      failure.getCause should not be null
      journalPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
      headPayloads(headEnabledSystem, pid) shouldBe Seq(1L -> "e1")
    }

    "allow an in-place event update when head mode is disabled" in {
      val pid = "head-disabled-in-place-update-1"
      persistOk(headDisabledSystem, pid, "e1")

      val journal = journalActor(headDisabledSystem)
      val probe   = newTestProbe(headDisabledSystem)
      journal.tell(DynamoDBJournal.InPlaceUpdateEvent(pid, 1L, "updated"), probe.ref)

      probe.expectMsg(60.seconds, Done)
      journalSequenceNrs(pid) shouldBe Seq(1L)
    }

  }
}
