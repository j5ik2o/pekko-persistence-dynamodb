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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.Try

/** The parts of a persistence id that the default `PersistenceIdBased` partition key and the default
  * `PersistenceIdWithSeqNr` sort key of the journal and the snapshot store are built from, and the parts that releases
  * up to 1.3.0 built them from (issue #1009).
  */
private[dynamodb] object PersistenceIdKeyParts {

  /** The largest sort key DynamoDB accepts, in UTF-8 bytes. */
  val MaxSortKeyBytes: Int = 1024

  /** The largest partition key DynamoDB accepts, in UTF-8 bytes. */
  val MaxPartitionKeyBytes: Int = 2048

  /** Starts a key part that is replaced with its SHA-256. */
  val HashMarker: String = "#h1#"

  /** Ends a key that holds a SHA-256. A key without a hash always ends with a digit, so the two forms never meet, even
    * when the text of an id is the marker and the digits of a hash.
    */
  val HashTerminator: String = "#"

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

  /** `${body}-${sequenceNumber}` with the sequence number in 19 digits. When that exceeds `MaxSortKeyBytes`, the key is
    * `${HashMarker}${sha256(body)}-${sequenceNumber}${HashTerminator}`, with the SHA-256 in 64 hex digits. The choice
    * depends on the body alone, so every sequence number of an id gets a sort key of the same form.
    */
  def sortKey(body: String, sequenceNumber: SequenceNumber): String = {
    val bodyBytes = body.getBytes(StandardCharsets.UTF_8)
    if (bodyBytes.length + 20 > MaxSortKeyBytes)
      "%s%s-%019d%s".format(HashMarker, sha256Hex(bodyBytes), sequenceNumber.value, HashTerminator)
    else "%s-%019d".format(body, sequenceNumber.value)
  }

  /** `${prefix}-${shard}`, or `${shard}` without a prefix, where `shard` is the 40-digit shard number. When that
    * exceeds `MaxPartitionKeyBytes`, the key is `${HashMarker}${sha256(prefix)}-${shard}${HashTerminator}`. The choice
    * depends on the prefix alone.
    */
  def partitionKey(prefix: Option[String], shard: String): String =
    prefix match {
      case None => shard
      case Some(text) =>
        val prefixBytes = text.getBytes(StandardCharsets.UTF_8)
        if (prefixBytes.length + 1 + shard.length > MaxPartitionKeyBytes)
          s"$HashMarker${sha256Hex(prefixBytes)}-$shard$HashTerminator"
        else s"$text-$shard"
    }

  /** The first part that releases up to 1.3.0 used for the partition key.
    *
    * Those releases split the id with `String.split`, which reads the separator as a regular expression, and took the
    * first element. The result is `Some(None)` when the id does not contain the separator, and none when the split
    * failed for the id, in which case those releases could not write a row for it.
    */
  def legacyPrefix(persistenceId: PersistenceId, separator: String): Option[Option[String]] =
    legacyElement(persistenceId, separator, 0)

  /** The second part that releases up to 1.3.0 used for the sort key: the second element of the same split. Every
    * element after it was dropped, so ids that differ only there shared a sort key (issue #1009).
    */
  def legacyBody(persistenceId: PersistenceId, separator: String): Option[Option[String]] =
    legacyElement(persistenceId, separator, 1)

  private def legacyElement(persistenceId: PersistenceId, separator: String, index: Int): Option[Option[String]] = {
    val id = persistenceId.asString
    if (id.contains(separator)) Try(id.split(separator)(index)).toOption.map(Some(_)) else Some(None)
  }

  /** DynamoDB accepts the key, so a row can be stored under it. A legacy key that is too long never held a row. */
  def fitsDynamoDB(partitionKey: String, sortKey: String): Boolean =
    partitionKey.getBytes(StandardCharsets.UTF_8).length <= MaxPartitionKeyBytes &&
    sortKey.getBytes(StandardCharsets.UTF_8).length <= MaxSortKeyBytes

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => "%02x".format(b & 0xff)).mkString
}
