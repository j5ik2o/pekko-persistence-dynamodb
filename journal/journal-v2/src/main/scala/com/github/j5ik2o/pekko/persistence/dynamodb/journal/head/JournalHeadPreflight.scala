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
package com.github.j5ik2o.pekko.persistence.dynamodb.journal.head

import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.JournalPluginContext
import com.github.j5ik2o.pekko.persistence.dynamodb.journal.config.JournalPluginConfig
import com.github.j5ik2o.pekko.persistence.dynamodb.utils.V2SyncClientFactory
import org.apache.pekko.actor.ActorSystem
import software.amazon.awssdk.services.dynamodb.model.{ DescribeTableRequest, ResourceNotFoundException }
import software.amazon.awssdk.services.dynamodb.{ DynamoDbClient => JavaDynamoDbSyncClient }

/** Checks the DynamoDB Stream settings of the `JournalHead` table and the journal table before head mode is enabled.
  *
  * Head mode orders the read model updates through the stream of the `JournalHead` table. It therefore needs a stream
  * on that table that carries the new item image, and it must not rely on the journal stream, which does not preserve
  * the order of one persistence id. This tool calls `DescribeTable` for both tables and reports a stream setting that
  * would break the ordering. It runs before the deployment, not as a plugin startup check, because a plugin cannot see
  * the tables of the other nodes and the check needs table metadata that costs a request.
  *
  * The tool reads the journal plugin config, so run it with the same config as the application:
  * {{{
  * sbt -batch "journal-v2/runMain com.github.j5ik2o.pekko.persistence.dynamodb.journal.head.JournalHeadPreflight"
  * }}}
  */
object JournalHeadPreflight {

  final case class TableStream(tableName: String, enabled: Boolean, viewType: Option[String])

  sealed trait Problem {
    def message: String
  }

  object Problem {

    final case class HeadStreamDisabled(tableName: String) extends Problem {
      override def message: String =
        s"the JournalHead table $tableName has DynamoDB Streams disabled; enable them with the stream view type NEW_IMAGE"
    }

    final case class HeadStreamViewTypeUnsupported(tableName: String, viewType: Option[String]) extends Problem {
      override def message: String =
        s"the JournalHead table $tableName uses the stream view type ${viewType.getOrElse("<none>")};" +
        " use NEW_IMAGE or NEW_AND_OLD_IMAGES"
    }

    final case class JournalStreamEnabled(tableName: String) extends Problem {
      override def message: String =
        s"the journal table $tableName has DynamoDB Streams enabled; disable them while head mode is on"
    }

  }

  val SupportedHeadStreamViewTypes: Set[String] = Set("NEW_IMAGE", "NEW_AND_OLD_IMAGES")

  def problems(head: TableStream, journal: TableStream): Vector[Problem] = {
    val headProblems =
      if (!head.enabled) Vector(Problem.HeadStreamDisabled(head.tableName))
      else if (!head.viewType.exists(SupportedHeadStreamViewTypes.contains))
        Vector(Problem.HeadStreamViewTypeUnsupported(head.tableName, head.viewType))
      else Vector.empty
    val journalProblems =
      if (journal.enabled) Vector(Problem.JournalStreamEnabled(journal.tableName)) else Vector.empty
    headProblems ++ journalProblems
  }

  def tableStream(client: JavaDynamoDbSyncClient, tableName: String): TableStream = {
    val request  = DescribeTableRequest.builder().tableName(tableName).build()
    val response = client.describeTable(request)
    val table    = response.table()
    val stream   = Option(table.streamSpecification())
    TableStream(
      tableName = table.tableName(),
      enabled = stream.flatMap(spec => Option(spec.streamEnabled())).exists(_.booleanValue()),
      viewType = stream.flatMap(spec => Option(spec.streamViewType())).map(_.toString)
    )
  }

  def check(
      client: JavaDynamoDbSyncClient,
      headTableName: String,
      journalTableName: String
  ): Vector[Problem] =
    problems(tableStream(client, headTableName), tableStream(client, journalTableName))

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("journal-head-preflight")
    val exitCodeTry = scala.util.Try {
      val config = system.settings.config
      val pluginConfig =
        JournalPluginConfig.fromConfig(config.getConfig("j5ik2o.dynamo-db-journal"))
      require(
        pluginConfig.clientConfig.clientVersion == ClientVersion.V2,
        "the JournalHead preflight requires client-version v2 without DAX"
      )
      val pluginContext = JournalPluginContext(system, pluginConfig)
      val client = pluginContext
        .newDynamicAccessor[V2SyncClientFactory]()
        .createThrow(pluginConfig.v2SyncClientFactoryClassName)
        .create
      try {
        val problems = check(client, pluginConfig.headConfig.tableName, pluginConfig.tableName)
        if (problems.isEmpty) {
          println(
            s"JournalHead preflight passed: the ${pluginConfig.headConfig.tableName} table has a stream," +
            s" and the ${pluginConfig.tableName} table has none"
          )
          0
        } else {
          problems.foreach(problem => System.err.println(s"JournalHead preflight failed: ${problem.message}"))
          1
        }
      } finally {
        client.close()
      }
    }
    exitCodeTry.failed.foreach {
      case _: ResourceNotFoundException =>
        System.err.println("JournalHead preflight failed: a configured table was not found")
      case exception =>
        System.err.println(s"JournalHead preflight failed: ${exception.getMessage}")
    }
    system.terminate()
    val exitCode = exitCodeTry.getOrElse(1)
    if (exitCode != 0) sys.exit(exitCode)
  }

}
