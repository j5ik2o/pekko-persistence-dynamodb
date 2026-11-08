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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.dao.SnapshotRow

/** Encodes and decodes a snapshot row from the columns declared by `schema`.
  *
  * The key comes from `tableSpec`, so the write item and the delete key share one definition.
  */
final class SnapshotRowCodec(val schema: SnapshotSchema, val tableSpec: SnapshotTableSpec) {

  def key(row: SnapshotRow): RowKey = tableSpec.keyOf(row.persistenceId, row.sequenceNumber)

  def encode(row: SnapshotRow): AttributeMap =
    key(row).values.merge(
      AttributeMap(
        Vector(
          schema.persistenceId  -> DdbValue.Str(row.persistenceId.asString),
          schema.sequenceNumber -> DdbValue.Num(BigDecimal(row.sequenceNumber.value)),
          schema.snapshot       -> DdbValue.Bin(row.snapshot.toVector),
          schema.created        -> DdbValue.Num(BigDecimal(row.created))
        )
      )
    )

  def decode(values: AttributeMap): Either[DecodeError, SnapshotRow] =
    for {
      persistenceId <- string(values, schema.persistenceId)
      sequenceNumber <- number(values, schema.sequenceNumber)
      snapshot <- binary(values, schema.snapshot)
      created <- number(values, schema.created)
    } yield SnapshotRow(
      persistenceId = PersistenceId(persistenceId),
      sequenceNumber = SequenceNumber(sequenceNumber.toLong),
      created = created.toLong,
      snapshot = snapshot.toArray
    )

  private def string(values: AttributeMap, name: AttributeName): Either[DecodeError, String] =
    values.get(name) match {
      case None                  => Left(DecodeError.Missing(name))
      case Some(DdbValue.Str(v)) => Right(v)
      case Some(_)               => Left(DecodeError.WrongType(name, AttributeKind.S))
    }

  private def number(values: AttributeMap, name: AttributeName): Either[DecodeError, BigDecimal] =
    values.get(name) match {
      case None                  => Left(DecodeError.Missing(name))
      case Some(DdbValue.Num(v)) => Right(v)
      case Some(_)               => Left(DecodeError.WrongType(name, AttributeKind.N))
    }

  private def binary(values: AttributeMap, name: AttributeName): Either[DecodeError, Vector[Byte]] =
    values.get(name) match {
      case None                  => Left(DecodeError.Missing(name))
      case Some(DdbValue.Bin(v)) => Right(v)
      case Some(_)               => Left(DecodeError.WrongType(name, AttributeKind.B))
    }
}
