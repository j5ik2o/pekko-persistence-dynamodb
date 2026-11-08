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
package com.github.j5ik2o.pekko.persistence.dynamodb.config

import com.github.j5ik2o.pekko.persistence.dynamodb.client.ClientVariantRegistry
import com.github.j5ik2o.pekko.persistence.dynamodb.config.client.ClientVersion
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

final class CommonConfigParserSpec extends AnyFreeSpec with Matchers {

  private val wrongClass = "java.lang.String"

  private val defaults: Config =
    ConfigFactory
      .parseString(
        """
          |v1-async-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v1-async-client-factory-class-name}
          |v1-sync-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v1-sync-client-factory-class-name}
          |v1-dax-async-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v1-dax-async-client-factory-class-name}
          |v1-dax-sync-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v1-dax-sync-client-factory-class-name}
          |v2-async-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v2-async-client-factory-class-name}
          |v2-sync-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v2-sync-client-factory-class-name}
          |v2-dax-async-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v2-dax-async-client-factory-class-name}
          |v2-dax-sync-client-factory-class-name = ${j5ik2o.dynamo-db-defaults.v2-dax-sync-client-factory-class-name}
          |metrics-reporter-provider-class-name = ${j5ik2o.dynamo-db-defaults.metrics-reporter-provider-class-name}
          |metrics-reporter-class-name = ${j5ik2o.dynamo-db-defaults.metrics-reporter-class-name}
          |trace-reporter-provider-class-name = ${j5ik2o.dynamo-db-defaults.trace-reporter-provider-class-name}
          |trace-reporter-class-name = ${j5ik2o.dynamo-db-defaults.trace-reporter-class-name}
          |write-backoff = ${j5ik2o.dynamo-db-defaults.write-backoff}
          |read-backoff = ${j5ik2o.dynamo-db-defaults.read-backoff}
          |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
      .resolve()

  private def config(overrides: String): Config =
    ConfigFactory.parseString(overrides).withFallback(defaults).resolve()

  "clientFactories" - {
    "reads every factory key even when its variant is not selected" in {
      val factories = CommonConfigParser.clientFactories(
        config("""v1-async-client-factory-class-name = "com.example.Unvalidated""""),
        ClientVersion.V2,
        CommonConfigParser.FactoryValidation.PerVariant
      )

      factories.v1AsyncClientFactoryClassName shouldBe "com.example.Unvalidated"
    }

    "validates only the selected variant when the policy is PerVariant" in {
      an[IllegalArgumentException] should be thrownBy {
        CommonConfigParser.clientFactories(
          config(s"""v2-async-client-factory-class-name = "$wrongClass""""),
          ClientVersion.V2,
          CommonConfigParser.FactoryValidation.PerVariant
        )
      }

      noException should be thrownBy {
        CommonConfigParser.clientFactories(
          config(s"""v2-dax-async-client-factory-class-name = "$wrongClass""""),
          ClientVersion.V2,
          CommonConfigParser.FactoryValidation.PerVariant
        )
      }

      an[IllegalArgumentException] should be thrownBy {
        CommonConfigParser.clientFactories(
          config(s"""v2-dax-async-client-factory-class-name = "$wrongClass""""),
          ClientVersion.V2Dax,
          CommonConfigParser.FactoryValidation.PerVariant
        )
      }

      noException should be thrownBy {
        CommonConfigParser.clientFactories(
          config(s"""v2-async-client-factory-class-name = "$wrongClass""""),
          ClientVersion.V2Dax,
          CommonConfigParser.FactoryValidation.PerVariant
        )
      }
    }

    "keeps v2 validating the v2 DAX keys when the policy is V2DaxUnderV2" in {
      an[IllegalArgumentException] should be thrownBy {
        CommonConfigParser.clientFactories(
          config(s"""v2-dax-async-client-factory-class-name = "$wrongClass""""),
          ClientVersion.V2,
          CommonConfigParser.FactoryValidation.V2DaxUnderV2
        )
      }

      noException should be thrownBy {
        CommonConfigParser.clientFactories(
          config(s"""v2-dax-async-client-factory-class-name = "$wrongClass""""),
          ClientVersion.V2Dax,
          CommonConfigParser.FactoryValidation.V2DaxUnderV2
        )
      }
    }
  }

  "validates" - {
    "selects the matching entry under PerVariant" in {
      ClientVariantRegistry.all.foreach { entry =>
        CommonConfigParser.validates(
          entry,
          entry.clientVersion,
          CommonConfigParser.FactoryValidation.PerVariant
        ) shouldBe true

        ClientVariantRegistry.all.filterNot(_ == entry).foreach { other =>
          CommonConfigParser.validates(
            other,
            entry.clientVersion,
            CommonConfigParser.FactoryValidation.PerVariant
          ) shouldBe false
        }
      }
    }

    "adds the v2 DAX entry to v2 and skips it under v2-dax for V2DaxUnderV2" in {
      CommonConfigParser.validates(
        ClientVariantRegistry.V2,
        ClientVersion.V2,
        CommonConfigParser.FactoryValidation.V2DaxUnderV2
      ) shouldBe true
      CommonConfigParser.validates(
        ClientVariantRegistry.V2Dax,
        ClientVersion.V2,
        CommonConfigParser.FactoryValidation.V2DaxUnderV2
      ) shouldBe true
      CommonConfigParser.validates(
        ClientVariantRegistry.V2,
        ClientVersion.V2Dax,
        CommonConfigParser.FactoryValidation.V2DaxUnderV2
      ) shouldBe false
      CommonConfigParser.validates(
        ClientVariantRegistry.V2Dax,
        ClientVersion.V2Dax,
        CommonConfigParser.FactoryValidation.V2DaxUnderV2
      ) shouldBe false
    }
  }

  "reporters" - {
    "reads the provider and optional reporter class names" in {
      val reporters = CommonConfigParser.reporters(defaults)

      reporters.metricsReporterProviderClassName should endWith("MetricsReporterProvider$Default")
      reporters.metricsReporterClassName shouldBe Some(
        "com.github.j5ik2o.pekko.persistence.dynamodb.metrics.MetricsReporter$None"
      )
      reporters.traceReporterProviderClassName should endWith("TraceReporterProvider$Default")
      reporters.traceReporterClassName shouldBe Some(
        "com.github.j5ik2o.pekko.persistence.dynamodb.trace.TraceReporter$None"
      )
    }

    "rejects a reporter class of the wrong type" in {
      an[IllegalArgumentException] should be thrownBy {
        CommonConfigParser.reporters(config("""metrics-reporter-class-name = "java.lang.String""""))
      }
    }
  }

  "backoffs" - {
    "reads the write and read backoff blocks" in {
      val backoffs = CommonConfigParser.backoffs(defaults)

      backoffs.write.enabled shouldBe false
      backoffs.write.minBackoff shouldBe 3.seconds
      backoffs.write.maxBackoff shouldBe 30.seconds
      backoffs.read.enabled shouldBe false
      backoffs.read.maxRestarts shouldBe 3
    }

    "merges an override with the defaults" in {
      val backoffs = CommonConfigParser.backoffs(config("write-backoff.enabled = true"))

      backoffs.write.enabled shouldBe true
      backoffs.write.minBackoff shouldBe 3.seconds
      backoffs.read.enabled shouldBe false
    }
  }
}
