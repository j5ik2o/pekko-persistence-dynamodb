## なぜ

https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/890

書き込みキューが満杯のときと閉じているときに、`WriteJournalDaoImpl` は汎用の `Exception` を返していました。呼び出し側は、満杯と閉鎖を区別できませんでした。既定の `queue-overflow-strategy = "fail"` では、満杯は `QueueOfferResult.Failure(BufferOverflowException)` として返り、キューは停止します。この経路も型にしました。

判定の基準は「`IllegalStateException` はあり得ない状況にだけ使い、あり得る状況は利用者が回復できる失敗として型で表す」です。

## 変更

- `JournalWriteQueueException` を sealed な基底型として追加しました。
- 満杯は `JournalWriteQueueFullException` で表し、バッファサイズと原因を持ちます。既定の fail 方式では `BufferOverflowException` を cause に残します。
- 閉鎖は `JournalWriteQueueClosedException` で表し、detach の原因を cause に残します。
- put と delete の両キューが一つの `enqueue` ヘルパーを通るようにし、重複した match を削りました。
- `QueueOfferResult.Failure` のうち `BufferOverflowException` は満杯に、それ以外は従来どおり汎用の `Exception` に写像します。
- Pekko のアクターの挙動は変わりません。書き込みの失敗は従来どおり `onPersistFailure` でアクターを停止します。型はログと `onPersistFailure` のコールバックで原因を区別するためのものです。

## 公開 API

`journal-base` に公開型が 3 つ増えます。`schema-check/public-api-baseline.txt` に記録しました。`ApiCompatibilitySpec` は公開宣言の消失だけを落とすため、この更新は必須ではありません。追加を記録して将来の削除を検出できるようにするためのものです。

- `JournalWriteQueueException`（sealed abstract class）
- `JournalWriteQueueFullException`（final class）
- `JournalWriteQueueClosedException`（final class）

## テスト

`WriteJournalDaoQueueSpec` を追加しました。Docker は起動しません。v2 の設定は base-v2 の provider クラスを解決するため、テストは journal-v2 に置いています。対象のキューは journal-base にあります。

既定の fail 方式の満杯を作るテストは、修正前に次のように失敗しました。

```
[info] - the default fail strategy reports a full queue as a typed failure with the overflow cause *** FAILED ***
[info]   no write was refused with JournalWriteQueueFullException
[info] - a detached write queue fails the write with a typed failure that keeps its cause *** FAILED ***
[info]   The reference was null
[info] Tests: succeeded 1, failed 2
```

修正後です。

```
[info] - a full write queue fails the write with a recoverable, typed failure
[info] - the default fail strategy reports a full queue as a typed failure with the overflow cause
[info] - a detached write queue fails the write with a typed failure that keeps its cause
[info] Tests: succeeded 3, failed 0
```

その他の確認:

- `sbt -batch "journal-base/test"`: 45 件成功
- `sbt -batch "schema-check/test"`: 20 件成功
- `sbt -batch lint`: 成功
- `sbt -batch "++2.12.20 journal-v2/Test/compile"` と `sbt -batch "++3.6.4 journal-v2/Test/compile"`: 成功
- `sbt -batch "journal-v1/testOnly *DynamoDBJournalV1SyncSpec"`: 16 件成功（前回の head）
- `sbt -batch "journal-v2/testOnly *DynamoDBJournalV2SyncSpec"`: 16 件成功（前回の head）

## 既知の制限

`backpressure` 方式で並行 offer が上限を超えると、`offer` は `IllegalStateException` で失敗します。これは満杯や閉鎖とは別の契約なので、この PR では扱いません。後続 issue 案として、`JournalWriteQueueBusyException` のような回復可能な型に写像することを提案します。

## 引き継ぎ

- 決定: `docs/HAND_OFF.md` はブランチ上の作業メモとしてだけ使い、成果物には残さない。ready 化またはマージ可能と報告する直前に削除する。この PR では `chore: remove work handoff notes` で削除した。
- 決定: `StreamDetachedException` は閉鎖に写像し、原因を cause に残す。
- 決定: `backpressure` の `IllegalStateException` はこの PR では扱わず、後続 issue 案にする。
- 残課題: 後続 issue 案（並行 offer 上限を `JournalWriteQueueBusyException` のような型にする）。
- 検証: `WriteJournalDaoQueueSpec` 3 件、`journal-base/test` 45 件、`schema-check/test` 20 件、`lint`、2.12.20 と 3.6.4 の `journal-v2/Test/compile` が成功。CI の 9 本の test ジョブは最終 head で確認する。

## 触っていない範囲

`JournalRowStore.scala` と、PR #886 が変更しているファイルには触れていません。`docs/HAND_OFF.md` は加藤さんの決定に従って成果物から削除しました。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
