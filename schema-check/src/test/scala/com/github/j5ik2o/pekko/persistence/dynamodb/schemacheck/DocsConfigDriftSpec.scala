package com.github.j5ik2o.pekko.persistence.dynamodb.schemacheck

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Checks the claims in `table-format.md` and `customize.md` against `reference.conf` and the table JSON.
  *
  * `SchemaDrift` owns the general check. This spec keeps the drift that C7 fixed from coming back: the removed
  * `TagsIndex`, the camelCase `persistenceId`, and the state sort-key claim.
  */
final class DocsConfigDriftSpec extends AnyFreeSpec with Matchers {

  "the docs agree with reference.conf and the table JSON" in {
    val repo     = Repo.locate()
    val problems = SchemaDrift.check(repo)

    withClue(problems.map(problem => s"${problem.id}: ${problem.detail}").mkString("\n")) {
      problems shouldBe empty
    }
  }

  "the removed TagsIndex is absent from the docs and remains only in the legacy table" in {
    val repo        = Repo.locate()
    val tableFormat = repo.read(repo.root.resolve("docs/src/sphinx/table-format.md"))
    val legacy      = repo.read(repo.root.resolve("tools/dynamodb-setup/legacy-journal-table.json"))

    tableFormat should not include "TagsIndex"
    legacy should include("TagsIndex")
  }

  "the snapshot index uses the configured persistence-id column, not the camelCase spelling" in {
    val repo        = Repo.locate()
    val tableFormat = repo.read(repo.root.resolve("docs/src/sphinx/table-format.md"))

    tableFormat should not include "persistenceId"
    tableFormat should include("GetSnapshotRowsIndex")
    tableFormat should not include "GetSnaphotRowsIndex"
  }

  "a State sort key of '-' in table-format.md is a drift problem" in {
    val stateTable =
      """{"KeySchema":[{"AttributeName":"pkey","KeyType":"HASH"},{"AttributeName":"skey","KeyType":"RANGE"}]}"""
    val tableFormat =
      "| type | name | partition key | sort key | comments |\n" +
      "|:--|:--|:--|:--|:--|\n" +
      "|table | State | `pkey` (String) | `-` | Used for durable state. |\n"

    val problem = SchemaDrift.stateSortKeyProblem(tableFormat, stateTable)

    problem.map(_.id) shouldBe Some("state-sort-key-table-format")
    problem.exists(_.detail.contains("skey")) shouldBe true
  }

  "a missing State sort key and a missing RANGE key are reported" in {
    val stateTable =
      """{"KeySchema":[{"AttributeName":"pkey","KeyType":"HASH"}]}"""
    val tableFormat =
      "| type | name | partition key | sort key | comments |\n" +
      "|:--|:--|:--|:--|:--|\n" +
      "|table | State | `pkey` (String) | - | Used for durable state. |\n"

    val problem = SchemaDrift.stateSortKeyProblem(tableFormat, stateTable)

    problem.map(_.id) shouldBe Some("state-sort-key-table-format")
    problem.exists(_.detail.contains("<missing>")) shouldBe true
  }

  "the State sort key in table-format.md matches the generated table JSON" in {
    val stateTable =
      """{"KeySchema":[{"AttributeName":"pkey","KeyType":"HASH"},{"AttributeName":"skey","KeyType":"RANGE"}]}"""
    val tableFormat =
      "| type | name | partition key | sort key | comments |\n" +
      "|:--|:--|:--|:--|:--|\n" +
      "|table | State | `pkey` (String) | `skey` (String) | Used for durable state. |\n"

    SchemaDrift.stateSortKeyProblem(tableFormat, stateTable) shouldBe None
  }

  "the state docs document the sort key and its resolver" in {
    val repo      = Repo.locate()
    val customize = repo.read(repo.root.resolve("docs/src/sphinx/customize.md"))

    customize.toLowerCase should not include "has not the sort-key"
    customize should include("sort-key-resolver-class-name")
    customize should include("state.SortKeyResolver$PersistenceIdBased")
  }
}
