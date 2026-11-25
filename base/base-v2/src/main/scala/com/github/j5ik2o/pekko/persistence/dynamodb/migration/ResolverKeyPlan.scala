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
package com.github.j5ik2o.pekko.persistence.dynamodb.migration

/** The plan of the default key resolvers of the journal and the snapshot store (issue #1014).
  *
  * `release130` gives the key of the resolvers of 1.3.0 (`PersistenceIdBased`, `PersistenceIdWithSeqNr`), or none when
  * 1.3.0 could not write the id. `plainText` gives the key of `PlainTextPersistenceIdBased` and
  * `PlainTextPersistenceIdWithSeqNr`. Both come from the functions of the resolvers. `configured` is the form of the
  * resolvers that the configuration selects.
  *
  * Forward, a row moves to the key of the configured resolvers from the key of 1.3.0 or from the key of issue #831
  * (`skey = ${sequenceNumber}`, which releases before #831 gave an id without the separator). With the resolvers of
  * 1.3.0 configured, only the rows of issue #831 move. With `reverse`, a row moves from the key of the new format, or
  * of issue #831, to the key of 1.3.0. A row under any other key is listed and left as it is.
  */
private[dynamodb] final class ResolverKeyPlan(
    release130: (String, Long) => Option[StoredKey],
    plainText: (String, Long) => Option[StoredKey],
    configured: KeyForm,
    reverse: Boolean
) extends KeyPlan {
  require(configured == KeyForm.Release130 || configured == KeyForm.PlainText, s"unsupported key form: $configured")

  private val unknown =
    "the stored key is neither the key of 1.3.0, of issue #831, nor of the new key format; it may come from another " +
    "shard-count, persistence-id-separator, or key resolver"

  private def isIssue831Key(stored: StoredKey, target: StoredKey, sequenceNr: Long): Boolean =
    stored.partitionKey == target.partitionKey && stored.sortKey == sequenceNr.toString

  override def placement(persistenceId: String, sequenceNr: Long, stored: StoredKey): Placement =
    if (!reverse) {
      val target =
        if (configured == KeyForm.PlainText) plainText(persistenceId, sequenceNr)
        else release130(persistenceId, sequenceNr)
      target match {
        case None => Placement.Unmovable("the configured key resolvers cannot build a key for this persistence id")
        case Some(key) if key == stored => Placement.InPlace
        case Some(key) if release130(persistenceId, sequenceNr).contains(stored) =>
          Placement.Move(key, KeyForm.Release130)
        case Some(key) if isIssue831Key(stored, key, sequenceNr) => Placement.Move(key, KeyForm.Issue831)
        case Some(_) if plainText(persistenceId, sequenceNr).contains(stored) =>
          Placement.Unmovable(
            "stored under the key of the new format, which the configuration does not select; run with --reverse"
          )
        case Some(_) => Placement.Unmovable(unknown)
      }
    } else {
      release130(persistenceId, sequenceNr) match {
        case Some(key) if key == stored                          => Placement.InPlace
        case Some(key) if isIssue831Key(stored, key, sequenceNr) => Placement.Move(key, KeyForm.Issue831)
        case Some(key) if plainText(persistenceId, sequenceNr).contains(stored) =>
          Placement.Move(key, KeyForm.PlainText)
        case None if plainText(persistenceId, sequenceNr).contains(stored) =>
          Placement.Unmovable("1.3.0 cannot build a key for this persistence id, so its rows cannot go back")
        case _ => Placement.Unmovable(unknown)
      }
    }

  /** The partition key and the sort key without the sequence number that 1.3.0 gave the id. */
  override def sharedKeyGroup(persistenceId: String): Option[String] =
    release130(persistenceId, 1L).map(key => s"${key.partitionKey} ${key.sortKey.dropRight(20)}")
}

import scala.concurrent.duration._

/** The command line options of the key migration tools. */
private[dynamodb] final case class MigrationOptions(settings: MigrationSettings, reverse: Boolean, progress: Boolean)

private[dynamodb] object MigrationOptions {

  val usage: String =
    "options: [--execute --writers-stopped] [--reverse] [--progress] [--persistence-id <id>]... [--segments <n>] " +
    "[--parallelism <n>] [--max-rate <rows per second>] [--scan-page-size <items>] [--index-wait <seconds>]"

  /** The tool moves rows only while nothing else writes to or recovers from the table (issue #1014). */
  val writersStoppedRequired: String =
    "--execute requires --writers-stopped: stop every application that writes to or recovers from the journal and " +
    "snapshot tables before you move the rows, because a replay, a recovery, or a delete that runs during the moves " +
    "can read the persistence-id index before DynamoDB has updated it"

  def parse(args: Seq[String]): MigrationOptions = {
    var settings = MigrationSettings(execute = false)
    var reverse  = false
    var progress = false
    var stopped  = false
    var ids      = Set.empty[String]
    var rest     = args.toList
    def value(name: String, tail: List[String]): (String, List[String]) = tail match {
      case v :: more if !v.startsWith("--") => (v, more)
      case _                                => throw new IllegalArgumentException(s"$name requires a value. $usage")
    }
    def number[A](name: String, text: String)(parse: String => A): A =
      try parse(text)
      catch { case _: NumberFormatException => throw new IllegalArgumentException(s"$name requires a number: $text") }
    while (rest.nonEmpty) {
      rest match {
        case "--execute" :: tail         => settings = settings.copy(execute = true); rest = tail
        case "--reverse" :: tail         => reverse = true; rest = tail
        case "--progress" :: tail        => progress = true; rest = tail
        case "--writers-stopped" :: tail => stopped = true; rest = tail
        case (name @ "--index-wait") :: tail =>
          val (v, more) = value(name, tail)
          settings = settings.copy(indexWait = number(name, v)(_.toInt).seconds); rest = more
        case (name @ "--persistence-id") :: tail =>
          val (v, more) = value(name, tail); ids += v; rest = more
        case (name @ "--segments") :: tail =>
          val (v, more) = value(name, tail); settings = settings.copy(segments = number(name, v)(_.toInt)); rest = more
        case (name @ "--parallelism") :: tail =>
          val (v, more) = value(name, tail)
          settings = settings.copy(parallelism = number(name, v)(_.toInt)); rest = more
        case (name @ "--max-rate") :: tail =>
          val (v, more) = value(name, tail)
          settings = settings.copy(maxRate = Some(number(name, v)(_.toDouble))); rest = more
        case (name @ "--scan-page-size") :: tail =>
          val (v, more) = value(name, tail)
          settings = settings.copy(scanPageSize = Some(number(name, v)(_.toInt))); rest = more
        case other :: _ => throw new IllegalArgumentException(s"unknown migration argument: $other. $usage")
        case Nil        =>
      }
    }
    if (settings.execute && !stopped) throw new IllegalArgumentException(writersStoppedRequired)
    MigrationOptions(
      settings.copy(persistenceIds = if (ids.isEmpty) None else Some(ids)),
      reverse,
      progress
    )
  }
}
