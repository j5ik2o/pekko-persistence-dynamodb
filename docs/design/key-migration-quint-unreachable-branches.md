# 設計：移行ツールの Quint 仕様の、届かない分岐を直す

`specs/key-migration/`（[#1034](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1034)）には、どの状態からも届かない分岐がある。CI の `quint` ジョブ（[#1039](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1039)）の設計の担当が、次の3つの1行の変更が `quint test` も `quint run` も通ることを見つけた（`docs/design/quint-in-ci.md`「設計の中で見つけたこと」）。

- A：`differ` から journal の `created` の比較を外す
- B：`differ` から snapshot の `deleted` の比較を外す
- C：`attempt` で、元にも行き先にも行が無いときの `Gone` を `Moved` にする

設計レビュー（R-14）は、3つとも仕様の中で届かない分岐が原因だと示した。仕様の検査が弱いと、仕様とコードのずれを CI で捕まえられない。この文書は、行の作り方と性質を広げて、3つ（と、同じ原因で見つけた4つ目）を CI が必ず捕まえるようにする設計である。公開済みの 1.4.0 のコードの不具合ではなく、移行ツールのコードは変えない。

## 主な決定

| 項目 | 決めたこと |
| :-- | :-- |
| A・B の宇宙 | journal の `created` と snapshot の `deleted` に、「プラグインが書かず、一部の項目だけが持つ属性」の意味を与える。`buildTable` が、その属性を持つ写しと持たない写しを作る |
| A・B の性質 | `versionsKept` を、`payload` だけでなく `differences` が比べる属性すべて（`compared`）で比べるように強める |
| C の宇宙 | 書き手のいない本体には足さない。`keyMigrationWriters.qnt` に、ツールが走る間にアプリが hard delete だけをするモデル（`stepD`）を足す |
| C の性質 | 新しい不変条件を2つ `safety` に入れる。`outcomesTruthful`：ツールが記録した結果は、その試みが見たキーの中身と合い、同じキーを残す結果どうし（`Moved` と `Merged`、`Gone` と「outcome unknown」）も区別する。`reservedRespected`：予約した行き先を、同じ実行のほかの候補が取らない |
| 決まった筋書き | 9本足し、既存の2本に確かめを足す。A〜E と、レビューの R-1・R-5・R-7 で見つかった変異を、それぞれ少なくとも1本が必ず落とす |
| CI | `ci-runs.txt` の1番目に witness を2つ足し、`wGaveUp` を1番目から2番目へ移し、`stepD` の run を2本足す |
| 性質の除外 | 付けない。`versionsKept` は強くなる。狭める必要は出なかった |
| コード | 変えない |

見込みの大きさは 2。基準は [#1039](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1039)（Quint の CI）を 3 とする。#1039 より小さいのは、ワークフローとスクリプトを変えず、仕様・筋書き・一覧・文書だけを変えるため。下の数字は、作業場所の外の写しですべて測ってある。

## 1. 再現

main の 297826be の `specs/key-migration/` を作業場所の外に写し、1行ずつ変えて `QUINT_SPECS_DIR=<写し> bash tools/quint-check-specs.sh` を流した。CI と同じ検査で、どれも `Every Quint spec passed.` で終わる。

| 変異 | 変える行 | `quint test` | `ci-runs.txt` の7本 |
| :-- | :-- | :-- | :-- |
| A | `differ`：`s.payload != t.payload or (st == Snapshot and s.deleted != t.deleted)` | 12 / 4 / 10 本とも通る | すべて期待どおり（1〜6 は違反なし、7 は違反） |
| B | `differ`：`s.payload != t.payload or (st == Journal and s.created != t.created)` | 同上 | 同上 |
| C | `attempt`：`else { table: t, outcome: Moved }` | 同上 | 同上 |
| E | `settleOutcome`：最後の `else Failed("outcome unknown")` を `else Moved` | 同上 | 同上 |

E は、C と同じ原因を調べる中で見つけた。`settle` の後で行がどちらのキーにも無いときの分岐で、これも届かない。

4つの分岐に届かないことを、診断用の witness で確かめた（`init`、400 × 120、種 `0x7`、`safety`）。

| witness | 中身 | 結果 |
| :-- | :-- | :-- |
| `wGone` | 結果に `Gone` がある | 0 / 400 |
| `wOutcomeUnknown` | 結果に `Failed("outcome unknown")` がある | 0 / 400 |
| `wJournalCreatedNonZero` | journal の表に `created != 0` の行がある | 0 / 400 |
| `wSnapshotDeletedTrue` | snapshot の表に `deleted` が真の行がある | 0 / 400 |

書き手のモデルでは、契約を外した `stepW`（`initW`、2,000 × 80）が 158 本のうち 5 本で `Gone` に届く。しかし `stepW` は CI の run に無く、共有の行き先で `Gone` になる経路は 0 本だった。

## 2. 原因

### A・B：行の作り方と性質の両方

`buildTable` は journal の `created` を常に 0、snapshot の `deleted` を常に偽にする。このため `differ` の2項は、どの状態でも偽になる。

宇宙だけを広げても、A・B は捕まらない。試作で宇宙だけを広げ、`versionsKept` を今のままにした写しに A・B を入れると、`safety` の run 1 と run 2 はどちらも違反なしだった。A なら、`payload` が同じで `created` だけが違う2つの写しがまとめられ、元のキーの写しの `created` が消える。ところが `versionsKept` は `payload` しか比べず、`contentsKept` は残った行の `created` が初めのどれかの写しにあれば通す。`converges` と `withinReference` は、基準の実行も同じ `differ` を使うので違いを見ない。

### C・E：書き手のいない本体では届かない

本体には書き手がいない。候補 `c` の元のキーから行を消せるのは `c` 自身だけで、消すときは行き先に同じ行を原子的に置く（`attempt` の移動とまとめ）。`attemptLost` も、書いた試みの応答を失わせるだけなので、行き先には行がある。したがって「元にも行き先にも行が無い」状態は起きない。

これはコードと合っている。コードは整合性のある読み取りを使い（KeyMigration.scala:976-981）、`--writers-stopped` の下ではツールだけが表を変える。`Gone`（:1186-1191）と `settle` の「outcome unknown」（:1214）は、止まっていない書き手が行を消したときのための分岐で、コードは `Gone` を書き手の兆候として報告する（:890-892、customize.md「a row that left its key without reaching the target key」）。

届いても、C を見分ける性質が無い。`Gone` と `Moved` はどちらも残りにならない。違いは予約（`reserves(Gone)`）と報告の数だけで、予約の違いは共有の行き先でしか表に出ない。

## 3. 直し方

### 3.1 もう一方の規則の列に意味を与える（A・B の宇宙）

コードの `differences`（KeyMigration.scala:1119-1131）は、2つの写しの属性名の和から、`pkey`、`skey`、規則の列（journal は `deleted`、snapshot は `created`）を除いたすべてを比べる。一方の写しにだけある属性も違いになる。

実物の列は次のとおりで、journal に `created` は無く、snapshot に `deleted` は無い（各モジュールの `reference.conf` の `columns-def`）。

| 表 | プラグインが書く列（キーと id・番号を除く） | 規則の列 |
| :-- | :-- | :-- |
| journal | `deleted`、`message`、`ordering`、`tags` | `deleted`（`DeletedFlag`） |
| snapshot | `snapshot`、`created`、`tags` | `created`（`LatestCreated`） |

そこで仕様の `Row` の、もう一方の規則の列に「プラグインが書かず、一部の項目だけが持つ属性」の意味を与える。運用者や別の道具が足した属性（たとえば、スクリプトが一部の項目に付けた TTL の属性）がこれに当たる。journal の `created` は 0 が無し、1 が有り、snapshot の `deleted` は偽が無し、真が有りを表す。`payload` は、これまでどおりプラグインが書く比較対象の列をまとめたもの。

`buildTable` に選択 `extra: LRow -> int`（0〜2）を足す。

| `extra` | 写しが1つ | 写しが2つ |
| :-- | :-- | :-- |
| 0 | 持たない | どちらも持たない |
| 1 | 持たない | 新しいキーの写しだけが持つ（写しが違う） |
| 2 | 持つ | どちらも持つ（同じ） |

`init` は journal と snapshot それぞれに `extra` を非決定的に選ぶ。「全行が動ける表」（mode 2）では 1 を 2 に読み替え（`sameExtra`）、写しが違わないようにする。`progress` の前提 `movableStart` を崩さないためで、属性を持つ行は mode 2 にも残る。

コードとの対応：移動は項目をまるごと `Put` する（`put(source, c.to, ...)`）ので、属性も一緒に移る。まとめは、journal なら行き先の写しを残して `deleted` を論理和にし（`markDeleted`・`check`）、snapshot なら `created` の大きい写しをまるごと残す（`merge`、:1233-1245）。仕様の `mergeTx` は今のままで合う。`differ` も今のままで、コードの `differences` と同じ形をしている。

### 3.2 `versionsKept` を強める（A・B を捕まえる性質）

比べる属性を `compared(st, r)` として1か所で定める。行から規則の列を消したもので、journal なら `{ ...r, deleted: false }`、snapshot なら `{ ...r, created: 0 }`。`differences` の `ignored` をそのまま写した形である。

`versionsKept` を「初めに論理的な行の写しが持っていた `compared` の値は、どれも今の写しのどれかが持つ」にする。今は `payload` だけを比べているので、これは強めることで、狭めることではない。`differ` とは別に定めるので、`differ` を壊すと `versionsKept` が落ちる。

### 3.3 `Gone` に届く経路（C・E の宇宙）

本体には書き手を足さない。足すと、`noLoss`、`exactlyOnce`、`converges` などが、アプリが消した行を除かないと成り立たなくなる。これは性質を狭めることになる。

代わりに `keyMigrationWriters.qnt` に、契約を外した別のモデル `stepD` を足す。ツール、インデックス、`--verify` の各段に加え、アプリの hard delete（`hardDeleteCurrent`、`hardDeleteLegacy`。`JournalRowStore.delete` の2つの `DeleteItem`、JournalRowStore.scala:288-340）が、ツールが走る間に起きる。消す行は `DELETE_ROWS = {b@1, h@1, i@1}` とする。b は直接移る行、h と i は `--reverse` で h130 を共有する組で、勝者の行が消えたときの予約を通す。書き込みと復旧（`recoverAndWrite`）は入れない。これらが壊すもの（A1、A2）は既存の run と筋書きが示しており、入れると `noLossW` の違反が `Gone` の経路を覆い隠す。

起点は2つ用意する。

- `initW`（既存）：`init` の何でもありの表
- `initD`（新規）：`{hP: h, iP: i, b130: b}`。`initSharedPair` と同じ重みづけで、共有の行き先の `Gone` を通りやすくする

実物で起きる形：`--writers-stopped` と書いたのにアプリが止まっていない、または止め忘れたノードが残っていて、移行の間に hard delete を送る。customize.md の警告が想定している状況で、コードはこのときのために `Gone` と兆候の報告を持っている。

### 3.4 `outcomesTruthful`（C・E を捕まえる性質）

ツールが結果を記録したとき、結果がその試みの見たものと合うことを確かめる。報告は `moved`、`merged`、`alreadyGone`、残りを別々に数え（KeyMigration.scala:886-893、:912-916）、`mergedRows` も別に出す（:1170、:1177、:1190、:1212）。そこで、同じキーを残す結果どうしも区別する（レビュー R-1）。

記録する段が見たものを `Seen = { before, after, merging, settled }` で渡す。`before` と `after` は段の前と後の表、`merging` は前の試みがまとめを送ったか、`settled` はその段が `settle` か試みか。`sawBoth` は「`merging`、または `before` で両方のキーにこの行があった」とする。

| 結果 | 確かめること |
| :-- | :-- |
| `Moved` | `after` で行き先に行があり、元のキーに無い。`sawBoth` でない |
| `Merged` | 同じキーで、`sawBoth` |
| `Gone` | 試みが、どちらのキーにも行を見なかった |
| `Failed("outcome unknown")` | `settle` が、どちらのキーにも行を見なかった |
| `Blocked(l)` | 試みが、元のキーにこの行、行き先に別の行 `l` を見た |
| `Conflict` | 試みが、両方のキーにこの行を見た |
| `Failed("rejected by DynamoDB")` | 試みが、元のキーにこの行を見て、行き先は空いていた |
| `Failed("gave up")` | `settle` が、元のキーにこの行を見た |

書き手のモデルでは、記録の後でアプリが行を消しうる。そこで状態の不変条件にはせず、記録の時点で確かめる。`Tool` に履歴の欄 `misreported: bool` を足し、`record(tool, c, o, seen)` が上の表を確かめて、合わなければ真にする。`attemptOk` は `{ before: 今の表, after: e.table, merging, settled: false }` を、`settle` は `{ before: 今の表, after: 今の表, merging, settled: true }` を渡す。不変条件は `outcomesTruthful = STORES.forall(st => not(tools.get(st).misreported))` で、`safety` と `cheapSafety` に入れる。

コードとの対応：`moveOne` の各結果は、そのとき読んだキーから決まる（:1134-1203）。`Merged` は、まとめを送った後（`merging`、:1177、:1190、:1212）か、両方のキーに行を読んでまとめたとき（:1167-1171）に出る。仕様の `attempt` は読み取りとトランザクションを1つの原子的な遷移にしている（`docs/design/key-migration.md` 8.4 の1）ので、段の前後の表で比べてよい。`settle` の読み取りそのものが失敗して「outcome unknown」になる経路（:1216-1219）は仕様に入れていない（R-6、8.4 に書く）。

本体では、どの結果も構造上この表のとおりなので、変異が無ければ違反は起きない。変異 C・E は、`stepD` でどちらのキーにも行が無い状態に届くと `Moved` を記録して落ちる。レビューの変異 F1・F2（まとめの後の `Merged` を `Moved` にする）は run 1 で、F3（「outcome unknown」を `Gone` にする）は `stepD` で落ちる。

### 3.5 `reservedRespected`（D を捕まえる性質）

予約（C-F1）を、基準の実行を使わずに確かめる（レビュー R-2）。勝者は保留中で元のキーが最も小さい候補（`winnerOf`）で、保留の集合は走査で決まる。だから、行き先の勝者の結果が予約する結果（`Failed`、`Gone`）なら、同じ実行で同じ行き先を要るほかの候補は、どれも結果を持たない。

```quint
val reservedRespected: bool = STORES.forall(st => {
  val os = tools.get(st).outcomes
  os.forall(co1 => (match co1._2 { | Failed(_) => true | Gone => true | _ => false }) implies
    os.forall(co2 => co2._1.dst != co1._1.dst or ORD.get(co2._1.src) <= ORD.get(co1._1.src)))
})
```

予約する結果は `reserves` を呼ばずに書き出す。`reserves` を壊す変異（D）で、この不変条件まで一緒に変わらないようにするため。実行の結果だけを読むので、書き手のモデルでも成り立つ。`safety` と `cheapSafety` に入れ、`stepD` の run でも確かめる。勝者が `Moved` した後でアプリがその行を消し、次のラウンドでほかの候補が行き先を取るのは、コードでも起きる正しい動きで、この不変条件は破れない（勝者の結果が予約しないため）。

### 3.6 `contentsKept` を snapshot の新しい意味にそろえる

`contentsKept` の `r.deleted implies origins.exists(o => o.deleted)` は、`deleted` が journal の論理和の列だった前提のままである。snapshot の `deleted` は3.1で「属性の有無」になったので、journal の `created` と同じく「初めの写しのどれかの値」にする（レビュー R-6）。journal の `deleted` は規則が論理和を取るので、今の形のまま残す。どちらも強めることで、狭めることではない。

### 3.7 witness

| witness | 置き場所 | 中身 |
| :-- | :-- | :-- |
| `wConflictExtraJournal`、`wConflictExtraSnapshot` | `keyMigration.qnt` | その表で `Conflict` があり、2つの写しの `payload` が同じ（属性だけが違いを生んだ） |
| `wGone` | `keyMigrationWriters.qnt` | 結果に `Gone` がある |
| `wGoneShared` | `keyMigrationWriters.qnt` | `Gone` の候補のほかに、同じ行き先を要る候補がこの実行にある |
| `wOutcomeUnknown` | `keyMigrationWriters.qnt` | 結果に `Failed("outcome unknown")` がある（レビュー R-3） |

これらを `ci-runs.txt` の `--witnesses` に入れる。仕様が変わって分岐にまた届かなくなると、witness が 0 になって CI が落ちる。今回の問題は「届かないことに誰も気づかない」ことだったので、この witness が再発を防ぐ。

## 4. 不変条件（除外なし）

### 4.1 本体（広げた宇宙、書き手なし）

すべての不変条件を、除外を付けずに今までどおり確かめる。`safety`（`init` 400 × 120、`initSharedPair` 2,000 × 60）と `idempotent`（2本）は違反なしだった。

| 不変条件 | 広げた宇宙で保たれる理由 |
| :-- | :-- |
| `noLoss`、`noDuplicate`、`exactlyOnce`、`keysKnown`、`unknownStays` | 行の数と置き場所は属性に関係しない |
| `contentsKept`（snapshot をそろえた） | 移動は行をまるごと移す。まとめで残る行は、初めの写しのどれかの属性を持つ |
| `versionsKept`（強めた） | まとめが起きるのは `compared` が等しいときだけなので、消える写しの値は残る写しが持つ |
| `converges`、`withinReference`、`finishedWithinReference`、`idempotent` | 基準の実行も同じ `attempt` を使う。属性が違う写しは、どちらでも `Conflict` になる |
| `progress` | 属性が違う写しのある起点は `movableStart` が前提から外す（今の `differ` のとおり）。mode 2 は `sameExtra` で写しを違わせない |
| `roundTrip` | 1.3.0 だけの表は写しが1つずつで、属性は行と一緒に戻る |
| `verifySound`、`verifyReverseShared`、`selectionRespected`、`finishedReported`、`unreversibleStays` | キーと id で決まり、属性に関係しない |
| `outcomesTruthful`、`reservedRespected`（新規） | 3.4、3.5 のとおり、構造上成り立つ |

### 4.2 `stepD`（契約を外したモデル）

`stepD` は既存の run 5〜7 と同じく、契約を外したモデルで成り立つものを確かめる別のモデルである。本体の不変条件は、本体の run がそのまま確かめる。`stepD` の run で確かめるのは次の12個で、`initW` 400 × 120 と `initD` 2,000 × 60 で違反なしだった。

`noLossW`、`noDuplicate`、`outcomesTruthful`、`reservedRespected`、`keysKnown`、`unknownStays`、`contentsKept`、`finishedReported`、`unreversibleStays`、`verifyReverseShared`、`verifySoundW`、`progress`

`noDuplicate` は本体と同じ強さのものを使う（レビュー R-2）。`stepD` は消すだけで書かないので、行の写しが増えることも、初めに無かった行が現れることも無い。`noDuplicateW` は書き込みのある run 5〜7 のための弱い形で、ここでは使わない。

`noLossW` と `verifySoundW` は journal の表だけを見る。アプリが消すのは journal だけで、snapshot の表は `stepD` の中でもツールだけが変える。snapshot の行が消えないこと（`noLoss`）と、消えない行の `compared` の値が残ること（`versionsKept`）は、本体の run 1・2 が両方の表で確かめる。

ほかの不変条件は、アプリの削除そのものが破る。どれも新しい除外を付けずに、次のどれかで扱う。

| 不変条件 | `stepD` で破れる理由 | 扱い |
| :-- | :-- | :-- |
| `noLoss`、`exactlyOnce`、`versionsKept`、`verifySound` | アプリが消した行には写しが無い | 消した行を考えに入れた `noLossW`、`verifySoundW` が既にある |
| `deletedStayGone` | 2つの `DeleteItem` の間に行が移る（A3） | 既存の筋書き `hardDeleteMissesMovedEventTest` が反例を示す |
| `selectionRespected` | 実行の間に、選んでいない id の行をアプリが消す | ツールの性質で、本体で確かめる |
| `converges`、`withinReference`、`finishedWithinReference` | 基準の実行にアプリがいない | 本体で確かめる |
| `roundTrip` | `initW` の1.3.0 だけの表で、アプリが b@1 を消した後に `--reverse` が終わると、表は初めの表に戻らない（`initW` 400 × 120 で、数トレース目に違反） | 本体で確かめる |

前の版の4.2は「`roundTrip` は `stepD` でも違反しなかった」と書いていたが、誤りだった。違反しなかったのは `initD` だけで、`initD` は1.3.0 だけの表ではないので前提が常に偽になる（レビュー R-2）。

## 5. 決まった筋書き（`quint test`）

| テスト | ファイル | 筋書き | 落とす変異 |
| :-- | :-- | :-- | :-- |
| `conflictJournalExtraTest` | `keyMigration_test.qnt` | journal の `k130 {deleted: 真}`、`kP {created: 1}`（`payload` は同じ）。`attempt` は `Conflict`、`fullRun` の後の表は初めと同じ、残りは k | A |
| `conflictSnapshotExtraTest` | 同上 | snapshot の `k130 {created: 2}`、`kP {created: 1, deleted: 真}`。同じく `Conflict` | B |
| `mergeExtraOnBothTest` | 同上 | 両方の写しが同じ属性を持つと、journal も snapshot もまとまる | 属性の有無だけで `Conflict` にする行き過ぎの直し |
| `goneReservesTest` | 同上 | 走査が h（hP）と i（iP）を見た後、h の行が消えている。`attempt` は `Gone`（まとめ中でも）、`roundStep` で h130 が予約され、次の `roundStep` で i は残りになり、表は変わらない | C、D（`reserves` から `Gone` を外す） |
| `settleNeitherKeyTest` | 同上 | 最後の試みの後、行がどちらのキーにも無い。`settleOutcome` は `Failed("outcome unknown")`、予約する | E、F3 |
| `truthfulTest` | 同上 | `truthful` が、移動・まとめ・応答を失ったまとめの後の試みと `settle`、どちらのキーにも無い行（試みと `settle`）、「gave up」と「rejected」を区別する | `truthful` の取り違え |
| `lostResponseTest`（足す） | 同上 | まとめの応答を失った後、次の試みも `settle` も `Merged` を返す（レビュー R-5） | F1、F2 |
| `placementReverseTest`（足す） | 同上 | 1.3.0 がキーを作れない j が、知られていない形のキーにあると `Unmovable(UNKNOWN_REASON)`（R-5） | P190 |
| `mergeJournalTargetDeletedTest` | 同上 | 行き先の写しが deleted のとき、まとめた後も deleted（R-7） | J |
| `mergeSnapshotTargetLaterTest` | 同上 | 行き先の写しの `created` が大きいとき、行き先の写しが残る（R-7） | S |
| `deleteBeforeAttemptGoneTest` | `keyMigrationWriters_test.qnt` | `initD` から `--reverse` を始め、最初のラウンドの後にアプリが h を消す（`hardDeleteCurrent`）。h の試みは `Gone`、h130 は予約、次のラウンドで実行が終わり、i は残り、表は hP が消えただけ。`outcomesTruthful`、`reservedRespected`、`noLossW`、`noDuplicate` が成り立つ | C、D |

`mergeExtraOnBothTest` と既存の `mergeJournalTest`・`mergeSnapshotTest` は逆向きの確かめで、規則の列や同じ値の属性を違いとして扱う変異を落とす。`keyMigrationWriters_test.qnt` の冒頭の説明は「不変条件が破れることを確かめる」なので、成り立つことを確かめる `deleteBeforeAttemptGoneTest` があることを書き足す。

変異の一覧（どれも1か所の置き換え）：

| 変異 | 場所 | 置き換え |
| :-- | :-- | :-- |
| A | `differ` | journal の `created` の比較を外す |
| B | `differ` | snapshot の `deleted` の比較を外す |
| C | `attempt` | どちらのキーにも無いときの `Gone` を `Moved` |
| D | `reserves` | `Gone` を外す |
| E | `settleOutcome` | 「outcome unknown」を `Moved` |
| F1 | `attempt` | 元が無く行き先にあるときの `if (merging) Merged else Moved` を `Moved`（R-1） |
| F2 | `settleOutcome` | 同じく `Moved`（R-1） |
| F3 | `settleOutcome` | 「outcome unknown」を `Gone`（R-1） |
| P190 | `placement` | `--reverse` で 1.3.0 のキーが無く知られていない形のキーのときの `Unmovable(UNKNOWN_REASON)` を `InPlace`（R-5） |
| J | `mergeTx` | journal の `deleted` を `src.deleted` だけにする（R-7） |
| S | `mergeTx` | snapshot で常に元の写しを残す（R-7） |

### 5.1 確かめた結果

2026-11-27、手元（Apple Silicon、ほかの作業で負荷の平均 30 前後）。どの写しも `QUINT_SPECS_DIR=<写し> bash tools/quint-check-specs.sh` の全体を流した。行の番号は直した後の `ci-runs.txt` の行（15 が run 1、18 が run 2、31 が run 8、32 が run 9）。

| 変異 | 直す前（297826be） | 直した後：終了コード | 直した後：落ちたテスト | 直した後：落ちた run |
| :-- | :-- | :-- | :-- | :-- |
| なし | 0 | 0 | — | — |
| A | 0 | 1 | `conflictJournalExtraTest` | 15（`versionsKept`） |
| B | 0 | 1 | `conflictSnapshotExtraTest` | 15（`versionsKept`） |
| C | 0 | 1 | `goneReservesTest`、`deleteBeforeAttemptGoneTest` | 31、32（`outcomesTruthful`） |
| D | 0 | 1 | `goneReservesTest`、`deleteBeforeAttemptGoneTest` | 32（`reservedRespected`） |
| E | 0 | 1 | `settleNeitherKeyTest` | 32（`outcomesTruthful`） |
| F1 | 0 | 1 | `lostResponseTest` | 15、31（`outcomesTruthful`） |
| F2 | 0 | 1 | `lostResponseTest` | 15、31（`outcomesTruthful`） |
| F3 | 0 | 1 | `settleNeitherKeyTest` | 32（`outcomesTruthful`） |
| P190 | 0 | 1 | `placementReverseTest` | — |
| J | 0 | 1 | `mergeJournalTargetDeletedTest` | — |
| S | 0 | 1 | `mergeSnapshotTargetLaterTest` | — |

直す前はどの変異も終了コード 0 で、`Every Quint spec passed.` で終わる。直した後はどの変異も、少なくとも1本の決まった筋書きが落とす。P190、J、S はランダムな run では見えない：P190 の分岐は宇宙に無い置き場所（j が知られていない形のキー）で、J と S は規則の列を見る不変条件が無いため（C-F7、別件）。

D がランダムな run でも落ちるのは、`reservedRespected` が予約する結果を `reserves` を呼ばずに書き出しているから。はじめ `reserves` を呼ぶ形で書いた写しでは、D は決まった筋書きでしか落ちなかった。

### 5.2 確かめる手順

1. 直す前（297826be）と直した後の `specs/key-migration/` を、作業場所の外に変異ごとに写す（`<tmp>/<before|after>/<変異>/key-migration/`）。
2. 写しごとに1か所を置き換える。置き換えが1か所だけであることを確かめる（置き換えの文字列は PR の本文に載せる）。
3. それぞれで `QUINT_SPECS_DIR=<tmp>/<before|after>/<変異> bash tools/quint-check-specs.sh` を流し、終了コードと、落ちたテストと `ci-runs.txt` の行を読む。
4. 変異の無い写し（`none`）は、直す前も直した後も終了コード 0 で通ることを確かめる。

## 6. CI の run（`ci-runs.txt`）

| 行 | 変更 | 回数 × 手数 | witness（直した後の回数） | 手元の所要 |
| :-- | :-- | :-- | :-- | :-- |
| 1 | `wConflictExtraJournal`、`wConflictExtraSnapshot` を足し、`wGaveUp` を外す | 400 × 120（今のまま） | 33、28 / 400 | 43秒 → 57秒 |
| 2 | `wGaveUp` を足す | 2,000 × 60（今のまま） | `wGaveUp` 23 / 2,000 | 35秒 → 38秒 |
| 8（新規） | `keyMigrationWriters`、`--init initW --step stepD`、4.2 の12個 | 400 × 120 | `wGone` 39 / 400 | 19秒 |
| 9（新規） | `keyMigrationWriters`、`--init initD --step stepD`、4.2 の12個 | 2,000 × 60 | `wGone` 344、`wGoneShared` 77、`wOutcomeUnknown` 9 / 2,000 | 12秒 |

8 では `wGoneShared` が 400 のうち 1 本、`wOutcomeUnknown` が 0 本なので、一覧の「4回以上」の決まりに合わず、witness に入れない。共有の行き先の `Gone` と「outcome unknown」は 9 と決まった筋書きで確かめる。

`wGaveUp` を 1 から 2 へ移した（レビュー R-4）。宇宙を広げると、同じ種でも乱数の列が変わる。1 の `wGaveUp` は 5 から 4 になり、決まり（4回以上）の境目に来た。レビューの実測では、種 `0x7` で 500 回にしても 4 のままだった。2 では 2,000 のうち 23 通る。

時間：run の合計は、直す前が手元で約125秒、直した後が約178秒（ほかの作業で負荷の平均 30 前後のとき）。`quint test` の増え方は1秒未満。CI のジョブは今5分22秒で、run の増え方を同じ割合と見て6分半〜7分、上限 15分に収まる。PR の CI で実測を確かめる。

## 7. 壊しうる入力

| 入力 | 扱い |
| :-- | :-- |
| 写しが1つで属性を持つ | `extra = 2`。移動で属性が一緒に移る（`contentsKept`、`versionsKept`） |
| 写しが2つで、片方だけが属性を持つ | `extra = 1`。`Conflict` になり、残りに出る（`conflict*ExtraTest`、witness） |
| 両方が同じ属性を持つ | `extra = 2`。まとまる（`mergeExtraOnBothTest`） |
| 規則の列だけが違う | 今までどおりまとまる。元と行き先のどちらが deleted か、どちらの `created` が大きいかの両側（`mergeJournalTest`、`mergeJournalTargetDeletedTest`、`mergeSnapshotTest`、`mergeSnapshotTargetLaterTest`） |
| 規則の列と属性の両方が違う | `Conflict`（`conflict*ExtraTest` は両方を変えている） |
| 全行が動ける表に属性が入る | `sameExtra` で違わない形だけにし、`progress` の前提を保つ |
| 勝者の行が、走査の後で試みの前に消える | `Gone`、行き先の予約（`goneReservesTest`、`deleteBeforeAttemptGoneTest`） |
| まとめ中（応答の消失の後）に行が消える | `attempt(..., merging = true)` も `Gone`（`goneReservesTest`） |
| 一時的な失敗の間に行が消える | `settle` が `Failed("outcome unknown")`（`settleNeitherKeyTest`、`wOutcomeUnknown` は `initD` の 2,000 のうち 9 本） |
| 共有しない行き先で消える（b） | `Gone`、予約しても誰も困らない（`stepD`） |
| `Moved` の後でアプリが行き先の行を消す | 記録の時点で確かめるので `outcomesTruthful` は破れない。次のラウンドでほかの候補がその行き先を取っても、勝者が予約しないので `reservedRespected` は破れない（`stepD`） |
| まとめの応答を失った後の試みと `settle` | `Merged` を返す（`lostResponseTest`、`outcomesTruthful`） |
| 選んでいない id の行を消す | `selectionRespected` を `stepD` に入れない理由（4.2） |
| 停止・再開の後に消す | `stepD` は `toolStep` の停止と再開をそのまま含む |

## 8. コード側の変更

要らない。移行ツールのコード（base-v2 の migration、journal-v2 と snapshot-v2 の入口）は変えない。

- `differences` は、もう一方の規則の列にあたる属性も、プラグインが書かない属性も、すでに比べている。仕様を広げると、仕様がコードに追いつく。
- `Gone` と「outcome unknown」の分岐と予約は、コードにすでにある。仕様はそれを、書き手のいるモデルで通るようにするだけ。
- 公開 API、保存済みのデータ、MiMa、上流への影響は無い。CI のワークフローとスクリプトも変えない。

## 9. 変えるファイル

| ファイル | 変更 |
| :-- | :-- |
| `specs/key-migration/keyMigration.qnt` | `Row` の説明、`carriesExtra`、`sameExtra`、`buildTable` の `extra`、`init` の `exJ`・`exS`、`Tool.misreported`、`Seen`、`truthful`、`record` の引数、`compared`、`versionsKept`、`contentsKept`、`outcomesTruthful`、`reservedRespected`、`safety`・`cheapSafety`、witness 2つ |
| `specs/key-migration/keyMigration_test.qnt` | `buildTable` の呼び出し（2か所）、筋書き8本、既存の2本への確かめ |
| `specs/key-migration/keyMigrationWriters.qnt` | `DELETE_ROWS`、`appDeleteStep`、`stepD`、`START_D`、`initD`、witness 3つ |
| `specs/key-migration/keyMigrationWriters_test.qnt` | `deleteBeforeAttemptGoneTest` と、冒頭の説明 |
| `specs/key-migration/ci-runs.txt` | 6節 |
| `docs/design/key-migration.md` | 8.1（テストの中身）、8.2（`extra`）、8.3（`compared`・`truthful`・`reservedRespected`・`stepD` の対応）、8.4（`settle` の読み取りの失敗を入れていないこと）、8.5（`stepD`） |
| `docs/design/key-migration-quint-results.md` | `stepD` の run、witness の回数、`versionsKept` と `contentsKept` の新しい意味 |
| `docs/design/quint-in-ci.md` | 「key-migration の run」の表（9本、回数、所要）。「設計の中で見つけたこと」からこの文書へのリンク |

## 10. 範囲の外

- 届かない分岐を網羅的に探すこと。今回は A〜E と、レビューが見つけた F1〜F3、P190、J、S を扱った。仕様の分岐ごとに witness を置き、0 の分岐を洗い出す作業は別件にする。
- 規則の列が保たれること（journal の deleted が戻らない、snapshot の大きい `created` が残る）を不変条件にすること（C-F7、別件のまま）。今回は決まった筋書きで両側を押さえるだけにした。
- snapshot の書き手（C-F8）。`stepD` のアプリは journal だけを消す。`attempt` と `settle` は表によらず同じなので、C と E は journal の側で捕まえられる。
- `quint verify`（C-F7）。

## 11. レビューの反映

設計の独立レビュー（antigravity、cafab570 が対象）の指摘を、次のように決めた。

| ID | 重さ | 決定 | 反映 |
| :-- | :-- | :-- | :-- |
| R-1 | 実装の前 | 直す | `truthful` に段の前の表、`merging`、`settle` か試みかを渡し、`Moved` と `Merged`、`Gone` と「outcome unknown」、「gave up」と「rejected」を区別した（3.4）。取り違えの変異 F1〜F3 を落とす筋書き（`lostResponseTest` への確かめ、`settleNeitherKeyTest`、`truthfulTest`）を足した |
| R-2 | 実装の前 | 直す | `roundTrip` の誤りを直し、破れる理由を表に書いた。`stepD` の run は `noDuplicateW` ではなく本体の `noDuplicate` を使う。`reservedRespected` を足して `safety` と `stepD` の run に入れた（3.5、4.2）。journal だけを見る性質と、snapshot をどこが確かめるかを書いた。`progress` は `stepD` でも成り立つので一覧に入れた |
| R-3 | 実装の中 | 直す | `wOutcomeUnknown` を足し、run 9 の witness に入れた（3.7、6） |
| R-4 | 実装の中 | 直す | `wGaveUp` を run 1 から run 2（`initSharedPair` 2,000 × 60、23 本）へ移した。回数を上げる案は、種 `0x7` では 500 回でも 4 本のままなので採らなかった |
| R-5 | 実装の中 | 直す | `placementReverseTest` に j の知られていない形のキーを、`lostResponseTest` に `merging` が真の試みと `settle` を足した |
| R-6 | 実装の中 | 直す | `contentsKept` の snapshot の `deleted` を「初めの写しのどれかの値」にした（3.6）。`settle` の読み取りの失敗を入れていないことを `key-migration.md` 8.4 に書いた |
| R-7 | 後で | 一部直す | まとめの規則の逆側の筋書き2本を足した。規則の列の不変条件は C-F7 のまま別件 |
| R-8 | 意見 | 採らない（変更不要） | `stepD` は本体を狭めない別のモデルで、「性質に除外を付ける」に当たらないという結論に同意する。設計は変えない |

