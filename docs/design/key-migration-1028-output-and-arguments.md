# キー移行ツールの出力と引数の確かめの直し（#1028）：設計

対象は issue [#1028](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1028)。1.4.0 で公開した実験的な移行ツール `JournalKeyMigration` と `SnapshotKeyMigration`、および base-v2 の `migration` パッケージを扱う。この文書は段1の設計で、製品のコードはまだ変えていない。

- 基準のコミット：main の 297826be。
- 公開の前の直し [#1033](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1033) と [#1036](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1036) は、このコミットに入っている。
- 再現には一時的なテスト `Issue1028ReproSpec` を使った。DynamoDB Local で動かし、このブランチには入れていない。結果は件ごとに書く。

## 0. 守る条件

1. 移動の判定は変えない。`ResolverKeyPlan.placement` と、`--execute` の `moveOne` と各回の進め方が対象になる。保存済みのデータの読み方と書き方も変えない。
2. `--verify` の判定（`VerifyReport.verified`）は変えない。
3. 公開 API は変えない。コマンドの引数の名前、終了コードの意味（成功で 0、`incomplete` または `--verify` の不一致で 1、引数の誤りは例外で終わる）がこれに当たる。`--max-rate` は、これまで通っていた値の一部を引数の誤りとして断る（2.1）。引数の名前と終了コードの意味は変わらない。
4. dry-run と `--execute` は一致させる。他の書き手がおらず、要求が失敗しない（`Failed` も `Gone` も起きない）表で、dry-run が動かすと数える行の集合は `--execute` が動かす行の集合と同じになり、統合すると数える行の集合は `--execute` が統合する行の集合と同じになる。残りの行は、`--execute` でも残りになる。今の main は 2.2 と 2.3 の場合にこれを破っている。直したあとは、この性質を除外なしで確かめる（3節）。dry-run は要求の失敗や他の書き手を知りようがない。それらは `--execute` だけの事象で、C-F1 のテストが受け持つ。

## 1. 一覧

| 件 | main 297826be での再現 | 直し方（要約） | Quint | 見込み |
| :-- | :-- | :-- | :-- | :-- |
| 2.1 `--max-rate` の桁あふれ | 当てはまる。`RateLimiter(1e-11)` は2回目から制限せず、`1e-11` と `Infinity` は引数の検査を通る | `--max-rate` を有限の 0.001 以上に限る | 関わらない | 1 |
| 2.2 動けない勝者の写しを「統合」と数える | 一部は #1033 で直った。勝者の行き先を、動けない候補の行がふさぐ場合が残る | dry-run を、`--execute` と同じ回の進め方の模擬にする | 関わらない | 5（2.3 と合わせて） |
| 2.3 中身の違う写しで止まる行を待つ後続を「順序つきの移動」と数える | 当てはまる | 2.2 と同じ直しで直る | 関わらない | （2.2 に含む） |
| 2.4 #831 の判定がロケールに依存する | 振る舞いは安全側のまま（移せない行として残る）。文書と起動時の表示も済み（#1027）。理由の文にロケールが出ない | 判定は変えない。理由の文にロケールの可能性を足す | 関わらない | 1 |
| 2.5 行の無い id の組を `--reverse --verify` が挙げない | 当てはまる（コードを読んで確認） | 文書の限界の節と `--reverse --verify` の表示に1文ずつ足す | 関わらない | 1 |

基準は [#1036](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1036) の 2 で、全体の見込みは 5。2.2 と 2.3 の dry-run の作り直しと、その性質のテストが大半を占める。ほかは文と検査の数行で済む。

## 2. 件ごとの設計

### 2.1 `--max-rate` の桁あふれ

**再現。** `RateLimiter(1e-11)` で `acquire()` を1回呼ぶと、`intervalNanos = (1e9 / 1e-11).toLong` は `Long.MaxValue` に飽和する。`next = slot + intervalNanos` は負に折り返し、続く `acquireBefore(now + 1 秒)` は `true` を返した。期待は `false` で、テストは落ちた。`MigrationOptions.parse(Seq("--max-rate", "1e-11"))` と `"Infinity"` は例外を出さずに通った。`Infinity` だと間隔が 0 になり、速度の制限が効かない。`NaN` は今も `> 0` の検査で断られる。

**直し方。** `MigrationSettings` と `VerifySettings` の `require` を、有限で 0.001 以上（1,000 秒に1つ）の値に限る形に変える。両方から同じ関数を呼び、文は1か所に置く。断ったときは今の `--max-rate must be positive` と同じく `IllegalArgumentException` で終わり、文には許す範囲を書く。下限を 0.001 にした理由：これより遅い移行は実用にならない。間隔は 1e12 ナノ秒で `Long` に収まり、加算も折り返さない。下限は戻せる選択で、利用者から要望があれば下げられる。`RateLimiter` 自体は変えない。下限があれば、そこまで届く入力が無いためである。

**変えないこと。** `--max-rate` の単位（行、走査のページ、読み取り、`--verify` の要求のそれぞれ1秒あたり）、0.001 以上の有限な値での待ち方。

**壊しうる入力。** `0`、負の数、`1e-11`、`0.000999`、`0.001`（ちょうど下限）、`Infinity`、`-Infinity`、`NaN`、`1e300`（間隔は 0 で、制限しないのと同じ。今と変わらない）。`--verify` 側も同じ値で試す。

**直す前に落ちるテスト。** `MigrationOptions.parse` の単体テスト（Docker を使わない）。`1e-11`、`0.000999`、`Infinity` で `IllegalArgumentException` が出ることを、移動と `--verify` の両方の形で確かめる。`0.001` と `1e300` は通ることも確かめる。

### 2.2 と 2.3 dry-run の分類を `--execute` に合わせる

**再現。** 2つとも DynamoDB Local で dry-run と `--execute` を続けて流した。

- 2.2：`A@1` の写しが k1 と k2 にあり、どちらも k9 へ移る。k9 には `C@1` があり、`C@1` は k8 へ移る。k8 には動かない `E@1` がある。dry-run は「1 merges」と数え、k2 の写しを統合の一覧に出した。`--execute` は何も統合せず、3行とも残りになった（「the target key holds C@1, which does not move」）。CodeRabbit が挙げた元の場合（k9 を動かない行がふさぐ）は #1033 で直っている。残っているのは、勝者が待つ行が動けない場合である。
- 2.3：`A@1`（k1→k2）、`B@1`（k2→k3）があり、k3 には中身の違う `B@1` の写しがある。dry-run は `A` を「1 after another move」と数えた。`--execute` は `B` を中身の違いで残し、`A` を「the target key holds B@1」で残した。

**原因。** dry-run は、各候補の行き先を一度だけ読み、それから辺をたどって分類する。`--execute` とは別の作りになっている。分類したあとで中身を比べ、違う写しを統合の一覧から外すが、その写しを待つ行の分類は直さない（2.3）。勝者でない候補は、勝者の分類を待たずに「勝者の写しと統合する」と決まる（2.2）。

**直し方。** dry-run を、`--execute` と同じ回の進め方の模擬に作り変える。書き込みはしない。

1. 今と同じく、行き先のうち候補の元のキーでないものを、射影つきで1回読む（今の `occupants`）。
2. キーごとの「仮の中身」を作る。候補の元のキーにはその行を、読んだ行き先には読んだもの（空き、行の (id, 番号)、行でない項目）を入れる。行には、中身をどのキーから読めばよいかも持たせる。
3. `--execute` の `ready` の式を関数 `readyOf(pending, reserved)` に切り出し、`--execute` と dry-run の両方から呼ぶ。`--execute` 側の変更はこの切り出しだけで、式は同じにする。dry-run では `reserved` は常に空になる。`Failed` と `Gone` を予測しないためである。
4. 各回の `ready` の候補について、`moveOne` と同じ決まりで結果を予測する。仮の中身が空きなら `Moved`。同じ行なら、元のキーと仮の中身の出どころを全項目で読み（今と同じく `--max-rate` の枠を1つずつ取る）、`differences` が空なら `Merged`、空でなければ `Conflict`。ほかの行なら `Blocked`、行でない項目なら `BlockedByItem`。予測した結果を仮の中身に反映する（`Moved` は行き先を埋めて元を空ける。`Merged` は元を空ける）。
5. 回が進まなくなったら、残った候補を `--execute` と同じ `findCycles` で循環と、それを待つ行に分ける。
6. 報告の分類は今の区分を保つ。`Moved` のうち、行き先がほかの候補の元のキーでなかったものを direct、元のキーだったものを after とする。`Merged` は統合、`Conflict` は中身の違う写し。`Blocked` と `BlockedByItem` のうち、ふさいでいるのが自分の元のキーに残った候補の行なら「待つ行」、それ以外は blocked とする。`outsideSelection` は今と同じく `!selected(id)` で決める。一覧の並びは `base.candidates` の順にそろえる。

この作りでは、dry-run は `--execute` の回の進め方と移動の決まりを同じ関数と同じ規則でなぞる。そのため、0節の4の一致が、模擬の作りそのものから出てくる。1回の中で `ready` の候補どうしは影響し合わない。どの候補の行き先も、残っている候補の元のキーではないからである。そのため、`--execute` が並行に動かす順と、模擬が順に予測する順の違いは結果に出ない。

**変えないこと。** `placement`、`moveOne`、`--execute` の回の進め方（関数に切り出すだけ）、`MigrationReport` の項目と表示の文、dry-run の項目ごとの区分の意味。dry-run が出す一覧の数は、2.2 と 2.3 の場合だけ変わる。今のテスト（循環、待つ行、ふさがれた行、C-F4 の勝者がふさがれる場合）は、値を変えずにそのまま通ることを確かめる。dry-run の読み取りの数は、勝者でない写しの統合で、今の最大3回から2回に減る。C-F3 の `--max-rate` のテストは、要求の間隔を見ているので影響しない見込み。実装の段で確かめる。

**守る不変条件。** 0節の1、2、4。`--execute` の出力と表は、切り出しの前と同じである。

**壊しうる入力。** 同じ行き先を求める3つ以上の写し。勝者が循環を待つ場合と、ふさがれた行を待つ場合。勝者の行き先に、中身の違う写しがある場合。勝者が待つ行の列の先で、ある候補の行き先が勝者でない候補の元のキーになっている場合。勝者でない候補は同じ行き先をふさぐ行を待つので、列は輪になる。行でない項目。`--persistence-id` で選ばれなかった id の行がふさぐ場合。journal（削除の印）と snapshot（`created`）。

**直す前に落ちるテスト。**

- `KeyMigrationSpec` に、2.2 と 2.3 の筋書きを回帰テストとして足す。dry-run の数が `--execute` の結果と合うことを確かめる。main では、再現と同じく落ちる。
- 性質のテスト（3節）。main では、2.2 か 2.3 の形を引いた種で落ちる見込み。実装の前に落ちることを確かめ、落ちた種を記録する。

### 2.4 #831 の判定のロケール

**再現。** `th-TH-u-nu-thai` で `"%019d".format(1L)` を作ると、タイの数字の 19 桁になる。これを `en` の JVM の `ResolverKeyPlan.placement` に渡すと `Unmovable`（理由は「neither the key of 1.3.0, of issue #831, nor of the new key format; it may come from another shard-count, persistence-id-separator, or key resolver」）になった。行は動かず、安全側に倒れている。文書（`customize.md` のロケールの段落）、起動時のロケールの行、`ar-EG` での公開版の試験（[#1027](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1027)、`ReleasedRowsMigrationSpec`）は、どれもすでに入っている。

**直し方。** 判定は変えない。書き込んだときのロケールを指定する引数は足さない。足すと公開 API が増え、#831 の判定、つまり移動の判定も変わるからである。理由の文の末尾にだけ「or another default format locale than this run's」の趣旨を足す。`--verify` の例の行も同じ文を使うので、そちらにも出る。

**守る不変条件。** 0節の1、2。`Unmovable` になる行の集合は変わらない。

**直す前に落ちるテスト。** `ResolverKeyPlan` の単体テスト。他のロケールで書いた #831 のキーが `Unmovable` になり、その理由に locale が含まれることを確かめる。main では、理由に locale が無いので落ちる。

### 2.5 行の無い id の組

**確認。** `KeyVerification` の `shared` は `expected.keys`、つまり表に行がある id だけから作る。`KeyMigration` の報告の組（`sharedKeyGroups`）も、走査で見た id から作る。片方の id の行がすべて消されていると、その組は挙がらない。両方が後で同じ番号を書くと `KeyConflict` になる。上書きにはならない。これは 1.3.0 からの振る舞いである。

**直し方。** 判定は変えない。表に無い id を知る手段がツールに無いためである。

- `customize.md` の `--reverse --verify` の段落に、1文を足す。「表に行が1つも無い `persistenceId` は判定の対象にならない。その id と組になる id が戻したあとに同じ番号を書くと、書き込みは衝突として失敗する（上書きはしない）」。
- `KeyVerification.render` の `--reverse` のときに、同じ限界を1行出す（一致したときも、しないときも）。`verified` には触れない。

**直す前に落ちるテスト。** `KeyVerification.render` に、`--reverse` の報告を渡す単体テスト。その行があることを確かめる。main では落ちる。

## 3. 性質のテスト（2.2、2.3）

ライブラリは足さない。固定した種の `scala.util.Random` で表と計画を作り、DynamoDB Local で確かめる。落ちたときは種を出す。

- **表の生成。** キーは k1〜k6。各キーは、空き、行、行でない項目のどれかになる。行の id は A〜C、番号は 1〜2、中身は2通り。journal（削除の印は有りと無し）と snapshot（`created` は2通り）の両方を使う。同じ (id, 番号) が複数のキーにあることも許す。
- **計画の生成。** 行のある元のキーごとに、動かさないか、k1〜k6 のほかのキーへ移すかを決める。`--persistence-id` は、指定しない場合と、id の一部を選ぶ場合の両方を作る。
- **性質。** 同じ表に dry-run と `--execute` を続けて流す。dry-run の direct と after の候補の集合は、`--execute` の動かした候補の集合と同じになる。統合の集合は統合した候補の集合と同じになる。それ以外の候補（循環、待つ行、blocked、中身の違う写し）の集合は、`--execute` の `remaining` の候補の集合と同じになる。数でなく集合で比べる。`--execute` の動かした候補は、候補全体から `remaining` を引いたものと、`mergedRows` から求める。
- **除外。** 無い。要求の失敗と他の書き手は、生成する表と流し方に現れない。どちらも dry-run が知りえない事象で、0節の4の前提に当たる。性質を狭めるものではない。
- **数。** 200 通り。1通りは表を作り、2回流す程度なので、数十秒で終わる見込み。

## 4. Quint

`specs/key-migration/` は `--execute` の回、`moveOne`、`--verify` を表すが、dry-run の分類は表していない（`dry` を含む定義は無い）。2.2 と 2.3 で `--execute` は関数を1つ切り出すだけで、遷移は変わらない。2.1、2.4、2.5 は引数の検査と表示だけである。そのため、仕様は変えない。仕様の検査（`quint test`）は実装の後に流し、結果が変わらないことを PR に書く。

## 5. 範囲の外

- `--segments` と `--parallelism` に上限が無い。とても大きな値ではスレッドを作りすぎる。DynamoDB は `TotalSegments` を 1,000,000 までに限っている。#1028 には入っていないので、必要なら別の issue にする。
- 書き込んだときのロケールを指定する引数（2.4）。
- 表に無い id の組を見つけること（2.5）。

## 6. 段2の手順

1. 2.1、2.4、2.5 の単体テストと、2.2・2.3 の回帰テストを書き、main のコードで落ちることを確かめる。性質のテストも書いて、落ちる種を記録する。
2. 2.1、2.4、2.5 を直す。続けて `readyOf` を切り出し、dry-run を模擬に作り変える。
3. `base-v2/test`（DynamoDB Local）、`journal-v2/testOnly *JournalKeyMigrationSpec *KeyMigrationPlanSpec *ReleasedKeysSpec`、`snapshot-v2` の移行のテスト、`schema-check/test`、`lint`、`mimaReportBinaryIssues`、2.12 と 3 の `Test/compile`、`quint test`、docs の `sphinx-build -W` を流す。
4. リリースノートに Unreleased の節を作り、1行足す。
