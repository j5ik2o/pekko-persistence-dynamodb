# HAND_OFF: issue 889

作業ブランチ上の引き継ぎメモ。作業完了時に削除し、要点を PR 本文へ移す。

## いまの状態

- 対象: https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/889
- `main` (03d57efe, #886 / #892 マージ済み) から分岐。
- `test(journal)` コミットで、head 書き込み経路の TransactionConflict / ProvisionedThroughputExceeded が
  SDK の `TransactionCanceledException` のまま抜けることを再現した。
  - 赤: 4 件中 3 件失敗（conflict / throttling / exhaustion）。conditional check の 1 件だけ成功。
- 修正を実装済み（未コミット時点のメモ）:
  - `JournalHeadException.scala` に `JournalHeadConcurrentWriteException` を追加。
  - `V2JournalRowWriteDriver` で取り消し理由を分類し、競合・スロットリングを `write-backoff` の上限付きで再送。
  - 上限を使い切ったら typed failure を返す。
  - `module-boundaries.allowlist` に新しいテストファイルを追加（v2 SDK を直接使うテストのため）。

## 次の一手

- `journal-v2/test` の結果を確認する。
- 緑なら修正をコミットし、`docs/HAND_OFF.md` を削除するコミットを積んで push。
- ドラフト PR を作成（日本語、issue URL、赤→緑の出力、公開 API の追加を記載）。

## 未決の判断

- 未知の取り消し理由は従来どおり SDK 例外のまま（issue の対象は競合・スロットリング）。
- 再試行は `write-backoff.enabled` に関係なく `max-restarts` と backoff を使う（#892 と同じ判断）。

## PR の URL

- 未作成。
