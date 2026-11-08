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
package com.github.j5ik2o.pekko.persistence.dynamodb.utils

import com.typesafe.config.{ Config, ConfigFactory, ConfigParseOptions, ConfigSyntax }

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

/** Reads the DynamoDB Local fixture that `schema-check` renders from the table specs.
  *
  * The resource holds the same `CreateTable` shape as the files under `tools/dynamodb-setup`, so the helper that owns
  * the AWS SDK turns each entry into a `CreateTableRequest`.
  */
object DynamoDBLocalFixture {

  val FixtureResource: String = "/dynamodb-local-fixture.json"

  def table(tableName: String): Config =
    tables
      .find(config => config.getString("TableName") == tableName)
      .getOrElse(sys.error(s"the DynamoDB Local fixture has no table $tableName"))

  private lazy val tables: Vector[Config] =
    fixture.getConfigList("tables").asScala.toVector

  private lazy val fixture: Config = {
    val stream = Option(getClass.getResourceAsStream(FixtureResource)).getOrElse {
      sys.error(s"the DynamoDB Local fixture resource $FixtureResource is missing")
    }
    val reader = new InputStreamReader(stream, StandardCharsets.UTF_8)
    try ConfigFactory.parseReader(reader, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.JSON))
    finally reader.close()
  }
}
