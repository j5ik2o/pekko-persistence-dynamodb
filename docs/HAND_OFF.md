# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

#886 の追加作業 2 点のうち、テストが赤になった段階。

1. head に省略できる版番号 `revision` を足す。通常の書き込みは書かない。属性がなければ版 0。`asyncUpdateEvent` が head を更新するたびに 1 増やす。
   列名は固定で、`JournalHeadColumnsDefConfig` の引数は増やさない。head の Put の条件は「`sequence-nr` と `revision` が一致」にする。
2. soft-delete された行の復活を防ぐ。head モードの行の更新は `deleted` を書き換えず、条件に「`deleted` が true でない」を足す。

赤の理由: `revision` 属性がない。soft-delete した最新のイベントを更新すると `Done` で成功し、`deleted=false` に戻る。

- 赤の実行: `sbt -batch "journal-v2/testOnly *JournalHeadSpec -- -z in-place"` (Docker が必要)、`sbt -batch "journal-base/testOnly *JournalRowStoreSpec"`

## 次の一手

`JournalRowStore.updateRowAndHead` に revision と条件を足し、行の更新を head モード用に分ける。`journal-head.md` の購読側の約束を
`(sequence-nr, revision)` のチェックポイントに書き直す。`table-format.md` と `journal-head.md` の属性表に `revision` を足す。

## 未決の判断

- head ストリームの購読側が、チェックポイント以下のイベント番号を捨てる作りだと、更新を受け取れない。
  ドキュメントに「イベント番号をキーに upsert する」と書いて対応した。head に版番号を足す案は、スキーマの変更になるので、この PR ではやらない。
- head の最新の書き込みに含まれない番号は、既存の `JournalHeadAsyncUpdateEventUnsupportedException` で拒否している。古いイベントの更新も通したいなら別の設計になる。
- 同時更新の再試行は 3 回で打ち切り、`IllegalStateException` で失敗する。専用の例外 (公開 API の追加) は作っていない。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/886 (ドラフト)
