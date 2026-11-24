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
package com.github.j5ik2o.pekko.persistence.dynamodb.model

import scala.util.Try

/** The parts of a persistence id that the default `PersistenceIdBased` partition key and the default
  * `PersistenceIdWithSeqNr` sort key of the journal and the snapshot store are built from.
  */
private[dynamodb] object PersistenceIdKeyParts {

  /** The text before the first separator and the text after it, or none when the id does not contain the separator.
    *
    * The separator is matched as plain text. The two parts joined by the separator are the whole id, so two different
    * ids never have the same two parts.
    */
  def split(persistenceId: PersistenceId, separator: String): Option[(String, String)] = {
    val id    = persistenceId.asString
    val index = if (separator.isEmpty) -1 else id.indexOf(separator)
    if (index < 0) None else Some((id.substring(0, index), id.substring(index + separator.length)))
  }

  /** The first part that releases up to 1.2.0 used for the partition key.
    *
    * Those releases split the id with `String.split`, which reads the separator as a regular expression, and took the
    * first element. The result is `Some(None)` when the id does not contain the separator, and none when the split
    * failed for the id, in which case those releases could not write a row for it.
    */
  def prefixBeforeFix(persistenceId: PersistenceId, separator: String): Option[Option[String]] =
    elementBeforeFix(persistenceId, separator, 0)

  /** The second part that releases up to 1.2.0 used for the sort key: the second element of the same split. Every
    * element after it was dropped, so ids that differ only there shared a sort key (issue #1009).
    */
  def bodyBeforeFix(persistenceId: PersistenceId, separator: String): Option[Option[String]] =
    elementBeforeFix(persistenceId, separator, 1)

  private def elementBeforeFix(persistenceId: PersistenceId, separator: String, index: Int): Option[Option[String]] = {
    val id = persistenceId.asString
    if (id.contains(separator)) Try(id.split(separator)(index)).toOption.map(Some(_)) else Some(None)
  }
}
