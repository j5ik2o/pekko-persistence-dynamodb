# HAND_OFF: head モードの asyncUpdateEvent (#844)

## いまの状態

テストが赤になった。head モードで `DynamoDBJournal.InPlaceUpdateEvent` を送ると、今は
`JournalHeadAsyncUpdateEventUnsupportedException` で拒否される。そのため 6 件の新しいテストのうち 4 件が、この拒否の例外で失敗する。
拒否が正しい 2 件 (最新でない番号、head なし) は、頭出しの確認として拒否のまま通る。

- テスト: `journal/journal-v2/src/test/scala/.../journal/JournalHeadSpec.scala` の `in-place` を含む 7 件
- 赤の実行: `sbt -batch "journal-v2/testOnly *JournalHeadSpec -- -z in-place"` (Docker が必要)

## 次の一手

`JournalRowStore.update` を head 対応にする。head が有効なら、head を読み、対象イベントを差し替え、
ジャーナル行の更新と head の置き換えを `TransactWrite` 1 回で送る。`DynamoDBJournal.asyncUpdateEvent` の拒否分岐は外す。
deprecated ドライバと公開 API は変えない。

## 未決の判断

- 対象の番号が head の最新の書き込みに含まれない場合は、既存の `JournalHeadAsyncUpdateEventUnsupportedException` で拒否する想定。
  head ストリームは古いイベントを配信済みで、書き換えを伝えられないため。
- 同時更新で head が変わった場合の再試行回数と、使い切ったときの例外。新しい `JournalHeadException` の子クラスは公開 API の追加になるので作らない。

## PR

まだない。
