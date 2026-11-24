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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal

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

  def create(pluginContext: JournalPluginContext): SortKeyResolverProvider = {
    val className = pluginContext.pluginConfig.sortKeyResolverProviderClassName
    pluginContext.newDynamicAccessor[SortKeyResolverProvider]().createThrow(className)
  }

  final class Default(
      pluginContext: JournalPluginContext
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
    */
  private[journal] def persistenceIdWithSeqNrSKey(
      persistenceId: PersistenceId,
      sequenceNumber: SequenceNumber,
      separator: String
  ): String =
    withSeqNr(PersistenceIdKeyParts.split(persistenceId, separator).fold(persistenceId.asString)(_._2), sequenceNumber)

  /** The sort key that releases up to 1.2.0 gave the row: the second element of a split that reads the separator as a
    * regular expression. It is none when they could not write a row for the id.
    */
  private[journal] def persistenceIdWithSeqNrSKeyBeforeFix(
      persistenceId: PersistenceId,
      sequenceNumber: SequenceNumber,
      separator: String
  ): Option[String] =
    PersistenceIdKeyParts
      .bodyBeforeFix(persistenceId, separator)
      .map(body => withSeqNr(body.getOrElse(persistenceId.asString), sequenceNumber))

  private def withSeqNr(body: String, sequenceNumber: SequenceNumber): String =
    "%s-%019d".format(body, sequenceNumber.value)

  final class SeqNr extends SortKeyResolver {

    // ${sequenceNumber}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey = {
      SortKey(sequenceNumber.value.toString)
    }

  }

  final class PersistenceIdWithSeqNr(pluginContext: JournalPluginContext)
      extends SortKeyResolver
      with ToPersistenceIdOps {

    override def separator: String =
      pluginContext.pluginConfig.sourceConfig
        .getAs[String]("persistence-id-separator").getOrElse(PersistenceId.Separator)

    // ${the text after the first separator, or the whole persistenceId}-${sequenceNumber}
    override def resolve(persistenceId: PersistenceId, sequenceNumber: SequenceNumber): SortKey = {
      SortKey(persistenceIdWithSeqNrSKey(persistenceId, sequenceNumber, separator))
    }

    /** The sort key that releases up to 1.2.0 gave the row, or none when they could not write a row for the id. */
    private[journal] def resolveBeforeFix(
        persistenceId: PersistenceId,
        sequenceNumber: SequenceNumber
    ): Option[SortKey] =
      persistenceIdWithSeqNrSKeyBeforeFix(persistenceId, sequenceNumber, separator).map(SortKey)

  }

}
