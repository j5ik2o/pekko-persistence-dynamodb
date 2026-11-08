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
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ ClientVariant, DdbClientAdapter }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1.V1ClientAdapter
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.schema.{
  SnapshotRowCodec,
  SnapshotSchema,
  SnapshotTableSpec
}
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.serialization.ByteArraySnapshotSerializer
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.store.SnapshotStore

import scala.concurrent.{ ExecutionContext, Future }

/** The AWS SDK v1 snapshot DAO over the shared store.
  *
  * The table format comes from the plugin config, so the new and the legacy classes share this implementation.
  */
private[dao] final class V1SnapshotDaoDelegate(
    pluginContext: SnapshotPluginContext,
    asyncClient: Option[AmazonDynamoDBAsync],
    syncClient: Option[AmazonDynamoDB],
    serialization: Serialization
) extends SnapshotDao {
  (asyncClient, syncClient) match {
    case (None, None) =>
      throw new IllegalArgumentException("aws clients is both None")
    case _ =>
  }

  import pluginContext._
  import pluginContext.pluginConfig._

  private val variant: ClientVariant =
    ClientVariant.fromClientVersion(clientConfig.clientVersion) match {
      case Some(value) => value
      case None        => throw new IllegalArgumentException(s"unknown client version: ${clientConfig.clientVersion}")
    }

  private val adapter: DdbClientAdapter = new V1ClientAdapter(variant, asyncClient, syncClient, pluginExecutor)

  private val schema    = SnapshotSchema.fromColumns(columnsDefConfig)
  private val tableSpec = SnapshotTableSpec.fromConfig(schema, pluginConfig, partitionKeyResolver, sortKeyResolver)
  private val codec     = new SnapshotRowCodec(schema, tableSpec)
  private val store     = new SnapshotStore(adapter, codec, consistentRead, clientConfig.batchWriteItemLimit)

  private val serializer = new ByteArraySnapshotSerializer(serialization, metricsReporter, traceReporter)

  override def delete(persistenceId: PersistenceId, sequenceNr: SequenceNumber): Source[Unit, NotUsed] = {
    store.delete(persistenceId, sequenceNr)
  }

  override def deleteAllSnapshots(
      persistenceId: PersistenceId
  )(implicit ec: ExecutionContext): Source[Unit, NotUsed] = {
    store.deleteAll(persistenceId)
  }

  override def deleteUpToMaxSequenceNr(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber
  )(implicit ec: ExecutionContext): Source[Unit, NotUsed] = {
    store.deleteUpToMaxSequenceNr(persistenceId, maxSequenceNr)
  }

  override def deleteUpToMaxTimestamp(persistenceId: PersistenceId, maxTimestamp: Long)(implicit
      ec: ExecutionContext
  ): Source[Unit, NotUsed] = {
    store.deleteUpToMaxTimestamp(persistenceId, maxTimestamp)
  }

  override def deleteUpToMaxSequenceNrAndMaxTimestamp(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber,
      maxTimestamp: Long
  )(implicit ec: ExecutionContext): Source[Unit, NotUsed] = {
    store.deleteUpToMaxSequenceNrAndMaxTimestamp(persistenceId, maxSequenceNr, maxTimestamp)
  }

  override def latestSnapshot(
      persistenceId: PersistenceId
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    deserialize(store.latest(persistenceId))
  }

  override def snapshotForMaxTimestamp(
      persistenceId: PersistenceId,
      maxTimestamp: Long
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    deserialize(store.forMaxTimestamp(persistenceId, maxTimestamp))
  }

  override def snapshotForMaxSequenceNr(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    deserialize(store.forMaxSequenceNr(persistenceId, maxSequenceNr))
  }

  override def snapshotForMaxSequenceNrAndMaxTimestamp(
      persistenceId: PersistenceId,
      maxSequenceNr: SequenceNumber,
      maxTimestamp: Long
  )(implicit ec: ExecutionContext): Source[Option[(SnapshotMetadata, Any)], NotUsed] = {
    deserialize(store.forMaxSequenceNrAndMaxTimestamp(persistenceId, maxSequenceNr, maxTimestamp))
  }

  override def save(snapshotMetadata: SnapshotMetadata, snapshot: Any)(implicit
      ec: ExecutionContext
  ): Source[Unit, NotUsed] = {
    Source.future(serializer.serialize(snapshotMetadata, snapshot)).flatMapConcat(store.save)
  }

  override def dispose(): Unit = adapter.close()

  private def deserialize(rows: Source[Option[SnapshotRow], NotUsed])(implicit
      ec: ExecutionContext
  ): Source[Option[(SnapshotMetadata, Any)], NotUsed] =
    rows.mapAsync(1) {
      case Some(row) => serializer.deserialize(row).map(Some(_))
      case None      => Future.successful(None)
    }
}

final class V1NewSnapshotDaoImpl(
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
