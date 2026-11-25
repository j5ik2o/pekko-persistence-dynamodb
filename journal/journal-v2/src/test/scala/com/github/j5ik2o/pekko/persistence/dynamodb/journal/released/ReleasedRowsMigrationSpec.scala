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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.released

import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, ScanRequest }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.migration.JournalKeyMigration
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{ PartitionKeyResolver, SortKeyResolver }
import com.github.j5ik2o.pekko.persistence.dynamodb.migration.{ MigrationReport, VerifyReport }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.migration.SnapshotKeyMigration
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.{ DockerAssignedPort, DynamoDBContainerHelper }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

object ReleasedRowsMigrationSpec extends DockerAssignedPort {

  /** A plugin that `ReleasedRowsDriver` runs with: the JVM classpath, and whether to select the PlainText resolvers. */
  final case class Plugin(label: String, classpath: String, plainText: Boolean)
}

/** The key migration tools on the rows that the published JARs of 1.0.59 and 1.3.0 wrote (issue #1027).
  *
  * The published plugins have the package and the class names of this code base, so each runs in its own JVM with the
  * classpath that a user of that release resolves (`project/ReleasedPlugins.scala`), and writes, through Pekko
  * Persistence, events, a soft delete, and a snapshot to DynamoDB Local (`ReleasedRowsDriver`). The tools of this code
  * base then move the rows forward, the plugin of this code base with the PlainText resolvers reads and changes them,
  * the tools move them back with `--reverse`, and the published plugin of 1.3.0 reads and changes them again.
  *
  * The persistence ids hold no separator, one, and two; no two of them share their keys of 1.3.0.
  */
final class ReleasedRowsMigrationSpec extends AnyWordSpec with Matchers with DynamoDBContainerHelper {
  import ReleasedRowsMigrationSpec.Plugin

  override lazy val dynamoDBPort: Int = ReleasedRowsMigrationSpec.dynamoDBPort
  override lazy val controller        = ReleasedRowsMigrationSpec.controller

  private val Arabic = Locale.forLanguageTag("ar-EG")

  // --- the plugins -------------------------------------------------------------------------------------------------

  private def released(version: String): Plugin = {
    val property = s"pekko-persistence-dynamodb.released-classpath.$version"
    val jars = sys.props.getOrElse(
      property,
      fail(s"$property is not set; run the spec through sbt, which resolves the published plugins")
    )
    val driver = Paths.get(ReleasedRowsDriver.getClass.getProtectionDomain.getCodeSource.getLocation.toURI).toString
    // The published JARs come first, so that no class of this code base replaces one of them.
    Plugin(s"the published $version", jars + File.pathSeparator + driver, plainText = false)
  }

  private val current = Plugin("this code base", sys.props("java.class.path"), plainText = true)

  private def pluginConfig(plainText: Boolean): String = {
    val plainTextResolvers: Seq[(String, Class[_])] = Seq(
      "j5ik2o.dynamo-db-journal.partition-key-resolver-class-name" -> classOf[
        PartitionKeyResolver.PlainTextPersistenceIdBased
      ],
      "j5ik2o.dynamo-db-journal.sort-key-resolver-class-name" -> classOf[
        SortKeyResolver.PlainTextPersistenceIdWithSeqNr
      ],
      "j5ik2o.dynamo-db-snapshot.partition-key-resolver-class-name" -> classOf[
        snapshot.PartitionKeyResolver.PlainTextPersistenceIdBased
      ],
      "j5ik2o.dynamo-db-snapshot.sort-key-resolver-class-name" -> classOf[
        snapshot.SortKeyResolver.PlainTextPersistenceIdWithSeqNr
      ]
    )
    val resolvers =
      if (plainText) plainTextResolvers.map { case (key, cls) => s"""$key = "${cls.getName}"""" }.mkString("\n")
      else ""
    val client =
      s"""
         |  dynamo-db-client {
         |    region = "ap-northeast-1"
         |    access-key-id = "x"
         |    secret-access-key = "x"
         |    endpoint = "http://$dockerHost:$dynamoDBPort/"
         |    client-version = "v2"
         |    client-type = "sync"
         |  }
         |""".stripMargin
    s"""
       |pekko.loglevel = WARNING
       |pekko.persistence.journal.plugin = "j5ik2o.dynamo-db-journal"
       |pekko.persistence.snapshot-store.plugin = "j5ik2o.dynamo-db-snapshot"
       |j5ik2o.dynamo-db-journal {
       |  shard-count = 1024
       |  soft-delete = true
       |  $client
       |}
       |j5ik2o.dynamo-db-snapshot {
       |  $client
       |}
       |$resolvers
       |""".stripMargin
  }

  /** Runs `ReleasedRowsDriver` with `plugin` in a new JVM and returns the states that its `read` steps printed. */
  private def drive(plugin: Plugin, locale: Option[Locale], steps: String, ids: Seq[String]): Seq[(String, String)] = {
    val configFile = Files.createTempFile("released-rows", ".conf")
    Files.write(configFile, pluginConfig(plugin.plainText).getBytes(StandardCharsets.UTF_8))
    val localeOptions = locale.toSeq.flatMap { l =>
      Seq(s"-Duser.language=${l.getLanguage}", s"-Duser.country=${l.getCountry}")
    }
    val command = Seq(Paths.get(sys.props("java.home"), "bin", "java").toString, "-cp", plugin.classpath) ++
      localeOptions ++ Seq(s"-Dconfig.file=$configFile", ReleasedRowsDriver.getClass.getName.stripSuffix("$"), steps) ++
      ids
    val process = new ProcessBuilder(command: _*).redirectErrorStream(true).start()
    val output  = scala.io.Source.fromInputStream(process.getInputStream, "UTF-8").getLines().toVector
    val exited  = process.waitFor(5, TimeUnit.MINUTES)
    Files.delete(configFile)
    val states = output.filter(_.startsWith("STATE\t")).map(_.split('\t')).map(f => s"${f(1)} ${f(2)}" -> f(3))
    info(s"${plugin.label} ($steps): ${states.map { case (k, v) => s"$k $v" }.mkString("; ")}")
    withClue(s"${plugin.label}, $steps:\n${output.filterNot(_.startsWith("STATE\t")).mkString("\n")}\n") {
      exited shouldBe true
      process.exitValue() shouldBe 0
    }
    states
  }

  /** The states that `read` prints for `id` with the highest sequence number `last`, the snapshot at `snapshot`, and
    * the events deleted up to `deletedTo`.
    */
  private def expected(id: String, last: Long, snapshot: Long, deletedTo: Long): Seq[(String, String)] = {
    def events(from: Long) = (from to last).map(seq => s"$seq:$id:e$seq").mkString(",")
    val notDeleted         = s"snapshot=none events=${events(deletedTo + 1)} last=$last"
    Seq(
      s"$id full"   -> s"snapshot=$snapshot:$id:snap@$snapshot events=${events(snapshot + 1)} last=$last",
      s"$id events" -> notDeleted,
      s"$id older"  -> notDeleted
    )
  }

  // The state after `write`, after one `mutate`, and after two.
  private def written(ids: Seq[String])      = ids.flatMap(expected(_, 3, 2, 1))
  private def mutatedOnce(ids: Seq[String])  = ids.flatMap(expected(_, 5, 4, 2))
  private def mutatedTwice(ids: Seq[String]) = ids.flatMap(expected(_, 7, 6, 4))

  // --- the tools ---------------------------------------------------------------------------------------------------

  private def toolConfig: Config =
    ConfigFactory.parseString(pluginConfig(plainText = true)).withFallback(ConfigFactory.load())

  private def withLocale[T](locale: Option[Locale])(f: => T): T = {
    val saved = Locale.getDefault(Locale.Category.FORMAT)
    locale.foreach(Locale.setDefault(Locale.Category.FORMAT, _))
    try f
    finally Locale.setDefault(Locale.Category.FORMAT, saved)
  }

  /** Runs the dry-run, `--execute`, and `--verify` of one tool, and returns the persistence ids whose rows moved. */
  private def migrate(
      name: String,
      run: (Seq[String], Config, String => Unit) => MigrationReport,
      verify: (Seq[String], Config, String => Unit) => VerifyReport,
      reverse: Boolean
  ): Set[String] = {
    val direction = if (reverse) Seq("--reverse") else Seq.empty
    val out       = (line: String) => info(s"$name: $line")
    val dryRun    = run(direction, toolConfig, out)
    withClue(s"$name dry-run: ") {
      dryRun.unmovable shouldBe empty
      dryRun.blocked shouldBe empty
      dryRun.cycles shouldBe empty
      dryRun.conflictingCopies shouldBe empty
      dryRun.sharedKeyGroups shouldBe empty
    }
    val executed = run(direction ++ Seq("--execute", "--writers-stopped"), toolConfig, out)
    withClue(s"$name --execute: ") {
      executed.incomplete shouldBe false
      executed.moved shouldBe dryRun.candidates.size.toLong
    }
    withClue(s"$name --verify: ") {
      verify(direction ++ Seq("--verify", "--verify-timeout", "60"), toolConfig, out).verified shouldBe true
    }
    dryRun.candidates.map(_.persistenceId).toSet
  }

  private def migrateBoth(locale: Option[Locale], reverse: Boolean): (Set[String], Set[String]) = withLocale(locale) {
    (
      migrate("journal", JournalKeyMigration.run, JournalKeyMigration.verify, reverse),
      migrate("snapshot", SnapshotKeyMigration.run, SnapshotKeyMigration.verify, reverse)
    )
  }

  // --- the rows ----------------------------------------------------------------------------------------------------

  private def typeOf(value: AttributeValue): String =
    Seq(
      "S"    -> Option(value.getS),
      "N"    -> Option(value.getN),
      "B"    -> Option(value.getB),
      "BOOL" -> Option(value.getBOOL),
      "SS"   -> Option(value.getSS),
      "NS"   -> Option(value.getNS),
      "BS"   -> Option(value.getBS),
      "L"    -> Option(value.getL),
      "M"    -> Option(value.getM),
      "NULL" -> Option(value.getNULL)
    ).collectFirst { case (name, Some(_)) => name }.getOrElse("?")

  /** The attribute names and types of the rows of `table`, one entry per distinct shape. */
  private def shapes(table: String): Set[String] = {
    val result = dynamoDBClient.scan(new ScanRequest(table).withConsistentRead(true))
    result.getLastEvaluatedKey shouldBe null
    result.getItems.asScala.map { item =>
      item.asScala.toSeq.map { case (name, value) => s"$name:${typeOf(value)}" }.sorted.mkString(" ")
    }.toSet
  }

  private def sortKeys(table: String): Seq[String] =
    dynamoDBClient.scan(new ScanRequest(table).withConsistentRead(true)).getItems.asScala.map(_.get("skey").getS).toSeq

  private def isArabicDigit(c: Char): Boolean = c >= '\u0660' && c <= '\u0669'

  // --- the scenario ------------------------------------------------------------------------------------------------

  /** Writes with `writer`, moves forward, reads and changes with this code base, moves back, and reads and changes with
    * the published 1.3.0. `forwardMovers` are the persistence ids whose rows the forward run moves.
    */
  private def scenario(
      writer: Plugin,
      locale: Option[Locale],
      ids: Seq[String],
      forwardMovers: Set[String],
      reverseMovers: Set[String]
  ): Unit = {
    deleteTable()
    createTable()

    drive(writer, locale, "write,read", ids) shouldBe written(ids)
    val journalShapes  = shapes(journalTableName)
    val snapshotShapes = shapes(snapshotTableName)
    info(s"journal rows of ${writer.label}: $journalShapes")
    info(s"snapshot rows of ${writer.label}: $snapshotShapes")
    val journalKeys = sortKeys(journalTableName)
    journalKeys should have size (ids.size * 3L)
    sortKeys(snapshotTableName) should have size ids.size.toLong
    if (locale.contains(Arabic)) {
      // The release wrote the sequence numbers in the digits of the locale, and the tools in another locale do not
      // recognize them.
      all(journalKeys.map(_.exists(isArabicDigit))) shouldBe true
      withLocale(Some(Locale.US)) {
        JournalKeyMigration.run(Seq.empty, toolConfig, _ => ()).unmovable should have size journalKeys.size.toLong
      }
    }

    migrateBoth(locale, reverse = false) shouldBe ((forwardMovers, forwardMovers))
    shapes(journalTableName) shouldBe journalShapes
    shapes(snapshotTableName) shouldBe snapshotShapes

    drive(current, locale, "read,mutate,read", ids) shouldBe written(ids) ++ mutatedOnce(ids)
    // The rows that this code base wrote have the names and the types of the rows of the release.
    shapes(journalTableName) shouldBe journalShapes
    shapes(snapshotTableName) shouldBe snapshotShapes

    migrateBoth(locale, reverse = true) shouldBe ((reverseMovers, reverseMovers))

    drive(released("1.3.0"), locale, "read,mutate,read", ids) shouldBe mutatedOnce(ids) ++ mutatedTwice(ids)
    shapes(journalTableName) shouldBe journalShapes
    shapes(snapshotTableName) shouldBe snapshotShapes
  }

  // No separator, one, and two. The second part of each id differs, so no two ids share a key of 1.3.0.
  private def ids(tag: String) = Seq(s"Plain$tag", s"Order-$tag", s"Account-$tag-x")

  "the rows that the published 1.3.0 wrote" should {
    "move forward to the PlainText keys and back, and stay readable and writable by this code base and by 1.3.0" in {
      val pids = ids("v130")
      // Only the id with two separators has a new key; the others have the same key in both formats.
      scenario(released("1.3.0"), None, pids, Set(pids(2)), Set(pids(2)))
    }

    "move under the locale ar-EG, whose digits the keys hold, when the tools run with the same locale" in {
      val pids = ids("arab")
      scenario(released("1.3.0"), Some(Arabic), pids, Set(pids(2)), Set(pids(2)))
    }
  }

  "the rows that the published 1.0.59 wrote" should {
    "move forward, including the rows of issue #831, and back to the keys of 1.3.0" in {
      val pids = ids("v1059")
      // The id without the separator has the key of issue #831, which moves in both directions to the same key, so
      // the reverse run moves only the id with two separators.
      scenario(released("1.0.59"), None, pids, Set(pids(0), pids(2)), Set(pids(2)))
    }
  }
}
