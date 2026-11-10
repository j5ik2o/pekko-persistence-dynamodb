# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

実装、テスト、docs、PR 本文は済んだ。残りは CI の確認とレビュー待ち。

- head に省略できる `revision` を足した。更新のたびに 1 増える。購読側の約束は `journal-head.md` にある。
- soft delete 済みの行は更新で戻らない。`JournalHeadRowUpdateRejectedException(RowDeleted)` で拒否する。
- 失敗を型で分けた。新しい子は `JournalHeadConcurrentUpdateException` と `JournalHeadRowUpdateRejectedException`。
  行の 400 KB 超過は送る前に測る。head が減った、head の形が壊れている、未知の取り消し理由は `IllegalStateException`。
- `DynamoDBJournal` のシリアライズ失敗は `IllegalArgumentException` に cause を残す。
- `write-backoff` が無効なときは、再試行の間に待たない。
- docs に、外から来た更新が集約を止めることがある点、soft delete と更新の重なり、リクエスト自体の失敗は SDK の例外のままである点を書いた。

全体テスト (`sbt -batch test`, Docker 込み) は失敗 0 件。取り消し 1 件は以前からある perf probe。
競合の結合テストは、60 件の連続永続化の間に 177 件の更新を送り、結果はすべて型のついたもの (`Done` 3、最新の書き込みにない拒否 174) だった。
集約の永続化は止まらなかった。`TransactionConflict` の経路はこのテストでは通っていない。起きない証明ではない。その経路は単体テストで確かめている。

## 次の一手

CI (test 9 本、lint) と Bugbot の結果を見る。指摘は「直す」「理由をつけて直さない」「聞く」に分ける。

## 未決の判断

- 古いイベント (head の最新の書き込みにない番号) の更新は、今の拒否のまま。通すなら head のスキーマを含む別の設計になる。
- 永続化側の `classifyTransactionCancellation` は `TransactionConflict` を再試行しない。外から来る更新を許すなら別の PR で扱う。範囲外。

## 範囲外

`runBatch` と `V1Responses`/`V2Responses` の `IllegalStateException`、スナップショットの一括削除、head なしの `updateRow`、ordering の上書き、キューが満杯のときの `Exception` (#887〜#891 など)。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/886 (ドラフト)
