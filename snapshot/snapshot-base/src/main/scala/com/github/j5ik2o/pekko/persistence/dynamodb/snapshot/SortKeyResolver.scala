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

final case class SortKey(value: String) {
  def asString: String = value
}

trait SortKeyResolver {
  def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey
}

trait SortKeyResolverProvider {

  def create: SortKeyResolver

}

object SortKeyResolverProvider {

  def create(pluginContext: SnapshotPluginContext): SortKeyResolverProvider = {
    val className = pluginContext.pluginConfig.sortKeyResolverProviderClassName
    pluginContext.newDynamicAccessor[SortKeyResolverProvider]().createThrow(className)
  }

  final class Default(
      pluginContext: SnapshotPluginContext
  ) extends SortKeyResolverProvider {

    override def create: SortKeyResolver = {
      val className = pluginContext.pluginConfig.sortKeyResolverClassName
      pluginContext.newDynamicAccessor[SortKeyResolver]().createThrow(className)
    }

  }

}

object SortKeyResolver {

  /** `${the text after the first separator}-${sequenceNumber}`, or `${persistenceId}-${sequenceNumber}` when the id
    * does not contain the separator. The separator is matched as plain text, so two different ids never share a key.
    * When the key would exceed 1,024 UTF-8 bytes, the text is replaced with its marked SHA-256 (see
    * `PersistenceIdKeyParts.sortKey`).
    */
  private[snapshot] def persistenceIdWithSeqNrSKey(
      persistenceId: PersistenceId,
      sequenceNumber: SequenceNumber,
      separator: String
  ): String =
    PersistenceIdKeyParts.sortKey(
      PersistenceIdKeyParts.split(persistenceId, separator).fold(persistenceId.asString)(_._2),
      sequenceNumber
    )

  /** The sort key of `LegacyPersistenceIdWithSeqNr`: the second element of a split that reads the separator as a
    * regular expression. It throws where releases up to 1.3.0 threw.
    */
  private[snapshot] def legacyPersistenceIdWithSeqNrSKey(
      persistenceId: PersistenceId,
      sequenceNumber: SequenceNumber,
      separator: String
  ): String = {
    val bodyOpt = new PersistenceIdOps(persistenceId, separator).body
    val seq     = sequenceNumber.value
    bodyOpt match {
      case Some(pid) =>
        "%s-%019d".format(pid, seq)
      case None =>
        "%s-%019d".format(persistenceId.asString, seq)
    }
  }

  final class SeqNr extends SortKeyResolver {

    // ${sequenceNumber}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey = {
      SortKey(sequenceNumber.value.toString)
    }

  }

  /** The default resolver. The body is the text after the first `persistence-id-separator`, which is matched as plain
    * text (issue #1009).
    */
  final class PersistenceIdWithSeqNr(pluginContext: SnapshotPluginContext)
      extends SortKeyResolver
      with ToPersistenceIdOps {

    override def separator: String =
      pluginContext.pluginConfig.sourceConfig
        .getAs[String]("persistence-id-separator").getOrElse(PersistenceId.Separator)

    private val legacyKeysEnabled: Boolean =
      pluginContext.pluginConfig.sourceConfig.getAs[Boolean]("legacy-keys-enabled").getOrElse(true)

    // ${the text after the first separator, or the whole persistenceId}-${sequenceNumber}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey(persistenceIdWithSeqNrSKey(persistenceId, sequenceNumber, separator))

    /** The sort key that `LegacyPersistenceIdWithSeqNr` gives the event, which releases up to 1.3.0 used by default. It
      * is none when that resolver cannot build a key for the id, and when `legacy-keys-enabled` is false.
      */
    private[snapshot] def resolveLegacy(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): Option[SortKey] =
      if (!legacyKeysEnabled) None
      else
        PersistenceIdKeyParts
          .legacyBody(persistenceId, separator)
          .map(body => SortKey("%s-%019d".format(body.getOrElse(persistenceId.asString), sequenceNumber.value)))

  }

  /** The default resolver of releases up to 1.3.0, kept as it was. It keeps only the second element of a split that
    * reads `persistence-id-separator` as a regular expression, so ids can share a key with it (issue #1009). Select it
    * only to keep the keys of an existing table exactly as they were.
    */
  final class LegacyPersistenceIdWithSeqNr(pluginContext: SnapshotPluginContext)
      extends SortKeyResolver
      with ToPersistenceIdOps {

    override def separator: String =
      pluginContext.pluginConfig.sourceConfig
        .getAs[String]("persistence-id-separator").getOrElse(PersistenceId.Separator)

    // ${persistenceId.bodyOrFullPersistenceId}-${sequenceNumber}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey =
      SortKey(legacyPersistenceIdWithSeqNrSKey(persistenceId, sequenceNumber, separator))

  }

}
