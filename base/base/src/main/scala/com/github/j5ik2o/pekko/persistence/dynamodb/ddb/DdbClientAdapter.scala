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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

/** Executes operations against one AWS SDK version.
  *
  * A transport or service failure fails the returned stream. A condition that did not hold is an `OperationResult`
  * value. A batch write that DynamoDB leaves partly unprocessed also fails the stream, so the caller retries the whole
  * idempotent batch instead of losing the unprocessed items.
  */
trait DdbClientAdapter {

  def capabilities: CapabilityProfile

  def run(operation: Operation): Source[OperationResult, NotUsed]

  def queryAll(query: Operation.Query): Source[AttributeMap, NotUsed]

  def close(): Unit
}
