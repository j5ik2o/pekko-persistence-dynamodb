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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v2

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.ClientVariant
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v2.V2ClientAdapter
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.JournalRowReadDriver
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.store.JournalRowStore
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ JournalPluginContext, JournalRow }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient, DynamoDbClient }

import scala.collection.mutable.ArrayBuffer

final class V2JournalRowReadDriver(
    val pluginContext: JournalPluginContext,
    val asyncClient: Option[DynamoDbAsyncClient],
    val syncClient: Option[DynamoDbClient]
) extends JournalRowReadDriver {

  override val system = pluginContext.system

  import pluginContext._

  (asyncClient, syncClient) match {
    case (None, None) =>
      throw new IllegalArgumentException("aws clients is both None")
    case _ =>
  }

  private def clientVariant: ClientVariant =
    ClientVariant
      .fromClientVersion(pluginConfig.clientConfig.clientVersion)
      .getOrElse(
        throw new IllegalArgumentException(
          s"unsupported client version: ${pluginConfig.clientConfig.clientVersion}"
        )
      )

  private val store = JournalRowStore.fromPluginContext(
    pluginContext,
    new V2ClientAdapter(clientVariant, asyncClient, syncClient, pluginExecutor)
  )

  override def dispose(): Unit = {
    (asyncClient, syncClient) match {
      case (Some(a), _) => a.close()
      case (_, Some(s)) => s.close()
      case _            =>
    }
  }

  override def getJournalRows(
      persistenceId: PersistenceId,
      toSequenceNr: SequenceNumber,
      deleted: Boolean
  ): Source[Seq[JournalRow], NotUsed] =
    store
      .read(persistenceId, None, toSequenceNr, None, Some(deleted))
      .fold(ArrayBuffer.empty[JournalRow])(_ += _)
      .map(_.toList)
      .withAttributes(logLevels)

  override def getJournalRows(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber,
      toSequenceNr: SequenceNumber,
      max: Long,
      deleted: Option[Boolean] = Some(false)
  ): Source[JournalRow, NotUsed] =
    store
      .read(persistenceId, Some(fromSequenceNr), toSequenceNr, Some(max), deleted)
      .withAttributes(logLevels)

  override def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber] = None,
      deleted: Option[Boolean] = None
  ): Source[Option[Long], NotUsed] =
    store
      .highestSequenceNr(persistenceId, fromSequenceNr, deleted)
      .withAttributes(logLevels)
}
