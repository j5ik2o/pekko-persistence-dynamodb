# HAND_OFF: スナップショットの一括削除と未処理項目 (#888)

## いまの状態

修正、テスト、PR 本文は済んだ。残りは CI の確認とレビュー待ち。

- スナップショットの一括削除は `write-backoff` の上限と backoff で再試行する。
- 再試行を使い切ったら `SnapshotStoreException.BatchWriteRetriesExhausted` を返す。型は `table`、`attempts`、原因を持つ。削除は冪等なので、呼び出し側はやり直せる。
- `RestartSource` の再試行の窓は 1 回の backoff より長く取った。窓が短いと回数がリセットされ、上限なく再試行してしまうため。
- v1 と v2 の DAO から `writeBackoffConfig` を `SnapshotStore` に渡す。
- テストは先にコミットした (`01efc73a`)。修正はその上に積んだ (`e085cfb1`)。

検証は `snapshot-base` 18 件、`snapshot-v1` 39 件、`snapshot-v2` 26 件、`schema-check` 20 件が成功した。`lint` と Scala 2.12.20 / 3.6.4 のコンパイルも成功した。

修正前の失敗:

```
[info] - retries a batch delete that DynamoDB leaves unprocessed *** FAILED ***
[info]   The future returned an exception of type: java.lang.IllegalStateException, with message: BatchWriteItem left 1 unprocessed items.
[info] *** 1 TEST FAILED ***
```

修正後:

```
[info] - retries a batch delete that DynamoDB leaves unprocessed
[info] - fails with a retryable exception when a batch delete keeps failing
[info] All tests passed.
```

## 次の一手

CI と Bugbot の結果を見る。指摘は「直す」「理由をつけて直さない」「聞く」に分ける。

## 未決の判断

- `write-backoff.enabled = false` でも未処理項目は再試行する。ジャーナルの `runBatch` が無条件に再試行していることに合わせた。設定で止めたいなら別 issue にする。
- `SnapshotStore` のコンストラクタに `writeBackoffConfig` を足した。公開 API の変更として PR 本文に書いた。
- `docs/HAND_OFF.md` は PR #886 でも追加している。マージ時に競合する可能性がある。

## 範囲外

issue #887 の `V1Responses`、`V2Responses`、`JournalRowStore` の未処理項目の型と再試行。スナップショット側だけを変えた。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/892 (ドラフト)
