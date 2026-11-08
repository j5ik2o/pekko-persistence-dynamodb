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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1

import com.amazonaws.services.dynamodbv2.model.{ ConditionalCheckFailedException, TransactionCanceledException }
import com.amazonaws.services.dynamodbv2.{ AmazonDynamoDB, AmazonDynamoDBAsync }
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.CompletableFutureUtils._
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.{ Executor, Future => JavaFuture }
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

/** Executes SDK-neutral operations with an AWS SDK v1 client. */
final class V1ClientAdapter(
    val variant: ClientVariant,
    asyncClient: Option[AmazonDynamoDBAsync],
    syncClient: Option[AmazonDynamoDB],
    executionContext: ExecutionContext
) extends DdbClientAdapter {

  require(
    variant == ClientVariant.V1 || variant == ClientVariant.V1Dax,
    s"V1ClientAdapter cannot run the $variant variant"
  )
  require(asyncClient.isDefined != syncClient.isDefined, "set exactly one of the async and sync clients")

  private implicit val ec: ExecutionContext = executionContext

  private implicit val executor: Executor = new Executor {
    override def execute(command: Runnable): Unit = executionContext.execute(command)
  }

  override def capabilities: CapabilityProfile = variant.capabilities

  override def run(operation: Operation): Source[OperationResult, NotUsed] =
    V1OperationRenderer.render(operation) match {
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
    asyncClient.foreach(_.shutdown())
    syncClient.foreach(_.shutdown())
  }

  private def execute(rendered: V1RenderedOperation): Future[OperationResult] = rendered match {
    case V1RenderedOperation.Put(request) =>
      call(client => client.putItemAsync(request), client => client.putItem(request))
        .map(_ => OperationResult.Written(1))
        .recover { case _: ConditionalCheckFailedException => conditionRejected }

    case V1RenderedOperation.Get(request) =>
      call(client => client.getItemAsync(request), client => client.getItem(request))
        .map(response => V1Responses.item(response.getItem))

    case V1RenderedOperation.Delete(request) =>
      call(client => client.deleteItemAsync(request), client => client.deleteItem(request))
        .map(_ => OperationResult.Written(1))
        .recover { case _: ConditionalCheckFailedException => conditionRejected }

    case V1RenderedOperation.Update(request) =>
      call(client => client.updateItemAsync(request), client => client.updateItem(request))
        .map(_ => OperationResult.Written(1))
        .recover { case _: ConditionalCheckFailedException => conditionRejected }

    case V1RenderedOperation.Query(request) =>
      call(client => client.queryAsync(request), client => client.query(request))
        .map(V1Responses.items)

    case V1RenderedOperation.BatchWrite(request) =>
      call(client => client.batchWriteItemAsync(request), client => client.batchWriteItem(request))
        .flatMap(response => V1Responses.batch(request, response).fold(Future.failed, Future.successful))

    case V1RenderedOperation.TransactWrite(request) =>
      call(client => client.transactWriteItemsAsync(request), client => client.transactWriteItems(request))
        .map { _ =>
          val count = Option(request.getTransactItems).map(_.size).getOrElse(0)
          OperationResult.TransactionCommitted(count)
        }
        .recover { case exception: TransactionCanceledException => transactionRejected(exception) }
  }

  private def call[A](
      asyncCall: AmazonDynamoDBAsync => JavaFuture[A],
      syncCall: AmazonDynamoDB => A
  ): Future[A] = asyncClient match {
    case Some(client) => asyncCall(client).toCompletableFuture.toScala
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
      Option(exception.getCancellationReasons)
        .map(_.asScala.toVector.zipWithIndex.map { case (reason, index) =>
          ConditionFailure(index, Option(reason.getCode).getOrElse(""))
        })
        .getOrElse(Vector.empty)
    )
}
