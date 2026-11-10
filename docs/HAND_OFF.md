# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

直した。`head.enabled=true` の `InPlaceUpdateEvent` は、ジャーナル行と JournalHead のイベントを `TransactWriteItems` 1 回で更新する。
変更の中心は `JournalRowStore.update` (journal-base)。deprecated ドライバと公開 API は変えていない。

- 追加した結果: `JournalHeadSpec` の `in-place` 7 件が通る (修正前は 5 件が拒否の例外で失敗)
- `JournalRowStoreSpec` に再試行と取り消しの 7 件を追加
- `sbt test` 全体、`lint`、Scala 2.12 と 3.6 のコンパイル、sphinx の `-W` ビルドが通る
- 独立レビューの指摘は 1 件ずつ仕分け済み (PR 本文に記載)

## 次の一手

PR のレビューコメント (Cursor Bugbot など) を待つ。届いたら「直す」「理由をつけて直さない」「聞く」に分ける。マージはしない。

## 未決の判断

- head ストリームの購読側が、チェックポイント以下のイベント番号を捨てる作りだと、更新を受け取れない。
  ドキュメントに「イベント番号をキーに upsert する」と書いて対応した。head に版番号を足す案は、スキーマの変更になるので、この PR ではやらない。
- head の最新の書き込みに含まれない番号は、既存の `JournalHeadAsyncUpdateEventUnsupportedException` で拒否している。古いイベントの更新も通したいなら別の設計になる。
- 同時更新の再試行は 3 回で打ち切り、`IllegalStateException` で失敗する。専用の例外 (公開 API の追加) は作っていない。

## PR

(PR を出したらここに URL を書く)
