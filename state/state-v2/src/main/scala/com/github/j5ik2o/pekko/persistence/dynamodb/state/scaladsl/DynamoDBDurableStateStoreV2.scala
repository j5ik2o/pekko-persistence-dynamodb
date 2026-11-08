/*
 * Copyright 2022 Junichi Kato
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
package com.github.j5ik2o.pekko.persistence.dynamodb.state.scaladsl

import org.apache.pekko.Done
import org.apache.pekko.actor.{ ActorSystem, CoordinatedShutdown }
import org.apache.pekko.annotation.ApiMayChange
import org.apache.pekko.persistence.state.scaladsl.GetObjectResult
import org.apache.pekko.serialization.{ Serialization, SerializationExtension }
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeName,
  ClientVariant,
  DdbClientAdapter,
  DdbValue,
  RowKey
}
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v2.V2ClientAdapter
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ Context, PersistenceId }
import com.github.j5ik2o.pekko.persistence.dynamodb.state.DynamoDBDurableStateStoreProvider.Identifier
import com.github.j5ik2o.pekko.persistence.dynamodb.state._
import com.github.j5ik2o.pekko.persistence.dynamodb.state.schema.{
  StateRow,
  StateRowCodec,
  StateSchema,
  StateTableSpec
}
import com.github.j5ik2o.pekko.persistence.dynamodb.state.store.StateStore
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.LoggingSupport
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient, DynamoDbClient }

import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ApiMayChange
final class DynamoDBDurableStateStoreV2[A](
    val pluginContext: StatePluginContext,
    val asyncClient: Option[DynamoDbAsyncClient],
    val syncClient: Option[DynamoDbClient]
) extends ScalaDurableStateUpdateStore[A]
    with LoggingSupport {

  val system = pluginContext.system

  import pluginContext._

  implicit val mat: ActorSystem     = pluginContext.system
  implicit val ec: ExecutionContext = pluginExecutor

  private val id: UUID = UUID.randomUUID()

  CoordinatedShutdown(system).addTask(
    CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
    s"$Identifier-$id"
  ) { () =>
    Future {
      dispose()
      Done
    }
  }

  private val adapter: DdbClientAdapter = {
    val variant = ClientVariant.fromClientVersion(pluginConfig.clientConfig.clientVersion) match {
      case Some(value) => value
      case None =>
        throw new IllegalArgumentException(s"unknown client version: ${pluginConfig.clientConfig.clientVersion}")
    }
    new V2ClientAdapter(variant, asyncClient, syncClient, pluginExecutor)
  }

  private val schema = StateSchema.fromColumns(pluginConfig.columnsDefConfig)
  private val tableSpec =
    StateTableSpec.fromConfig(schema, pluginConfig, tableNameResolver, partitionKeyResolver, sortKeyResolver)
  private val codec = new StateRowCodec(schema, tableSpec)
  private val store = new StateStore(adapter, codec, pluginConfig.consistentRead)

  private val serialization: Serialization = SerializationExtension(system)
  private val akkaSerialization            = new StateSerializer(serialization, metricsReporter, traceReporter)

  override def getRawObject(persistenceId: String): Future[GetRawObjectResult[A]] = {
    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeStateStoreGetObject(context))

    def future = {
      val key  = tableSpec.keyOf(pid)
      val pkey = text(key, schema.partitionKey)
      val skey = text(key, schema.sortKey)
      store
        .get(pid).flatMapConcat {
          case None => Source.single(GetRawObjectResult.Empty)
          case Some(row) =>
            Source.future(
              akkaSerialization
                .deserialize(
                  row.persistenceId,
                  AkkaSerialized(row.serializerId, row.serializerManifest, row.payload)
                ).map { payload =>
                  GetRawObjectResult.Just(
                    pkey,
                    skey,
                    row.persistenceId,
                    payload.asInstanceOf[A],
                    row.revision,
                    row.serializerId,
                    row.serializerManifest,
                    row.tag,
                    row.ordering
                  )
                }
            )
        }.runWith(Sink.head)
    }

    val traced = traceReporter.fold(future)(_.traceStateStoreGetObject(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterStateStoreGetObject(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorStateStoreGetObject(newContext, ex))
    }

    traced
  }

  override def getObject(persistenceId: String): Future[GetObjectResult[A]] = {
    getRawObject(persistenceId).map {
      case GetRawObjectResult.Empty =>
        GetObjectResult(None, 0)
      case GetRawObjectResult.Just(_, _, _, value, revision, _, _, _, _) =>
        GetObjectResult(Some(value), revision)
    }
  }

  override def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] = {
    require(revision > 0)
    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeStateStoreUpsertObject(context))

    def future =
      akkaSerialization.serialize(persistenceId, value).flatMap { serialized =>
        val row = StateRow(
          persistenceId = persistenceId,
          revision = revision,
          payload = serialized.payload,
          serializerId = serialized.serializerId,
          serializerManifest = serialized.serializerManifest,
          ordering = System.currentTimeMillis(),
          tag = if (tag.isEmpty) None else Some(tag)
        )
        store.upsert(row).runWith(Sink.ignore).map(_ => Done)
      }

    val traced = traceReporter.fold(future)(_.traceStateStoreUpsertObject(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterStateStoreUpsertObject(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorStateStoreUpsertObject(newContext, ex))
    }

    traced
  }

  override def deleteObject(persistenceId: String, revision: Long): Future[Done] = deleteObject(persistenceId)

  override def deleteObject(persistenceId: String): Future[Done] = {
    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeStateStoreDeleteObject(context))

    def future = store.delete(pid).runWith(Sink.ignore).map(_ => Done)

    val traced = traceReporter.fold(future)(_.traceStateStoreDeleteObject(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterStateStoreDeleteObject(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorStateStoreDeleteObject(newContext, ex))
    }

    traced
  }

  private def text(key: RowKey, name: AttributeName): String =
    key.get(name).collect { case DdbValue.Str(value) => value }.getOrElse("")

  private def dispose(): Unit = adapter.close()

}
