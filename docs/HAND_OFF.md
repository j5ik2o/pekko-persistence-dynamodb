# HAND_OFF

## いまの状態

- issue #890 の範囲を修正した。書き込みキューが満杯のときは `JournalWriteQueueFullException`、閉じているときは `JournalWriteQueueClosedException` を返す。
- `JournalWriteQueueException` を sealed な基底型として `journal-base` に追加した。
- put と delete の両キューは `WriteJournalDaoImpl.enqueue` を共有する。
- `dispose()` 後に `offer` が返す `StreamDetachedException` は、閉鎖と同じ状態として `JournalWriteQueueClosedException` に写像する。
- ブランチは `t3/issue-890-typed-recoverable-errors-1`。テストを先にコミットし（`0dafd59b`）、修正をその上に積んだ（`c7f8ad36`）。
- 公開型が 3 つ増えたので `schema-check/public-api-baseline.txt` に記録した。
- 確認済みのコマンドは次のとおり。
  - `sbt -batch "journal-v2/testOnly *WriteJournalDaoQueueSpec"` は 2 件成功。
  - `sbt -batch "journal-base/test"` は 45 件成功。
  - `sbt -batch "schema-check/test"` は 20 件成功。
  - `sbt -batch lint` は成功。
  - `journal-v2/Test/compile` は 2.12.20 と 3.6.4 で成功。
  - `DynamoDBJournalV1SyncSpec` と `DynamoDBJournalV2SyncSpec` は各 16 件成功。

## 次の一手

- ドラフト PR のレビューコメントを、一つずつ「直す」「理由をつけて直さない」「聞く」に分ける。
- レビューが付いたら `sbt -batch lint` と関係モジュールのテストを再実行する。
- マージ、issue のクローズ、force-push、main への push はしない。

## 未決の判断

- `QueueOfferResult.Failure` は今回の issue の範囲外なので、汎用の `Exception` のままにした。型を付けるなら別 issue にする。
- `StreamDetachedException` を閉鎖に写像してよいかはレビューで確認したい。plugin の `dispose()` 後という意味では閉鎖と同じだが、materializer 側の異常を閉鎖に丸める判断でもある。
- このリポジトリでは `docs/HAND_OFF.md` が main に存在しない。PR #886 が同じファイルを追加しているため、両方がマージされるとコンフリクトする。作業指示に従って追加したが、統合時に内容を合わせる必要がある。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/893
