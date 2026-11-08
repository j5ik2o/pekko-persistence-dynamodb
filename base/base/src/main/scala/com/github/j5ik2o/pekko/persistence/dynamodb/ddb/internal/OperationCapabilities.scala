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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.internal

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ CapabilityProfile, EncodeError, Operation }

/** Rejects an operation that the selected client variant cannot carry.
  *
  * The adapter is the boundary that knows the variant, so the rejection happens before a request is rendered or sent.
  */
private[dynamodb] object OperationCapabilities {

  def validate(profile: CapabilityProfile, operation: Operation): Either[EncodeError, Unit] = operation match {
    case Operation.TransactWrite(items) =>
      if (!profile.transactWrite) {
        Left(EncodeError("this client variant does not support transactional writes"))
      } else if (items.size > profile.transactItemLimit) {
        Left(
          EncodeError(
            s"a transaction carries ${items.size} items, more than the limit of ${profile.transactItemLimit}"
          )
        )
      } else {
        Right(())
      }
    case Operation.BatchWrite(_, puts, deletes) =>
      val count = puts.size + deletes.size
      if (count > profile.batchWriteLimit) {
        Left(EncodeError(s"a batch write carries $count items, more than the limit of ${profile.batchWriteLimit}"))
      } else {
        Right(())
      }
    case _ => Right(())
  }
}
