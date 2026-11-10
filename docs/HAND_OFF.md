# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

revision と soft-delete の保護、移行ツールの `revision` 削除は完了 (push 済み)。

いまは `asyncUpdateEvent` の失敗を型で分ける作業。型 (`JournalHeadConcurrentUpdateException`、`JournalHeadRowUpdateRejectedException`) は追加済みで、テストが赤。

- 単体テスト (`JournalRowStoreSpec`): 10 件が赤。やり直せる失敗、行なし・削除済み、head が減った場合、head の形が壊れている場合、行の 400 KB 超過。
- 結合テスト (`JournalHeadSpec`): 行なし、soft-delete、行の 400 KB 超過が赤。更新と永続化が競合するテストも追加した。
- 赤の実行: `sbt -batch "journal-base/testOnly *JournalRowStoreSpec"`、`sbt -batch "journal-v2/testOnly *JournalHeadSpec -- -z in-place"`

## 次の一手

`JournalRowStore.updateRowAndHead` を作り直す。head を読んで型にし (形が壊れていたら `IllegalStateException`)、取り消し理由を分類する。
行の条件が外れたときは行を読み直して、なし・削除済みを区別する。スロットリングと `TransactionConflict` は backoff で再試行する。
行の 400 KB は送る前に測る。`DynamoDBJournal` のシリアライズ失敗は元の例外を cause に残す。そのあと docs と PR 本文を直す。

## 未決の判断

- head ストリームの購読側が、チェックポイント以下のイベント番号を捨てる作りだと、更新を受け取れない。
  ドキュメントに「イベント番号をキーに upsert する」と書いて対応した。head に版番号を足す案は、スキーマの変更になるので、この PR ではやらない。
- head の最新の書き込みに含まれない番号は、既存の `JournalHeadAsyncUpdateEventUnsupportedException` で拒否している。古いイベントの更新も通したいなら別の設計になる。
- 同時更新の再試行は 3 回で打ち切り、`IllegalStateException` で失敗する。専用の例外 (公開 API の追加) は作っていない。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/886 (ドラフト)
