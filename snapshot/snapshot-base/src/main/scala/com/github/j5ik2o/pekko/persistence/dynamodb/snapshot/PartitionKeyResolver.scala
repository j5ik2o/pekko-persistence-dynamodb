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

  /** The shard of the id, `md5(persistenceId.reverse) % shardCount + 1`, in 40 digits. */
  private[snapshot] def shardOf(persistenceId: PersistenceId, shardCount: Int): String = {
    val md5   = MessageDigest.getInstance("MD5")
    val df    = new DecimalFormat("0000000000000000000000000000000000000000")
    val bytes = persistenceId.asString.reverse.getBytes(StandardCharsets.UTF_8)
    val hash  = BigInt(md5.digest(bytes))
    df.format((hash.abs % shardCount) + 1)
  }

  /** The partition key of `PlainTextPersistenceIdBased`: `${prefix}-${shard}`, or the shard alone without a prefix. A
    * prefix that would make the key exceed 2,048 bytes is replaced with its marked SHA-256 (see
    * `PersistenceIdKeyParts.partitionKey`).
    */
  private[snapshot] def persistenceIdBased(
      persistenceId: PersistenceId,
      prefix: Option[String],
      shardCount: Int
  ): PartitionKey =
    PartitionKey(PersistenceIdKeyParts.partitionKey(prefix, shardOf(persistenceId, shardCount)))

  /** The partition key of `PersistenceIdBased`, the format of 1.3.0, which has no bound. */
  private[snapshot] def legacyPersistenceIdBased(
      persistenceId: PersistenceId,
      prefix: Option[String],
      shardCount: Int
  ): PartitionKey = {
    val shard = shardOf(persistenceId, shardCount)
    prefix match {
      case Some(modelName) => PartitionKey("%s-%s".format(modelName, shard))
      case None            => PartitionKey(shard) // fallback
    }
  }

  /** A partition key resolver, other than `PlainTextPersistenceIdBased`, that can also give the key that releases up to
    * 1.3.0 gave an id. Tests use it; the default resolver is recognized by its class.
    */
  private[snapshot] trait LegacyKeys {
    def resolveLegacy(persistenceId: PersistenceId): Option[PartitionKey]
  }

  /** The experimental resolver of the new key format (issue #1009). The prefix is the text before the first
    * `persistence-id-separator`, which is matched as plain text, so every id gets a key of its own. It is not the
    * default; select it by its class name.
    */
  final class PlainTextPersistenceIdBased(pluginContext: SnapshotPluginContext)
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

    /** The partition key that `PersistenceIdBased` gives the id, the default of releases up to 1.3.0 and of this one.
      * It is none when that resolver cannot build a key for the id, and when `legacy-keys-enabled` is false.
      */
    private[snapshot] def resolveLegacy(persistenceId: PersistenceId): Option[PartitionKey] =
      if (!legacyKeysEnabled) None
      else
        PersistenceIdKeyParts
          .legacyPrefix(persistenceId, separator)
          .map(legacyPersistenceIdBased(persistenceId, _, pluginConfig.shardCount))

  }

  /** The default resolver, which gives the keys of 1.3.0. It splits the id with `String.split`, which reads
    * `persistence-id-separator` as a regular expression, and keeps the first element, so ids can share a key with it
    * (issue #1009). `PlainTextPersistenceIdBased` gives every id a key of its own.
    */
  final class PersistenceIdBased(pluginContext: SnapshotPluginContext)
      extends PartitionKeyResolver
      with ToPersistenceIdOps {
    import pluginContext._

    override def separator: String =
      pluginConfig.sourceConfig.getAs[String]("persistence-id-separator").getOrElse(PersistenceId.Separator)

    // ${persistenceId.prefix}-${md5(persistenceId.reverse) % shardCount}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): PartitionKey =
      legacyPersistenceIdBased(persistenceId, persistenceId.prefix, pluginConfig.shardCount)

  }

}
