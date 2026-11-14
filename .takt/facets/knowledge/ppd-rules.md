# ppd-rules

PPD（pekko-persistence-dynamodb、サンプルの pekko-cqrs-es-example を含む）の全ステップ共通の規約の実行用要約。正はリポジトリの文書（AGENTS.md、docs/、build.sbt）であり、矛盾したら文書が優先する。

## マージと公開

- マージは squash のみ。
- 不可逆操作（publish、tag 付け、force push、deploy、外部への送信、秘密情報の変更、未 push の作業の削除）はオーナーの承認が要る。ゴールの中では行わない。

## レビュー

- レビューボットのコメントは無視しない。直すか反論して、スレッドをすべて解決する。

## 生成物

- `tools/dynamodb-setup/` の table JSON と `test/src/main/resources/dynamodb-local-fixture.json` は生成物。手編集しない（再生成は ppd-coding-rules 参照）。

## 検証

- push の前に `sbt -batch lint` を通す。
- 変更した tools スクリプトの該当テストを `python3 -m unittest` で実行する。
- Docker が要るテスト（journal、snapshot、state の各スイート）は、変更が書き込み経路に触れるときに Docker を起動してから実行する。

## コードの規約

- AWS SDK の import は base-v1、base-v2、と許可リストのファイルに限る。
- 判断は「おすすめ」を根拠にしない。決定的な主張は一次情報で裏取り、反証を試み、Jev（TypeSafe AI の判断照会 API）の確率で判定する。

## ボード

- 進捗ボードのステータス移動は、着手したとき In Progress へ、プルリクエストを作成したとき In Review へ行う。この移動はゴールごとに manager が gh で行う（横断の決まり、2026-11-14）。本体はプロジェクト #11、サンプルはプロジェクト #12。コマンドは `gh project item-edit <ボード番号> --owner j5ik2o --url <issue の URL> --field "Status" --value "In Progress"`（In Review も同様）。
- 項目の追加・クローズ・マージ・再オープンの遷移はボードの自動ルールが担む。手で移すのは着手時とプルリクエスト作成時だけ。

## 報告

- 報告は初めて読む人にも通じる形に書く。略語は展開し、触れた issue やプルリクエストには URL を添える。
- 現在の値（Run の ID、ターミナル、モデルなど）は推測で埋めず、実物で確認する。
