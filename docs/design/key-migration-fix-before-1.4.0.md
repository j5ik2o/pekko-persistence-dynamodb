# キー移行ツールの公開の前の直し：設計

対象は、まだ公開していない 1.4.0 に入る移行ツール（[#1025](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1025)、main の a5d28a3d / edfce815）。出来上がった時点の設計は `docs/design/key-migration.md`、仕様は `specs/key-migration/`。この文書は、その設計と仕様に付いた2本の設計の独立レビューのうち、公開の前に直すものの直し方を決める。製品のコードはまだ変えていない。この枝には、設計、仕様の変更、直す前に落ちるテストだけが入っている。

- レビュー C（cursor）：`~/.orca/bots/sub-marshal/audit/ppd-1025-design-review/REPORT.md`。指摘を C-F1… と呼ぶ。
- レビュー O（Opus）：`/Users/j5ik2o/tmp-ppd-368-design-review/report.md`。指摘を O-F1… と呼ぶ。
- 枝：`fix/key-migration-before-1.4.0`（`docs/key-migration-design-quint` の 5ff2d62d の上）。

## 0. 守る条件

元帥の条件（依頼の「元帥の条件」）をそのまま守る。

1. 性質から除外をなくす。除外が要るなら、その集合を明示し、その中でも守ることを別の性質で確かめる。性質を狭める必要が出たら、依頼元へ知らせる。今回、狭めた性質は無い（2.6）。
2. 見逃した組み合わせ（逆向き＋共有＋あきらめ＋流し直し）を必ず通る決まった筋書きと、同じ筋書きの Scala の回帰テストを足し、直す前に落ちることを確かめる（2.7、2.8）。
3. 文書の「1つだけが戻る」の説明を、直した後の挙動に合わせる（2.10）。
4. 保存済みのデータの読み方、公開 API（`JournalKeyMigration` と `SnapshotKeyMigration` の `main`）、既定は変えない。どの直しもこれに触れない（件ごとの「影響」）。

## 1. 一覧

| 件 | 直し方（要約） | Quint | Scala のテスト（直す前の結果） | 見込み |
| :-- | :-- | :-- | :-- | :-- |
| C-F1（O-F6） | 勝者が行き先に着かずに終わった（`Failed`、`Gone`）行き先は、その実行の残りの間ほかの候補に渡さない。負けた候補は保留のまま残りとして一覧に出し、次の実行で同じ勝者が行き先を得る | 性質を3つに分け、除外を無くした。決まった筋書き 2本 | 2本とも落ちた | 2 |
| C-F2 | 起動時に3つの警告（DAX を通らない、JournalHead が無効、書き手の停止を確かめられない）。文書とリリースノートに明記。#1026 の本体は範囲外 | 無し（状態遷移でない） | 落ちた | 1 |
| C-F3 | Scan の各ページと dry-run の読み取りも `--max-rate` の枠を1つずつ取る。移動の単位（行／秒）は変えない | 無し | 落ちた | 1 |
| C-F4 | 識別の属性が無いか型の違う項目を「別の項目」として扱う。走査は落ちずに「動かせない」で一覧、行き先にあれば dry-run も `--execute` も「ふさがれている」。`KeyVerification` は別件 | 無し（判定の純粋な関数） | 落ちた | 2 |
| O-F1 | `versionsKept` を足す | 足した。as built で違反なし | — | （済み） |
| O-F2 | `progress` を足す | 足した。as built で違反なし | — | （済み） |
| O-F3 | 書き手のモデルに、手順を守るアプリと、古い値を返すインデックスを足す | 設計だけ（7.3）。実装の段で書く | — | 3 |
| O-F4 | Streams と Kinesis の利用者への注意を文書に書く | 無し | — | 1（O-F5 と合わせて） |
| O-F5 | グローバルテーブルの扱いを文書に書く | 無し | — | （O-F4 に含む） |

合わせた見込みは 11（6節）。8 を超えるので、2つの PR に分ける案を6節に書いた。

## 2. C-F1：あきらめた勝者の行き先を、負けた候補が取る

### 2.1 何が起きるか

`--reverse` で、1.3.0 のキーを共有する2つの id（Quint の h と i、行き先は h130）が両方とも新しいキーにあるとき、`winnerOf` は今のキーが小さい h を選ぶ。止めずに流すと h が h130 に入り、i は `Blocked(h)` で一覧に出る。

h の試みがスロットリングなどで `maxAttempts` 回とも失敗すると、`settle` は `Failed("gave up")` を返す。コード（`KeyMigration.scala:800-813`）は h を `pending` から外すので、次のラウンドでは i だけが h130 を求め、i が `winnerOf` になって h130 に入る。流し直すと h が `Blocked(i)` になる。最終の表は、止めずに流した場合と違う。

- レビュー C は、実行1の最後のラウンドの直前で止め、同じ条件で流し直す筋書き（12ステップ）で、`converges` の違反を示した。
- レビュー O は、実行1が「あきらめて終わる」筋書きを示した。この場合は、次の実行が新しい起点から比べられるので、`converges` は成り立ってしまう。収束の性質の「あきらめが出た実行は除く」が、反例を隠していた。

### 2.2 直し方の候補

| 案 | 中身 | 収束 | 止めずに流した場合の挙動 | 採否 |
| :-- | :-- | :-- | :-- | :-- |
| A（C の案を広げたもの） | 勝者の結果が「行き先にこの行が無い」（`Failed` のすべての理由、`Gone`）なら、その行き先をその実行の残りの間ほかの候補に渡さない | 戻る | 400 KB の拒否のときだけ変わる（下） | **採る** |
| A′（C の案のとおり） | A と同じだが、予約するのは `settle` のあきらめ（`gave up`、`outcome unknown`）と `Gone` だけ | 戻る | 変わらない | 採らない。理由の文字列で分けることになり、400 KB の拒否では dry-run の示す勝者と結果が食い違ったまま |
| B | あきらめが1つ出たら、その実行全体を止める | 戻る | 変わらない | 採らない。関係の無い行まで止まり、大きな表で1行のスロットリングが全体を止める |
| C | あきらめた勝者を `pending` に戻し、後のラウンドで再び試す | 戻る | 変わらない | 採らない。試みの上限の意味が変わり、スロットリングが続くと終わらない |
| D | 勝者以外の候補を、そもそも試さない | 戻る | 変わる（負けた候補の理由が `Blocked(h)` でなく「待つ」になり、選択の外の判定と書き手の兆候 `unseenByScan` を通らない） | 採らない。ふつうの経路の報告を変える |
| E | 文書だけ直す（O-F6 の1つ目の案） | 破れたまま | 変わらない | 採らない。元帥の決定（公開の前に直す） |

### 2.3 採る案（A）のコードの変更

`KeyMigration.Session.execute`（`KeyMigration.scala:772-836`）だけを変える。

1. `execute` の中に、予約した行き先と、その勝者の表を持つ：`val reserved = mutable.LinkedHashMap.empty[StoredKey, Candidate]`。
2. `ready` を作るとき、予約した行き先の組を外す：`pending.values.groupBy(_.to).filterNot { case (to, _) => reserved.contains(to) }.values.map(winnerOf)...`。
3. 結果を処理するとき（`outcomes.foreach`）、`Failed(_)` と `Gone` なら `reserved.update(c.to, c)` とする。`Failed` は今と同じく `remaining` に入る。`Gone` は今と同じく書き手の兆候を出す。
4. ループの後、保留に残った候補の理由に3つ目を足す。行き先が予約されていれば、`s"the target key is kept for ${winner.persistenceId}@${winner.sequenceNr}, which gets it and did not move in this run; run again"`。それ以外は今の2つ（循環、待ち）。
5. コメントの直し：`:667` と `:776` の「only the one that the dry-run names goes first; the others are tried after it」を、「その行が行き先に着かずに終われば、ほかの行はその実行では試さない」に直す。

dry-run（`dryRun`）は変えない。もとから `winnerOf` だけが行き先を得るとして分類している。

### 2.4 変えないこと

- 止めずに流した実行の結果。勝者が `Moved` か `Merged` なら行き先にはその行があり、負けた候補は今と同じく試されて `Blocked` になる。`Blocked` と `Conflict` の勝者は予約しない。行き先にほかの行があるので、負けた候補も同じ行にふさがれる。
- 例外が1つある。勝者が 400 KB の超過で拒否された（`Failed("DynamoDB rejected the request…")`）とき、今のコードは負けた候補を行き先に入れる。直した後は入れない。行き先は勝者のために空いたまま残り、負けた候補は「kept for」の理由で一覧に出る。dry-run はもとからこの勝者を示しているので、dry-run と `--execute` が合う。拒否は決まった結果なので、流し直しても同じで、収束も冪等も保たれる。利用者が勝者の行を手で直せば、次の実行で勝者が入る。
- 終了コード。負けた候補は `remaining` に入るので、今と同じく 1。
- `MigrationReport` の形、`Outcome` の種類、公開 API、既定。

### 2.5 守る不変条件

書き手が止まっている前提で、5ff2d62d の 14 個に加えて次を守る。

- 行がよそへ行かない（`withinReference`、全状態、除外なし）。同じ条件の実行が始まってから、各論理的な行は、その起点のときのままか、起点から止めずに流した結果のままか、のどちらか。止めずに流した結果に無いキーへ行が入ることは無い。
- あきらめた実行は手前で止まる（`finishedWithinReference`、終わった実行のすべて、除外なし）。終わった実行で、各論理的な行は、止めずに流した結果のままか、起点のままで一覧（残り、動かせない行）に出ているか、のどちらか。
- 収束（`converges`）。同じ条件の実行が、何度止まっても、応答を失っても、一時的に失敗しても、**あきらめて終わっても**、あきらめずに終わった実行の表は、最初の実行の起点から止めずに流した結果と同じ。

### 2.6 Quint の性質の変更と、除外の扱い

| 性質 | 5ff2d62d | この枝 |
| :-- | :-- | :-- |
| `converges` | あきらめなしで終わった実行だけ。あきらめて終わった実行の後は、比べる起点（`epochStart`）が新しくなる | 対象は同じ（あきらめなしで終わった実行）だが、起点は、あきらめて終わった実行の後も続く。あきらめた実行の後のクリーンな実行は、最初の実行の起点と比べる |
| `withinReference` | 無し | 新しく足した。全状態、除外なし |
| `finishedWithinReference` | 無し | 新しく足した。終わった実行すべて、除外なし |

- **なくした除外**：「あきらめが出た実行は収束から除き、その後は比べ直す」。あきらめた実行で起点を切り直すことをやめた。
- **残す集合**：G＝あきらめて終わった実行（`tool.phase == Finished and tool.gaveUp`）。G の表そのものは、止めずに流した結果とは違う（勝者が動いていない）ので、`converges` の等式は G には当てはまらない。G の中で守るものは次の2つで、どちらも除外なしの性質で確かめる。
  - 行は消えない・2つにならない・よそへ行かない：`noLoss`、`noDuplicate`、`exactlyOnce`、`withinReference`。
  - 残りは未移動のまま止まり、一覧に出る：`finishedWithinReference`。
  - 流し直したら決まった1つが戻る：G の後のクリーンな実行に、起点を切り直さない `converges` を当てる。
- `roundTrip` の「あきらめなしで終わった `--reverse`」も同じ集合 G を除いている。G の `--reverse` には `finishedWithinReference` が当たり、その後のクリーンな `--reverse` には `roundTrip` が当たる（`roundTrip` は初めの表と比べるので、起点の切り直しの問題は無い）。
- 狭めた性質は無い。`converges` は、比べる対象を増やした（起点を切り直さない）ので、5ff2d62d より強い。

### 2.7 決まった筋書き（Quint）

`specs/key-migration/keyMigrationGaveUp_test.qnt`。表 `{hP: h, iP: i}` から `--reverse` を流し、h の試みを2回とも書かずに失敗させ（`attemptTransient`）、`settle` であきらめさせる。そのうえで次の2つを流す。

- 筋書き1（O-F6 の形）：実行1を最後まで流して「あきらめて終わる」。`withinReference`、`finishedWithinReference`、表が起点のままであることを確かめる。同じ条件で流し直し、`converges` と、表が `{h130: h, iP: i}` であることを確かめる。
- 筋書き2（C の 4.2 の形）：実行1を2歩進めて止め、同じ条件で流し直す。途中で `withinReference`、最後に `converges` と表を確かめる。

それぞれを、今のコードの規則（`fixed = false`）と直した後の規則（`fixed = true`）の両方で流す。仕様は、ツールの規則を状態の変数 `fixed` で切り替える（`init` は今のコード、`initFixed` は直した後）。

結果（Quint 0.32.0、`quint test keyMigrationGaveUp_test.qnt --main keyMigrationGaveUp_test`）：

| テスト | 結果 |
| :-- | :-- |
| `finishThenRerunAsBuiltTest` | **落ちた**。`assert(withinReference)`（実行1の後、i が h130 にいる） |
| `stopThenRerunAsBuiltTest` | **落ちた**。`assert(withinReference)`（2歩目で i が h130 に入った直後） |
| `finishThenRerunFixedTest` | 通った（10,000 回） |
| `stopThenRerunFixedTest` | 通った（10,000 回） |
| `cleanRunUnchangedTest` | 通った。止めずに流した結果は、直す前と後で同じ |

ランダムな run の重みづけとして、初めの表を `{hP: h, iP: i, a130: a, b130: b}` に固定する `initSharedPair`（今のコード）と `initSharedPairFixed`（直した後）を足した。結果は7節。今のコードでは、`withinReference` が 215 トレース目前後（2.5秒）、`converges` と `finishedWithinReference` も数秒で違反を見つける。5ff2d62d の初めの表の選び方（何でもあり）では、`wGaveUpShared`（共有の行き先であきらめ）に届くのは 1,000 トレースのうち 4 で、ランダムに頼ると見逃しうる。決まった筋書きは、この経路を必ず通す。

### 2.8 Scala の回帰テスト

| テスト | 筋書き | 今のコード（main）での結果 |
| :-- | :-- | :-- |
| `KeyMigrationSpec`「the fixes before 1.4.0 / keep a shared target key for its winner when the tool gives up on the winner…（C-F1）」 | A と B が key 9 を求める（A が小さい）。A の `TransactWriteItems` を書かずに失敗させ、`maxAttempts = 2` で実行1。続けて故障なしで実行2。故障なしで1回流した別の表と比べる | **落ちた**：`Some(("B", 1)) was not equal to None`（実行1で B が key 9 に入った） |
| `JournalKeyMigrationSpec`「the fixes before 1.4.0 / give the shared key of 1.3.0 to the same id after a --reverse run that gave up on it…（C-F1）」 | 本物のリゾルバで、1.3.0 のキーを共有する2つの id を新しいキーに書き、`--reverse` で同じことをする | **落ちた**：負けた id の行が 1.3.0 のキー（`X-0000000000000000001`）に入っていた |

### 2.9 壊しうる入力

- 共有の行き先が3つ以上の候補に求められる組（Quint の宇宙には2つしか無い）。予約は行き先ごとなので、3つ目以降も同じく待つ。
- 勝者が 400 KB の拒否（2.4 の例外）。
- `--persistence-id` で負けた候補だけを選んだ実行。勝者は候補に入らないので予約は起きず、行き先が空なら負けた候補が入る。dry-run も選んだ候補の中で `winnerOf` を決めるので、dry-run と `--execute` は合う。選び方で勝者が変わる点は今のコードと同じで、この直しでは変えない（C-F9 の7番、8節の別件）。
- 応答が失われた勝者の取引が、`settle` の後に反映される場合（O-F10）。行き先に勝者が入るので、予約しても負けた候補は次の実行で `Blocked` になる。
- 書き手がいる場合の `Gone`。行き先の状態が分からないので予約し、負けた候補は待つ。

### 2.10 文書の直し（実装の段で入れる文面）

`docs/src/sphinx/customize.md` の「When two rows need the same new key」の文を、次に替える。

> When two rows need the same new key, the one with the smaller stored key gets it, in the dry-run and in `--execute` alike, and the other is listed. When that row does not reach the key in a run (DynamoDB rejects it, or the tool gives up after its attempts), the key stays free for it: the other row is not moved in that run and is listed, and the next run gives the key to the same row.

`docs/design/key-migration.md` の3節「往復から除く組」の「1.3.0 でキーを共有する組の両方に行がある場合」の「その中でも守るもの」を「1〜3、`winnerOf` の1つだけが戻る（その実行で勝者が行き先に着かなければ、ほかの行も動かない）、ほかは一覧（8）、`--verify --reverse` は一致しない（13）」に、「収束から除くもの」の節を 2.6 の扱いに替える。8.3 の対応表に `reserved` を足す。

## 3. C-F2：書き手の停止、DAX、JournalHead の警告

### 3.1 直し方

起動時（dry-run、`--execute`、`--verify` の始め、今の `localeLine` の次）に、当てはまるものだけを1行ずつ出す。文面は、テストが確かめる語句を太字にした。

| 条件 | 出す道具 | 文面 |
| :-- | :-- | :-- |
| 元の設定の `client-version` が `v1-dax` か `v2-dax` | 両方 | `warning: client-version is v2-dax; the tool reads and writes **without DAX**, so drop the DAX item and query caches before you start the application` |
| `j5ik2o.dynamo-db-journal.journal-head.enabled = false`（既定） | `JournalKeyMigration` だけ | `warning: the **JournalHead is disabled**; an actor that recovers while the index lags can overwrite a moved event (see #1026), so keep the application stopped until --verify matches twice` |
| `--execute` | 両方 | `warning: the tool **cannot check that the writers are stopped**; --writers-stopped states it` |

- `withClient` は、設定を書き換える前の `client-version` を読み、`f` に渡す（今は書き換えた後の設定しか `f` に渡らない）。JournalHead の有無は `JournalPluginConfig` から読める。
- 文書：`release-notes.md` の Experimental の欄に「JournalHead が無効のとき、手順どおりに止めて `--verify` を2回通しても、復旧の上書きの危険は0にならない（#1026）」を1文足す。`customize.md` の「Stop the application」の囲みに、ツールが起動時に出す警告を書く。
- 範囲の外：[#1026](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1026)（書き込み側の守り）。対話の確認（プロンプト）は足さない。スクリプトでの運用を壊すため。

### 3.2 変えないこと、影響

警告を足すだけで、動き、終了コード、`--writers-stopped` の要否は変えない。出力の行が増えるので、出力を読むスクリプトには影響しうるが、ツールは未公開。

### 3.3 Quint、テスト

状態遷移ではないので Quint は変えない。テストは `JournalKeyMigrationSpec`「the fixes before 1.4.0 / warn at the start…（C-F2）」：`v1-dax` と `v2-dax` で両方の道具と `--verify` が「without DAX」を出すこと、`plain`（JournalHead 無効）で「JournalHead is disabled」を出し「without DAX」を出さないこと、`--execute` で「cannot check that the writers are stopped」を出すこと、JournalHead を有効にした設定では出さないこと。main では **落ちた**（`client-version v1-dax: false was not equal to true`）。

## 4. C-F3：Scan と dry-run の読み取りが `--max-rate` を無視する

### 4.1 直し方の候補

| 案 | 中身 | 採否 |
| :-- | :-- | :-- |
| a | Scan の各ページの前、dry-run の写しの比べ（`read(c.from)`、`read(c.to)`、`KeyMigration.scala:726-732`）の各読み取りの前に、`limiter.foreach(_.acquire())` を足す。移動（`moveOne`）は今と同じく1回の試みで1枠 | **採る** |
| b | すべての要求（Scan、GetItem、TransactWriteItems）の前で1枠取り、`--max-rate` を「要求／秒」にする（`--verify` と同じ単位） | 採らない（この仕事では）。`--execute` の `--max-rate` の意味（行／秒）が変わり、同じ値で移動が遅くなる。未公開とはいえ、文書にある利用者向けの意味を変えるので、この直しの範囲に入れない。別件にして決める |

### 4.2 文書

`customize.md` の `--max-rate` の行を「Move at most `n` rows a second. In a dry-run, check at most `n` target keys a second. Each page of the scan, and each read that compares two copies, also takes one of the `n` a second; set `--scan-page-size` to bound the items of a page. With `--verify`, send at most `n` requests a second.」に替える。`ResolverKeyPlan.scala:101` の使い方の文も合わせる。

### 4.3 テストと影響

`KeyMigrationSpec`「the fixes before 1.4.0 / pace the pages of the scan and every read of a dry-run with --max-rate（C-F3）」。10行、`--scan-page-size 2`、`--max-rate 5`（200 ミリ秒ごと）で dry-run を流し、Scan と GetItem の送った時刻の間隔がすべて 180 ミリ秒以上であること。main では **落ちた**（間隔 10, 8, 7, 7, 8, 204, 10, 5, 13, 5 ミリ秒。204 は今も枠を取る行き先の確認）。

影響：`--max-rate` を付けた実行は、走査が遅くなる。付けない実行は変わらない（既定は無制限）。Quint は変えない（`--max-rate` は仕様の外、8.4 #7）。

## 5. C-F4：識別の属性が無い項目

### 5.1 直し方

識別の属性（`persistence-id` の文字列、`sequence-nr` の数）が無いか型の違う項目を、「別の項目」と呼ぶ。プラグインは書かないが、手で書いた項目や別のアプリの項目がありうる。

1. 走査（`scanTable`、`KeyMigration.scala:588-590`）：`.s()`／`.n()` を直接呼ばずに `identity` と同じ読み方をする。別の項目は、`--persistence-id` が無いときだけ `UnmovableRow(persistenceId = "", sequenceNr = -1, stored, reason = "the item has no persistence-id string or sequence-nr number; the tool does not move it")` として一覧に入れる。`IdStats` には数えない。`--persistence-id` があるときは数えるだけ。
2. 行き先の判定（`occupantOf`、`:894-898`）：項目があって識別が読めなければ、`Free` でなく新しい `Occupant` の `OtherItem(key)` を返す。dry-run はこれを「ふさがれている」に分ける（`BlockedMove(candidate, occupantPersistenceId = "", occupantSequenceNr = -1)`、表示は「an item that is not a row」）。
3. 移動（`moveOne`、`:1071-1072`）：行き先に識別の読めない項目があれば、新しい `Outcome` の `BlockedByItem(key)` を返す。今は何もせず次の試みへ進み、10回の後に「gave up」になる。`execute` はこれを残りとして一覧に出す（理由「the target key holds an item that is not a row of this table; move or remove it by hand」）。予約（2.3）はしない。

### 5.2 変えないこと、範囲の外

- 正規の行の扱いは変えない。`MigrationReport` の型は変えない（`UnmovableRow` と `BlockedMove` に入れる）。
- `KeyVerification`（`:117-119, 152`）の同じ問題は別件（8節）。C は「最低でも `occupantOf` と `moveOne`」とした。走査も入れたのは、今は走査が先に NPE で止まり、`occupantOf` に届く前に終わるため（下のテスト）。

### 5.3 Quint

入れない。項目の分類は純粋な関数で、状態遷移の規則（ラウンド、試み、予約）は変わらない。`BlockedByItem` は `Blocked` と同じく残りになり、予約もしないので、Quint の `Blocked` と同じ扱いになる。

### 5.4 テスト

`KeyMigrationSpec`「the fixes before 1.4.0 / block a move whose target key holds an item without a persistence id or sequence number…（C-F4）」。key 9 にキーだけの項目を置き、A を key 9 へ移す計画で dry-run と `--execute` を流す。dry-run が A をふさがれたとし、key 9 を動かせない項目として一覧に出すこと、`--execute` が A を動かさず、取引を1回だけ送り、理由に「gave up」を含まないこと、両方の項目がそのまま残ること。main では **落ちた**（走査の `NullPointerException`）。

### 5.5 壊しうる入力

識別の属性が無い、型が違う（`persistence-id` が数、`sequence-nr` が文字列）、`sequence-nr` が `Long` に収まらない数。3つとも「別の項目」にする。

## 6. 見込み

基準：[#1032](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1032)（公開版の JAR の行での試験）を 5 とする。

| 件 | 見込み | 大きさを決めているもの |
| :-- | :-- | :-- |
| C-F1 | 2 | コードは `execute` の中だけ。テストは書いた。文書の文面も決めた |
| C-F2 | 1 | `withClient` に元の設定を渡す変更と、3行の警告 |
| C-F3 | 1 | 3か所の `acquire` と文書 |
| C-F4 | 2 | 新しい `Occupant` と `Outcome`、表示、走査の読み方 |
| O-F1・O-F2 | 0（仕様は済み） | 結果の文書に数を書くだけ。C-F1 の仕様の仕上げ（`fixed` を外す）に含む |
| O-F3 | 3 | 書き手のモデルに2つの版、run と結果。不確かさが大きい（7.3 の予想が外れれば、文書の言い方を直す） |
| O-F4・O-F5 | 1 | 文書だけ |
| 仕様と設計の仕上げ | 1 | `fixed` を外して直した後の規則だけにする、結果の文書、`key-migration.md` の直し |
| 合計 | 11 | |

8 を超えるので、次の2つに分ける。

1. **PR-A（コード、5）**：C-F1、C-F2、C-F3、C-F4 と、それぞれの文書の直し。この枝の失敗するテストが通ることで確かめる。Quint は `fixed` を外し、直した後の規則だけにする。
2. **PR-B（仕様と文書、5）**：O-F3 の書き手のモデル、O-F4・O-F5 の文書、結果の文書と `key-migration.md` の仕上げ。PR-A の後に出す。

## 7. 仕様（O-F1〜O-F5）

### 7.1 O-F1：`versionsKept`

O の提案のとおり足し、`safety` に入れた。今のコードで違反なし（7.6）。コードの `differences` は正しいので、コードは変えない。設計 3節の4番に「初めの写しの中身の違いは、まとめで消えない」を足す（実装の段）。C-F7 の「`deleted` が真から偽に戻る」「`created` の大きい方を選ぶ規則」は `versionsKept` では捕えないので、別件（8節）。

### 7.2 O-F2：`progress`

O の提案を、向きと設定の4通りに広げた。前提 `movableStart` は、全 id を選び、止めずに動ける行だけの起点（どの行も `Unmovable` でない、詰まる集合 STUCK の行が無い、写しの中身が同じ、`--reverse` では共有の組の両方が無い）。あきらめた実行は G で、そこでは `finishedWithinReference` が当たる。前提の外では `finishedReported` が当たる。今のコードで違反なし（7.6）。`wProgress` は 1,000 トレースのうち 487 で前提が成り立った。

### 7.3 O-F3：文書の手順を守れば反例が消えること（設計だけ）

`keyMigrationWriters.qnt` に次の2つを足す（実装の段）。

1. 手順を守るアプリ `appStepGuarded`：アプリが動いてよいのは、両方の表で「最後の `--verify` が一致し、その後にツールが走っておらず、表が変わっていない」とき。これで `noLossW`、`noDuplicateW`、`deletedStayGone`、`verifySoundW` を run する。予想：インデックスが単調なモデルでは、4つとも成り立つ。
2. 古い値を返すインデックス `staleRead`：アプリの読み取りが、インデックスの今の値か、最後に伝わる前の値のどちらかを返す。予想：`--verify` が1回一致した後でも、JournalHead が無効なら A1 の上書きが起きる（文書の「2回の一致は確率を下げるだけ」と合う）。JournalHead が有効なら起きない（`recoverAndWrite` に head の番号を足す）。

予想が外れたら（1 で破れる、2 で破れない）、文書の手順か言い方を直す必要があるので、依頼元へ知らせる。

### 7.4 O-F4：Streams と Kinesis の利用者

`customize.md` の「Stop the application」の囲みに次を足す。設計 5節と 6節にも1行ずつ足す。

> A move reaches DynamoDB Streams and Kinesis Data Streams as an insert under the new key and a remove under the old key, two changes to two items, which a consumer can receive in either order. Stop the consumers that treat a remove as the deletion of an event or a snapshot, or let them skip a remove whose `persistenceId` and sequence number the table still holds.

順序の保証（項目ごとにしか保証しない）は、O が一次情報で確かめていないと書いている。実装の段で AWS の文書を読み、文面を合わせる。

### 7.5 O-F5：グローバルテーブル

`customize.md` の移行の節に次を足す。起動時の `DescribeTable` の `Replicas` の確認は、`--execute` に `dynamodb:DescribeTable` の権限を新しく求めることになるので、この直しでは入れず別件にする（8節）。

> With a global table, run both tools against one region, and run `--verify` of both tools against the endpoint of every region. Start the application in no region before both match in every region: a transaction is atomic in the region that runs it, and another region can show it half applied while it replicates.

これも O が一次情報で確かめていない点（取引の途中が見えうること）を含むので、実装の段で AWS の文書を読み、文面を合わせる。

### 7.6 検査の結果（この枝）

Quint 0.32.0、Apple Silicon の手元の機械。すべて `specs/key-migration/` で、`quint run keyMigration.qnt …`。

`typecheck` は5つのファイルとも通った。`quint test` は、`keyMigration_test.qnt` が12件、`keyMigrationWriters_test.qnt` が5件とも通った（変更の前と同じ）。`keyMigrationGaveUp_test.qnt` は 2.7 のとおり。

| 何を | 条件 | 結果 | 所要 |
| :-- | :-- | :-- | :-- |
| 今のコード、重みづけの表 | `--init initSharedPair --max-samples 2000 --max-steps 40 --invariant withinReference --seed 0x7` | **違反**（約 215 トレース目）。最後の状態で、h が `Failed("gave up")`、i が `Moved` で h130 にいる | 3秒 |
| 今のコード、重みづけの表 | 同じ表、3,000 × 40、`--invariant converges` | **違反** | 2秒 |
| 今のコード、重みづけの表 | 同じ表、3,000 × 40、`--invariant finishedWithinReference` | **違反** | 2秒 |
| 今のコード、重みづけの表 | 同じ表、3,000 × 40、`--invariant cheapSafety`（参照の実行を計算しない13個） | 違反なし。C-F1 は、行が消えも増えもしないので、これらでは捕えられない | 24秒 |
| 今のコード、何でもありの表 | `--max-samples 1000 --max-steps 120 --invariant safety --seed 0x7`（新しい4つを含む17個） | 違反なし。`wGaveUpShared` は 1,000 のうち 4。ランダムな表に頼ると見逃す | 118秒 |
| 今のコード、何でもありの表 | 1,000 × 120、`--invariant versionsKept` | 違反なし | 19秒 |
| 今のコード、何でもありの表 | 1,000 × 120、`--invariant progress` | 違反なし | 5秒 |
| 直した後、重みづけの表 | `--init initSharedPairFixed`、3,000 × 40、`withinReference`、`finishedWithinReference`、`converges` をそれぞれ | 3つとも違反なし | 18秒、6秒、4秒 |
| 直した後、重みづけの表 | 3,000 × 40、`--invariant safety`、witness つき | 違反なし | 41秒 |
| 直した後、何でもありの表 | `--init initFixed --max-samples 1000 --max-steps 120 --invariant safety --seed 0x7`、witness つき | 違反なし。witness：`wGaveUpShared` 4、`wCleanAfterGaveUp` 32、`wGaveUp` 13、`wFinishedAfterStop` 33、`wProgress` 487、`wMerged` 607、`wConflict` 311、`wBlocked` 852（1,000 のうち） | 156秒 |
| 直した後、何でもありの表 | `--init initFixed`、500 × 60、`--invariant idempotent` | 違反なし | 23秒 |
| 突然変異 m2（移動を書かずに `Failed`） | 仕様の写しで、300 × 120、`--invariant progress` | 違反（2トレース目）。`progress` が「何も動かさない」実装を捕える | 1秒未満 |
| 突然変異 m1（中身が違っても常にまとめる） | 仕様の写しで、1,000 × 120、`--invariant versionsKept` | 違反（最初のトレース） | 1秒未満 |

`wCleanAfterGaveUp` は「起点が実行の開始と違う、あきらめなしで終わった実行」で、止まった後の実行も数えるので、あきらめの後の実行だけの数ではない。あきらめの後のクリーンな実行は、2.7 の決まった筋書きで必ず通している。

`quint verify` は流していない。5ff2d62d と同じく `fullRun` の範囲が定数でなく、Apalache が受け付けない（C-F7、別件）。

## 8. 「後でよい」「意見」の処理の案

この仕事では直さない。件ごとに、別件（追跡の issue を立てる案）か採らないかを書く。issue は依頼元が立てる前提で、題の案を書いた。

| 件 | 処理 | issue の題の案、または採らない理由 |
| :-- | :-- | :-- |
| C-F2 の本体 | 別件（既存） | [#1026](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1026)（JournalHead 無効のときの書き込み側の守り） |
| C-F4 の残り | 別件 | 「移行ツール：`--verify` が識別の属性の無い項目で落ちる」 |
| C-F5 | 別件 | 「移行ツール：権限・期限切れ・表が無いエラーを再試行せずに止める」。大きな移行の前に入れたい。C-F1 の直しの後は、恒久のエラーで勝者があきらめても行き先は予約されるので、行は動かず安全側 |
| C-F6 | 別件 | 「移行ツール：`--verify` の `describeTable` の時間切れ、`--segments` の逐次実行、要約の言い方（SHA-256 の 128 ビット）」 |
| C-F7 | 別件 | 「Quint：`contentsKept` を強める（`deleted` の戻り、`created` の規則）、`attemptOk` の上限、`fullRun` の範囲を定数に」。`attemptOk` の上限の抜けは、モデルがコードより多くの試みを許す側の差で、安全の性質の反例を隠さない。今回の筋書きはこの差に頼っていない |
| C-F8 | 別件 | 「Quint：停止性、dry-run と `--execute` の一致、二重起動、snapshot の書き手、`verifySound` の `--reverse` と F130、選択つきの冪等」。O-F10 と合わせる |
| C-F9 | 別件 | 「移行ツール：`SequenceRanges` の `Long.MaxValue` のあふれ、`--reverse` とまとめの 400 KB、空の表・`seq = 0`・区切りの境界、設計 3節の表に不明キー `u` を足す」。PBT は O-F12 と合わせる |
| C-F10 | 採らない | `System.nanoTime()` の値が一周するのは起点から約292年後で、1回の実行の中で起きる見込みは無い。差で比べる形への書き換えは、ほかの直しのついでに入れてよい |
| O-F7 | 別件 | 「移行ツール：1.3.0 がキーを作れない #831 の行（区切りの無い長い id）の `--reverse` の理由と、Quint の宇宙」 |
| O-F8 | 別件 | 「移行ツール：取引の取り消しの理由の `ValidationError` を `ValidationException` と同じ Failed にする」。[#1032](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1032) の実機の試験に 400 KB の場合を入れる |
| O-F9 | 別件 | 「文書：`--verify` を `--execute` と同時に流さない」。C-F8 の二重起動と合わせる |
| O-F10 | 別件 | C-F8 と同じ issue |
| O-F11 | 別件 | 「Quint：`verifySound` を独立の確かめにする、網羅（TLC・Apalache）の縮めたモデル」。witness の数の言いすぎは、この枝の結果の文書で直す（7.6 に実数を書いた） |
| O-F12 | 別件 | 「移行ツール：ScalaCheck の性質（往復のキー、どの形でもないキー、ロケール `ar-EG`）」。C-F9 の PBT と合わせる |
| O-F13（意見） | 採らない | ツールはプラグインと同じ関数でキーを作るので、空の区切りでも扱いはプラグインと一貫する。空の区切りを設定として許すかはプラグインの設定の検査の問題で、移行ツールの直しの範囲ではない |
| O-F14（意見） | 別件 | 「文書：DAX のキャッシュの捨て方（ノードの再起動、負のキャッシュ）を一次情報で確かめて書き直す」。C-F2 の警告の文面は「drop the DAX item and query caches」にとどめ、操作の名前を出さない |
| O-F15（意見） | 別件 | 「文書：自動のロールバックと、同じ表を使う別のサービスを、手順 1 の『every node』に含める。順方向の後に 1.3.0 で起動するには先に `--reverse` が要ることを手順の最後にも書く」 |
| O-F5 の実行時の確認 | 別件 | 「移行ツール：グローバルテーブル（`Replicas`）を検出して警告する」。`DescribeTable` の権限が要る |
| C-F3 の案 b | 別件 | 「移行ツール：`--max-rate` を全要求の単位にそろえるか決める」 |

## 9. この枝の中身

| ファイル | 中身 |
| :-- | :-- |
| `docs/design/key-migration-fix-before-1.4.0.md` | この設計 |
| `specs/key-migration/keyMigration.qnt` | ツールの規則を `fixed` で切り替える（`init` は今のコード、`initFixed` は直した後）。`reserved`、新しい性質（`withinReference`、`finishedWithinReference`、`versionsKept`、`progress`）、`converges` の起点の変更、`initSharedPair`、witness |
| `specs/key-migration/keyMigrationGaveUp_test.qnt` | 決まった筋書き。`…AsBuiltTest` は直すまで落ちる |
| `specs/key-migration/keyMigration_test.qnt`、`keyMigrationWriters.qnt` | 新しい引数と変数に合わせただけ。結果は変わらない（12件、5件とも通る） |
| `KeyMigrationSpec.scala`、`JournalKeyMigrationSpec.scala` | 「the fixes before 1.4.0」の5件。main では5件とも落ちる |

実装の段で、PR-A は `fixed` を外して直した後の規則だけにし、`…AsBuiltTest` を消す（直した後の規則での同じ筋書きは `…FixedTest` として残す）。
