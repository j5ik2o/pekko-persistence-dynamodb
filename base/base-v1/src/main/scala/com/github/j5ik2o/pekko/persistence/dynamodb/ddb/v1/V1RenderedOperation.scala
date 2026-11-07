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

import com.amazonaws.services.dynamodbv2.model.{
  BatchWriteItemRequest,
  DeleteItemRequest,
  GetItemRequest,
  PutItemRequest,
  QueryRequest,
  TransactWriteItemsRequest,
  UpdateItemRequest
}

/** One AWS SDK v1 request produced from an `Operation`. */
sealed trait V1RenderedOperation

object V1RenderedOperation {
  final case class Put(request: PutItemRequest) extends V1RenderedOperation
  final case class Get(request: GetItemRequest) extends V1RenderedOperation
  final case class Delete(request: DeleteItemRequest) extends V1RenderedOperation
  final case class Update(request: UpdateItemRequest) extends V1RenderedOperation
  final case class Query(request: QueryRequest) extends V1RenderedOperation
  final case class BatchWrite(request: BatchWriteItemRequest) extends V1RenderedOperation
  final case class TransactWrite(request: TransactWriteItemsRequest) extends V1RenderedOperation
}
