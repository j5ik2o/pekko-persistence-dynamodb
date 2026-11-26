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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.released

import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorRef, ActorSystem, PoisonPill, Props, Status }
import org.apache.pekko.pattern.ask
import org.apache.pekko.persistence._
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

/** The program that `ReleasedRowsMigrationSpec` runs in a separate JVM, with the published plugin of a release or with
  * the plugin of this code base on the classpath, and the plugin configuration in `-Dconfig.file` (issue #1027).
  *
  * It uses only the API of Pekko Persistence, so the same class runs with either plugin. The arguments are the steps,
  * separated by commas, and the persistence ids:
  *
  *   - `write`: persists `e1` and `e2`, saves the snapshot `snap@2`, persists `e3`, and deletes the events up to 1.
  *   - `mutate`: recovers; with the highest sequence number `L` and the snapshot at `P`, persists `e(L+1)`, saves
  *     `snap@(L+1)`, deletes the snapshot at `P`, deletes the events up to `L-1`, and persists `e(L+2)`.
  *   - `read`: recovers three times and prints each state as a line `STATE <id> <mode> <state>`: `full` (the latest
  *     snapshot and the events after it), `events` (no snapshot, every event that is not deleted), and `older` (the
  *     latest snapshot below `P`, which `mutate` deleted, and the events after it).
  *
  * Each payload holds its persistence id, so a row of another persistence id shows in the state.
  */
object ReleasedRowsDriver {

  final case class Persist(payload: String)
  final case class SaveSnap(payload: String)
  final case class DeleteEventsTo(sequenceNr: Long)
  final case class DeleteSnapAt(sequenceNr: Long)
  case object GetState

  final case class State(snapshot: Option[(Long, String)], events: Vector[(Long, String)], last: Long) {
    def render: String = {
      val snap = snapshot.fold("none") { case (seq, payload) => s"$seq:$payload" }
      s"snapshot=$snap events=${events.map { case (seq, payload) => s"$seq:$payload" }.mkString(",")} last=$last"
    }
  }

  final class Subject(id: String, recoveryMode: Recovery) extends PersistentActor {
    override def persistenceId: String = id
    override def recovery: Recovery    = recoveryMode

    private var snapshot: Option[(Long, String)] = None
    private var events                           = Vector.empty[(Long, String)]
    private var replyTo: ActorRef                = ActorRef.noSender

    override def receiveRecover: Receive = {
      case SnapshotOffer(metadata, payload: String) => snapshot = Some(metadata.sequenceNr -> payload)
      case payload: String                          => events :+= (lastSequenceNr -> payload)
      case RecoveryCompleted                        =>
    }

    override def receiveCommand: Receive = {
      case GetState => sender() ! State(snapshot, events, lastSequenceNr)
      case Persist(payload) =>
        val to = sender()
        persist(payload)(_ => to ! lastSequenceNr)
      case SaveSnap(payload)           => replyTo = sender(); saveSnapshot(payload)
      case SaveSnapshotSuccess(_)      => replyTo ! Done
      case SaveSnapshotFailure(_, e)   => replyTo ! Status.Failure(e)
      case DeleteEventsTo(sequenceNr)  => replyTo = sender(); deleteMessages(sequenceNr)
      case DeleteMessagesSuccess(_)    => replyTo ! Done
      case DeleteMessagesFailure(e, _) => replyTo ! Status.Failure(e)
      case DeleteSnapAt(sequenceNr)    => replyTo = sender(); deleteSnapshot(sequenceNr)
      case DeleteSnapshotSuccess(_)    => replyTo ! Done
      case DeleteSnapshotFailure(_, e) => replyTo ! Status.Failure(e)
    }

    override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
      Console.err.println(s"persist failed for $id at $seqNr: $cause")
      super.onPersistFailure(cause, event, seqNr)
    }
  }

  private implicit val timeout: Timeout = Timeout(60.seconds)

  private def call(subject: ActorRef, message: Any): Any = Await.result(subject ? message, timeout.duration)

  private def withSubject[T](system: ActorSystem, id: String, recovery: Recovery)(f: ActorRef => T): T = {
    val subject = system.actorOf(Props(new Subject(id, recovery)))
    try f(subject)
    finally subject ! PoisonPill
  }

  private def state(system: ActorSystem, id: String, recovery: Recovery): State =
    withSubject(system, id, recovery)(call(_, GetState).asInstanceOf[State])

  private def write(system: ActorSystem, id: String): Unit =
    withSubject(system, id, Recovery()) { subject =>
      call(subject, Persist(s"$id:e1"))
      call(subject, Persist(s"$id:e2"))
      call(subject, SaveSnap(s"$id:snap@2"))
      call(subject, Persist(s"$id:e3"))
      call(subject, DeleteEventsTo(1))
    }

  private def mutate(system: ActorSystem, id: String): Unit =
    withSubject(system, id, Recovery()) { subject =>
      val current  = call(subject, GetState).asInstanceOf[State]
      val last     = current.last
      val snapshot = current.snapshot.map(_._1).getOrElse(sys.error(s"$id has no snapshot"))
      call(subject, Persist(s"$id:e${last + 1}"))
      call(subject, SaveSnap(s"$id:snap@${last + 1}"))
      call(subject, DeleteSnapAt(snapshot))
      call(subject, DeleteEventsTo(last - 1))
      call(subject, Persist(s"$id:e${last + 2}"))
    }

  private def read(system: ActorSystem, id: String): Unit = {
    val full = state(system, id, Recovery())
    println(s"STATE\t$id\tfull\t${full.render}")
    println(s"STATE\t$id\tevents\t${state(system, id, Recovery(fromSnapshot = SnapshotSelectionCriteria.None)).render}")
    val below = full.snapshot.map(_._1 - 1).getOrElse(0L)
    val older = state(system, id, Recovery(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = below)))
    println(s"STATE\t$id\tolder\t${older.render}")
  }

  def main(args: Array[String]): Unit = {
    val steps  = args.head.split(',').toSeq
    val ids    = args.tail.toSeq
    val system = ActorSystem("released-rows-driver")
    val ok =
      try {
        for {
          step <- steps
          id <- ids
        } step match {
          case "write"  => write(system, id)
          case "mutate" => mutate(system, id)
          case "read"   => read(system, id)
          case other    => sys.error(s"unknown step $other")
        }
        true
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          false
      } finally {
        Await.result(system.terminate(), 60.seconds)
        ()
      }
    if (!ok) sys.exit(1)
  }
}
