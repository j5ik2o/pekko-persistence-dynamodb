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
package com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.migration

import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.config.SnapshotPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.{
  PartitionKeyResolver,
  PartitionKeyResolverProvider,
  SnapshotPluginContext,
  SortKeyResolver,
  SortKeyResolverProvider
}
import com.github.j5ik2o.pekko.persistence.dynamodb.migration._
import com.github.j5ik2o.pekko.persistence.dynamodb.model.{ PersistenceId, PersistenceIdKeyParts, SequenceNumber }
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2SyncClientFactory
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.apache.pekko.actor.ActorSystem

import scala.util.Try

/** Moves the snapshot rows that 1.3.0 wrote to the keys of `PlainTextPersistenceIdBased` and
  * `PlainTextPersistenceIdWithSeqNr` (issue #1014), and back to the keys of 1.3.0 with `--reverse`.
  *
  * Nothing is written unless `--execute` is given. The tool reads `j5ik2o.dynamo-db-snapshot`: the table, the column
  * names, `shard-count`, `persistence-id-separator`, and the key resolvers. Forward, rows move to the keys of the
  * configured resolvers, so select the new resolvers before you run it; with the resolvers of 1.3.0 configured, only
  * the rows of issue #831 move. See `KeyMigration` for how a row is moved.
  */
object SnapshotKeyMigration {

  private val plainTextNames = (
    classOf[PartitionKeyResolver.PlainTextPersistenceIdBased].getName,
    classOf[SortKeyResolver.PlainTextPersistenceIdWithSeqNr].getName
  )

  private val release130Names = (
    classOf[PartitionKeyResolver.PersistenceIdBased].getName,
    classOf[SortKeyResolver.PersistenceIdWithSeqNr].getName
  )

  /** The form of the configured key resolvers. Other resolvers are refused, because the tool cannot know their keys. */
  private[dynamodb] def configuredForm(pluginConfig: SnapshotPluginConfig): KeyForm = {
    require(
      pluginConfig.partitionKeyResolverProviderClassName == classOf[PartitionKeyResolverProvider.Default].getName &&
      pluginConfig.sortKeyResolverProviderClassName == classOf[SortKeyResolverProvider.Default].getName,
      "the key migration supports only the default key resolver providers"
    )
    require(
      !pluginConfig.legacyTableFormat,
      "the key migration does not support legacy-table-format, whose keys are the persistence id and the sequence number"
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

  private def separatorOf(pluginConfig: SnapshotPluginConfig): String =
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

  private[dynamodb] def plan(pluginConfig: SnapshotPluginConfig, reverse: Boolean): KeyPlan = {
    val separator = separatorOf(pluginConfig)
    new ResolverKeyPlan(
      release130Key(separator, pluginConfig.shardCount),
      plainTextKey(separator, pluginConfig.shardCount),
      configuredForm(pluginConfig),
      reverse
    )
  }

  private[dynamodb] def table(pluginConfig: SnapshotPluginConfig): MigrationTable = {
    val columns = pluginConfig.columnsDefConfig
    MigrationTable(
      tableName = pluginConfig.tableName,
      partitionKeyColumn = columns.partitionKeyColumnName,
      sortKeyColumn = columns.sortKeyColumnName,
      persistenceIdColumn = columns.persistenceIdColumnName,
      sequenceNrColumn = columns.sequenceNrColumnName,
      optionalColumns = Seq.empty,
      sequential = false,
      duplicates = DuplicateRule.LatestCreated(columns.createdColumnName)
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
      val prefix = if (options.settings.execute) "" else "[dry-run] "
      val report = KeyMigration.run(
        client,
        table(pluginConfig),
        plan(pluginConfig, options.reverse),
        options.settings,
        if (options.progress) text => Console.err.println(s"${prefix}snapshot key migration progress $text")
        else _ => ()
      )
      KeyMigration.render(report, if (options.reverse) "reverse" else "forward").foreach(out)
      report
    }

  /** Checks the whole table and its index (`--verify`) and prints the report with `out`. */
  private[dynamodb] def verify(args: Seq[String], config: Config, out: String => Unit): VerifyReport =
    withClient(args, config) { (options, pluginConfig, client) =>
      val settings = options.verify.getOrElse(throw new IllegalArgumentException("verify needs --verify"))
      val report = KeyVerification.run(
        client,
        table(pluginConfig),
        pluginConfig.getSnapshotRowsIndexName,
        plan(pluginConfig, options.reverse),
        settings,
        if (options.progress) text => Console.err.println(s"[verify] snapshot key migration progress $text")
        else _ => ()
      )
      val legacyKeysLine = !options.reverse && configuredForm(pluginConfig) == KeyForm.PlainText
      KeyVerification.render(report, if (options.reverse) "reverse" else "forward", legacyKeysLine).foreach(out)
      report
    }

  private def withClient[T](args: Seq[String], config: Config)(
      f: (MigrationOptions, SnapshotPluginConfig, software.amazon.awssdk.services.dynamodb.DynamoDbClient) => T
  ): T = {
    val options = MigrationOptions.parse(args)
    // The tool connects with the plain v2 client whatever client-version the plugin uses: the moves bypass DAX, and
    // the v2 client builders accept only a v2 client-version.
    val toolConfig = config.withValue(
      "j5ik2o.dynamo-db-snapshot.dynamo-db-client.client-version",
      ConfigValueFactory.fromAnyRef("v2")
    )
    val system = ActorSystem("snapshot-key-migration", toolConfig)
    try {
      val pluginConfig  = SnapshotPluginConfig.fromConfig(system.settings.config.getConfig("j5ik2o.dynamo-db-snapshot"))
      val pluginContext = SnapshotPluginContext(system, pluginConfig)
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
