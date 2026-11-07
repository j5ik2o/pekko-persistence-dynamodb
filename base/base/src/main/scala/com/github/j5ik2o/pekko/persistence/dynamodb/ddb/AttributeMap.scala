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

/** An attribute map that keeps insertion order, so that encoding and size calculation are reproducible. */
final case class AttributeMap(entries: Vector[(AttributeName, DdbValue)]) {

  def get(name: AttributeName): Option[DdbValue] =
    entries.collectFirst { case (candidate, value) if candidate == name => value }

  def contains(name: AttributeName): Boolean = get(name).isDefined

  def updated(name: AttributeName, value: DdbValue): AttributeMap =
    AttributeMap(entries.filterNot { case (candidate, _) => candidate == name } :+ (name -> value))

  def removed(name: AttributeName): AttributeMap =
    AttributeMap(entries.filterNot { case (candidate, _) => candidate == name })

  def merge(other: AttributeMap): AttributeMap =
    other.entries.foldLeft(this) { case (accumulator, (name, value)) => accumulator.updated(name, value) }

  def names: Vector[AttributeName] = entries.map { case (name, _) => name }

  def isEmpty: Boolean = entries.isEmpty

  def nonEmpty: Boolean = entries.nonEmpty

  def size: Int = entries.size

  def sorted: AttributeMap = AttributeMap(entries.sortBy { case (name, _) => name.value })
}

object AttributeMap {
  val empty: AttributeMap = AttributeMap(Vector.empty)

  def of(entries: (AttributeName, DdbValue)*): AttributeMap = AttributeMap(entries.toVector)
}
