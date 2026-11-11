package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{
  AttributeKind,
  AttributeName,
  Column,
  Presence,
  PrimaryKey,
  StreamView,
  TableName,
  TableSpec
}
import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

final class TableSpecJsonSpec extends AnyFreeSpec with Matchers {

  "the fixture renderer rejects two tables with the same name" in {
    val id = AttributeName("persistence-id")
    val journal = TableSpec(
      table = TableName("Journal"),
      columns = Vector(Column(id, AttributeKind.S, Presence.Required)),
      primaryKey = PrimaryKey(id, None),
      indexes = Vector.empty,
      stream = None
    )
    val headWithJournalName = journal.copy(stream = Some(StreamView.NewImage))

    val error = intercept[IllegalArgumentException] {
      TableSpecJson.renderFixture(
        Seq(TableSpecJson.FixtureTable(journal), TableSpecJson.FixtureTable(headWithJournalName))
      )
    }

    error.getMessage should include("the DynamoDB Local fixture has duplicate table names: Journal, Journal")
  }

  "the generated fixture contains one journal table and one journal head table" in {
    val fixture = TableSpecArtifacts.artifacts.toMap.apply("test/src/main/resources/dynamodb-local-fixture.json")
    val names   = ConfigFactory.parseString(fixture).getConfigList("tables").asScala.map(_.getString("TableName"))

    names.count(_ == "Journal") shouldBe 1
    names.count(_ == "JournalHead") shouldBe 1
  }
}
