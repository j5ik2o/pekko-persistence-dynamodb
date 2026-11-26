# 設計：CI で `specs/` の Quint 仕様を検査する

`specs/key-migration/` の Quint 仕様（[#1034](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1034) で入った）を、PR ごとに CI で検査する `quint` ジョブを足す。今は CI で流していないので、仕様が壊れても（型の誤り、テストの期待の誤り、不変条件の反例）気づけない。形は [j5ik2o/fraktor-ng-rs#216](https://github.com/j5ik2o/fraktor-ng-rs/pull/216) に合わせる：Quint を lockfile で固定し、評価器を sha256 で確かめ、typecheck・test・run を流し、`timeout-minutes` を付ける。

この文書は PR 本文の下書きを兼ねる。設計の独立レビュー（antigravity）の指摘は、末尾の「レビューの反映」にまとめた。

## 主な決定

| 項目 | 決めたこと |
| :-- | :-- |
| 走る条件 | `specs/**`、検査のスクリプト、`package.json`・`package-lock.json`、`ci.yml`、仕様が説明するコード（下の表の10か所）が変わった PR。push と workflow_dispatch では常に走る |
| 流すもの | 全 `.qnt` の `quint typecheck`、全 `*_test.qnt` の `quint test`、仕様ごとの一覧ファイルに書いた `quint run`（7本、種は `0x7` に固定） |
| 流さないもの | `quint verify`（Apalache）。`fullRun` の範囲が定数でなく、Apalache が受け付けない（結果の文書、C-F7） |
| 版の固定 | Quint 0.32.0 を `package.json` の devDependencies に足し、`npm ci` で入れる。評価器 v0.6.0 は tar.gz と実行ファイルの sha256 を `ci.yml` に固定する |
| 取得の失敗への備え | 評価器は `actions/cache` に残し、無いときだけジョブのトークンで取得し、3回まで再試行する。npm は `setup-node` のキャッシュと `npm ci` の再試行の設定。このジョブは Maven Central を使わない |
| 時間の上限 | `timeout-minutes: 15`。所要の見込みはジョブ全体で4〜6分 |
| 必須のチェック | `PR CI Required` に入れる。走る条件に当たらない PR では、ジョブは省略され、省略を成功として扱う |
| 壊した仕様での確認 | 手元で12通りの壊し方を流す。CI では、テストが通って `quint run` だけが落ちる1行の変更を一時的なコミットで push し、落ちたのを確かめてから revert する |

見込みの大きさは 3。基準は [#1024](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1024)（mima の依存のキャッシュと再試行）を 2 とする。#1024 より大きいのは、ジョブとスクリプトと一覧ファイルの3つを新しく作り、集約の配線と tools のテストを直し、CI で壊した仕様を1回流すため。

## 何を足すか

| ファイル | 変更 |
| :-- | :-- |
| `.github/workflows/ci.yml` | `quint` ジョブを足す。`changes` ジョブに出力 `quint` と `quint_code_files` を足す。`required` ジョブの `needs` と確認の段に `quint` を足す |
| `.github/workflows/tools-tests.yml` | `Check the test counts` の `expected` を、足すテストの数だけ増やす（この1行だけ） |
| `tools/quint-check-specs.sh` | 検査の本体。ジョブはこれを呼ぶだけで、手元でもリポジトリ直下で同じものが流れる。`tools/` に置くので、変えると既存の規則で `tools` も選ばれ、そのテストが流れる（R-2） |
| `specs/key-migration/ci-runs.txt` | この仕様の `quint run` の一覧（下の「run の一覧」） |
| `package.json`・`package-lock.json` | `@informalsystems/quint` を `0.32.0` に固定して devDependencies へ足す |
| `tools/tests/test_pr_ci_required.py` | `quint` の結果の組み合わせのテストを足し、既存のテストの環境に `RUN_QUINT=false`・`QUINT_RESULT=skipped` を足す |
| `tools/tests/test_pr_test_merge.py` | `verify_steps` に `quint` ジョブを足す（検証済みのテストマージを取り出す段が、ほかのジョブと同じであることを確かめる） |
| `tools/tests/test_quint_check_specs.py` | スクリプトの決まりのテスト。偽の `quint` を置いて、出力の読み方と、落とすべき入力で落ちることを確かめる |
| `tools/tests/test_quint_job.py` | `ci.yml` から段を取り出して流すテスト。`changes` の `quint` の判定、コードだけが変わったときの注意の段、評価器の取得の再試行（偽の `gh`） |
| `docs/design/key-migration.md` | 8.3 の対応表に `JournalAggregateStore.highestSequenceNr` を足す。8 に「不変条件を足すときは `safety` に入れるか `ci-runs.txt` に行を足す」を足す |
| `docs/design/quint-in-ci.md` | この文書 |

`.github/workflows` の変更は、ジョブの追加と、それを集約に入れるための配線だけにする。ほかのジョブの条件、push の `paths`、マトリクスは変えない。`tools-tests.yml` の `expected` を変えるのは、集約の確認を変えると tools のテストも足すことになり、`Check the test counts` が件数を固定しているため。`required` の確認の段を変えても、`ci.yml` の変更だけでは `tools` は選ばれない（`tools/*`、`tools-tests.yml`、`.github/scripts/*` だけが選ぶ）。この PR では `tools/` も変えるので、tools のテストは同じ PR の CI で流れる。

## 走る条件

`changes` ジョブの `Select PR checks` に `quint` の判定を足す。PR では、次のどれかが変わったときに `quint=true` にする。

| パス | 理由 |
| :-- | :-- |
| `specs/*` | 仕様と一覧ファイル |
| `tools/quint-check-specs.sh` | 検査の本体 |
| `package.json`、`package-lock.json` | Quint の版 |
| `.github/workflows/ci.yml` | ジョブの定義 |
| `base/base-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/migration/*` | `ResolverKeyPlan`、`KeyMigration`、`KeyVerification`（`docs/design/key-migration.md` の 8.3 の表） |
| `journal/journal-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/migration/*` | journal の移行の入口 `JournalKeyMigration` |
| `snapshot/snapshot-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/migration/*` | snapshot の移行の入口 `SnapshotKeyMigration` |
| `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/store/JournalRowStore.scala` | 書き手のモデルの `recoverAndWrite`、`hardDeleteCurrent`、`hardDeleteLegacy` が説明する書き込みと削除 |
| `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/aggregate/JournalAggregateStore.scala` | JournalHead が有効なときの復旧（`highestSequenceNr` が journal と head の大きい方を取る）。書き手のモデルの `headEnabled`（R-3） |
| `base/base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/model/PersistenceIdKeyParts.scala` | 移行の入口がキーを組み立てる関数。仕様の宇宙のキーの関係（PlainText のキーは一意、#831 のキーは他の形と重ならない）の前提（R-3） |
| `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/PartitionKeyResolver.scala`、同じ場所の `SortKeyResolver.scala` | 同上（journal のキー） |
| `snapshot/snapshot-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/PartitionKeyResolver.scala`、同じ場所の `SortKeyResolver.scala` | 同上（snapshot のキー） |

push（main）と workflow_dispatch では、`scala` と同じく常に `quint=true` にする。push の `paths` には `.qnt` を足さない。足すと、仕様だけを変えた push でも Scala のジョブが全部走り、ほかの CI の挙動が変わるため。main は保護されていて、仕様の変更は PR の CI を通ってから入る。

### コードだけの変更でも走らせ、注意を出す

このジョブは仕様だけを検査し、Scala のコードと仕様を突き合わせない。種を固定するので、仕様が変わらなければ、コードだけが変わった PR で流しても結果は main と同じになる。それでも、コードの変更で走らせる。価値は検査の結果ではなく、次の注意にある。

- 仕様が説明するコードが変わり、`specs/` が変わっていない PR では、`quint` ジョブの段 `Point out code that the specs describe` が `::warning::` を出し、ジョブの要約（`GITHUB_STEP_SUMMARY`）に、変わったファイルと `docs/design/key-migration.md` の 8.3（Quint とコードの対応）を並べる。PR の作者とレビューボットが、仕様を直すか、直さない理由を書くきっかけにする
- 入力の経路（R-4）：判定は `changes` ジョブの `Select PR checks` で、`quint` の判定と同じ `git diff "$BASE_SHA...$HEAD_SHA"` の一覧から行う。仕様が説明するコードのパスに当たり、`specs/` に当たるものが1つも無いときだけ、当たったパスを改行で区切って出力 `quint_code_files` に入れる。PR でないイベントでは空にする。注意の段は `if: needs.changes.outputs.quint_code_files != ''` で走り、ファイルの一覧は環境変数で受け取る。パスの一覧を持つのは `Select PR checks` の1か所だけにする
- 検査のスクリプトは仕様の検査だけを受け持ち、変更の一覧は読まない。手元で流しても注意は出ない
- 注意は落ちる理由にしない。動きを変えないリファクタリングでも仕様の変更を強いることになるため

所要は4〜6分で、9本の test のジョブより短く並行に終わるので、待ち時間は増えない。実装が仕様の性質を守るかは、これまでどおり Scala のテスト（`KeyMigrationSpec`、`JournalKeyMigrationSpec`、`ReleasedRowsMigrationSpec` など）が見る。

## 流すもの

スクリプトは、まず `specs/` の下の `.qnt` をすべて探し、`specs/<ディレクトリ>/<ファイル>.qnt` の深さに無いものが1つでもあれば落とす（`specs/` の直下や2段より深い場所の仕様が黙って外れるのを防ぐ、R-7）。そのうえで `specs/*/` のディレクトリごとに、次を順に流す。1つのディレクトリや1つの段が落ちても残りは流し、最後に落ちたものを `::error file=…::` で並べて非ゼロで終える。

1. 版の確認：`./node_modules/.bin/quint --version` が、`package-lock.json` の `node_modules/@informalsystems/quint` の `version` と同じであること。`npx` のように別の版を取りに行く経路は使わない
2. `quint typecheck`：ディレクトリのすべての `.qnt`
3. `quint test`：すべての `*_test.qnt`。`--main` はファイル名と同じ名前のモジュール（今の3本はすべてこの形）。`--seed 0x7 --max-samples 10000` を付ける
4. `quint run`：ディレクトリの `ci-runs.txt` の行ごと

### test の決まり

`quint test` は `--match` が無いと、名前が `Test` で終わる `run` しか流さない。名前が違う `run` は黙って外れ、期待が壊れていても通る（fraktor-ng-rs#216 の設計レビューの F1）。そこで fraktor と同じく、`quint parse` の IR から main モジュールの `run` の名前を取り、次を確かめる。

- ディレクトリに `*_test.qnt` が1本以上ある
- ファイル名と同じ名前のモジュールがある（無ければ main を推測せずに落とす）
- `run` が1本以上あり、どの名前も `Test` で終わる
- `quint test` の `N passing` が `run` の数と同じ

`--seed` を付けると `quint test` の `--max-samples` の既定は 1 になる（0.32.0 の `--help`）。`--seed` は、`quint run` と同じ `0x7` にする。`keyMigrationGaveUp_test.qnt` の3本はランダムな筋書きを 10,000 回流すので、`--max-samples 10000` を必ず付ける。手元で2回流し、どちらも `finishThenRerunTest`・`stopThenRerunTest`・`rejectedOnSharedTargetTest` が各 10,000 回、計4件が通った（約10秒）。

### run の一覧

`fraktor` は「どの仕様も `invariant` という名前の不変条件を持つ」決まりにして glob で一律に流した。PPD の仕様は、初期状態（`init`、`initSharedPair`、`initG` など）と不変条件（`safety`、`idempotent`、書き手のモデルの4つ）を run ごとに選ぶ必要があり、この決まりに合わない。そこで、ディレクトリごとに `ci-runs.txt` を置き、1行に1つの `quint run` を書く。

```
# <期待> <ファイル> <quint run に渡す引数>
pass keyMigration.qnt --invariant safety --max-samples 400 --max-steps 120 --seed 0x7 --witnesses wMoved wMerged ...
violation keyMigrationWriters.qnt --main keyMigrationWriters --init initGStale --step stepG --invariant noLossW ...
```

スクリプトは、各行について次を確かめる。

- 行の読み方（R-6）：`eval` を使わず、`set -f` のもとで空白で区切って配列にする。空行と `#` で始まる行は飛ばす。1つ目は `pass` か `violation`、2つ目はそのディレクトリにある `.qnt` のファイル名（`/` を含まない）だけを許す
- 引数に `--seed`、`--max-samples`、`--max-steps` がある。`--seed` だけだと、`quint run` の `--max-samples` の既定が 1 になり、1本のトレースしか見ない（0.32.0 の `--help`）。`--max-samples` の値は 2 以上、`--max-steps` の値は 1 以上。`--flag 値` と `--flag=値` の両方の形を読む
- `--invariant` か `--invariants` がある。`--invariant` の既定は `true` で、書き忘れると何も確かめずに通るため。値が `true` の行も落とす
- `--verbosity` は書かせない。スクリプトが `--verbosity=2` を付ける。`Trace length statistics` の行と違反のトレースは、`--verbosity=2` のときだけ出る
- 出力は一時ファイルに書き、ログには要約（所要時間、`[ok]` か `[violation]` の行、`Trace length statistics`、witness の行、種の行）だけを出す。落ちたときは出力の末尾も出す。`--verbosity=2` の出力は1本で数百 KB から 1.5 MB あり、そのまま出すとログが読めないため（R-6）
- `[ok] No violation found` と `[violation]` は、行の先頭に限らずに探す。CI のように端末でない出力では、進捗の表示の直後に改行なしで続くことがある（R-6）
- `pass` の行：終了コードが 0 で、`[ok] No violation found` が出る。`Trace length statistics: max=N` の N が 2 以上。1 以下なら、どのトレースも1手も進んでおらず、不変条件は初期状態でしか見ていない（fraktor-ng-rs#216 のレビューの F2）。この行が出なければ落とす
- `violation` の行：終了コードが 0 以外で、`[violation]` と `Invariant violated` が出る。名前の誤り（`QNT404`）など、ほかの理由で落ちたものは成功にしない。違反を見つけた run は `Trace length statistics` を出さないので（R-1、0.32.0 で実測）、代わりに反例のトレースに `[State 1]` があることを確かめる。初期状態ですぐ破れる壊れ方（`[State 0]` だけ）は落とす
- `--witnesses` に書いた witness は、どれも1回以上通る。一覧には、通ることを確かめたい経路の witness で、決めた種で4回以上通るものだけを書く。1回しか通らない witness は、仕様に無害な変更で乱数の使われ方がずれただけで 0 回になりうるので入れない（R-8）。種が固定なので、回数は毎回同じになる。witness が 0 回で落ちたときは、仕様の誤りか標本の不足かを切り分ける（`ci-runs.txt` の先頭のコメントに書く）

ディレクトリの `*_test.qnt` でない `.qnt` は、どれも `ci-runs.txt` のどこかの行のファイルとして現れること。現れない仕様があれば落とす（仕様を足したのに run を書き忘れた場合）。ディレクトリに `.qnt` があって `ci-runs.txt` が無い場合も落とす。

### key-migration の run（9本）

[docs/design/key-migration-quint-results.md](key-migration-quint-results.md) の run から、CI の時間に収まる回数を選んだ。不変条件を1本ずつ流す代わりに、本体の不変条件をまとめた `safety`（届かない分岐の直しの後は19個）を流す（`idempotent` だけは `safety` に入っていないので別に流す）。種は結果の文書と同じ `0x7`。所要は 2026-11-26 に手元（Apple Silicon、14コア、負荷の平均 22 前後）で測った実時間。

| # | 期待 | モデル | 不変条件 | 回数 × 手数 | 確かめる witness | 手元の所要 |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| 1 | pass | `keyMigration`、`init` | `safety` | 400 × 120 | `wMoved` `wMerged` `wBlocked` `wConflict` `wRejected` `wProgress` `wFinishedAfterStop` `wRoundTrip` `wVerifyForward` `wVerifyReverse` `wIndexLag` `wMergedAfterLost` `wCycleRemaining` `wFinishedForward` `wConflictExtraJournal` `wConflictExtraSnapshot` | 43秒（直しの後 57秒） |
| 2 | pass | `keyMigration`、`initSharedPair` | `safety` | 2,000 × 60 | `wGaveUpShared` `wRejectedShared` `wFinishedAfterStop` `wGaveUp` | 34秒（直しの後 38秒） |
| 3 | pass | `keyMigration`、`init` | `idempotent` | 300 × 60 | なし | 12秒 |
| 4 | pass | `keyMigration`、`initSharedPair` | `idempotent` | 500 × 60 | なし | 11秒 |
| 5 | pass | `keyMigrationWriters`、`initG` / `stepG` | `noLossW` `noDuplicateW` `deletedStayGone` `verifySoundW` | 2,000 × 80 | `wAppStarted` | 7秒 |
| 6 | pass | `keyMigrationWriters`、`initGStaleHead` / `stepGForward` | 同じ4つ | 5,000 × 80 | `wAppStarted` | 15秒 |
| 7 | violation | `keyMigrationWriters`、`initGStale` / `stepG` | `noLossW` | 2,000 × 80 | なし | 3秒 |
| 8 | pass | `keyMigrationWriters`、`initW` / `stepD` | `noLossW` `noDuplicate` `outcomesTruthful` `reservedRespected` `keysKnown` `unknownStays` `contentsKept` `finishedReported` `unreversibleStays` `verifyReverseShared` `verifySoundW` `progress` | 400 × 120 | `wGone` | 19秒 |
| 9 | pass | `keyMigrationWriters`、`initD` / `stepD` | 8 と同じ12個 | 2,000 × 60 | `wGone` `wGoneShared` `wOutcomeUnknown` | 12秒 |

- 1 で通った witness の回数（400 のうち）：`wMoved` 400、`wMerged` 259、`wBlocked` 346、`wConflict` 138、`wRejected` 292、`wGaveUp` 5、`wProgress` 189、`wFinishedAfterStop` 78、`wRoundTrip` 177、`wVerifyForward` 11、`wVerifyReverse` 159、`wIndexLag` 400、`wMergedAfterLost` 21、`wCycleRemaining` 239、`wFinishedForward` 385。`wGaveUpShared` と `wRejectedShared` は 0 で、結果の文書の 1,000 回と同じく `init` では通らないので、2 で確かめる
- 2 で通った回数（2,000 のうち）：`wGaveUpShared` 10、`wRejectedShared` 489、`wFinishedAfterStop` 382。1,000 回では `wGaveUpShared` が 4 だったので、2,000 回に増やした（R-8）
- 届かない分岐の直し（[設計](key-migration-quint-unreachable-branches.md)）で、1 に `wConflictExtraJournal` 33 と `wConflictExtraSnapshot` 28 を足し、`wGaveUp` を 1 から 2 へ移した。直しで `init` の乱数の使われ方が変わり、1 の `wGaveUp` は 5 から 4 になって決まりの境目に来た。2 では 2,000 のうち 23 通る。直しの後の 1 のほかの回数：`wMerged` 231、`wBlocked` 353、`wConflict` 176、`wRejected` 282、`wFinishedAfterStop` 77、`wRoundTrip` 179、`wVerifyForward` 10、`wVerifyReverse` 158、`wMergedAfterLost` 22、`wCycleRemaining` 248、`wFinishedForward` 381
- 8 と 9 は、ツールが走る間にアプリが削除だけをするモデル（`stepD`）。`Gone` と `settle` の「outcome unknown」に届く。8 で `wGone` は 400 のうち 39、9 で `wGone` 344、`wGoneShared` 28、`wOutcomeUnknown` 9（2,000 のうち）。8 の `wGoneShared` と `wOutcomeUnknown` は 0 なので、8 には入れない
- `wCleanAfterGaveUp` は、1 で 1 回、2 で 2 回しか通らないので一覧に入れない（R-8）。この経路は `keyMigrationGaveUp_test.qnt` の `finishThenRerunTest` が決まった筋書きで毎回通す
- 4 は、`idempotent` を `rejectShared` が真になりうる `initSharedPair` でも流すために足した（R-12）
- 5 と 6 の `wAppWrote` は 0 で、一覧に入れない。手順を守るアプリのモデルでは、`recoverAndWrite` はインデックスが行を示していないときだけ起き、それが起きないことがこのモデルの結論だから
- 7 は負の対照。古い値を返す読み取りで JournalHead が無効なら、手順を守っても上書きが起きる（結果の文書の A1）。この run が違反を見つけなくなったら、書き手のモデルが何も確かめなくなった兆しなので、落とす
- 結果の文書の run（`safety` の 1,000 × 120 で 123秒、不変条件ごとの 500 × 120 など）より回数を減らした。CI の run は、仕様の変更で反例が出ないこと、経路が通ることの回帰の検査で、広い探索は結果の文書の run が受け持つ
- 合計は手元で約125秒（直しの後、ほかの作業で負荷の平均 30 前後の手元で約178秒）。`--seed` を付けると評価器は1スレッドで動く（レビューの実測で CPU 約 102%）ので、`ubuntu-latest` の1コアの速さで決まる。手元の2〜3倍の4〜6分と見る。実装の後、CI の実測で回数を見直す

### 種を固定する

fraktor は種を乱数のままにし、回を重ねて広く探す方を選んだ。PPD では種を `0x7` に固定する。依頼が「決まった種で再現できること」で、`--witnesses` の回数を1回以上と確かめる決まりは、種が固定でないと揺れて落ちるため。

- 再現：手元で `safety` の 200 × 120 を `--n-threads` 1、4、8、既定（14）で流し、witness の回数（`wMoved` 200、`wGaveUp` 5、`wBlocked` 170）と最後の行の種がすべて同じだった。スレッドの数は結果を変えない
- 実装の後、CI（Linux x64）の witness の回数が手元（macOS arm64）と同じかを確かめ、PR に書く。違っていたら、再現は Linux x64 の評価器でだけ成り立つと書き、手元で再現する手順（Docker の `node:22` で同じスクリプトを流す）を添える
- 違反が出たら、ログのコマンドをそのまま流せば同じ反例が出る。種を変えて広く探すのは、結果の文書の run の役目にする

## 版の固定と取得

### Quint

`package.json` の devDependencies に `"@informalsystems/quint": "0.32.0"` を足し、`package-lock.json` を `npm install` で作り直す。既存の `aislop` の版は変えない。ジョブは `actions/setup-node`（Node 22、`cache: npm`）の後に `npm ci --no-audit --no-fund` を流す。npm の取得は `--fetch-retries=3 --fetch-retry-mintimeout=10000 --fetch-retry-maxtimeout=60000` で再試行する。待つ時間は最悪でも 10 + 60 + 60 = 130秒で、15分の上限に収まる（R-9）。lockfile に入った包みは、それぞれの `integrity`（sha512）を npm が確かめる。

### 評価器

Quint 0.32.0 の既定の評価器（Rust、v0.6.0）は npm の包みに入っていない。初めて使うときに、認証なしの GitHub releases API の1ページ目から探して `~/.quint/rust-evaluator-v0.6.0/` へ取得する。新しいランナーでは毎回この API を呼ぶので、認証なしの上限（IP ごとに1時間60回）で落ちうる。リリースが増えて v0.6.0 が1ページ目から外れると、毎回落ちるようになる（fraktor-ng-rs#216 の調べ）。そこで fraktor と同じ形にする。

0. actions の版は、既存の `ci.yml` に合わせる（`actions/checkout@v5`、`actions/cache@v4`、`actions/setup-node@v4`）
1. 評価器の版は、入った Quint の `QUINT_EVALUATOR_VERSION`（`dist/src/rust/binaryManager.js`）から読む。lockfile と自動で揃う
2. Linux X64 以外のランナーでは、この段で落とす（固定する digest が x86_64 Linux のものだけなので）
3. 版ごとに digest を `ci.yml` に固定する。v0.6.0 の値は、2026-11-26 に `gh release download evaluator/v0.6.0 --repo informalsystems/quint` で取得して確かめた。fraktor-ng-rs#216 の値と同じ
   - tar.gz（`quint_evaluator-x86_64-unknown-linux-gnu.tar.gz`）：`61755a09d5052d93a4e75e840059edfd0d3674aeda164b9d2464be3d6e21b1c2`
   - 実行ファイル（`quint_evaluator`）：`b2efdeac5713d153e41bf2143b94ed75d888fdd5637f4a5d61a04c695313510a`
   - digest の無い版は `::error::` で落ちる。Quint を上げて評価器の版が変わったら、digest を足す
4. `actions/cache` で `~/.quint/rust-evaluator-<版>/` を残す。鍵は OS、CPU、版、実行ファイルの digest
5. キャッシュが無いときだけ、ジョブのトークンで `gh release download --clobber` する。失敗したら 30秒、60秒と間をあけて3回まで試す。試すたびに、前の回が残した途中のファイルを `rm -f` で消してから取る（R-5）。取得した tar.gz は展開の前に `sha256sum -c` で確かめる。digest が合わないときは再試行せずに落とす（取得の失敗ではなく、資産が差し替わったため）
6. 実行ファイルは、キャッシュから戻したときも含めて毎回 `sha256sum -c` で確かめる。Quint は実行ファイルがあれば API を呼ばない
7. Quint は環境変数 `QUINT_HOME` があると `~/.quint` の代わりにそこを使う。ジョブは `QUINT_HOME` を設定しない前提で、キャッシュの場所を `~/.quint` にする。ジョブのコメントにそう書く（R-12）

版を上げる手順（R-12）：`package.json` の版を変えて `npm install` で lockfile を作り直す → 入った Quint の `QUINT_EVALUATOR_VERSION` を読む → 評価器が変わったら、`gh release download` で x86_64 Linux の tar.gz を取り、tar.gz と展開した実行ファイルの sha256 を出して `ci.yml` の `case` に足す → `tools/quint-check-specs.sh` を流し、witness の回数と所要を確かめる（評価器が変わると、同じ種でも乱数の列が変わりうる）→ `ci-runs.txt` の回数と witness を直す。この手順は `ci.yml` のコメントにも書く

今日の Maven Central の 429 は、このジョブには関係しない。sbt を使わず、取得するのは npm レジストリと GitHub の releases だけだから。

## 時間の上限と所要

`timeout-minutes: 15`。見込みは次のとおりで、上限は合計の2〜3倍にした。

| 段 | 見込み |
| :-- | :-- |
| checkout と検証済みのテストマージの取り出し | 10〜20秒 |
| `setup-node` と `npm ci`（キャッシュあり） | 15〜30秒 |
| 評価器（キャッシュあり／なし） | 数秒／20秒 |
| typecheck（5本） | 7秒前後（手元で各1.3秒） |
| test（3本） | 20〜40秒（手元で 1.7、10.5、2.0秒） |
| run（9本） | 5〜7分（手元で約125秒、届かない分岐の直しの後は約178秒） |
| 合計 | 4〜6分（キャッシュが無く、取得を再試行したときは7〜8分） |

上限に当たるのは、評価器が返ってこない、仕様の変更で状態が増えて run が極端に遅くなった、のどちらか。スクリプトは段ごとに所要時間を出すので、遅くなった段がログで分かる。

## 必須のチェックに入れる

入れる。必須にしないと、仕様が落ちていてもマージできる。ブランチの保護の必須のチェックは `PR CI Required` と9本の test と `Check unresolved comments` で、`quint` は `PR CI Required` の確認の段に入れる。ブランチの保護の設定は変えない。

`required` ジョブに足すもの：

- `needs` に `quint`
- 環境変数 `RUN_QUINT: ${{ needs.changes.outputs.quint }}`、`QUINT_RESULT: ${{ needs.quint.result }}`
- 今の `case` とは別の `case` として、`case "$RUN_QUINT:$QUINT_RESULT" in true:success|false:skipped) ;; *) …exit 1 ;; esac`。今の `case` に組み合わせを足すと、パターンの数が倍になるので分ける
- jobs API で確認する式に `(if $quint == "true" then passed("quint"; "<検査の段の名前>") else true end)` を足し、`jq` に `--arg quint "$RUN_QUINT"` を渡す

走る条件に当たらない PR では、`quint=false` で `quint` ジョブは省略され（`skipped`）、`false:skipped` を成功として扱う。`tools` と同じ扱い。`quint=true` なのに省略された、または `quint=false` なのに走った場合は落とす。

tools のテストに足すもの（`test_pr_ci_required.py`）：`quint` が走って通ったときに通る、`quint` が選ばれたのに省略されたときに落ちる、`quint` のジョブか検査の段が失敗したときに落ちる、`quint` が選ばれていないのに走ったときに落ちる。`quint` が落ちた PR で `PR CI Required` が落ちることは、CI の一時的なコミットでは見えないので（下の「CI」）、このテストで確かめる（R-10）。

## わざと壊した仕様で落ちることを確かめる

### 手元（スクリプトを、壊した写しに流す）

ワークフローから取り出したのと同じ段（`yaml.safe_load` で `jobs.quint.steps` の検査の段の `run` を抜き、リポジトリ直下で `bash`）を、壊した写しを置いて流し、どれも非ゼロで終わることを確かめる。壊した写しはコミットしない。コードだけが変わった PR の注意の段は、この PR の CI では通らない（この PR は `specs/` を変える）。そこで `test_quint_job.py` で、`Select PR checks` と注意の段を取り出して流し、注意が出ること、`specs/` も変わったときと PR でないときには出ないことを確かめる（R-10）。

| 壊し方 | 落ちるはずの段 |
| :-- | :-- |
| 型の誤り（1行） | typecheck |
| `keyMigration_test.qnt` の `assert` を成り立たなくする | test |
| `run` の名前の `Test` を外す | test の決まり（名前を出して落ちる） |
| `*_test.qnt` を消す | test の決まり（ディレクトリに test が無い） |
| `settleOutcome` の `if (not(source) and target)` を `if (target)` にする（下） | run の 1（test は通る） |
| `initGStale` の `staleReads` を偽にする | run の 6（違反が出なくなる） |
| `ci-runs.txt` から `--max-samples` を消す | 一覧の決まり |
| `--witnesses` に通らない witness を足す | witness の決まり |
| `ci-runs.txt` を消す、または `.qnt` を1本足して一覧に書かない | 一覧の決まり |
| `specs/` を空にする | 仕様が1本も無い |
| `keyMigration.qnt` の `step` を常に偽にする | run の 1〜4（`max=1`、R-10） |
| `specs/` の直下に `.qnt` を置く | 置き場所の決まり（R-7） |
| `ci-runs.txt` の行から `--invariant` を消す、`--max-samples 1` にする、`--verbosity` を書く | 一覧の決まり（R-6） |

### CI（一時的なコミット）

テストが通って `quint run` だけが落ちる1行の変更を、PR の枝に一時的なコミットとして push する。候補は `keyMigration.qnt` の `settleOutcome` の1行で、試みの上限に着いた後、元のキーにまだ行があっても、行き先に行があれば移ったと報告するように変える。

```
-    if (not(source) and target) { if (merging) Merged else Moved }
+    if (target) { if (merging) Merged else Moved }
```

手元の写しで確かめた結果：`quint test` は3本とも通る（12、4、10 passing）。`quint run keyMigration.qnt --invariant safety --max-samples 200 --max-steps 120 --seed 0x7` は違反を見つける（`Invariant violated`、2秒）。

手順：

1. 実装を push し、CI が通るのを確かめる
2. 上の1行だけのコミットを push する。`quint` ジョブが run の段で落ち、typecheck と test は通ることを、ジョブのログで確かめる。待つ間は GitHub を読み直さず、Orca の見張り（`orca automations watch-pr`）か `gh event listen` で起こしてもらう
3. `git revert` でそのコミットを戻して push する（履歴は書き換えない）。`concurrency` の `cancel-in-progress` で、壊したコミットの実行のほかのジョブは止まるが、`quint` ジョブの結果は残る。このため、壊したコミットで `PR CI Required` が落ちるところまでは見ない。それは `test_pr_ci_required.py` で確かめる（R-10）。戻した後の CI が通ることを確かめる
4. 壊したコミットと戻したコミットの SHA、`quint` ジョブの実行へのリンク、落ちた段とエラーの行を PR 本文に書く

壊したコミットの実行では、この PR が `ci.yml` を変えているので Scala のジョブも走る。壊すのは1回だけにし、ほかの壊し方は手元で確かめる。

## 壊しうる入力

| 入力 | 扱い |
| :-- | :-- |
| 仕様のファイルが増える | typecheck と test は glob で拾う。`*_test.qnt` でない仕様が `ci-runs.txt` に無ければ落とす |
| 仕様のファイルが消える | `ci-runs.txt` の行が指すファイルが無ければ落とす。`specs/*/` に1本も無ければ落とす |
| 新しいディレクトリ `specs/<名前>/` | glob で拾う。`ci-runs.txt` か `*_test.qnt` が無ければ落とす |
| `*_test.qnt` が無い | 落とす |
| `*_test.qnt` に `run` が無い、名前が `Test` で終わらない、`passing` の数が合わない | 落とす |
| ファイル名と同じ名前のモジュールが無い `*_test.qnt` | main を推測せずに落とす |
| `ci-runs.txt` の行に `--seed`・`--max-samples`・`--max-steps`・`--invariant(s)` が無い、`--max-samples` が 1、`--invariant true`、`--verbosity` がある | 落とす |
| `ci-runs.txt` の1つ目・2つ目の語が決まりに合わない、`*` や `$(…)` を含む | 1つ目・2つ目は落とす。引数は `eval` せず `set -f` で読むので、展開されずに Quint に渡り、Quint が落とす |
| `specs/` の直下や2段より深い場所の `.qnt` | 落とす（R-7） |
| 不変条件や init の名前の誤り | Quint が `QNT404` で落ちる。`violation` の行でも、`[violation]` が無いので成功にしない |
| どのトレースも1手も進まない | `pass` の行は `max` が 1 以下で落とす。`Trace length statistics` の行が出ない（Quint の出力が変わった）場合も落とす。`violation` の行は反例に `[State 1]` が無ければ落とす |
| witness が 0 回 | 落とす |
| 新しい不変条件を、`safety` にも `ci-runs.txt` にも入れない | 見つけられない（下の「限界」） |
| Quint の版が lockfile と違う | 版の確認で落とす |
| 評価器の取得の失敗 | 途中のファイルを消して3回まで試し、それでも失敗したら `::error::` で落とす |
| 評価器の digest が合わない | 再試行せずに落とす |
| 評価器の版が変わった（Quint を上げた） | digest が無いので落とす |
| npm レジストリに届かない | `npm ci` の再試行の後に落とす |
| ランナーが Linux X64 でない | 最初の段で落とす |
| 時間切れ | 15分でジョブが止まり、`PR CI Required` が落ちる |
| `jq` が無い | `ubuntu-latest` には入っている。手元では、スクリプトの最初に `jq` と `node` があるかを確かめて落とす |

### 限界

- このジョブは仕様を検査するだけで、Scala のコードとの突き合わせはしない。コードだけが変わったときは注意を出すだけで、ずれを見つけるのは Scala のテストとレビュー
- `safety` に入れず `ci-runs.txt` にも書かない不変条件は流れない。不変条件の `val` と補助の `val` は Quint の上で区別できないので、スクリプトは見分けない。不変条件を足すときは `safety` に入れるか、`ci-runs.txt` に行を足す。この決まりは、このジョブで新しく決めるもので、`docs/design/key-migration.md` の 8 にも1文足す
- run は回数を絞った回帰の検査で、深い経路の探索は結果の文書の run が受け持つ

## 設計の中で見つけたこと（このジョブの範囲の外）

壊した写しを試す中で、次の3つの1行の変更は、`quint test` が3本とも通り、`quint run` でも違反が出なかった。

| 変更 | 流した run | 結果 |
| :-- | :-- | :-- |
| `differ` から journal の `created` の比較を外す | `safety`、`init` 1,000 × 120 と `initSharedPair` 1,000 × 60 | 違反なし |
| `differ` から snapshot の `deleted` の比較を外す | `safety`、`init` 1,000 × 120 | 違反なし |
| `attempt` で、元にも行き先にも行が無いときの `Gone` を `Moved` にする | `safety`、`init` 200 × 120 | 違反なし |

設計レビュー（R-14）が、3つとも、このジョブを入れた時点（297826be）の仕様の中で届かない分岐だと突き止めた。その仕様の `buildTable` は journal の `created` を常に 0、snapshot の `deleted` を常に偽にしていたので、`differ` の2項はどの状態でも偽だった。書き手のいないモデルでは元のキーの行を消せるのは候補自身だけなので、`Gone` の分岐にも届かなかった。このジョブは仕様が定義したものを流すだけで、届かない分岐は見つけない。

この制限は、[#1042](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1042)（設計は [key-migration-quint-unreachable-branches.md](key-migration-quint-unreachable-branches.md)）で仕様の側を直した。`buildTable` がプラグインの書かない属性を持つ写しを作り、書き手のモデルに `stepD` を足した。CI は、その分岐に届いたことを `ci-runs.txt` の witness（`wConflictExtraJournal`、`wConflictExtraSnapshot`、`wGone`、`wGoneShared`、`wOutcomeUnknown`）で確かめる。分岐にまた届かなくなると witness が 0 になり、ジョブが落ちる。上の3つの変更は、直した後の仕様ではどれも落ちる（設計の 5.1）。

## 守る不変条件

- Quint は `package-lock.json` の 0.32.0、評価器は digest を固定した v0.6.0 だけを使う
- 検査を弱めない：typecheck の失敗、test の失敗、`pass` の行の違反、`violation` の行の違反が無いことは、どれもジョブを非ゼロで終わらせる
- 黙って外れる検査を作らない：`quint test` が拾わない `run`、`run` の無いテスト、1回しか見ない run、不変条件を書かない run、1手も進まない run、通らない witness、一覧に無い仕様、決まった深さに無い仕様は、どれも落とす
- 種を固定し、同じコミットでは同じ結果になる
- ほかの CI のジョブの条件と結果は変えない。走る条件に当たらない PR では、`PR CI Required` の結果は今と同じ

## 影響と戻し方

- 影響：CI にジョブが1つ増える。走る条件に当たる PR だけで、Scala のジョブと並行に終わる。上流、保存済みのデータ、公開 API、MiMa への影響は無い
- TAKT の `verify_aislop`（`.takt/workflows/ppd-code-change.yaml` と `ppd-tools-change.yaml`）の `npm ci` が、`@informalsystems/quint` も入れるようになる。`test_aislop_adoption.py` は `aislop` の版だけを見るので、通ったまま（R-9）
- 戻し方：`ci.yml` の `quint` ジョブと、`changes`・`required` に足した行を消す。`package.json` の1行を消して `npm install` で lockfile を作り直す。`tools/quint-check-specs.sh`、`specs/key-migration/ci-runs.txt`、`tools/tests/test_quint_check_specs.py`、`tools/tests/test_quint_job.py` を消し、`test_pr_ci_required.py`・`test_pr_test_merge.py` に足したものと `tools-tests.yml` の `expected` を戻す

## 状態遷移

ワークフローは仕様を検査するだけで、モデルにする状態遷移を増やさない。Quint の新しいモデルは作らない。

## レビューの反映

設計の独立レビュー（antigravity、`f3103960` が対象）の指摘14件を、次のように処理した。

### 実装の前に直すべき4件：どれも直した

| 指摘 | 処理 | 変えた点 |
| :-- | :-- | :-- |
| R-1 違反の行では `Trace length statistics` が出ない | 直す | `max >= 2` の確認は `pass` の行だけにした。`violation` の行は、反例のトレースに `[State 1]` があることを確かめる。0.32.0 で、違反を見つけた run 7 の出力に `Trace length statistics` が無く、`[State 0]` から `[State 9]` までが出ることを確かめた。スクリプトが `--verbosity=2` を必ず付ける |
| R-2 スクリプトだけを変えた PR で、そのテストが走らない | 直す | スクリプトを `tools/quint-check-specs.sh` に置いた。変えると既存の規則で `scala` と `tools` が選ばれ、`test_quint_check_specs.py` が流れる。`required` の既存の `case` は変えない |
| R-3 キーの組み立てと JournalHead の復旧のパスが漏れている | 直す | 走る条件と注意の対象に6ファイルを足した（`PersistenceIdKeyParts`、journal と snapshot の `PartitionKeyResolver`・`SortKeyResolver`、`JournalAggregateStore`）。6つとも実在を確かめ、`JournalAggregateStore.highestSequenceNr` が head と journal の大きい方を取ることを読んだ。`key-migration.md` の 8.3 に `JournalAggregateStore` の行を足す |
| R-4 コードだけ変わった PR の注意の入力の経路が無い | 直す | 判定を `changes` の `Select PR checks` に置き、出力 `quint_code_files` で渡す。`quint` ジョブの別の段がそれを読んで注意を出す。PR でないイベントでは空。検査のスクリプトは変更の一覧を読まない |

### 実装の中で直せばよい6件

| 指摘 | 処理 | 中身 |
| :-- | :-- | :-- |
| R-5 再試行の前に途中のファイルを消さない | 直す | 試すたびに `rm -f` し、`gh release download --clobber` にする。`test_quint_job.py` で、偽の `gh` を使って「1回目が途中のファイルを残して失敗し、2回目で通る」「digest が合わなければ再試行しない」「3回失敗で `::error::`」を確かめる |
| R-6 一覧の行の検査と出力の読み方の抜け | 直す | `--invariant(s)` を必須にし、`true` を落とす。`--max-samples` は 2 以上、`--max-steps` は 1 以上を、両方の書き方で読む。`--verbosity` は書かせない。出力は一時ファイルに書き、要約だけをログに出す。`[ok]` と `[violation]` は行の先頭に限らずに探す。行は `eval` せず `set -f` で読み、1つ目と2つ目の語を決まりで確かめる |
| R-7 決まった深さに無い `.qnt` が外れる | 直す | `specs/` の下の `.qnt` を全部探し、`specs/<ディレクトリ>/<ファイル>.qnt` でないものがあれば落とす。テストに足す |
| R-8 `wCleanAfterGaveUp` が1回しか通らない | 直す | `wCleanAfterGaveUp` を一覧から外した（`finishThenRerunTest` が毎回通す）。一覧には決めた種で4回以上通る witness だけを書く決まりにし、`wGaveUpShared` が 4 だった run 2 は 2,000 回に増やした（10回、+15秒） |
| R-9 `npm ci` の再試行で最悪8分待つ、TAKT への影響 | 直す | 再試行を `--fetch-retries=3 --fetch-retry-mintimeout=10000 --fetch-retry-maxtimeout=60000`（最悪130秒）にした。「影響」に TAKT の `verify_aislop` を書いた。actions の版は既存の `ci.yml` に合わせる |
| R-10 壊した仕様の確認の抜け | 直す | 手元の表に `step` を常に偽にする写し（`max=1`）、置き場所、一覧の決まりを足した。注意の段は `test_quint_job.py` で確かめる。壊したコミットでは `PR CI Required` の失敗までは見ず、`test_pr_ci_required.py` で確かめると書いた。`keyMigration_test.qnt` は `assert` と直した |

### 後でよい2件と意見2件

| 指摘 | 処理 | 中身 |
| :-- | :-- | :-- |
| R-11 種の固定による死角と、並行する PR のすり抜け | 別件 | [#1038](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1038)。`main` の先端で、乱数の種の `quint run` を定期的に流すワークフローを足す。この PR の種の固定と push の `paths` は変えない |
| R-12 版を上げる手順、`idempotent` の `initSharedPair`、使っていない witness 3本 | 直す | 版を上げる手順を「評価器」の節と `ci.yml` のコメントに書く。`QUINT_HOME` を設定しない前提を書いた。run 4（`initSharedPair` の `idempotent`、500 × 60、11秒）を足し、`wMergedAfterLost`（21）、`wCycleRemaining`（239）、`wFinishedForward`（385）を run 1 の一覧に足した |
| R-13 コードだけの変更で落とさない設計は妥当 | 採らない（変更なし） | 意見に同意し、設計は変えない。前提に挙がった R-3、R-4、R-10 は上で直した |
| R-14 範囲の外の3つの変更は、届かない分岐が原因 | 別件 | 依頼元が仕様の項目として立てた。「設計の中で見つけたこと」に原因を書いた |

設計を変えたので、変わった点（上の表）は実装の後の CI で確かめ、結果を PR 本文に書く。
