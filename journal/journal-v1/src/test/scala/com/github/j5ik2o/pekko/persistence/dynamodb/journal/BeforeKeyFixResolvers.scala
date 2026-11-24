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

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.DecimalFormat

/** The default journal key resolvers as releases up to 1.3.0 shipped them (issue #1009), copied from 1.3.0. A test
  * system that is configured with these classes writes the rows that an existing deployment already has in its tables.
  */
object BeforeKeyFix {

  def prefix(id: String, separator: String): Option[String] =
    if (id.contains(separator)) Some(id.split(separator)(0)) else None

  def body(id: String, separator: String): Option[String] =
    if (id.contains(separator)) Some(id.split(separator)(1)) else None

  def partitionKey(id: String, separator: String, shardCount: Int): String = {
    val md5  = MessageDigest.getInstance("MD5")
    val df   = new DecimalFormat("0000000000000000000000000000000000000000")
    val hash = BigInt(md5.digest(id.reverse.getBytes(StandardCharsets.UTF_8)))
    val mod  = (hash.abs % shardCount) + 1
    prefix(id, separator) match {
      case Some(modelName) => "%s-%s".format(modelName, df.format(mod))
      case None            => df.format(mod)
    }
  }

  def sortKey(id: String, sequenceNr: Long, separator: String): String =
    "%s-%019d".format(body(id, separator).getOrElse(id), sequenceNr)

  final class JournalPartitionKeyResolver(pluginContext: JournalPluginContext) extends PartitionKeyResolver {
    private val separator =
      if (pluginContext.pluginConfig.sourceConfig.hasPath("persistence-id-separator"))
        pluginContext.pluginConfig.sourceConfig.getString("persistence-id-separator")
      else PersistenceId.Separator
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      PartitionKey(partitionKey(persistenceId.asString, separator, pluginContext.pluginConfig.shardCount))
  }

  final class JournalSortKeyResolver(pluginContext: JournalPluginContext) extends SortKeyResolver {
    private val separator =
      if (pluginContext.pluginConfig.sourceConfig.hasPath("persistence-id-separator"))
        pluginContext.pluginConfig.sourceConfig.getString("persistence-id-separator")
      else PersistenceId.Separator
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey(sortKey(persistenceId.asString, sequenceNumber.value, separator))
  }
}
