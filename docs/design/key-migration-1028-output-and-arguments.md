# キー移行ツールの出力と引数の確かめの直し（#1028）：設計

対象は issue [#1028](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1028)。1.4.0 で公開した実験的な移行ツール `JournalKeyMigration` と `SnapshotKeyMigration`、および base-v2 の `migration` パッケージを扱う。この文書は実装の前の設計で、製品のコードはまだ変えていない。

- 基準のコミット：main の 297826be。
- 公開の前の直し [#1033](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1033) と [#1036](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1036) は、このコミットに入っている。
- 再現には一時的なテスト `Issue1028ReproSpec` を使った。DynamoDB Local で動かし、このブランチには入れていない。結果は件ごとに書く。
- 設計の独立レビュー（antigravity）：`/Users/j5ik2o/tmp-ppd-1028-design-review/report.md`。指摘を D#1… と呼ぶ。反映した点は 8節にまとめた。

## 0. 守る条件

1. 移動の判定は変えない。`ResolverKeyPlan.placement` と、`--execute` の `moveOne` と各回の進め方が対象になる。保存済みのデータの読み方と書き方も変えない。
2. `--verify` の判定（`VerifyReport.verified`）は変えない。
3. 公開 API は変えない。コマンドの引数の名前、終了コードの意味（成功で 0、`incomplete` または `--verify` の不一致で 1、引数の誤りは例外で終わる）がこれに当たる。`--max-rate` は、これまで通っていた値の一部を引数の誤りとして断る（2.1）。引数の名前と終了コードの意味は変わらない。MiMa の基準（1.4.0）に対して、公開のバイトコードの形も変えない。`MigrationReport`、`VerifyReport`、`KeyVerification.render` の形はそのままにする。
4. dry-run と `--execute` の関係を、次の2つの性質で決める。どちらも、1回の `--execute` を、同じ表と同じ引数の dry-run と比べる。
   - **P1 一致。** 3つの集合 E1〜E3 のどれも起きない実行では、dry-run が「動かす」と数える候補の集合は、`--execute` が動かす候補の集合と同じになる。「統合する」と数える候補の集合は、統合する候補の集合と同じになる。残りの候補の集合も、`--execute` の残りの集合と同じになる。応答の消失と、上限より前に治まる一時的な失敗は、E に入らない。`moveOne` が両方のキーを読み直して結果を決めるので、どちらも P1 の中で起こす。
   - **P2 安全側。** E1 か E2 が起きる実行（他の書き手はいない）では、`--execute` は dry-run の予測の手前で止まり、ほかへは行かない。式で書くと次の4つになる。`exec.moved ⊆ dry.moved`、`exec.merged ⊆ dry.merged`、`dry.remaining ⊆ exec.remaining`、`(dry.moved ∪ dry.merged) \ (exec.moved ∪ exec.merged) ⊆ exec.remaining`。動かなかった候補は、どれも元のキーに読んだときの中身のまま残る。
   - **E1** は、DynamoDB が 400 KB の超過で `Put` を断る候補（`ValidationException` で `Failed`）。項目の大きさと新しいキーの長さだけで決まり、同じ表なら毎回起きる。dry-run は項目の本体を読まないので、これを予測しない。`customize.md` の限界の節に書いてある。
   - **E2** は、一時的な失敗が試みの上限まで続いてあきらめた候補（`settle` の `Failed`）。
   - **E3** は、ツールの外の書き手（`Gone`、`writerSigns`）。`--writers-stopped` の契約の外にある。`--execute` は兆しとして `writerSigns` に出し、`incomplete` にする。この場合の振る舞いは `specs/key-migration/keyMigrationWriters.qnt` が受け持ち、P1 と P2 は前提にしない。
   - 今の main は、E が無くても P1 を破る（2.2、2.3）。直したあと、P1 と P2 は Scala の性質のテスト（3節）と Quint（4節）で確かめる。除外は E1〜E3 だけで、E1 と E2 の中でも P2 を確かめる。E3 の中は keyMigrationWriters.qnt が確かめる。

## 1. 一覧

| 件 | main 297826be での再現 | 直し方（要約） | Quint | 見込み |
| :-- | :-- | :-- | :-- | :-- |
| 2.1 `--max-rate` の桁あふれ | 当てはまる。`RateLimiter(1e-11)` は2回目から制限せず、`1e-11` と `Infinity` は引数の検査を通る | `--max-rate` を有限の 0.001 以上に限る。同じ検査を3か所から呼ぶ | 関わらない | 1 |
| 2.2 動けない勝者の写しを「統合」と数える | 一部は #1033 で直った。勝者の行き先を、動けない候補の行がふさぐ場合が残る | dry-run を、`--execute` と同じ回の進め方の模擬にする | dry-run を足す（4節） | 8（2.3 と Quint を合わせて） |
| 2.3 中身の違う写しで止まる行を待つ後続を「順序つきの移動」と数える | 当てはまる | 2.2 と同じ直しで直る | 同上 | （2.2 に含む） |
| 2.4 #831 の判定がロケールに依存する | 振る舞いは安全側のまま（移せない行として残る）。文書と起動時の表示も済み（#1027）。理由の文にロケールが出ない | 判定は変えない。理由の文にロケールの可能性を足す | 関わらない | 1 |
| 2.5 行の無い id の組を `--reverse --verify` が挙げない | 当てはまる（コードを読んで確認） | 文書の2か所と `--reverse --verify` の表示に1文ずつ足す | 関わらない | 1 |

基準は [#1036](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1036) の 2。最初の設計では全体を 5 と見たが、Quint の dry-run（4節）と P2 の性質のテストが加わったので、2.2〜2.3 だけで 8 になる。8 を超えないように、PR は2本に分ける。

- PR-A：2.1、2.4、2.5（見込み 2）。
- PR-B：2.2、2.3 と、その Quint と性質のテスト（見込み 8）。

PR-A は PR-B を待たずにマージできる。リリースノートの Unreleased の1行は、PR-A で作り、PR-B で書き足す。

## 2. 件ごとの設計

### 2.1 `--max-rate` の桁あふれ

**再現。** `RateLimiter(1e-11)` で `acquire()` を1回呼ぶと、`intervalNanos = (1e9 / 1e-11).toLong` は `Long.MaxValue` に飽和する。`next = slot + intervalNanos` は負に折り返し、続く `acquireBefore(now + 1 秒)` は `true` を返した。期待は `false` で、テストは落ちた。`MigrationOptions.parse(Seq("--max-rate", "1e-11"))` と `"Infinity"` は例外を出さずに通った。`Infinity` だと間隔が 0 になり、速度の制限が効かない。`NaN` は今も `> 0` の検査で断られる。

**直し方。** 検査の関数を1つ作り（`requireMaxRate`、`migration` パッケージの中）、次の3か所から呼ぶ。許すのは有限で 0.001 以上（1,000 秒に1つ）の値だけ。

- `MigrationSettings` の `require`
- `VerifySettings` の `require`
- `RateLimiter` のコンストラクタ（D#5）。`private[migration]` なので直接作れる。そのため、入口の検査だけに頼らない。

断ったときは、今の `--max-rate must be positive` と同じく `IllegalArgumentException` で終わる。文には許す範囲を書く。下限を 0.001 にした理由は3つある。これより遅い移行は実用にならない。間隔は 1e12 ナノ秒で、`Long` に収まり、加算も折り返さない。下限は戻せる選択で、利用者から要望があれば下げられる。`customize.md` の `--max-rate <n>` の行に「(at least 0.001)」を足す（D#5）。

**変えないこと。** `--max-rate` の単位（行、走査のページ、読み取り、`--verify` の要求のそれぞれ1秒あたり）、0.001 以上の有限な値での待ち方。

**壊しうる入力。** `0`、負の数、`1e-11`、`0.000999`、`0.001`（ちょうど下限）、`Infinity`、`-Infinity`、`1e1000`（`Double` では `Infinity` になる）、`NaN`、`1e300`（間隔は 0 で、制限しないのと同じ。今と変わらない）。

**直す前に落ちるテスト。** Docker を使わない単体テスト。上の入力を、次の4つの入口それぞれに渡す。断るべき値で `IllegalArgumentException` が出ること、`0.001` と `1e300` が通ることを確かめる。

- `MigrationOptions.parse`（移動の形と `--verify` の形）
- `MigrationSettings` の直接の生成
- `VerifySettings` の直接の生成。`parse` は `--max-rate` を読んだ時点で `MigrationSettings` を作るので、`VerifySettings` の検査は `parse` からは届かない（D#5）。
- `new KeyMigration.RateLimiter(...)`

### 2.2 と 2.3 dry-run の分類を `--execute` に合わせる

**再現。** 2つとも DynamoDB Local で dry-run と `--execute` を続けて流した。

- 2.2：`A@1` の写しが k1 と k2 にあり、どちらも k9 へ移る。k9 には `C@1` があり、`C@1` は k8 へ移る。k8 には動かない `E@1` がある。dry-run は「1 merges」と数え、k2 の写しを統合の一覧に出した。`--execute` は何も統合せず、3行とも残りになった（「the target key holds C@1, which does not move」）。CodeRabbit が挙げた元の場合（k9 を動かない行がふさぐ）は #1033 で直っている。残っているのは、勝者が待つ行が動けない場合である。
- 2.3：`A@1`（k1→k2）、`B@1`（k2→k3）があり、k3 には中身の違う `B@1` の写しがある。dry-run は `A` を「1 after another move」と数えた。`--execute` は `B` を中身の違いで残し、`A` を「the target key holds B@1」で残した。

**原因。** dry-run は、各候補の行き先を一度だけ読み、それから辺をたどって分類する。`--execute` とは別の作りになっている。分類したあとで中身を比べ、違う写しを統合の一覧から外すが、その写しを待つ行の分類は直さない（2.3）。勝者でない候補は、勝者の分類を待たずに「勝者の写しと統合する」と決まる（2.2）。

**直し方。** dry-run を、`--execute` と同じ回の進め方の模擬に作り変える。書き込みはしない。

1. 行き先のうち、どの候補の元のキーでもないものを、キーごとに1回だけ射影つきで読む（D#3）。今は候補ごとに読むので、同じ行き先を求める候補が2つあれば2回読む。表は変わらないので、1回で足りる。
2. キーごとの「仮の中身」を作る。中身は「空き」「行（id、番号、出どころのキー）」「行でない項目」のどれかで、出どころのキーは、その行の全項目を読むときのキーを指す。候補の元のキーには、その候補の行を出どころ＝そのキーとして入れる。手順1で読んだ行き先には、読んだものを入れる。
3. `--execute` の `ready` の式を関数 `readyOf(pending, reserved)` に切り出し、`--execute` と dry-run の両方から呼ぶ。`--execute` 側の変更はこの切り出しだけで、式は同じにする。dry-run では `reserved` は常に空になる。`Failed` と `Gone` を予測しないためである。
4. 各回の `ready` の候補について、`moveOne` と同じ決まりで結果を予測する。
   - 仮の中身が空きなら `Moved`。
   - 同じ行（id と番号が同じ）なら、元のキーと、仮の中身の出どころのキーを、全項目で読む。どちらの読み取りも、今と同じく `--max-rate` の枠を1つずつ取る。`differences` が空なら `Merged`、空でなければ `Conflict`。どちらかの読み取りが空だったら（E3 の書き手が消した）、例外にせず、今のコードと同じく違い無しとして `Merged` にする（D#7）。
   - ほかの行なら `Blocked`。行でない項目なら `BlockedByItem`。
   - 予測した結果を仮の中身に反映する。`Moved` は行き先に候補の行を入れ（出どころはその候補の元のキー）、元を空きにする。`Merged` は元を空きにする。ほかの結果は何も変えない。
5. 回が進まなくなったら、残った候補を、`--execute` と同じ `findCycles` で循環とそれ以外に分ける。
6. 報告の区分は、今の項目を保ったまま、次の決まりで埋める。
   - `Moved` のうち、行き先がどの候補の元のキーでもなかったものを direct、元のキーだったものを after と数える。
   - `Merged` は統合の一覧、`Conflict` は中身の違う写しの一覧に入れる。
   - `Blocked` は、キーで判定する（D#3）。行き先 `c.to` がある候補の元のキーで、かつ仮の中身の出どころがその `c.to` のままなら、その候補の行が元のキーから動けずに残っている。その場合は待つ行に入れる。それ以外の `Blocked` は blocked に入れる。`outsideSelection` は今と同じく `!selected(id)` で決める。(id, 番号) で判定すると、同じ行の写しがほかのキーにあるときに誤る。
   - `BlockedByItem` は、いつも blocked（`BlockedMove.byItem`）に入れる。
   - 手順5で循環に入った候補は cycles に、それ以外の残りは待つ行に入れる。
   - 各一覧の並びは `base.candidates` の順にそろえる。cycles の中の並びは `findCycles` の順とする。
   - どの候補も、direct、after、統合、中身の違う写し、blocked、待つ行、cycles のちょうど1つに入る。

この作りでは、dry-run は `--execute` の回の進め方と移動の決まりを、同じ関数と同じ規則でなぞる。そのため、E の無い実行での一致（P1）が作りそのものから出てくる。1回の中で、`ready` の候補どうしは影響し合わない。どの候補の行き先も、残っている候補の元のキーではないからである。そのため、`--execute` が並行に動かす順と、模擬が順に予測する順の違いは結果に出ない。この点は Quint の仕様の 8.4 の 2 と同じ理由で、仕様も1つずつ動かしている。

**変えないこと。** `placement`、`moveOne`、`--execute` の回の進め方（関数に切り出すだけ）、`MigrationReport` の項目と表示の文、dry-run の項目ごとの区分の意味。

**区分の内訳が変わる場合（D#3）。** dry-run の blocked、待つ行、cycles の内訳は、`--execute` の分類に合わせて変わる。2.2 と 2.3 のほかに、循環と共有の行き先が絡む場合にも変わる。

- 例1：`KeyMigrationSpec` の「list a row that waits for a row in a cycle」の表（`A: k1→k2`、`B: k2→k1`、`C: k3→k2`、`D: k4→k3`）。
- 例2：`C: k0→k2`、`A: k1→k2`、`B: k2→k1`。今は cycles が空で、新しい作りでは `[[B, A]]` になり、`C` は待つ行になる。

どれも `--execute` の結果（`C` は「waits for a row that did not move」、`A` と `B` は循環）にそろう方向の変化で、互換のための特例は入れない。今のテストの値は、`--execute` と食い違っていたものだけを直す。直したテストと、その理由は PR に一覧で書く。

**C-F3 のテスト（D#3）。** `KeyMigrationSpec` の「pace the pages of the scan and every read of a dry-run with --max-rate (C-F3)」は、読み取りの数を `times.size should be >= 4 + 4 + 7` で確かめている。新しい作りでは、次の2つが減る。

- 行き先の読み取りが、4回（候補ごと）から3回（キーごと）になる。`D` の2つの写しは同じ k19 を求める。
- 比べる読み取りが、7回から6回になる。`D` の勝者でない写しは、元のキーと勝者の元のキーの2回だけを読む。

このテストの下限を `4 + 3 + 6` に、注釈をこの内訳に直す。各要求が枠を取ることは、同じテストの経過時間と間隔の判定が確かめる。そちらは変えない。

**守る不変条件。** 0節の1、2、4。`--execute` の出力と表は、切り出しの前と同じである。

**壊しうる入力。** 3節の生成器が、次の形を毎回の実行で必ず通るようにする。

- 同じ行き先を求める3つ以上の写し。
- 勝者が循環を待つ場合と、ふさがれた行を待つ場合。
- 勝者の行き先に、中身の違う写しがある場合。
- 勝者が待つ行の列の先で、ある候補の行き先が、勝者でない候補の元のキーになっている場合（列が輪になる）。
- 行でない項目、`Unmovable` の行、`--persistence-id` で選ばれなかった id の行が、行き先をふさぐ場合。
- journal（削除の印）と snapshot（`created`）。

**直す前に落ちるテスト。**

- `KeyMigrationSpec` に、2.2 と 2.3 の筋書きを回帰テストとして足す。dry-run の各区分が `--execute` の結果と合うことを確かめる。main では、再現と同じく落ちる。
- 性質のテスト P1（3節）。main では、2.2 か 2.3 の形を引いた種で落ちる見込み。実装の前に落ちることを確かめ、落ちた種を記録する。
- Quint の `dryRunMatches`（4節）。main の dry-run は Quint に無いので、仕様の上では「落ちる前」が無い。代わりに、今の dry-run の誤りを写した版（勝者でない写しを勝者の分類を見ずに統合と数える）で反例が出ることを一度確かめ、負の対照として記録する。

### 2.4 #831 の判定のロケール

**再現。** `th-TH-u-nu-thai` で `"%019d".format(1L)` を作ると、タイの数字の 19 桁になる。これを `en` の JVM の `ResolverKeyPlan.placement` に渡すと `Unmovable`（理由は「neither the key of 1.3.0, of issue #831, nor of the new key format; it may come from another shard-count, persistence-id-separator, or key resolver」）になった。行は動かず、安全側に倒れている。文書（`customize.md` のロケールの段落）、起動時のロケールの行、`ar-EG` での公開版の試験（[#1027](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1027)、`ReleasedRowsMigrationSpec`）は、どれもすでに入っている。

**直し方。** 判定は変えない。書き込んだときのロケールを指定する引数は足さない。足すと公開 API が増え、#831 の判定、つまり移動の判定も変わるからである。理由の文の末尾にだけ「or another default format locale than this run's」の趣旨を足す。`--verify` の例の行も同じ文を使うので、そちらにも出る。

**守る不変条件。** 0節の1、2。`Unmovable` になる行の集合は変わらない。

**直す前に落ちるテスト。** `ResolverKeyPlan` の単体テスト。他のロケールで書いた #831 のキーが `Unmovable` になり、その理由に locale が含まれることを確かめる。main では、理由に locale が無いので落ちる。

### 2.5 行の無い id の組

**確認。** `KeyVerification` の `shared` は `expected.keys`、つまり表に行がある id だけから作る。`KeyMigration` の報告の組（`sharedKeyGroups`）も、走査で見た id から作る。片方の id の行がすべて消されていると、その組は挙がらない。両方が後で同じ番号を書くと `KeyConflict` になる。上書きにはならない。これは 1.3.0 からの振る舞いである。

**直し方。** 判定は変えない。表に無い id を知る手段が、ツールに無いためである。

- `customize.md` の `--reverse --verify` の段落に、1文を足す。「表に行が1つも無い `persistenceId` は判定の対象にならない。その id と組になる id が戻したあとに同じ番号を書くと、書き込みは衝突として失敗する（上書きはしない）」。
- `customize.md` の dry-run の段落（1.3.0 でキーを共有した組を挙げる箇所）にも、「表に行がある `persistenceId` だけが対象になる」と1句足す（D#9）。
- `KeyVerification.render` で、`--reverse` のときに同じ限界を1行出す。一致したときも、しないときも出す。`--reverse` かどうかは、今の引数 `direction` が `"reverse"` かで決める。`render` の形と `VerifyReport` は変えない（D#6、MiMa）。`verified` には触れない。

**直す前に落ちるテスト。** `KeyVerification.render` の単体テスト。`direction = "reverse"` のときにその行があり、`"forward"` のときに無いことを確かめる。main では、`"reverse"` の側が落ちる。

## 3. 性質のテスト（2.2、2.3）

ライブラリは足さない。固定した種の `scala.util.Random` で表と計画を作り、DynamoDB Local で確かめる。落ちたときは種を出す。

### 3.1 生成器（D#2）

- **表。** キーは k1〜k8。各キーは、空き（20%）、行（65%）、行でない項目（15%）のどれかになる。行の (id, 番号) は A〜C と 1〜2 の6通りから選ぶので、同じ行の写しがよく出る。中身は2通り。journal（削除の印は有りと無し）と snapshot（`created` は2通り）を半々に使う。
- **計画。** 主には `ResolverKeyPlan` と同じく (id, 番号) ごとに決める。こうすると、同じ行の写しはいつも同じ行き先を持つ。(id, 番号) ごとに、動かさない（25%）、k1〜k8 のどれかへ移す（60%）、`Unmovable`（15%）を選ぶ。動かす行の写しが行き先のキー自体にあれば、その写しは `InPlace` になる。2割の場合は、元のキーごとに行き先を選ぶ計画にして、解決器に無い形のグラフも通す。
- **選択。** 7割は `--persistence-id` なし。3割は id の空でない一部を選ぶ。

### 3.2 P1 一致（E の無い実行）

同じ表に dry-run と `--execute` を続けて流す。`--execute` では、ランダムに1割の `TransactWriteItems` の応答を、適用したあとで失わせる（`SdkClientException`）。上限より前に治まる失敗も1割入れる。どちらも E に入らない。

dry-run の側で確かめること：

- direct、after、統合、中身の違う写し、blocked、待つ行、cycles の集合が互いに素で、和が候補の集合になる。
- dry-run の動かす集合（候補から、ほかの6つの区分を引いたもの）の大きさが、`directMoves + orderedMoves` と同じになる。
- `directMoves` が、動かす集合のうち、行き先がどの候補の元のキーでもないものの数と同じになる。
- `MigrationReport` に direct と after の集合は無いので、集合は差で求める。`MigrationReport` は MiMa の対象なので、項目は足さない。

`--execute` の側で確かめること：

- 統合の集合を `mergedRows` から、残りの集合を `remaining` から求め、動かした集合を、候補からその2つを引いて求める。
- `moved` が動かした集合の大きさ、`merged` が `mergedRows` の大きさと同じになる。統合と残りは互いに素になる。
- `alreadyGone == 0` で、`writerSigns` は空になる。

両者で確かめること：

- dry-run の動かす集合と `--execute` の動かした集合、統合の集合どうし、dry-run の残り（中身の違う写し、blocked、待つ行、cycles）と `--execute` の `remaining` が、それぞれ等しい。
- `unmovable` の集合が等しい。
- 流したあとの表全体を、期待の表と照らす。期待の表は、流す前の表から、`--execute` の報告どおりに作る。動かした候補は、元のキーから行き先へ中身ごと移す。統合した候補は、元のキーから消し、行き先を重複の決まり（削除の印、`created`）どおりにする。ほかのキーは流す前のままにする。キーごとに「元のキーが空」を確かめる形にはしない。連鎖する移動（`A` が k1→k2、`B` が k2→k3）では、`B` の元のキー k2 に `A` が入るからである。

### 3.3 P2 安全側（E1、E2）

同じ表で dry-run を流し、候補からランダムに1〜3個を選んで失敗を入れ、`--execute` を流す。候補の要求は、`TransactWriteItems` の `Delete` のキーが候補の元のキーであることで見分ける。

- E1：その候補の最初の `TransactWriteItems` で、`ValidationException`（エラーコード `ValidationException` の `DynamoDbException`）を投げる。`moveOne` は試み直さず `Failed` にする。
- E2：`maxAttempts = 2` にし、その候補の `TransactWriteItems` を毎回、送る前に `SdkClientException` で落とす。`settle` が元のキーに行を見つけ、`Failed("gave up …")` にする。

確かめることは、0節の4の P2 の4つの式と、3.2 と同じ形の表全体の照合。期待の表は `--execute` の報告から作るので、失敗を入れた候補は元のキーに中身のまま残る。

### 3.4 除外と、通ったことの確かめ

- **除外。** E1〜E3 だけ（0節の4）。E1 と E2 は P2 が確かめる。E3 は生成しない。keyMigrationWriters.qnt が確かめる。
- **数。** P1 と P2 を 200 通りずつ。1通りは表を作り、2回流す程度なので、合わせて数分に収まる見込み。実装の後に実測して PR に書く。
- **通ったことの確かめ（Quint の `--witnesses` にあたるもの）。** 200 通りのあいだに、次の形がどれも3回以上出たことを、テストの終わりに確かめる。
  - direct、after、journal の統合、snapshot の統合、中身の違う写し、cycles、行で blocked、行でない項目で blocked、選択の外の行で blocked、待つ行、`Unmovable`、応答の消失。
  - 2.2 の形：同じ行の2つの候補が1つの行き先を求め、勝者が `--execute` で動かない。
  - 2.3 の形：ある候補の行き先が、中身の違う写しとして残る候補の元のキーになっている。
  - 3つ以上の候補が同じ行き先を求める形。
  - P2 では、E1 と E2 のそれぞれで、dry-run の予測より `--execute` の結果が少なかった場合。
  
  足りなければ生成の割合を直す。形を一覧から外して済ませない。

## 4. Quint（D#4）

dry-run の作り変えは、回を重ねて、進まなくなるまで状態を変える処理になる。これは状態の遷移なので、仕様に入れる。`docs/design/key-migration.md` の 8.4 の 6 は、「dry-run は入れていない。dry-run の予測と `--execute` の結果が一致するかは確かめていない」と、この穴を挙げていた。

### 4.1 足すもの（`specs/key-migration/keyMigration.qnt`）

- `predict(st, t, c)`：dry-run の予測。`attempt` から、400 KB の拒否（`tooBig`）と、元のキーが空の分岐（応答の消失と書き手）を除いたもの。仮の中身の空き／同じ行（`differ` で統合か中身の違い）／ほかの行、の3つに分ける。コードの `predict` に当たる。仕様でも `attempt` とは別の関数にする。コードも別の関数で、`moveOne` を呼ばないからである。
- `dryRun(st, t, p)`：`scanCandidates` の候補から始めて、`readyOf(pending, Set())` で各回を進め、`predict` を当てていく。結果は、表、動かす集合、統合する集合、残りの集合（`Blocked`、`Conflict`、進まなくなって残った候補）。`readyOf` は `roundStep` と同じ関数を使う。`roundStep` と `fullRunWith` は変えない。
- 不変条件を2つ足し、`safety` に入れる。
  - `withinDryRun`（どの状態でも）：今の実行（`tool.startTable` が空でないもの）の結果のうち、`Moved` の候補は `dryRun(st, tool.startTable, tool.params)` の動かす集合に、`Merged` の候補は統合する集合に入る。止まった実行の途中も含む。P2 の最初の2つの式に当たる。
  - `dryRunMatches`（終わった実行）：`tool.phase == Finished` なら、dry-run の残りの集合は `tool.remaining` に入り、dry-run の動かす集合と統合する集合のうち動かなかった候補は `tool.remaining` に入る。さらに、その実行が `gaveUp` でなく、どの結果も `Failed` でないときは、3つの集合が dry-run と等しい。前半が P2 の残りの2つの式、後半が P1 に当たる。
- witness を足す：`wDryRunShortfall`（終わった実行で、dry-run が予測したより動いた候補が少ない）、`wDryRun22`、`wDryRun23`（2.2 と 2.3 の形を通った実行）。

### 4.2 宇宙に足すもの

- 2.3 の形は、今の宇宙にある。`a` は b130 へ移り、`b` は b130 から bP へ移る。`init` は `b` の写しを bP に中身を変えて置ける（`keysOf` の 3）。
- 2.2 の形は、今の宇宙に無い。同じ行の2つの写しが同じ行き先へ移る行が無いためである。そこで行 `n` を1つ足す。R130 は n130、K831 は n831、PLAIN は k130 とする。forward では、n130 と n831 の2つの写しがどちらも k130 へ移る。k130 には `k` が元のキーとしてあり、`k` は kP へ移る。kP に中身の違う `k` の写しがあると、`k` は残り、`n` の勝者も、勝者でない写しも動かない。PLAIN の k130 は、ほかの id の PLAIN と重ならないので、「PLAIN はほかの id と共有しない」という宇宙の決まりは保たれる。`GROUP`、`ORD`、`R130`、`PLAIN`、`K831` に `n` の分を足す。
- 行を足すと、`init` の状態の数と、CI の run の時間が増える。実装の後に `tools/quint-check-specs.sh` で時間を測り、`docs/design/quint-in-ci.md` の表を直す。CI の run 1 で、新しい witness が固定の種で4回未満しか出ないときは、`initSharedPair` と同じ形の開始 `initDryRunShapes` を足し、CI の run を1本足す。

### 4.3 テストと文書

- `keyMigration_test.qnt` に、2.2 と 2.3 の決まった筋書きを `run` で足す。dry-run の3つの集合が `fullRun` の結果と等しいことを確かめる。
- 負の対照：今の dry-run の誤り（勝者でない写しを、勝者の結果を見ずに統合と数える）を写した `dryRunAsBefore` で、`dryRunMatches` を破る反例が出ることを、手元で一度確かめて PR に書く。CI には入れない。
- `docs/design/key-migration.md` の 8.3（コードとの対応の表）に `predict`、`dryRun`、`readyOf` の行を足し、8.4 の 6 を「dry-run は `dryRun` として入れた」に書き換える。
- 検査は `./tools/quint-check-specs.sh`（`typecheck`、`quint test`、`ci-runs.txt` の全 run）で流す。

## 5. 範囲の外

- `--segments` と `--parallelism` に上限が無い件は、[#1041](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1041) で追跡する（D#8）。
- 書き込んだときのロケールを指定する引数（2.4）。
- 表に無い id の組を見つけること（2.5）。

## 6. 段2の手順

PR-A（2.1、2.4、2.5）：

1. 2.1、2.4、2.5 の単体テストを書き、main のコードで落ちることを確かめる。
2. 直す。`customize.md` に、2.1 の下限、2.5 の2か所を足す。
3. リリースノートに Unreleased の節を作り、1行足す。

PR-B（2.2、2.3）：

1. 回帰テスト、性質のテスト P1 と P2、Quint の `predict`、`dryRun`、不変条件、`n`、筋書きを書く。Scala のテストは main のコードで落ちることを確かめ、落ちた種を記録する。Quint は負の対照で反例が出ることを確かめる。
2. `readyOf` を切り出し、dry-run を模擬に作り変える。C-F3 のテストの下限と、`--execute` と食い違っていた既存テストの値を直す。
3. リリースノートの Unreleased の行に書き足す。

両方の PR で、push の前に流すもの（Docker が要るものを含む）：

- `sbt -batch "base-v2/test"`（DynamoDB Local）
- `sbt -batch "journal-v2/testOnly *JournalKeyMigrationSpec *KeyMigrationPlanSpec *ReleasedKeysSpec *ReleasedRowsMigrationSpec"`。snapshot の移行のテストも journal-v2 にあり、`snapshot-v2` には無い（D#7）。
- `sbt -batch "schema-check/test"`、`sbt -batch lint`
- `sbt -batch mimaReportBinaryIssues`、`sbt -batch "++2.12.20 mimaReportBinaryIssues"`、`sbt -batch "++3.6.4 mimaReportBinaryIssues"`（D#7）
- `++2.12.20` と `++3.6.4` での `base-v2/Test/compile`、`journal-v2/Test/compile`、`snapshot-v2/Test/compile`
- `./tools/quint-check-specs.sh`（PR-B。PR-A でも、仕様が変わらないことの確かめとして流す）
- `target/docs-venv/bin/sphinx-build -W -b html docs/src/sphinx target/docs-build`

## 7. 見込み

| PR | 中身 | 見込み |
| :-- | :-- | :-- |
| PR-A | 2.1（検査の関数と3か所、テスト、文書）、2.4（理由の文とテスト）、2.5（文書の2か所、表示の1行、テスト） | 2 |
| PR-B | dry-run の模擬、既存テストの値の直し、回帰テスト、性質のテスト P1・P2 と witness、Quint の `predict`・`dryRun`・不変条件2つ・行 `n`・筋書き・負の対照、CI の時間の測り直し | 8 |

基準は [#1036](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1036) を 2 とした目盛り。PR-B の大きさを決めているのは Quint の宇宙の変更で、CI の時間と witness の回数が読めない。8 を超えそうなら、Quint を PR-B から別の PR に分ける。その場合、PR-B の dry-run の変更は、Quint の PR が main に入るまでマージしない。

## 8. レビューの反映

レビュー（`/Users/j5ik2o/tmp-ppd-1028-design-review/report.md`）の9件を、次のように扱った。

| 指摘 | 扱い | 変えた点 |
| :-- | :-- | :-- |
| D#1 400 KB とあきらめが隠れた除外になっている | 直す（実装の前） | 0節の4で、除外を E1（400 KB）、E2（あきらめ）、E3（他の書き手）として名指しした。E の無い実行の一致 P1 と、E1・E2 の中の安全側 P2 の2つに分け、P2 を性質のテスト（3.3）と Quint（`withinDryRun`、`dryRunMatches`）で確かめる。応答の消失と、上限より前に治まる失敗は E から外し、P1 の中で起こす。性質を狭めたものは無い |
| D#2 生成器の範囲、区分の不変条件、`Unmovable` | 直す（実装の前） | 3.1 を、(id, 番号) ごとの計画、行の割合 65%、キー8つ、`Unmovable` 15%、元のキーごとの計画 2割に変えた。3.2 に、区分が互いに素で和が候補になること、件数と集合の一致、`--execute` 側の件数、`unmovable` の一致、表との照合を足した。3.4 に、各形が3回以上出たことの確かめを足した |
| D#3 C-F3 の回数、内訳の変化の範囲、待つ行の判定 | 直す（実装の前） | 2.2 の手順1を、行き先をキーごとに1回読む形に決めた。C-F3 のテストの下限を `4 + 3 + 6` に直すと書いた。「2.2 と 2.3 の場合だけ変わる」を消し、循環と共有の行き先が絡む場合の変化を例つきで書いた。手順6の待つ行の判定を、出どころのキーで決める形にし、`BlockedByItem` はいつも blocked と書いた |
| D#4 dry-run の回が Quint の外にある | 直す（実装の前） | 4節を書き直した。`predict`、`dryRun`、`withinDryRun`、`dryRunMatches`、witness、2.2 の形のための行 `n` を足す。負の対照と、`key-migration.md` の 8.3・8.4 の更新も書いた。見込みが 5 から 8 に増えたので、PR を2本に分けた（1節、7節） |
| D#5 `VerifySettings` の直接のテスト、`RateLimiter` の守り、文書の下限 | 直す（実装の中） | 2.1 で、検査の関数を `RateLimiter` からも呼ぶことにした。テストの入口を4つに増やし、`1e1000` を足した。`customize.md` に下限を書く |
| D#6 2.5 の `--reverse` の判定と MiMa | 直す（実装の中） | 2.5 で、`direction == "reverse"` で判定し、`render` と `VerifyReport` の形を変えないと書いた。テストは reverse と forward の両方で確かめる。0節の3に MiMa の条件を足した |
| D#7 比べる読み取りの空、検証の手順のずれ | 直す（実装の中） | 2.2 の手順4に、読み取りが空のときは例外にせず違い無しとして扱う（今のコードと同じ）と書いた。6節の検証を、journal-v2 の4つのテスト、3つの Scala の版での MiMa、`./tools/quint-check-specs.sh` に直した |
| D#8 `--segments` と `--parallelism` の上限 | 別件（依頼元の決定） | [#1041](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1041) を立て、5節にリンクを書いた |
| D#9 dry-run の段落にも、行がある id だけが対象と書く | 直す（意見を採る） | 2.5 に、`customize.md` の dry-run の段落へ1句足すことを書いた |
