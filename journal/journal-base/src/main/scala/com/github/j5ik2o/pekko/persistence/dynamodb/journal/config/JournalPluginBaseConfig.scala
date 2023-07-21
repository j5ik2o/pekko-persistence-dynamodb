package com.github.j5ik2o.pekko.persistence.dynamodb.journal.config

import com.github.j5ik2o.pekko.persistence.dynamodb.config.{ BackoffConfig, PluginConfig }

trait JournalPluginBaseConfig extends PluginConfig {
  val columnsDefConfig: JournalColumnsDefConfig
  val getJournalRowsIndexName: String
  val consistentRead: Boolean
  val queryBatchSize: Int
  val readBackoffConfig: BackoffConfig
}
