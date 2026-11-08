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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeKind,
  AttributeMap,
  AttributeName,
  DdbValue,
  DecodeError,
  RowKey
}
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.JournalRow
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }

import scala.util.Try

final class JournalRowCodec(schema: JournalSchema) {

  def encode(row: JournalRow, key: RowKey): AttributeMap = {
    val base = AttributeMap.of(
      schema.partitionKey  -> requiredKeyValue(key, schema.partitionKey),
      schema.sortKey       -> requiredKeyValue(key, schema.sortKey),
      schema.persistenceId -> DdbValue.Str(row.persistenceId.asString),
      schema.sequenceNr    -> DdbValue.Num(BigDecimal(row.sequenceNumber.value)),
      schema.deleted       -> DdbValue.Bool(row.deleted),
      schema.message       -> DdbValue.Bin(row.message.toVector),
      schema.ordering      -> DdbValue.Num(BigDecimal(row.ordering))
    )
    row.tags.fold(base)(tag => base.updated(schema.tags, DdbValue.Str(tag)))
  }

  def decode(values: AttributeMap): Either[DecodeError, JournalRow] =
    for {
      persistenceId <- string(values, schema.persistenceId)
      sequenceNr <- long(values, schema.sequenceNr)
      deleted <- boolean(values, schema.deleted)
      message <- binary(values, schema.message)
      ordering <- long(values, schema.ordering)
      tags <- optionalString(values, schema.tags)
    } yield JournalRow(
      persistenceId = PersistenceId(persistenceId),
      sequenceNumber = SequenceNumber(sequenceNr),
      deleted = deleted,
      message = message.toArray,
      ordering = ordering,
      tags = tags
    )

  private def requiredKeyValue(key: RowKey, name: AttributeName): DdbValue =
    key
      .get(name).getOrElse(
        throw new IllegalArgumentException(s"the journal row key has no value for the column ${name.value}")
      )

  private def string(values: AttributeMap, name: AttributeName): Either[DecodeError, String] =
    values.get(name) match {
      case None                     => Left(DecodeError.Missing(name))
      case Some(DdbValue.Str(text)) => Right(text)
      case Some(_)                  => Left(DecodeError.WrongType(name, AttributeKind.S))
    }

  private def long(values: AttributeMap, name: AttributeName): Either[DecodeError, Long] =
    values.get(name) match {
      case None =>
        Left(DecodeError.Missing(name))
      case Some(DdbValue.Num(number)) =>
        Try(number.toLongExact).toOption.toRight(
          DecodeError.InvalidValue(name, s"$number is not a whole number")
        )
      case Some(_) =>
        Left(DecodeError.WrongType(name, AttributeKind.N))
    }

  private def boolean(values: AttributeMap, name: AttributeName): Either[DecodeError, Boolean] =
    values.get(name) match {
      case None                      => Left(DecodeError.Missing(name))
      case Some(DdbValue.Bool(flag)) => Right(flag)
      case Some(_)                   => Left(DecodeError.WrongType(name, AttributeKind.B))
    }

  private def binary(values: AttributeMap, name: AttributeName): Either[DecodeError, Vector[Byte]] =
    values.get(name) match {
      case None                      => Left(DecodeError.Missing(name))
      case Some(DdbValue.Bin(bytes)) => Right(bytes)
      case Some(_)                   => Left(DecodeError.WrongType(name, AttributeKind.B))
    }

  private def optionalString(values: AttributeMap, name: AttributeName): Either[DecodeError, Option[String]] =
    values.get(name) match {
      case None                     => Right(None)
      case Some(DdbValue.Str(text)) => Right(Some(text))
      case Some(_)                  => Left(DecodeError.WrongType(name, AttributeKind.S))
    }
}
