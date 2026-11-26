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
| C の性質 | 新しい不変条件 `outcomesTruthful`：ツールが記録した結果は、その時点のキーの中身と合う。`safety` に入れる |
| 決まった筋書き | 6本足す。A、B、C、4つ目の変異 E を、それぞれ少なくとも1本が必ず落とす |
| CI | `ci-runs.txt` の1番目に witness を2つ足し、`stepD` の run を2本足す。手元の所要は約30秒増える |
| 性質の除外 | 付けない。`versionsKept` は強くなる。狭める必要は出なかった |
| コード | 変えない |

見込みの大きさは 2。基準は [#1039](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1039)（Quint の CI）を 3 とする。#1039 より小さいのは、ワークフローとスクリプトを変えず、仕様・筋書き・一覧・文書だけを変えるため。手元の試作（作業場所の外の写し）で、下の数字はすべて測ってある。

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

ツールが結果を記録したとき、その時点のキーの中身が結果の言うとおりであることを確かめる。

| 結果 | 記録の直後の表 |
| :-- | :-- |
| `Moved`、`Merged` | 行き先に行があり、元のキーに無い |
| `Gone`、`Failed("outcome unknown")` | どちらのキーにも無い |
| `Blocked(l)` | 元のキーに行があり、行き先に別の行 `l` がある |
| `Conflict` | 両方のキーに行がある |
| `Failed("gave up")`、`Failed("rejected by DynamoDB")` | 元のキーに行がある |

書き手のモデルでは、記録の後でアプリが行を消しうる。そこで状態の不変条件にはせず、記録の時点で確かめる。`Tool` に履歴の欄 `misreported: bool` を足し、`record(tool, c, o, t)` が記録の直後の表 `t` で上の表を確かめて、合わなければ真にする。`attemptOk` は `e.table` を、`settle` は `tables.get(st)` を渡す。不変条件は `outcomesTruthful = STORES.forall(st => not(tools.get(st).misreported))` で、`safety` と `cheapSafety` に入れる。

コードとの対応：`moveOne` の各結果は、そのとき読んだキーから決まる（:1134-1203）。報告の `moved`、`merged`、`alreadyGone` はこの結果を数える（:886-893）。仕様の `attempt` は読み取りとトランザクションを1つの原子的な遷移にしている（`docs/design/key-migration.md` 8.4 の1）ので、記録の時点の表で比べてよい。

本体では、どの結果も構造上この表のとおりなので、変異が無ければ違反は起きない。変異 C・E は、`stepD` でどちらのキーにも行が無い状態に届くと、`Moved` を記録して落ちる。

### 3.5 witness

| witness | 置き場所 | 中身 |
| :-- | :-- | :-- |
| `wConflictExtraJournal`、`wConflictExtraSnapshot` | `keyMigration.qnt` | その表で `Conflict` があり、2つの写しの `payload` が同じ（属性だけが違いを生んだ） |
| `wGone` | `keyMigrationWriters.qnt` | 結果に `Gone` がある |
| `wGoneShared` | `keyMigrationWriters.qnt` | `Gone` の候補のほかに、同じ行き先を要る候補がこの実行にある |

これらを `ci-runs.txt` の `--witnesses` に入れる。仕様が変わって分岐にまた届かなくなると、witness が 0 になって CI が落ちる。今回の問題は「届かないことに誰も気づかない」ことだったので、この witness が再発を防ぐ。

## 4. 不変条件（除外なし）

### 4.1 本体（広げた宇宙、書き手なし）

すべての不変条件を、除外を付けずに今までどおり確かめる。試作の `safety`（`init` 400 × 120、`initSharedPair` 2,000 × 60）と `idempotent`（2本）は違反なしだった。

| 不変条件 | 広げた宇宙で保たれる理由 |
| :-- | :-- |
| `noLoss`、`noDuplicate`、`exactlyOnce`、`keysKnown`、`unknownStays` | 行の数と置き場所は属性に関係しない |
| `contentsKept` | 移動は行をまるごと移す。まとめで残る行は、初めの写しのどれかの属性を持つ |
| `versionsKept`（強めた） | まとめが起きるのは `compared` が等しいときだけなので、消える写しの値は残る写しが持つ |
| `converges`、`withinReference`、`finishedWithinReference`、`idempotent` | 基準の実行も同じ `attempt` を使う。属性が違う写しは、どちらでも `Conflict` になる |
| `progress` | 属性が違う写しのある起点は `movableStart` が前提から外す（今の `differ` のとおり）。mode 2 は `sameExtra` で写しを違わせない |
| `roundTrip` | 1.3.0 だけの表は写しが1つずつで、属性は行と一緒に戻る |
| `verifySound`、`verifyReverseShared`、`selectionRespected`、`finishedReported`、`unreversibleStays` | キーと id で決まり、属性に関係しない |
| `outcomesTruthful`（新規） | 3.4 のとおり、書き手のいないモデルでは構造上成り立つ |

### 4.2 `stepD`（契約を外したモデル）

`stepD` は既存の run 5〜7 と同じく、契約を外したモデルで成り立つものを確かめる別のモデルである。本体の不変条件は、本体の run がそのまま確かめる。`stepD` の run で確かめるのは次の10個で、試作（`initD` 2,000 × 60、`initW` 400 × 120）では違反なしだった。

`noLossW`、`noDuplicateW`、`outcomesTruthful`、`keysKnown`、`unknownStays`、`contentsKept`、`finishedReported`、`unreversibleStays`、`verifyReverseShared`、`verifySoundW`

ほかの不変条件は、アプリの削除そのものが破る。どれも新しい除外を付けずに、次のどれかで扱う。

| 不変条件 | `stepD` で破れる理由 | 扱い |
| :-- | :-- | :-- |
| `noLoss`、`exactlyOnce`、`versionsKept`、`verifySound` | アプリが消した行には写しが無い | 消した行を考えに入れた `noLossW`、`verifySoundW` が既にある |
| `deletedStayGone` | 2つの `DeleteItem` の間に行が移る（A3） | 既存の筋書き `hardDeleteMissesMovedEventTest` が反例を示す |
| `selectionRespected` | 実行の間に、選んでいない id の行をアプリが消す | ツールの性質で、本体で確かめる |
| `converges`、`withinReference`、`finishedWithinReference` | 基準の実行にアプリがいない | 本体で確かめる |

`progress` と `roundTrip` は試作の `stepD` でも違反しなかった。`initD` は 1.3.0 だけの表ではなく前提を満たさないため、確かめた意味が薄いので一覧には入れない。

## 5. 決まった筋書き（`quint test`）

| テスト | ファイル | 筋書き | 落とす変異 |
| :-- | :-- | :-- | :-- |
| `conflictJournalExtraTest` | `keyMigration_test.qnt` | journal の `k130 {deleted: 真}`、`kP {created: 1}`（`payload` は同じ）。`attempt` は `Conflict`、`fullRun` の後の表は初めと同じ、残りは k | A |
| `conflictSnapshotExtraTest` | 同上 | snapshot の `k130 {created: 2}`、`kP {created: 1, deleted: 真}`。同じく `Conflict` | B |
| `mergeExtraOnBothTest` | 同上 | 両方の写しが同じ属性を持つと、journal も snapshot もまとまる | 属性の有無だけで `Conflict` にする行き過ぎの直し |
| `goneReservesTest` | 同上 | 走査が h（hP）と i（iP）を見た後、h の行が消えている。`attempt` は `Gone`（まとめ中でも）、`roundStep` で h130 が予約され、次の `roundStep` で i は残りになり、表は変わらない | C、D（`reserves` から `Gone` を外す） |
| `settleNeitherKeyTest` | 同上 | 最後の試みの後、行がどちらのキーにも無い。`settleOutcome` は `Failed("outcome unknown")`、予約する | E |
| `deleteBeforeAttemptGoneTest` | `keyMigrationWriters_test.qnt` | `initD` から `--reverse` を始め、最初のラウンドの後にアプリが h を消す（`hardDeleteCurrent`）。h の試みは `Gone`、h130 は予約、次のラウンドで実行が終わり、i は残り、表は hP が消えただけ。`outcomesTruthful`、`noLossW`、`noDuplicateW` が成り立つ | C、D |

`mergeExtraOnBothTest` と既存の `mergeJournalTest`・`mergeSnapshotTest` は逆向きの確かめで、規則の列や同じ値の属性を違いとして扱う変異を落とす。`keyMigrationWriters_test.qnt` の冒頭の説明は「不変条件が破れることを確かめる」なので、成り立つことを確かめる `deleteBeforeAttemptGoneTest` の節にはそう書く。

### 5.1 確かめた結果（試作）

直す前は、どの変異も CI の検査全体を通る（1節）。直した後は、どの変異でも CI の検査全体が落ちる。

| 変異 | 直した後に落ちるもの |
| :-- | :-- |
| A | `conflictJournalExtraTest`。`safety` の run 1（`versionsKept`、数秒で） |
| B | `conflictSnapshotExtraTest`。`safety` の run 1（`versionsKept`） |
| C | `goneReservesTest`、`deleteBeforeAttemptGoneTest`。`stepD` の run（`outcomesTruthful`） |
| D | `goneReservesTest`、`deleteBeforeAttemptGoneTest` |
| E | `settleNeitherKeyTest`。`stepD` の run（`outcomesTruthful`） |

D の予約の違いは、書き手のモデルに基準の実行が無いので、ランダムな run の不変条件では見えない。決まった筋書きだけが落とす。

### 5.2 確かめる手順（実装の段で PR に結果を書く）

1. `specs/key-migration/` を作業場所の外に5つ写す（`<tmp>/<変異>/key-migration/`）。
2. 写しごとに1か所を変える。変える文字列は、1節の表の A・B・C・E と、D の `match o { | Failed(_) => true | Gone => true | _ => false }` → `match o { | Failed(_) => true | _ => false }`。置き換えが1か所だけであることを確かめる。
3. それぞれで `QUINT_SPECS_DIR=<tmp>/<変異> bash tools/quint-check-specs.sh` を流し、終了コードが 1 で、上の表の名前が `::error` の行に出ることを確かめる。
4. 直す前の main の仕様でも同じ写しを作って流し、終了コード 0 と `Every Quint spec passed.` を確かめる。
5. 変えていない直した後の仕様が、終了コード 0 で通ることを確かめる。

## 6. CI の run（`ci-runs.txt`）

| 行 | 変更 | 回数 × 手数 | witness |
| :-- | :-- | :-- | :-- |
| 1 | `--witnesses` に `wConflictExtraJournal`、`wConflictExtraSnapshot` を足す | 400 × 120（今のまま） | 33、28 / 400 |
| 8（新規） | `keyMigrationWriters`、`--init initW --step stepD`、4.2 の10個 | 400 × 120 | `wGone` 39 / 400 |
| 9（新規） | `keyMigrationWriters`、`--init initD --step stepD`、4.2 の10個 | 2,000 × 60 | `wGone` 344、`wGoneShared` 77 / 2,000 |

8 では `wGoneShared` が 400 のうち 1 本なので、一覧の「4回以上」の決まりに合わず、witness に入れない。共有の行き先の `Gone` は 9 と決まった筋書きで確かめる。

時間（手元、2026-11-27、Apple Silicon）：run 1 は 43秒から 47〜49秒、8 は 16秒、9 は 8〜10秒。`quint test` の増え方は1秒未満。スクリプト全体は、直す前の run の合計（約133秒）から約30秒増える。CI のジョブは今5分22秒で、増え方を同じ割合と見て6分前後、上限 15分に収まる。

宇宙を広げると、同じ種でも乱数の列が変わる。試作では run 1 の `wGaveUp` が 5 から 4 になった。決まり（4回以上）は満たすが、境目にある。実装で 4 を下回ったら、witness を外さず、run 1 の回数を 500 に上げる（約12秒増える）。

## 7. 壊しうる入力

| 入力 | 扱い |
| :-- | :-- |
| 写しが1つで属性を持つ | `extra = 2`。移動で属性が一緒に移る（`contentsKept`、`versionsKept`） |
| 写しが2つで、片方だけが属性を持つ | `extra = 1`。`Conflict` になり、残りに出る（`conflict*ExtraTest`、witness） |
| 両方が同じ属性を持つ | `extra = 2`。まとまる（`mergeExtraOnBothTest`） |
| 規則の列だけが違う | 今までどおりまとまる（`mergeJournalTest`、`mergeSnapshotTest`） |
| 規則の列と属性の両方が違う | `Conflict`（`conflict*ExtraTest` は両方を変えている） |
| 全行が動ける表に属性が入る | `sameExtra` で違わない形だけにし、`progress` の前提を保つ |
| 勝者の行が、走査の後で試みの前に消える | `Gone`、行き先の予約（`goneReservesTest`、`deleteBeforeAttemptGoneTest`） |
| まとめ中（応答の消失の後）に行が消える | `attempt(..., merging = true)` も `Gone`（`goneReservesTest`） |
| 一時的な失敗の間に行が消える | `settle` が `Failed("outcome unknown")`（`settleNeitherKeyTest`、`stepD` で 2,000 のうち 9 本） |
| 共有しない行き先で消える（b） | `Gone`、予約しても誰も困らない（`stepD`） |
| `Moved` の後でアプリが行き先の行を消す | 記録の時点で確かめるので `outcomesTruthful` は破れない（`stepD`） |
| 選んでいない id の行を消す | `selectionRespected` を `stepD` に入れない理由（4.2） |
| 停止・再開の後に消す | `stepD` は `toolStep` の停止と再開をそのまま含む |

## 8. コード側の変更

要らない。移行ツールのコード（base-v2 の migration、journal-v2 と snapshot-v2 の入口）は変えない。

- `differences` は、もう一方の規則の列にあたる属性も、プラグインが書かない属性も、すでに比べている。仕様を広げると、仕様がコードに追いつく。
- `Gone` と「outcome unknown」の分岐と予約は、コードにすでにある。仕様はそれを、書き手のいるモデルで通るようにするだけ。
- 公開 API、保存済みのデータ、MiMa、上流への影響は無い。CI のワークフローとスクリプトも変えない。

## 9. 変えるファイル（実装の段）

| ファイル | 変更 |
| :-- | :-- |
| `specs/key-migration/keyMigration.qnt` | `Row` の説明、`carriesExtra`、`sameExtra`、`buildTable` の `extra`、`init` の `exJ`・`exS`、`Tool.misreported`、`truthful`、`record` の引数、`compared`、`versionsKept`、`outcomesTruthful`、`safety`・`cheapSafety`、witness 2つ |
| `specs/key-migration/keyMigration_test.qnt` | `buildTable` の呼び出し（2か所）、筋書き5本 |
| `specs/key-migration/keyMigrationWriters.qnt` | `DELETE_ROWS`、`appDeleteStep`、`stepD`、`START_D`、`initD`、witness 2つ |
| `specs/key-migration/keyMigrationWriters_test.qnt` | `deleteBeforeAttemptGoneTest` と、冒頭の説明 |
| `specs/key-migration/ci-runs.txt` | 6節 |
| `docs/design/key-migration.md` | 8.2（`extra`）、8.3（`truthful`・`compared`・`stepD` の対応）、8.5（`stepD`） |
| `docs/design/key-migration-quint-results.md` | `stepD` の run、witness の回数、`versionsKept` の新しい意味 |
| `docs/design/quint-in-ci.md` | 「key-migration の run」の表（9本、回数、所要）。「設計の中で見つけたこと」からこの文書へのリンク |

## 10. 範囲の外

- 届かない分岐を網羅的に探すこと。今回は A・B・C と、同じ原因の E だけを扱った。仕様の分岐ごとに witness を置き、0 の分岐を洗い出す作業は別件にする。
- snapshot の書き手（C-F8）。`stepD` のアプリは journal だけを消す。`attempt` と `settle` は表によらず同じなので、C と E は journal の側で捕まえられる。
- `quint verify`（C-F7）。
