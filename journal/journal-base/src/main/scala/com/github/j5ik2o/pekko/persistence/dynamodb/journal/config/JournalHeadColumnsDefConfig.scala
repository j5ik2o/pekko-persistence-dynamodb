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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.config

import net.ceedubs.ficus.Ficus._
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.LoggingSupport
import com.typesafe.config.{ Config, ConfigFactory }

object JournalHeadColumnsDefConfig extends LoggingSupport {

  val persistenceIdColumnNameKey   = "persistence-id-column-name"
  val sequenceNrColumnNameKey      = "sequence-nr-column-name"
  val eventsColumnNameKey          = "events-column-name"
  val eventSequenceNrColumnNameKey = "event-sequence-nr-column-name"
  val eventMessageColumnNameKey    = "event-message-column-name"

  def fromConfig(config: Config): JournalHeadColumnsDefConfig = {
    val cfg = if (config == null) ConfigFactory.empty() else config
    JournalHeadColumnsDefConfig(
      persistenceIdColumnName = cfg.getAs[String](persistenceIdColumnNameKey).getOrElse("persistence-id"),
      sequenceNrColumnName = cfg.getAs[String](sequenceNrColumnNameKey).getOrElse("sequence-nr"),
      eventsColumnName = cfg.getAs[String](eventsColumnNameKey).getOrElse("events"),
      eventSequenceNrColumnName = cfg.getAs[String](eventSequenceNrColumnNameKey).getOrElse("sequence-nr"),
      eventMessageColumnName = cfg.getAs[String](eventMessageColumnNameKey).getOrElse("message")
    )
  }

}

final case class JournalHeadColumnsDefConfig(
    persistenceIdColumnName: String,
    sequenceNrColumnName: String,
    eventsColumnName: String,
    eventSequenceNrColumnName: String,
    eventMessageColumnName: String
)
