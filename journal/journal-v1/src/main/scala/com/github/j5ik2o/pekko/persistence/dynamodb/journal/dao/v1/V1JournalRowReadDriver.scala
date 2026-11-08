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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.v1

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import com.amazonaws.services.dynamodbv2.{ AmazonDynamoDB, AmazonDynamoDBAsync }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.ClientVariant
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1.V1ClientAdapter
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.dao.JournalRowReadDriver
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.store.JournalRowStore
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ JournalPluginContext, JournalRow }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

import scala.collection.mutable.ArrayBuffer

final class V1JournalRowReadDriver(
    val pluginContext: JournalPluginContext,
    val asyncClient: Option[AmazonDynamoDBAsync],
    val syncClient: Option[AmazonDynamoDB]
) extends JournalRowReadDriver {

  override def system: ActorSystem = pluginContext.system

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
    new V1ClientAdapter(clientVariant, asyncClient, syncClient, pluginExecutor)
  )

  override def dispose(): Unit = {
    (asyncClient, syncClient) match {
      case (Some(a), _) => a.shutdown()
      case (_, Some(s)) => s.shutdown()
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
      .map(_.toVector)
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
      fromSequenceNr: Option[SequenceNumber],
      deleted: Option[Boolean]
  ): Source[Option[Long], NotUsed] =
    store
      .highestSequenceNr(persistenceId, fromSequenceNr, deleted)
      .withAttributes(logLevels)
}
