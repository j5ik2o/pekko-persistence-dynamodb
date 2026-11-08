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

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ EncodeError, Operation, RowKey, TableName, TransactItem }

/** Checks that every row key belongs to the table of its operation.
  *
  * A key carries its table, so a key read from one table cannot be used against another table without this check
  * failing before anything is sent to DynamoDB.
  */
private[dynamodb] object OperationKeys {

  def validate(operation: Operation): Either[EncodeError, Unit] = operation match {
    case Operation.Get(table, key, _)       => check(table, key)
    case Operation.Delete(table, key, _)    => check(table, key)
    case Operation.Update(table, key, _, _) => check(table, key)
    case Operation.BatchWrite(table, _, deletes) =>
      deletes.foldLeft[Either[EncodeError, Unit]](Right(())) { (result, key) =>
        result.flatMap(_ => check(table, key))
      }
    case Operation.TransactWrite(items) =>
      items.foldLeft[Either[EncodeError, Unit]](Right(())) { (result, item) =>
        result.flatMap(_ => validateTransactItem(item))
      }
    case _ => Right(())
  }

  private def validateTransactItem(item: TransactItem): Either[EncodeError, Unit] = item match {
    case TransactItem.Delete(table, key, _)    => check(table, key)
    case TransactItem.Update(table, key, _, _) => check(table, key)
    case TransactItem.Put(_, _, _)             => Right(())
  }

  private def check(table: TableName, key: RowKey): Either[EncodeError, Unit] =
    if (key.table == table) Right(())
    else Left(EncodeError(s"the key belongs to table '${key.table.value}', not '${table.value}'"))
}
