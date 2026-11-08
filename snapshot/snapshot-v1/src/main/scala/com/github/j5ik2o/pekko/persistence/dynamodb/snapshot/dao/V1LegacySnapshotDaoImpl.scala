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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.dao

import org.apache.pekko.NotUsed
import org.apache.pekko.persistence.SnapshotMetadata
import org.apache.pekko.serialization.Serialization
import org.apache.pekko.stream.scaladsl.Source
import com.amazonaws.services.dynamodbv2.{ AmazonDynamoDB, AmazonDynamoDBAsync }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext

import scala.concurrent.ExecutionContext

@deprecated(
  "Use com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.store.SnapshotStore; this DAO delegates to it.",
  since = "1.2.0"
)
final class V1LegacySnapshotDaoImpl(
    pluginContext: SnapshotPluginContext,
    asyncClient: Option[AmazonDynamoDBAsync],
    syncClient: Option[AmazonDynamoDB],
    serialization: Serialization
) extends SnapshotDao {

  private val delegate = new V1SnapshotDaoDelegate(pluginContext, asyncClient, syncClient, serialization)

  override def delete(persistenceId: PersistenceId, sequenceNr: SequenceNumber): Source[Unit, NotUsed] = {
    delegate.delete(persistenceId, sequenceNr)
  }

  override def deleteAllSnapshots(
      persistenceId: PersistenceId
  )(implicit ec: ExecutionContext): Source[Unit, NotUsed] = {
    delegate.deleteAllSnapshots(persistenceId)
  }

  override def deleteUpToMaxSequenceNr(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber
  )(implicit ec: ExecutionContext): Source[Unit, NotUsed] = {
    delegate.deleteUpToMaxSequenceNr(persistenceId, maxSequenceNr)
  }

  override def deleteUpToMaxTimestamp(persistenceId: PersistenceId, maxTimestamp: Long)(implicit
      ec: ExecutionContext
  ): Source[Unit, NotUsed] = {
    delegate.deleteUpToMaxTimestamp(persistenceId, maxTimestamp)
  }

  override def deleteUpToMaxSequenceNrAndMaxTimestamp(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber,
      maxTimestamp: Long
  )(implicit ec: ExecutionContext): Source[Unit, NotUsed] = {
    delegate.deleteUpToMaxSequenceNrAndMaxTimestamp(persistenceId, maxSequenceNr, maxTimestamp)
  }

  override def latestSnapshot(
      persistenceId: PersistenceId
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    delegate.latestSnapshot(persistenceId)
  }

  override def snapshotForMaxTimestamp(
      persistenceId: PersistenceId,
      maxTimestamp: Long
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    delegate.snapshotForMaxTimestamp(persistenceId, maxTimestamp)
  }

  override def snapshotForMaxSequenceNr(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    delegate.snapshotForMaxSequenceNr(persistenceId, maxSequenceNr)
  }

  override def snapshotForMaxSequenceNrAndMaxTimestamp(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber,
      maxTimestamp: Long
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    delegate.snapshotForMaxSequenceNrAndMaxTimestamp(persistenceId, maxSequenceNr, maxTimestamp)
  }

  override def save(snapshotMetadata: SnapshotMetadata, snapshot: Any)(implicit
      ec: ExecutionContext
  ): Source[Unit, NotUsed] = {
    delegate.save(snapshotMetadata, snapshot)
  }

  override def dispose(): Unit = delegate.dispose()
}
