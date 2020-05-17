package com.github.j5ik2o.akka.persistence.dynamodb.config

import com.typesafe.config.ConfigFactory
import org.scalatest.{ FreeSpec, Matchers }

class LegacyConfigFormatSpec extends FreeSpec with Matchers {
  "config" - {
    "load" in {
      def config(legacyConfigFormat: Boolean) = ConfigFactory.parseString(
        s"""
          |j5ik2o {
          |  dynamo-db-journal {
          |    legacy-config-format = ${legacyConfigFormat}
          |    class = "com.github.j5ik2o.akka.persistence.dynamodb.journal.DynamoDBJournal"
          |    plugin-dispatcher = "akka.actor.default-dispatcher"
          |    circuit-breaker {
          |      max-failures = 10
          |      call-timeout = 10s
          |      reset-timeout = 30s
          |    }
          |    table-name = "Journal"
          |    get-journal-rows-index-name = "GetJournalRowsIndex"
          |    tags-index-name = "TagsIndex"
          |    shard-count = 32
          |    partition-key-resolver-class-name = "com.chatwork.sagrada.interfaceAdaptor.aggregate.persistence.SagdaraPartitionKeyResolver"
          |    queue-enable = false
          |    queue-buffer-size = 32
          |    queue-overflow-strategy = "Fail"
          |    queue-parallelism = 32
          |    write-parallelism = 32
          |    query-batch-size = 32
          |    scan-batch-size = 512
          |    replay-batch-size = 32
          |    consistent-read = false
          |    soft-delete = true
          |    metrics-reporter-class-name = "com.chatwork.sagrada.interfaceAdaptor.aggregate.persistence.SagradaMetricsReporter"
          |    dynamo-db-client {
          |      max-concurrency = 32
          |      max-pending-connection-acquires = 1000
          |      read-timeout = 10s
          |      write-timeout = 10s
          |      connection-timeout = 10s
          |      connection-acquisition-timeout = 10s
          |      connection-time-to-live = 10s
          |      max-idle-connection-timeout = 10s
          |      use-connection-reaper = true
          |      threads-of-event-loop-group = 32
          |      use-http2 = false
          |      http2-max-streams = 32
          |      batch-get-item-limit = 100
          |      batch-write-item-limit = 25
          |    }
          |  }
          |
          |}
          |""".stripMargin
      )
      val journalPluginConfig1 = JournalPluginConfig.fromConfig(config(true).getConfig("j5ik2o.dynamo-db-journal"))
      journalPluginConfig1.tableName shouldBe "Journal"
      journalPluginConfig1.clientConfig.v2ClientConfig.asyncClientConfig.maxConcurrency shouldBe 32
      journalPluginConfig1.clientConfig.v2ClientConfig.asyncClientConfig.maxPendingConnectionAcquires shouldBe 1000

      val journalPluginConfig2 = JournalPluginConfig.fromConfig(config(false).getConfig("j5ik2o.dynamo-db-journal"))
      journalPluginConfig2.tableName shouldBe "Journal"
      journalPluginConfig2.clientConfig.v2ClientConfig.asyncClientConfig.maxConcurrency shouldBe 50
      journalPluginConfig2.clientConfig.v2ClientConfig.asyncClientConfig.maxPendingConnectionAcquires shouldBe 10000
    }
  }
}
