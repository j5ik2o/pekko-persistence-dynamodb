# HAND_OFF: スナップショットの一括削除と未処理項目 (#888)

## いまの状態

修正、テスト、PR 本文、別系統レビューへの対応は済んだ。残りは新しい head の CI 確認。

- スナップショットの一括削除は `write-backoff` の上限と backoff で再試行する。
- 再試行を使い切ったら `SnapshotStoreException.BatchWriteRetriesExhausted` を返す。型は `table`、`attempts`、原因を持つ。削除は冪等なので、呼び出し側はやり直せる。
- 再試行するのは adapter が未処理項目を表す `IllegalStateException` のときだけ。恒久的な失敗は再試行せず、元の例外のまま伝播する。
- v1 と v2 の DAO から `writeBackoffConfig` を `SnapshotStore` に渡す。
- テストは先にコミットした (`01efc73a`)。修正はその上に積んだ (`e085cfb1`、`d65bbc13`)。

検証は `snapshot-base` 20 件、`snapshot-v1` 39 件、`snapshot-v2` 26 件、`V1ResponsesSpec` 6 件、`V2ResponsesSpec` 6 件、`schema-check` 20 件が成功した。`lint` と Scala 2.12.20 / 3.6.4 のコンパイルも成功した。

Bugbot の指摘「Permanent failures marked as retryable」は直した。`d65bbc13` で再試行の対象を未処理項目の `IllegalStateException` に絞り、恒久失敗は 1 回で元の例外を返す。`SnapshotStoreSpec` に恒久失敗のテストを足し、スレッドは解決済み。

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
[info] - does not retry a permanent batch delete failure
[info] - does not retry when max-restarts is zero
[info] All tests passed.
```

## 別系統レビューへの対応

レビューは blocker なし、should 2 件、nit 3 件だった。分類は次のとおり。

1. should 1、再試行の判定が `IllegalStateException` 頼みで #887 の型変更に気づけない。直した。`SnapshotStore` のコメントに #887 への依存を明記し、`V1ResponsesSpec` と `V2ResponsesSpec` で未処理項目の失敗が `IllegalStateException` であることを固定した。#887 が型を変えるとこれらのテストが落ちる。
2. should 2、PR 本文が古い。直した。`RestartSource` の記述を消し、現在の `recoverWithRetries` と `Source.delay` の実装、テスト件数に合わせた。
3. nit 3、`docs/HAND_OFF.md` を PR に含めている。聞く。タスクの指示で追記と push を行うが、PR に含めるかは加藤さんの判断待ち。PR #886 との競合に注意。
4. nit 4、既定設定でも再試行が走る。理由をつけて直さない。ジャーナルの `runBatch` と同じく、未処理項目は設定の `enabled` に関係なく再試行する。上限と待ち時間は `write-backoff` から取るので、上限なくは再試行しない。PR 本文とこの HAND_OFF に明記した。
5. nit 5、テストの隙間。直した。恒久失敗は同じ例外インスタンスが返ることを見る。再試行を使い切るテストは `getCause` を見る。`maxRestarts = 0` の境界テストを足した。

## 次の一手

新しい head の CI を見る。追加のレビューコメントが来たら、一つずつ「直す」「理由をつけて直さない」「聞く」に分ける。

## 未決の判断

- `write-backoff.enabled = false` でも未処理項目は再試行する。ジャーナルの `runBatch` が無条件に再試行していることに合わせた。設定で止めたいなら別 issue にする。
- `SnapshotStore` のコンストラクタに `writeBackoffConfig` を足した。公開 API の変更として PR 本文に書いた。
- `docs/HAND_OFF.md` を PR に含めるかは加藤さんの判断待ち。PR #886 でも同じファイルを追加しているため、マージ時に競合する可能性がある。

## 範囲外

issue #887 の `V1Responses`、`V2Responses`、`JournalRowStore` 本体の変更。スナップショット側の実装と、未処理項目のシグナルを固定するテストだけを変えた。

## PR

https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/892 (ドラフト)
