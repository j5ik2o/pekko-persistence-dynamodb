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
package com.github.j5ik2o.pekko.persistence.dynamodb.state.schema

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.PersistenceId

/** Encodes and decodes a durable state row from the columns declared by `schema`.
  *
  * The key comes from `tableSpec`, so the write item and the delete key share one definition.
  */
final class StateRowCodec(val schema: StateSchema, val tableSpec: StateTableSpec) {

  def key(persistenceId: PersistenceId): RowKey = tableSpec.keyOf(persistenceId)

  def encode(row: StateRow): AttributeMap = {
    val required = key(PersistenceId(row.persistenceId)).values.merge(
      AttributeMap(
        Vector(
          schema.persistenceId -> DdbValue.Str(row.persistenceId),
          schema.revision      -> DdbValue.Num(BigDecimal(row.revision)),
          schema.payload       -> DdbValue.Bin(row.payload.toVector),
          schema.serializerId  -> DdbValue.Num(BigDecimal(row.serializerId)),
          schema.ordering      -> DdbValue.Num(BigDecimal(row.ordering))
        )
      )
    )
    val withManifest = row.serializerManifest.fold(required)(manifest =>
      required.updated(schema.serializerManifest, DdbValue.Str(manifest))
    )
    row.tag.filter(_.nonEmpty).fold(withManifest)(tag => withManifest.updated(schema.tags, DdbValue.Str(tag)))
  }

  def decode(values: AttributeMap): Either[DecodeError, StateRow] =
    for {
      persistenceId <- string(values, schema.persistenceId)
      revision <- number(values, schema.revision)
      payload <- binary(values, schema.payload)
      serializerId <- number(values, schema.serializerId)
      ordering <- number(values, schema.ordering)
    } yield StateRow(
      persistenceId = persistenceId,
      revision = revision.toLong,
      payload = payload.toArray,
      serializerId = serializerId.toInt,
      serializerManifest = optionalString(values, schema.serializerManifest),
      ordering = ordering.toLong,
      tag = optionalString(values, schema.tags)
    )

  private def string(values: AttributeMap, name: AttributeName): Either[DecodeError, String] =
    values.get(name) match {
      case None                  => Left(DecodeError.Missing(name))
      case Some(DdbValue.Str(v)) => Right(v)
      case Some(_)               => Left(DecodeError.WrongType(name, AttributeKind.S))
    }

  private def optionalString(values: AttributeMap, name: AttributeName): Option[String] =
    values.get(name).collect { case DdbValue.Str(value) => value }

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
