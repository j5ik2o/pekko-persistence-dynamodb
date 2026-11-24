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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.migration

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.{ ClientType, ClientVersion }
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalPluginContext,
  PartitionKeyResolver,
  SortKeyResolver
}
import com.github.j5ik2o.pekko.persistence.dynamodb.migration.{ KeyForm, KeyPlan, Placement, StoredKey }
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.SnapshotPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.migration.SnapshotKeyMigration
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.ConfigHelper
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/** The plans of the key migration tools (issue #1014) decide from the functions of the key resolvers. Every id up to
  * four characters, for several separators, gets the placement that the resolver classes imply: a row under the key of
  * 1.3.0 moves exactly when the PlainText resolvers give another key, and a row under the new key moves back exactly
  * when 1.3.0 can build a key for the id.
  */
final class KeyMigrationPlanSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val separators = Seq("-", "|", "::", ".", "-+")

  private def config(separator: String): Config =
    ConfigFactory
      .parseString(s"""
        |j5ik2o.dynamo-db-journal.persistence-id-separator = "$separator"
        |j5ik2o.dynamo-db-snapshot.persistence-id-separator = "$separator"
        |j5ik2o.dynamo-db-journal.partition-key-resolver-class-name = "${classOf[
                       PartitionKeyResolver.PlainTextPersistenceIdBased
                     ].getName}"
        |j5ik2o.dynamo-db-journal.sort-key-resolver-class-name = "${classOf[
                       SortKeyResolver.PlainTextPersistenceIdWithSeqNr
                     ].getName}"
        |j5ik2o.dynamo-db-snapshot.partition-key-resolver-class-name = "${classOf[
                       snapshot.PartitionKeyResolver.PlainTextPersistenceIdBased
                     ].getName}"
        |j5ik2o.dynamo-db-snapshot.sort-key-resolver-class-name = "${classOf[
                       snapshot.SortKeyResolver.PlainTextPersistenceIdWithSeqNr
                     ].getName}"
        |""".stripMargin)
      .withFallback(
        ConfigHelper.config(
          Some("journal-reference"),
          legacyConfigFormat = false,
          legacyJournalMode = false,
          dynamoDBHost = "localhost",
          dynamoDBPort = 1,
          clientVersion = ClientVersion.V2.toString,
          clientType = ClientType.Sync.toString
        )
      )

  private lazy val systems: Map[String, ActorSystem] =
    separators.map(s => s -> ActorSystem(s"plan-${s.map(_.toInt).mkString("x")}", config(s))).toMap

  override def afterAll(): Unit = {
    systems.values.foreach(s => Await.result(s.terminate(), 30.seconds))
    super.afterAll()
  }

  private type Keys = String => Option[StoredKey]

  /** The keys of the four resolver classes of a store, and its plans forward and back. */
  private final case class Store(name: String, release130: Keys, plainText: Keys, forward: KeyPlan, reverse: KeyPlan)

  private def stores(separator: String): Seq[Store] = {
    val system         = systems(separator)
    val journalConfig  = JournalPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
    val snapshotConfig = SnapshotPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-snapshot"))
    val journal        = JournalPluginContext(system, journalConfig)
    val snapshots      = SnapshotPluginContext(system, snapshotConfig)
    def key(pkey: => String, skey: => String): Option[StoredKey] = Try(StoredKey(pkey, skey)).toOption.filter { k =>
      k.partitionKey.getBytes("UTF-8").length <= 2048 && k.sortKey.getBytes("UTF-8").length <= 1024
    }
    val seq = SequenceNumber(1)
    Seq(
      Store(
        "journal",
        id =>
          key(
            new PartitionKeyResolver.PersistenceIdBased(journal).resolve(PersistenceId(id), seq).asString,
            new SortKeyResolver.PersistenceIdWithSeqNr(journal).resolve(PersistenceId(id), seq).asString
          ),
        id =>
          key(
            new PartitionKeyResolver.PlainTextPersistenceIdBased(journal).resolve(PersistenceId(id), seq).asString,
            new SortKeyResolver.PlainTextPersistenceIdWithSeqNr(journal).resolve(PersistenceId(id), seq).asString
          ),
        JournalKeyMigration.plan(journalConfig, reverse = false),
        JournalKeyMigration.plan(journalConfig, reverse = true)
      ),
      Store(
        "snapshot",
        id =>
          key(
            new snapshot.PartitionKeyResolver.PersistenceIdBased(snapshots).resolve(PersistenceId(id), seq).asString,
            new snapshot.SortKeyResolver.PersistenceIdWithSeqNr(snapshots).resolve(PersistenceId(id), seq).asString
          ),
        id =>
          key(
            new snapshot.PartitionKeyResolver.PlainTextPersistenceIdBased(snapshots)
              .resolve(PersistenceId(id), seq)
              .asString,
            new snapshot.SortKeyResolver.PlainTextPersistenceIdWithSeqNr(snapshots)
              .resolve(PersistenceId(id), seq)
              .asString
          ),
        SnapshotKeyMigration.plan(snapshotConfig, reverse = false),
        SnapshotKeyMigration.plan(snapshotConfig, reverse = true)
      )
    )
  }

  private def ids(separator: String): Seq[String] = {
    val alphabet = ("ab" + separator).distinct
    (1 to 4).flatMap { length =>
      (0 until length).foldLeft(Seq("")) { (prefixes, _) =>
        for {
          p <- prefixes
          c <- alphabet
        } yield p + c
      }
    }.distinct
  }

  "the plans" should {

    "move a row exactly when the key of the resolver classes changes, for every id up to four characters" in {
      separators.foreach { separator =>
        stores(separator).foreach { store =>
          var changed      = 0
          var unreversible = 0
          val all          = ids(separator)
          all.foreach { id =>
            val old    = store.release130(id)
            val plain  = store.plainText(id).getOrElse(fail(s"no new key for $id"))
            val differ = !old.contains(plain)
            if (differ && old.isDefined) changed += 1
            if (differ && old.isEmpty) unreversible += 1
            withClue(s"${store.name} separator=$separator id=$id old=$old new=$plain: ") {
              store.forward.placement(id, 1, plain) shouldBe Placement.InPlace
              old.foreach { key =>
                store.forward.placement(id, 1, key) shouldBe (if (differ) Placement.Move(plain, KeyForm.Release130)
                                                              else Placement.InPlace)
                store.reverse.placement(id, 1, key) shouldBe Placement.InPlace
              }
              store.reverse.placement(id, 1, plain) match {
                case Placement.Move(target, KeyForm.PlainText) => old shouldBe Some(target)
                case Placement.InPlace                         => old shouldBe Some(plain)
                case Placement.Unmovable(reason) =>
                  old shouldBe None
                  reason should include("1.3.0 cannot build a key")
                case other => fail(s"unexpected $other")
              }
            }
          }
          info(
            s"${store.name} separator '$separator': ${all.size} ids, $changed with rows of 1.3.0 under another key, " +
            s"$unreversible that 1.3.0 could not write"
          )
        }
      }
    }

    "move the rows of issue #831 to the key that both formats give an id without the separator" in {
      stores("-").foreach { store =>
        val plain = store.plainText("Plain123").get
        store.release130("Plain123") shouldBe Some(plain)
        val issue831 = StoredKey(plain.partitionKey, "1")
        store.forward.placement("Plain123", 1, issue831) shouldBe Placement.Move(plain, KeyForm.Issue831)
        store.reverse.placement("Plain123", 1, issue831) shouldBe Placement.Move(plain, KeyForm.Issue831)
        store.forward.placement("Plain123", 1, StoredKey("x", "y")) shouldBe a[Placement.Unmovable]
      }
    }

    "leave the rows of the new format to --reverse when the configuration selects the resolvers of 1.3.0" in {
      val system = systems("-")
      val journalConfig = JournalPluginConfig.fromConfig(
        ConfigFactory
          .parseString(s"""
            |partition-key-resolver-class-name = "${classOf[PartitionKeyResolver.PersistenceIdBased].getName}"
            |sort-key-resolver-class-name = "${classOf[SortKeyResolver.PersistenceIdWithSeqNr].getName}"
            |""".stripMargin)
          .withFallback(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
      )
      JournalKeyMigration.configuredForm(journalConfig) shouldBe KeyForm.Release130
      val forward = JournalKeyMigration.plan(journalConfig, reverse = false)
      val store   = stores("-").head
      val id      = "Order-1-2"
      forward.placement(id, 1, store.release130(id).get) shouldBe Placement.InPlace
      forward.placement(id, 1, store.plainText(id).get) shouldBe a[Placement.Unmovable]
      val plain = store.plainText("Plain1").get
      forward.placement("Plain1", 1, StoredKey(plain.partitionKey, "1")) shouldBe Placement.Move(
        plain,
        KeyForm.Issue831
      )
    }

    "refuse key resolvers that it cannot know" in {
      val system = systems("-")
      val custom = JournalPluginConfig.fromConfig(
        ConfigFactory
          .parseString(s"""
            |partition-key-resolver-class-name = "${classOf[PartitionKeyResolver.SequenceNumberBased].getName}"
            |""".stripMargin)
          .withFallback(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
      )
      an[IllegalArgumentException] should be thrownBy JournalKeyMigration.plan(custom, reverse = false)
    }

    "keep the keys at the length limits of DynamoDB, in UTF-8 bytes" in {
      val store = stores("-").head
      // 1,004 bytes of body: the key of 1.3.0 is 1,024 bytes and does not change.
      val fits = "Long-" + "€" * 334 + "xx"
      store.release130(fits).map(_.sortKey.getBytes("UTF-8").length) shouldBe Some(1024)
      store.plainText(fits) shouldBe store.release130(fits)
      store.forward.placement(fits, 1, store.release130(fits).get) shouldBe Placement.InPlace
      // 1,005 bytes: 1.3.0 could not write it; the new key is hashed and has nothing to move back to.
      val over  = "Long-" + "€" * 334 + "xxx"
      val plain = store.plainText(over).get
      store.release130(over) shouldBe None
      plain.sortKey should startWith("#h1#")
      store.forward.placement(over, 1, plain) shouldBe Placement.InPlace
      store.reverse.placement(over, 1, plain) shouldBe a[Placement.Unmovable]
      // With two separators, 1.3.0 kept only the second part, so the old key fits and the new one is hashed.
      val twoParts = "Long-a-" + "€" * 334 + "xxx"
      val moved    = store.forward.placement(twoParts, 1, store.release130(twoParts).get)
      moved shouldBe Placement.Move(store.plainText(twoParts).get, KeyForm.Release130)
      store.plainText(twoParts).get.sortKey.getBytes("UTF-8").length should be <= 1024
    }
  }
}
