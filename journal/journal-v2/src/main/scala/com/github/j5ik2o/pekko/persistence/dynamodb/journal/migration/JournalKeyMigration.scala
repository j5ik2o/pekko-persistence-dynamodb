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

import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.{
  JournalPluginContext,
  PartitionKeyResolver,
  PartitionKeyResolverProvider,
  SortKeyResolver,
  SortKeyResolverProvider
}
import com.github.j5ik2o.pekko.persistence.dynamodb.migration._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, PersistenceIdKeyParts, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2SyncClientFactory
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.apache.pekko.actor.ActorSystem

import scala.util.Try

/** Moves the journal rows that 1.3.0 wrote to the keys of `PlainTextPersistenceIdBased` and
  * `PlainTextPersistenceIdWithSeqNr` (issue #1014), and back to the keys of 1.3.0 with `--reverse`.
  *
  * Nothing is written unless `--execute` is given. The tool reads `j5ik2o.dynamo-db-journal`: the table, the column
  * names, `shard-count`, `persistence-id-separator`, and the key resolvers. Forward, rows move to the keys of the
  * configured resolvers, so select the new resolvers before you run it; with the resolvers of 1.3.0 configured, only
  * the rows of issue #831 move. See `KeyMigration` for how a row is moved.
  */
object JournalKeyMigration {

  private val plainTextNames = (
    classOf[PartitionKeyResolver.PlainTextPersistenceIdBased].getName,
    classOf[SortKeyResolver.PlainTextPersistenceIdWithSeqNr].getName
  )

  private val release130Names = (
    classOf[PartitionKeyResolver.PersistenceIdBased].getName,
    classOf[SortKeyResolver.PersistenceIdWithSeqNr].getName
  )

  /** The form of the configured key resolvers. Other resolvers are refused, because the tool cannot know their keys. */
  private[dynamodb] def configuredForm(pluginConfig: JournalPluginConfig): KeyForm = {
    require(
      pluginConfig.partitionKeyResolverProviderClassName == classOf[PartitionKeyResolverProvider.Default].getName &&
      pluginConfig.sortKeyResolverProviderClassName == classOf[SortKeyResolverProvider.Default].getName,
      "the key migration supports only the default key resolver providers"
    )
    require(
      pluginConfig.columnsDefConfig.sortKeyColumnName != pluginConfig.columnsDefConfig.sequenceNrColumnName,
      "the key migration does not support a journal table whose sort key is the sequence number"
    )
    (pluginConfig.partitionKeyResolverClassName, pluginConfig.sortKeyResolverClassName) match {
      case names if names == plainTextNames  => KeyForm.PlainText
      case names if names == release130Names => KeyForm.Release130
      case (partition, sort) =>
        throw new IllegalArgumentException(
          s"the key migration supports only the resolvers of 1.3.0 and the PlainText resolvers, not $partition and $sort"
        )
    }
  }

  private def separatorOf(pluginConfig: JournalPluginConfig): String =
    if (pluginConfig.sourceConfig.hasPath("persistence-id-separator"))
      pluginConfig.sourceConfig.getString("persistence-id-separator")
    else PersistenceId.Separator

  /** The key that `PersistenceIdBased` and `PersistenceIdWithSeqNr` give the event, from the same functions. */
  private[dynamodb] def release130Key(
      separator: String,
      shardCount: Int
  )(id: String, sequenceNr: Long): Option[StoredKey] =
    Try {
      val persistenceId = PersistenceId(id)
      PersistenceIdKeyParts.legacyPrefix(persistenceId, separator).map { prefix =>
        StoredKey(
          PartitionKeyResolver.legacyPersistenceIdBased(persistenceId, prefix, shardCount).asString,
          SortKeyResolver.legacyPersistenceIdWithSeqNrSKey(persistenceId, SequenceNumber(sequenceNr), separator)
        )
      }
    }.toOption.flatten.filter(key => PersistenceIdKeyParts.fitsDynamoDB(key.partitionKey, key.sortKey))

  /** The key that `PlainTextPersistenceIdBased` and `PlainTextPersistenceIdWithSeqNr` give the event. */
  private[dynamodb] def plainTextKey(
      separator: String,
      shardCount: Int
  )(id: String, sequenceNr: Long): Option[StoredKey] =
    Try {
      val persistenceId = PersistenceId(id)
      StoredKey(
        PartitionKeyResolver
          .persistenceIdBased(
            persistenceId,
            PersistenceIdKeyParts.split(persistenceId, separator).map(_._1),
            shardCount
          )
          .asString,
        SortKeyResolver.persistenceIdWithSeqNrSKey(persistenceId, SequenceNumber(sequenceNr), separator)
      )
    }.toOption

  private[dynamodb] def plan(pluginConfig: JournalPluginConfig, reverse: Boolean): KeyPlan = {
    val separator = separatorOf(pluginConfig)
    new ResolverKeyPlan(
      release130Key(separator, pluginConfig.shardCount),
      plainTextKey(separator, pluginConfig.shardCount),
      configuredForm(pluginConfig),
      reverse
    )
  }

  private[dynamodb] def table(pluginConfig: JournalPluginConfig): MigrationTable = {
    val columns = pluginConfig.columnsDefConfig
    MigrationTable(
      tableName = pluginConfig.tableName,
      partitionKeyColumn = columns.partitionKeyColumnName,
      sortKeyColumn = columns.sortKeyColumnName,
      persistenceIdColumn = columns.persistenceIdColumnName,
      sequenceNrColumn = columns.sequenceNrColumnName,
      optionalColumns = Seq(columns.deletedColumnName, columns.tagsColumnName, columns.orderingColumnName),
      indexAttributes =
        Seq(columns.deletedColumnName, columns.messageColumnName, columns.orderingColumnName, columns.tagsColumnName),
      sequential = true,
      duplicates = DuplicateRule.DeletedFlag(columns.deletedColumnName)
    )
  }

  def main(args: Array[String]): Unit = {
    // A run that left a selected row behind, or a check that did not pass, is not a success for a script.
    val ok =
      if (args.contains("--verify")) verify(args.toSeq, ConfigFactory.load(), println).verified
      else !run(args.toSeq, ConfigFactory.load(), println).incomplete
    if (!ok) sys.exit(1)
  }

  /** Moves the rows (a dry-run without `--execute`) and prints the report with `out`. */
  private[dynamodb] def run(args: Seq[String], config: Config, out: String => Unit): MigrationReport =
    withClient(args, config) { (options, pluginConfig, client) =>
      require(options.verify.isEmpty, "use verify for --verify")
      out(localeLine)
      val prefix = if (options.settings.execute) "" else "[dry-run] "
      val report = KeyMigration.run(
        client,
        table(pluginConfig),
        plan(pluginConfig, options.reverse),
        options.settings,
        if (options.progress) text => Console.err.println(s"${prefix}journal key migration progress $text")
        else _ => ()
      )
      KeyMigration.render(report, if (options.reverse) "reverse" else "forward").foreach(out)
      report
    }

  /** Checks the whole table and its index (`--verify`) and prints the report with `out`. */
  private[dynamodb] def verify(args: Seq[String], config: Config, out: String => Unit): VerifyReport =
    withClient(args, config) { (options, pluginConfig, client) =>
      val settings = options.verify.getOrElse(throw new IllegalArgumentException("verify needs --verify"))
      out(s"[verify] $localeLine")
      val report = KeyVerification.run(
        client,
        table(pluginConfig),
        pluginConfig.getJournalRowsIndexName,
        plan(pluginConfig, options.reverse),
        settings,
        progress =
          if (options.progress) text => Console.err.println(s"[verify] journal key migration progress $text")
          else _ => (),
        reverse = options.reverse
      )
      val legacyKeysSetting =
        if (!options.reverse && configuredForm(pluginConfig) == KeyForm.PlainText)
          Some(("j5ik2o.dynamo-db-journal.legacy-keys-enabled", "SnapshotKeyMigration"))
        else None
      KeyVerification.render(report, if (options.reverse) "reverse" else "forward", legacyKeysSetting).foreach(out)
      report
    }

  /** The keys hold numbers formatted with the default format locale, as the plugin writes them, so the tool must run
    * with the locale of the application that wrote the rows.
    */
  private def localeLine: String =
    s"default format locale: ${java.util.Locale.getDefault(java.util.Locale.Category.FORMAT)} (the keys hold numbers " +
    "in its digits; run the tool with the locale of the application that wrote the rows)"

  private def withClient[T](args: Seq[String], config: Config)(
      f: (MigrationOptions, JournalPluginConfig, software.amazon.awssdk.services.dynamodb.DynamoDbClient) => T
  ): T = {
    val options = MigrationOptions.parse(args)
    // The tool connects with the plain v2 client whatever client-version the plugin uses: the moves bypass DAX, and
    // the v2 client builders accept only a v2 client-version.
    val toolConfig = config.withValue(
      "j5ik2o.dynamo-db-journal.dynamo-db-client.client-version",
      ConfigValueFactory.fromAnyRef("v2")
    )
    val system = ActorSystem("journal-key-migration", toolConfig)
    try {
      val pluginConfig  = JournalPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-journal"))
      val pluginContext = JournalPluginContext(system, pluginConfig)
      val client = pluginContext
        .newDynamicAccessor[V2SyncClientFactory]()
        .createThrow(pluginConfig.v2SyncClientFactoryClassName)
        .create
      try f(options, pluginConfig, client)
      finally client.close()
    } finally {
      system.terminate()
      ()
    }
  }
}
