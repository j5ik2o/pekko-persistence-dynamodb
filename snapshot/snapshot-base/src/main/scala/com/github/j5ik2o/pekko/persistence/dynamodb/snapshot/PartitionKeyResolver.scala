/*
 * Copyright 2019 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot

import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, PersistenceIdKeyParts, SequenceNumber }
import net.ceedubs.ficus.Ficus._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.DecimalFormat

final case class PartitionKey(private val value: String) {
  def asString: String = value
}

trait PartitionKeyResolver {

  def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey

}

trait PartitionKeyResolverProvider {

  def create: PartitionKeyResolver

}

object PartitionKeyResolverProvider {

  def create(pluginContext: SnapshotPluginContext): PartitionKeyResolverProvider = {
    val className = pluginContext.pluginConfig.partitionKeyResolverProviderClassName
    pluginContext.newDynamicAccessor[PartitionKeyResolverProvider]().createThrow(className)
  }

  final class Default(pluginContext: SnapshotPluginContext) extends PartitionKeyResolverProvider {

    override def create: PartitionKeyResolver = {
      val className = pluginContext.pluginConfig.partitionKeyResolverClassName
      pluginContext.newDynamicAccessor[PartitionKeyResolver]().createThrow(className)
    }

  }
}

object PartitionKeyResolver {

  final class SequenceNumberBased(pluginContext: SnapshotPluginContext) extends PartitionKeyResolver {
    import pluginContext._

    // ${persistenceId}-${sequenceNumber % shardCount}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey = {
      val pkey = s"${persistenceId.asString}-${sequenceNumber.value % pluginConfig.shardCount}"
      PartitionKey(pkey)
    }

  }

  /** `${prefix}-${md5(persistenceId.reverse) % shardCount + 1}` with the shard in 40 digits, or the shard alone when
    * there is no prefix.
    */
  private[snapshot] def persistenceIdBased(
      persistenceId: PersistenceId,
      prefix: Option[String],
      shardCount: Int
  ): PartitionKey = {
    val md5   = MessageDigest.getInstance("MD5")
    val df    = new DecimalFormat("0000000000000000000000000000000000000000")
    val bytes = persistenceId.asString.reverse.getBytes(StandardCharsets.UTF_8)
    val hash  = BigInt(md5.digest(bytes))
    val mod   = (hash.abs % shardCount) + 1
    prefix match {
      case Some(modelName) => PartitionKey("%s-%s".format(modelName, df.format(mod)))
      case None            => PartitionKey(df.format(mod)) // fallback
    }
  }

  /** The default resolver. The prefix is the text before the first `persistence-id-separator`, which is matched as
    * plain text (issue #1009).
    */
  final class PersistenceIdBased(pluginContext: SnapshotPluginContext)
      extends PartitionKeyResolver
      with ToPersistenceIdOps {
    import pluginContext._

    override def separator: String =
      pluginConfig.sourceConfig.getAs[String]("persistence-id-separator").getOrElse(PersistenceId.Separator)

    private val legacyKeysEnabled: Boolean =
      pluginConfig.sourceConfig.getAs[Boolean]("legacy-keys-enabled").getOrElse(true)

    // ${the text before the first separator}-${md5(persistenceId.reverse) % shardCount}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      persistenceIdBased(
        persistenceId,
        PersistenceIdKeyParts.split(persistenceId, separator).map(_._1),
        pluginConfig.shardCount
      )

    /** The partition key that `LegacyPersistenceIdBased` gives the id, which releases up to 1.3.0 used by default. It
      * is none when that resolver cannot build a key for the id, and when `legacy-keys-enabled` is false.
      */
    private[snapshot] def resolveLegacy(persistenceId: PersistenceId): Option[PartitionKey] =
      if (!legacyKeysEnabled) None
      else
        PersistenceIdKeyParts
          .legacyPrefix(persistenceId, separator)
          .map(persistenceIdBased(persistenceId, _, pluginConfig.shardCount))

  }

  /** The default resolver of releases up to 1.3.0, kept as it was. It splits the id with `String.split`, which reads
    * `persistence-id-separator` as a regular expression, so ids can share a key with it (issue #1009). Select it only
    * to keep the keys of an existing table exactly as they were.
    */
  final class LegacyPersistenceIdBased(pluginContext: SnapshotPluginContext)
      extends PartitionKeyResolver
      with ToPersistenceIdOps {
    import pluginContext._

    override def separator: String =
      pluginConfig.sourceConfig.getAs[String]("persistence-id-separator").getOrElse(PersistenceId.Separator)

    // ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      persistenceIdBased(persistenceId, persistenceId.prefix, pluginConfig.shardCount)

  }

}
