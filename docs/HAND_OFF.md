# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

#886 の追加作業のうち、revision と soft-delete の保護は実装済み (全体テスト緑)。

1. head に省略できる `revision` を足した。head の Put の条件は `sequence-nr` と `revision` の一致。
2. head モードの行の更新は `deleted` を書き換えず、条件は「行があり、`deleted` が true でない」。

独立レビューで次の 2 点が出た。これから直す。
- 移行ツールの `raiseJournalHeadIfBehind` が `revision` を残す。`REMOVE revision` を足す。
- 購読側の再構築手順で `revision` が未指定。docs に「再構築の前に head の `revision` を読む」と、events が空の record の行を足す。

## 次の一手

上の 2 点をテスト先行で直す。そのあと、例外の型の整理 (IllegalStateException は「あり得ない状態」だけ) に進む。

## 未決の判断

- head ストリームの購読側が、チェックポイント以下のイベント番号を捨てる作りだと、更新を受け取れない。
  ドキュメントに「イベント番号をキーに upsert する」と書いて対応した。head に版番号を足す案は、スキーマの変更になるので、この PR ではやらない。
- head の最新の書き込みに含まれない番号は、既存の `JournalHeadAsyncUpdateEventUnsupportedException` で拒否している。古いイベントの更新も通したいなら別の設計になる。
- 同時更新の再試行は 3 回で打ち切り、`IllegalStateException` で失敗する。専用の例外 (公開 API の追加) は作っていない。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/886 (ドラフト)
