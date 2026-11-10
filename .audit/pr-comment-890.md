別系統レビューの 7 件への対応です。blocker と should 2 を直し、should 3 は後続 issue 案、should 5 は加藤さんの判断待ちにしました。

| 指摘 | 分類 | 対応 |
|:--|:--|:--|
| 1. blocker: 既定 fail の満杯が型にならない | 直す | `QueueOfferResult.Failure(BufferOverflowException)` を `JournalWriteQueueFullException` に写像し、原因を残しました。既定 fail で満杯を作るテストを追加しました。 |
| 2. should: `StreamDetachedException` の原因と Javadoc | 直す | `JournalWriteQueueClosedException` が `StreamDetachedException` を cause に持ちます。Javadoc を「disposed または異常終了」に直しました。 |
| 3. should: backpressure の `IllegalStateException` | この PR では直さない（後続 issue 案） | 既定の本番方式は fail です。並行 offer 上限は満杯・閉鎖とは別の契約で、公開型を増やすと #890 の範囲を超えます。後続案は下に書きます。 |
| 4. should: 「回復可能」とアクターの挙動 | 直す（注記） | PR 本文と HAND_OFF に、アクターは従来どおり `onPersistFailure` で停止する旨を書きました。 |
| 5. should: HAND_OFF の衝突 | 聞く | #886 と #892 の両方と add/add で衝突します。削除・改名はせず、PR に含めるかは加藤さんの判断待ちにしました。 |
| 6. nit: ベースラインの説明 | 直す（表現） | `ApiCompatibilitySpec` は公開宣言の消失だけを落とすので更新は必須ではない、と PR 本文を直しました。ベースラインの記録自体は残しました。 |
| 7. nit: テストの置き場所 | 理由をつけて直さない | v2 の設定は base-v2 の provider クラスを解決し、journal-base のテスト classpath には無いため journal-v2 に置いています。理由をテストの Scaladoc に書きました。 |

### 後続 issue 案（should 3）

タイトル案は「書き込みキューの並行 offer 上限を回復可能な型で表す」です。`backpressure` 方式で `offer` が上限を超えると `IllegalStateException` で失敗するため、`JournalWriteQueueBusyException` のような型に写像し、再試行できる失敗として扱います。テストは `WriteJournalDaoQueueSpec` に、backpressure で 3 件以上を同時に offer するケースを追加します。

### 検証

新しい head は `c3fbc44d` です。テストを先に積み（`9838c695`）、修正をその上に置き（`0cac0960`）、HAND_OFF の追記を足しました（`c3fbc44d`）。`journal-v2/testOnly *WriteJournalDaoQueueSpec` は 3 件成功、`journal-base/test` は 45 件成功、`schema-check/test` は 20 件成功、`lint` は成功、2.12.20 と 3.6.4 の `journal-v2/Test/compile` も成功しています。CI はこのコメントの後に確認します。
