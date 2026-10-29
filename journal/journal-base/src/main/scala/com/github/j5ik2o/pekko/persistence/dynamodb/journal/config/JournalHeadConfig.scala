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
import com.typesafe.config.{ Config, ConfigFactory }

object JournalHeadConfig {

  val headKey          = "head"
  val enabledKey       = "enabled"
  val tableNameKey     = "table-name"
  val columnsDefKey    = "columns-def"
  val defaultTableName = "JournalHead"

  def fromConfig(config: Config): JournalHeadConfig = {
    val cfg = if (config == null) ConfigFactory.empty() else config
    JournalHeadConfig(
      enabled = cfg.getAs[Boolean](enabledKey).getOrElse(false),
      tableName = cfg.getAs[String](tableNameKey).getOrElse(defaultTableName),
      columnsDefConfig = JournalHeadColumnsDefConfig.fromConfig(
        cfg.getAs[Config](columnsDefKey).getOrElse(ConfigFactory.empty())
      )
    )
  }

}

/** Configuration of the JournalHead table.
  *
  * The JournalHead table holds one item per persistence id. That item carries the last sequence number written for the
  * persistence id and the events of the write that produced it. It is used as the ordering source for the read model
  * updates: DynamoDB Streams on this table deliver the events of a single persistence id in write order.
  *
  * @param enabled
  *   when true, the journal plugin writes the journal rows and the JournalHead item in one TransactWriteItems call.
  *   When false the plugin behaves exactly as before.
  * @param tableName
  *   name of the JournalHead table. It must differ from the journal table name.
  * @param columnsDefConfig
  *   column names of the JournalHead table.
  */
final case class JournalHeadConfig(
    enabled: Boolean,
    tableName: String,
    columnsDefConfig: JournalHeadColumnsDefConfig
)
