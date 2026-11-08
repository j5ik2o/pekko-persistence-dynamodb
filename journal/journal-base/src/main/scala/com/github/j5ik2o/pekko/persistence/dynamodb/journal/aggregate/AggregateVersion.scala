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

import com.github.j5ik2o.pekko.persistence.dynamodb.model.SequenceNumber

/** The sequence number an aggregate has reached, and the number its next write starts from.
  *
  * The journal and the JournalHead item are two sources of the same highest sequence number, and Pekko asks for the
  * number the next write starts from. Deriving the next number here keeps the pair from drifting apart, so no caller
  * adds one to a highest sequence number of its own.
  */
final case class AggregateVersion(highestSequenceNr: Long) {

  def nextSequenceNr: Long = Math.addExact(highestSequenceNr, 1L)

}

object AggregateVersion {

  val Zero: AggregateVersion = AggregateVersion(0L)

  /** Reads a highest sequence number. An absent value means the aggregate has no event yet. */
  def fromHighestSequenceNr(highestSequenceNr: Option[Long]): AggregateVersion =
    AggregateVersion(highestSequenceNr.getOrElse(0L))

  def fromSequenceNumber(sequenceNumber: SequenceNumber): AggregateVersion =
    AggregateVersion(sequenceNumber.value)

  def highestOf(left: AggregateVersion, right: AggregateVersion): AggregateVersion =
    if (left.highestSequenceNr >= right.highestSequenceNr) left else right

}
