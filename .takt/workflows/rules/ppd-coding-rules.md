# ppd-coding-rules

機械ゲートで判定されるルールの事前提示。正は AGENTS.md と build.sbt である。

- Scala の整形は scalafmt、静的な直しは scalafix。`sbt -batch lint` が scalafmtCheck、test:scalafmtCheck、scalafmtSbtCheck、scalafixAll --check、checkModuleBoundaries として検査する。違反は `sbt fmt` で直す。
- 手編集禁止の生成物: `tools/dynamodb-setup/` の table JSON（account-table.json と legacy-*.json を除く）と `test/src/main/resources/dynamodb-local-fixture.json`。これらは TableSpecArtifactSpec が table spec との一致を検査する。直す場合は `TABLE_SPEC_WRITE=1 sbt -batch "schema-check/testOnly *TableSpecArtifactSpec"` で再生成する。
- `tools/` のスクリプトを変更したら、`tools/tests/` の該当テストを `python3 -m unittest` で実行して緑を確認する。
- AWS SDK の import は `base/base-v1`、`base/base-v2` と `project/module-boundaries.allowlist` の許可されたファイルに限る。lint の checkModuleBoundaries が検査する。
- 列名は各プラグインの `columns-def` が単一の情報源。`reference.conf`、table JSON、docs の間で名前をそろえる（SchemaDriftSpec が検査する）。
