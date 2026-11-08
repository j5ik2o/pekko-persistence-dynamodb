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

/** An attribute map that keeps insertion order and one value per name.
  *
  * The constructor deduplicates by name and keeps the last value, so `get`, the renderer's map conversion, and the size
  * calculation all see the same value for a name.
  */
final class AttributeMap private (val entries: Vector[(AttributeName, DdbValue)]) {

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

  override def equals(other: Any): Boolean = other match {
    case that: AttributeMap => entries == that.entries
    case _                  => false
  }

  override def hashCode(): Int = entries.hashCode()

  override def toString: String = s"AttributeMap(${entries.mkString(", ")})"
}

object AttributeMap {
  val empty: AttributeMap = new AttributeMap(Vector.empty)

  def apply(entries: Vector[(AttributeName, DdbValue)]): AttributeMap =
    new AttributeMap(deduplicate(entries))

  def of(entries: (AttributeName, DdbValue)*): AttributeMap = apply(entries.toVector)

  private def deduplicate(entries: Vector[(AttributeName, DdbValue)]): Vector[(AttributeName, DdbValue)] = {
    val seen = scala.collection.mutable.HashSet.empty[AttributeName]
    entries.reverse.filter { case (name, _) => seen.add(name) }.reverse
  }
}
