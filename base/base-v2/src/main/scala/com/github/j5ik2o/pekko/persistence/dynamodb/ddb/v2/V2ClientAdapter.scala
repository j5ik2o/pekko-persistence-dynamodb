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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v2

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import software.amazon.awssdk.services.dynamodb.model.{ ConditionalCheckFailedException, TransactionCanceledException }
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient, DynamoDbClient }

import java.util.concurrent.CompletableFuture
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

/** Executes SDK-neutral operations with an AWS SDK v2 client. */
final class V2ClientAdapter(
    val variant: ClientVariant,
    asyncClient: Option[DynamoDbAsyncClient],
    syncClient: Option[DynamoDbClient],
    executionContext: ExecutionContext
) extends DdbClientAdapter {

  require(
    variant == ClientVariant.V2 || variant == ClientVariant.V2Dax,
    s"V2ClientAdapter cannot run the $variant variant"
  )
  require(asyncClient.isDefined != syncClient.isDefined, "set exactly one of the async and sync clients")

  private implicit val ec: ExecutionContext = executionContext

  override def capabilities: CapabilityProfile = variant.capabilities

  override def run(operation: Operation): Source[OperationResult, NotUsed] =
    V2OperationRenderer.render(operation) match {
      case Left(error)     => Source.failed(new IllegalArgumentException(error.detail))
      case Right(rendered) => Source.future(execute(rendered))
    }

  override def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed] = {
    def loop(next: Option[AttributeMap]): Source[AttributeMap, NotUsed] = {
      val operation = next.fold(query)(key => query.copy(exclusiveStartKey = Some(key)))
      run(operation).flatMapConcat {
        case OperationResult.Items(values, lastEvaluatedKey) =>
          Source(values).concat(lastEvaluatedKey.fold(Source.empty[AttributeMap])(key => loop(Some(key))))
        case other =>
          Source.failed(new IllegalStateException(s"unexpected query result: $other"))
      }
    }
    loop(None)
  }

  override def close(): Unit = {
    asyncClient.foreach(_.close())
    syncClient.foreach(_.close())
  }

  private def execute(rendered: V2RenderedOperation): Future[OperationResult] = rendered match {
    case V2RenderedOperation.Put(request) =>
      call(client => client.putItem(request), client => client.putItem(request))
        .map(_ => OperationResult.Written(1))
        .recover { case _: ConditionalCheckFailedException => conditionRejected }

    case V2RenderedOperation.Get(request) =>
      call(client => client.getItem(request), client => client.getItem(request))
        .map(response => V2Responses.item(response.item()))

    case V2RenderedOperation.Delete(request) =>
      call(client => client.deleteItem(request), client => client.deleteItem(request))
        .map(_ => OperationResult.Written(1))
        .recover { case _: ConditionalCheckFailedException => conditionRejected }

    case V2RenderedOperation.Update(request) =>
      call(client => client.updateItem(request), client => client.updateItem(request))
        .map(_ => OperationResult.Written(1))
        .recover { case _: ConditionalCheckFailedException => conditionRejected }

    case V2RenderedOperation.Query(request) =>
      call(client => client.query(request), client => client.query(request))
        .map(V2Responses.items)

    case V2RenderedOperation.BatchWrite(request) =>
      call(client => client.batchWriteItem(request), client => client.batchWriteItem(request))
        .flatMap(response => V2Responses.batch(request, response).fold(Future.failed, Future.successful))

    case V2RenderedOperation.TransactWrite(request) =>
      call(client => client.transactWriteItems(request), client => client.transactWriteItems(request))
        .map { _ =>
          val count = Option(request.transactItems()).map(_.size).getOrElse(0)
          OperationResult.TransactionCommitted(count)
        }
        .recover { case exception: TransactionCanceledException => transactionRejected(exception) }
  }

  private def call[A](
      asyncCall: DynamoDbAsyncClient => CompletableFuture[A],
      syncCall: DynamoDbClient => A
  ): Future[A] = asyncClient match {
    case Some(client) => asyncCall(client).toScala
    case None =>
      syncClient match {
        case Some(client) => Future(syncCall(client))
        case None         => Future.failed(new IllegalStateException("no DynamoDB client"))
      }
  }

  private def conditionRejected: OperationResult =
    OperationResult.ConditionRejected(Vector(ConditionFailure(0, "ConditionalCheckFailed")))

  private def transactionRejected(exception: TransactionCanceledException): OperationResult =
    OperationResult.TransactionRejected(
      Option(exception.cancellationReasons())
        .map(_.asScala.toVector.zipWithIndex.map { case (reason, index) =>
          ConditionFailure(index, Option(reason.code()).getOrElse(""))
        })
        .getOrElse(Vector.empty)
    )
}
