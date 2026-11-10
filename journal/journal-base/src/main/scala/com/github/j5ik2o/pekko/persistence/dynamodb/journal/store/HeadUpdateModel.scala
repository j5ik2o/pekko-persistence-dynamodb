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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, ConditionFailure, DdbValue }

/** A JournalHead item that was read, with every attribute the update needs already checked.
  *
  * An item that does not have this shape was not written by the plugin or the migration tool, so `parse` reports it as
  * a plain message that the caller turns into an illegal state instead of guessing a value.
  */
private[store] final case class ObservedHead(
    item: AttributeMap,
    sequenceNr: BigDecimal,
    revision: Option[BigDecimal],
    events: Vector[ObservedHead.Event]
) {

  def revisionOrZero: BigDecimal = revision.getOrElse(BigDecimal(0))

  /** The head only moves forward: a write raises `sequence-nr` and an update raises `revision`. */
  def isBehind(previous: ObservedHead): Boolean =
    sequenceNr < previous.sequenceNr || (sequenceNr == previous.sequenceNr && revisionOrZero < previous.revisionOrZero)

}

private[store] object ObservedHead {

  final case class Event(sequenceNr: BigDecimal, entries: Vector[(String, DdbValue)], message: Vector[Byte]) {

    def withMessage(messageName: String, value: Vector[Byte]): Event =
      copy(
        entries = entries.map {
          case (name, _) if name == messageName => name -> DdbValue.Bin(value)
          case entry                            => entry
        },
        message = value
      )

    def toValue: DdbValue = DdbValue.M(entries)

  }

  final case class Names(
      sequenceNr: AttributeName,
      events: AttributeName,
      revision: AttributeName,
      eventSequenceNr: String,
      eventMessage: String
  )

  def parse(item: AttributeMap, names: Names): Either[String, ObservedHead] =
    for {
      sequenceNr <- item.get(names.sequenceNr) match {
        case Some(DdbValue.Num(value)) => Right(value)
        case Some(other)               => Left(s"`${names.sequenceNr.value}` is not a number: $other")
        case None                      => Left(s"`${names.sequenceNr.value}` is missing")
      }
      revision <- item.get(names.revision) match {
        case Some(DdbValue.Num(value)) => Right(Some(value))
        case Some(other)               => Left(s"`${names.revision.value}` is not a number: $other")
        case None                      => Right(None)
      }
      rawEvents <- item.get(names.events) match {
        case Some(DdbValue.L(values)) => Right(values)
        case Some(other)              => Left(s"`${names.events.value}` is not a list: $other")
        case None                     => Left(s"`${names.events.value}` is missing")
      }
      events <- rawEvents.foldLeft[Either[String, Vector[Event]]](Right(Vector.empty)) { (acc, raw) =>
        acc.flatMap(events => parseEvent(raw, names).map(events :+ _))
      }
    } yield ObservedHead(item, sequenceNr, revision, events)

  private def parseEvent(raw: DdbValue, names: Names): Either[String, Event] =
    raw match {
      case DdbValue.M(entries) =>
        val sequenceNr = entries.collectFirst {
          case (name, DdbValue.Num(value)) if name == names.eventSequenceNr => value
        }
        val message = entries.collectFirst { case (name, DdbValue.Bin(value)) if name == names.eventMessage => value }
        (sequenceNr, message) match {
          case (Some(sequenceNr), Some(message)) => Right(Event(sequenceNr, entries, message))
          case _ =>
            Left(
              s"an element of `${names.events.value}` has no numeric `${names.eventSequenceNr}` or binary `${names.eventMessage}`"
            )
        }
      case other =>
        Left(s"an element of `${names.events.value}` is not a map: $other")
    }

}

/** Why DynamoDB cancelled the transaction of an update, as the store acts on it.
  *
  * Index 0 of the transaction is the journal row and index 1 is the JournalHead item.
  */
private[store] sealed trait UpdateCancellation

private[store] object UpdateCancellation {

  /** The journal row does not exist or is deleted. The store reads the row to tell which. */
  case object RowConditionFailed extends UpdateCancellation

  /** The `sequence-nr` or the `revision` of the head is not the one that was read. */
  case object HeadChanged extends UpdateCancellation

  /** Other transactions touch the same items, or DynamoDB throttled the request. Trying again later can succeed. */
  final case class Contended(code: String) extends UpdateCancellation

  /** A reason that this code does not know how to recover from. */
  final case class Unknown(reasons: Vector[ConditionFailure]) extends UpdateCancellation

  private val ConditionalCheckFailed = "ConditionalCheckFailed"
  private val JournalRowIndex        = 0
  private val HeadIndex              = 1
  private val contention             = Set("TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded")

  def classify(reasons: Vector[ConditionFailure]): UpdateCancellation = {
    val failed = reasons.collect { case ConditionFailure(index, code) if code != "None" => (index, code) }
    if (failed.contains((JournalRowIndex, ConditionalCheckFailed))) RowConditionFailed
    else if (failed.contains((HeadIndex, ConditionalCheckFailed))) HeadChanged
    else
      failed
        .collectFirst { case (_, code) if contention.contains(code) => Contended(code) }
        .getOrElse(Unknown(reasons.filter(_.code != "None")))
  }

}
