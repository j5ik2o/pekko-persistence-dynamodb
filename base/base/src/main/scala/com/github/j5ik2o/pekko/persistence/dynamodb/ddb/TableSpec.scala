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

sealed trait AttributeKind

object AttributeKind {
  case object S extends AttributeKind
  case object N extends AttributeKind
  case object B extends AttributeKind
}

sealed trait Presence

object Presence {
  case object Required extends Presence
  case object Optional extends Presence
}

final case class Column(name: AttributeName, kind: AttributeKind, presence: Presence)

final case class PrimaryKey(hash: AttributeName, range: Option[AttributeName])

sealed trait Projection

object Projection {
  case object All extends Projection
  final case class Include(columns: Vector[AttributeName]) extends Projection
  case object KeysOnly extends Projection
}

final case class IndexSpec(
    name: IndexName,
    hash: AttributeName,
    range: Option[AttributeName],
    projection: Projection
)

sealed trait StreamView

object StreamView {
  case object NewImage extends StreamView
  case object NewAndOldImages extends StreamView
  case object KeysOnly extends StreamView
  case object OldImage extends StreamView
}

/** The physical shape of one table: its columns, key, indexes, and stream setting.
  *
  * `withColumns` returns a new value, so a caller can derive a variant of a table without changing the original.
  */
final case class TableSpec(
    table: TableName,
    columns: Vector[Column],
    primaryKey: PrimaryKey,
    indexes: Vector[IndexSpec],
    stream: Option[StreamView]
) {

  def withColumns(columns: Vector[Column]): TableSpec = copy(columns = columns)

  def column(name: AttributeName): Option[Column] = columns.find(_.name == name)

  /** Extracts the primary key attributes from a full item.
    *
    * The returned `RowKey` carries only the key attributes and the table name, so a key built for one table cannot be
    * used against another without the type showing it.
    */
  def keyOf(values: AttributeMap): Either[KeyError, RowKey] = {
    for {
      hash <- keyValue(primaryKey.hash, values)
      range <- primaryKey.range match {
        case None       => Right(None)
        case Some(name) => keyValue(name, values).map(Some(_))
      }
    } yield {
      val rangeEntries = primaryKey.range.zip(range).map { case (name, value) => name -> value }
      RowKey(table, AttributeMap(Vector(primaryKey.hash -> hash) ++ rangeEntries))
    }
  }

  private def keyValue(name: AttributeName, values: AttributeMap): Either[KeyError, DdbValue] = {
    values.get(name).toRight(KeyError.Missing(name)).flatMap { value =>
      column(name) match {
        case None                                   => Left(KeyError.Missing(name))
        case Some(column) if matches(column, value) => Right(value)
        case Some(column)                           => Left(KeyError.WrongType(name, column.kind))
      }
    }
  }

  private def matches(column: Column, value: DdbValue): Boolean = (column.kind, value) match {
    case (AttributeKind.S, DdbValue.Str(_)) => true
    case (AttributeKind.N, DdbValue.Num(_)) => true
    case (AttributeKind.B, DdbValue.Bin(_)) => true
    case _                                  => false
  }
}
