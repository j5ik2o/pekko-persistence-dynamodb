# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

`asyncUpdateEvent` の失敗を型で分ける実装は済んだ。単体テスト 27 件、結合テストの in-place 群、全体テスト、lint、Scala 2.12 と 3.6 のコンパイル、sphinx の `-W` ビルドが通る。

- `JournalHeadConcurrentUpdateException`: 再試行を使い切った場合 (head の変化、`TransactionConflict`、スロットリング)
- `JournalHeadRowUpdateRejectedException`: 行がない、soft-delete 済み。`reason` を値で持つ
- 行の 400 KB 超過は送る前に測る。head が減った、head の形が壊れている、未知の取り消し理由は `IllegalStateException`
- `DynamoDBJournal` のシリアライズ失敗は `IllegalArgumentException` に cause を残す

独立レビューの指摘のうち、backoff を切っていても待つ点と、テストの穴を、このあと直す。

## 次の一手

backoff の無効時は待たない。テストの穴を埋める。docs に「リクエスト自体の失敗は SDK の例外のまま」を足す。PR 本文を直す。

## 未決の判断

- head ストリームの購読側が、チェックポイント以下のイベント番号を捨てる作りだと、更新を受け取れない。
  ドキュメントに「イベント番号をキーに upsert する」と書いて対応した。head に版番号を足す案は、スキーマの変更になるので、この PR ではやらない。
- head の最新の書き込みに含まれない番号は、既存の `JournalHeadAsyncUpdateEventUnsupportedException` で拒否している。古いイベントの更新も通したいなら別の設計になる。
- 同時更新の再試行は 3 回で打ち切り、`IllegalStateException` で失敗する。専用の例外 (公開 API の追加) は作っていない。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/886 (ドラフト)
