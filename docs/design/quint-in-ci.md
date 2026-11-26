# 設計：CI で `specs/` の Quint 仕様を検査する

`specs/key-migration/` の Quint 仕様（[#1034](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1034) で入った）を、PR ごとに CI で検査する `quint` ジョブを足す。今は CI で流していないので、仕様が壊れても（型の誤り、テストの期待の誤り、不変条件の反例）気づけない。形は [j5ik2o/fraktor-ng-rs#216](https://github.com/j5ik2o/fraktor-ng-rs/pull/216) に合わせる：Quint を lockfile で固定し、評価器を sha256 で確かめ、typecheck・test・run を流し、`timeout-minutes` を付ける。

この文書は PR 本文の下書きを兼ねる。段1（設計）で止め、設計の独立レビューを通してから実装する。

## 主な決定

| 項目 | 決めたこと |
| :-- | :-- |
| 走る条件 | `specs/**`、検査のスクリプト、`package.json`・`package-lock.json`、`ci.yml`、仕様が説明するコード（下の表）が変わった PR。push と workflow_dispatch では常に走る |
| 流すもの | 全 `.qnt` の `quint typecheck`、全 `*_test.qnt` の `quint test`、仕様ごとの一覧ファイルに書いた `quint run`（6本、種は `0x7` に固定） |
| 流さないもの | `quint verify`（Apalache）。`fullRun` の範囲が定数でなく、Apalache が受け付けない（結果の文書、C-F7） |
| 版の固定 | Quint 0.32.0 を `package.json` の devDependencies に足し、`npm ci` で入れる。評価器 v0.6.0 は tar.gz と実行ファイルの sha256 を `ci.yml` に固定する |
| 取得の失敗への備え | 評価器は `actions/cache` に残し、無いときだけジョブのトークンで取得し、3回まで再試行する。npm は `setup-node` のキャッシュと `npm ci` の再試行の設定。このジョブは Maven Central を使わない |
| 時間の上限 | `timeout-minutes: 15`。所要の見込みはジョブ全体で3〜5分 |
| 必須のチェック | `PR CI Required` に入れる。走る条件に当たらない PR では、ジョブは省略され、省略を成功として扱う |
| 壊した仕様での確認 | 手元で10通りの壊し方を流す。CI では、テストが通って `quint run` だけが落ちる1行の変更を一時的なコミットで push し、落ちたのを確かめてから revert する |

見込みの大きさは 3。基準は [#1024](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1024)（mima の依存のキャッシュと再試行）を 2 とする。#1024 より大きいのは、ジョブとスクリプトと一覧ファイルの3つを新しく作り、集約の配線と tools のテストを直し、CI で壊した仕様を1回流すため。

## 何を足すか

| ファイル | 変更 |
| :-- | :-- |
| `.github/workflows/ci.yml` | `quint` ジョブを足す。`changes` ジョブに出力 `quint` を足す。`required` ジョブの `needs` と確認の段に `quint` を足す |
| `.github/workflows/tools-tests.yml` | `Check the test counts` の `expected` を、足すテストの数だけ増やす（この1行だけ） |
| `scripts/quint-check-specs.sh` | 検査の本体。ジョブはこれを呼ぶだけで、手元でもリポジトリ直下で同じものが流れる |
| `specs/key-migration/ci-runs.txt` | この仕様の `quint run` の一覧（下の「run の一覧」） |
| `package.json`・`package-lock.json` | `@informalsystems/quint` を `0.32.0` に固定して devDependencies へ足す |
| `tools/tests/test_pr_ci_required.py` | `quint` の結果の組み合わせのテストを足し、既存のテストの環境に `RUN_QUINT=false`・`QUINT_RESULT=skipped` を足す |
| `tools/tests/test_pr_test_merge.py` | `verify_steps` に `quint` ジョブを足す（検証済みのテストマージを取り出す段が、ほかのジョブと同じであることを確かめる） |
| `tools/tests/test_quint_check_specs.py` | スクリプトの決まりのテスト。偽の `quint` を置いて、出力の読み方と、落とすべき入力で落ちることを確かめる |
| `docs/design/quint-in-ci.md` | この文書 |

`.github/workflows` の変更は、ジョブの追加と、それを集約に入れるための配線だけにする。ほかのジョブの条件、push の `paths`、マトリクスは変えない。`tools-tests.yml` の `expected` を変えるのは、集約の確認を変えると tools のテストも足すことになり、`Check the test counts` が件数を固定しているため。`required` の確認の段を変えても、`ci.yml` の変更だけでは `tools` は選ばれない（`tools/*`、`tools-tests.yml`、`.github/scripts/*` だけが選ぶ）。この PR では `tools/tests/` も変えるので、tools のテストは同じ PR の CI で流れる。

## 走る条件

`changes` ジョブの `Select PR checks` に `quint` の判定を足す。PR では、次のどれかが変わったときに `quint=true` にする。

| パス | 理由 |
| :-- | :-- |
| `specs/*` | 仕様と一覧ファイル |
| `scripts/quint-check-specs.sh` | 検査の本体 |
| `package.json`、`package-lock.json` | Quint の版 |
| `.github/workflows/ci.yml` | ジョブの定義 |
| `base/base-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/migration/*` | `ResolverKeyPlan`、`KeyMigration`、`KeyVerification`（`docs/design/key-migration.md` の 8.3 の表） |
| `journal/journal-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/migration/*` | journal の移行の入口 `JournalKeyMigration` |
| `snapshot/snapshot-v2/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/snapshot/migration/*` | snapshot の移行の入口 `SnapshotKeyMigration` |
| `journal/journal-base/src/main/scala/com/github/j5ik2o/pekko/persistence/dynamodb/journal/store/JournalRowStore.scala` | 書き手のモデルの `recoverAndWrite`、`hardDeleteCurrent`、`hardDeleteLegacy` が説明する書き込みと削除 |

push（main）と workflow_dispatch では、`scala` と同じく常に `quint=true` にする。push の `paths` には `.qnt` を足さない。足すと、仕様だけを変えた push でも Scala のジョブが全部走り、ほかの CI の挙動が変わるため。main は保護されていて、仕様の変更は PR の CI を通ってから入る。

### コードだけの変更でも走らせ、注意を出す

このジョブは仕様だけを検査し、Scala のコードと仕様を突き合わせない。種を固定するので、仕様が変わらなければ、コードだけが変わった PR で流しても結果は main と同じになる。それでも、コードの変更で走らせる。価値は検査の結果ではなく、次の注意にある。

- 仕様が説明するコードが変わり、`specs/` が変わっていない PR では、スクリプトが `::warning::` を出し、ジョブの要約（`GITHUB_STEP_SUMMARY`）に、変わったファイルと `docs/design/key-migration.md` の 8.3（Quint とコードの対応）を並べる。PR の作者とレビューボットが、仕様を直すか、直さない理由を書くきっかけにする
- 注意は落ちる理由にしない。動きを変えないリファクタリングでも仕様の変更を強いることになるため

所要は3〜5分で、9本の test のジョブより短く並行に終わるので、待ち時間は増えない。実装が仕様の性質を守るかは、これまでどおり Scala のテスト（`KeyMigrationSpec`、`JournalKeyMigrationSpec`、`ReleasedRowsMigrationSpec` など）が見る。

## 流すもの

スクリプトは `specs/*/` のディレクトリごとに、次を順に流す。1つのディレクトリや1つの段が落ちても残りは流し、最後に落ちたものを `::error file=…::` で並べて非ゼロで終える。

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

`--seed` を付けると `quint test` の `--max-samples` の既定は 1 になる（0.32.0 の `--help`）。`keyMigrationGaveUp_test.qnt` の3本はランダムな筋書きを 10,000 回流すので、`--max-samples 10000` を必ず付ける。手元で2回流し、どちらも `finishThenRerunTest`・`stopThenRerunTest`・`rejectedOnSharedTargetTest` が各 10,000 回、計4件が通った（約10秒）。

### run の一覧

`fraktor` は「どの仕様も `invariant` という名前の不変条件を持つ」決まりにして glob で一律に流した。PPD の仕様は、初期状態（`init`、`initSharedPair`、`initG` など）と不変条件（`safety`、`idempotent`、書き手のモデルの4つ）を run ごとに選ぶ必要があり、この決まりに合わない。そこで、ディレクトリごとに `ci-runs.txt` を置き、1行に1つの `quint run` を書く。

```
# <期待> <ファイル> <quint run に渡す引数>
pass keyMigration.qnt --invariant safety --max-samples 400 --max-steps 120 --seed 0x7 --witnesses wMoved wMerged ...
violation keyMigrationWriters.qnt --main keyMigrationWriters --init initGStale --step stepG --invariant noLossW ...
```

スクリプトは、各行について次を確かめる。

- 引数に `--seed`、`--max-samples`、`--max-steps` がある。`--seed` だけだと、`quint run` の `--max-samples` の既定が 1 になり、1本のトレースしか見ない（0.32.0 の `--help`）
- `pass` の行：終了コードが 0 で、`[ok] No violation found` が出る
- `violation` の行：終了コードが 0 以外で、`[violation]` と `Invariant violated` が出る。名前の誤り（`QNT404`）など、ほかの理由で落ちたものは成功にしない
- `Trace length statistics: max=N` の N が 2 以上。1 以下なら、どのトレースも1手も進んでおらず、不変条件は初期状態でしか見ていない（fraktor-ng-rs#216 のレビューの F2）
- `--witnesses` に書いた witness は、どれも1回以上通る。一覧には、通ることを確かめたい経路の witness だけを書く。種が固定なので、回数は毎回同じになる

ディレクトリの `*_test.qnt` でない `.qnt` は、どれも `ci-runs.txt` のどこかの行のファイルとして現れること。現れない仕様があれば落とす（仕様を足したのに run を書き忘れた場合）。ディレクトリに `.qnt` があって `ci-runs.txt` が無い場合も落とす。

### key-migration の run（6本）

[docs/design/key-migration-quint-results.md](key-migration-quint-results.md) の run から、CI の時間に収まる回数を選んだ。不変条件を1本ずつ流す代わりに、17の不変条件をまとめた `safety` を流す（`idempotent` だけは `safety` に入っていないので別に流す）。種は結果の文書と同じ `0x7`。所要は 2026-11-26 に手元（Apple Silicon、14コア、負荷の平均 22 前後）で測った実時間。

| # | 期待 | モデル | 不変条件 | 回数 × 手数 | 確かめる witness | 手元の所要 |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| 1 | pass | `keyMigration`、`init` | `safety` | 400 × 120 | `wMoved` `wMerged` `wBlocked` `wConflict` `wRejected` `wGaveUp` `wCleanAfterGaveUp` `wProgress` `wFinishedAfterStop` `wRoundTrip` `wVerifyForward` `wVerifyReverse` `wIndexLag` | 43秒 |
| 2 | pass | `keyMigration`、`initSharedPair` | `safety` | 1,000 × 60 | `wGaveUpShared` `wRejectedShared` `wCleanAfterGaveUp` `wFinishedAfterStop` | 19秒 |
| 3 | pass | `keyMigration`、`init` | `idempotent` | 300 × 60 | なし | 12秒 |
| 4 | pass | `keyMigrationWriters`、`initG` / `stepG` | `noLossW` `noDuplicateW` `deletedStayGone` `verifySoundW` | 2,000 × 80 | `wAppStarted` | 7秒 |
| 5 | pass | `keyMigrationWriters`、`initGStaleHead` / `stepGForward` | 同じ4つ | 5,000 × 80 | `wAppStarted` | 15秒 |
| 6 | violation | `keyMigrationWriters`、`initGStale` / `stepG` | `noLossW` | 2,000 × 80 | なし | 3秒 |

- 1 で通った witness の回数（400 のうち）：`wMoved` 400、`wMerged` 259、`wBlocked` 346、`wConflict` 138、`wRejected` 292、`wGaveUp` 5、`wCleanAfterGaveUp` 1、`wProgress` 189、`wFinishedAfterStop` 78、`wRoundTrip` 177、`wVerifyForward` 11、`wVerifyReverse` 159、`wIndexLag` 400。`wGaveUpShared` と `wRejectedShared` は 0 で、結果の文書の 1,000 回と同じく `init` では通らないので、2 で確かめる
- 2 で通った回数（1,000 のうち）：`wGaveUpShared` 4、`wRejectedShared` 249、`wCleanAfterGaveUp` 1、`wFinishedAfterStop` 193
- 4 と 5 の `wAppWrote` は 0 で、一覧に入れない。手順を守るアプリのモデルでは、`recoverAndWrite` はインデックスが行を示していないときだけ起き、それが起きないことがこのモデルの結論だから
- 6 は負の対照。古い値を返す読み取りで JournalHead が無効なら、手順を守っても上書きが起きる（結果の文書の A1）。この run が違反を見つけなくなったら、書き手のモデルが何も確かめなくなった兆しなので、落とす
- 結果の文書の run（`safety` の 1,000 × 120 で 123秒、不変条件ごとの 500 × 120 など）より回数を減らした。CI の run は、仕様の変更で反例が出ないこと、経路が通ることの回帰の検査で、広い探索は結果の文書の run が受け持つ
- 合計は手元で約100秒。`ubuntu-latest`（4 vCPU）では1コアあたりの速さが手元より遅い見込みで、1.5〜2倍の2.5〜3.5分と見る。実装の後、CI の実測で回数を見直す

### 種を固定する

fraktor は種を乱数のままにし、回を重ねて広く探す方を選んだ。PPD では種を `0x7` に固定する。依頼が「決まった種で再現できること」で、`--witnesses` の回数を1回以上と確かめる決まりは、種が固定でないと揺れて落ちるため。

- 再現：手元で `safety` の 200 × 120 を `--n-threads` 1、4、8、既定（14）で流し、witness の回数（`wMoved` 200、`wGaveUp` 5、`wBlocked` 170）と最後の行の種がすべて同じだった。スレッドの数は結果を変えない
- 実装の後、CI（Linux x64）の witness の回数が手元（macOS arm64）と同じかを確かめ、PR に書く。違っていたら、再現は Linux x64 の評価器でだけ成り立つと書き、手元で再現する手順（Docker の `node:22` で同じスクリプトを流す）を添える
- 違反が出たら、ログのコマンドをそのまま流せば同じ反例が出る。種を変えて広く探すのは、結果の文書の run の役目にする

## 版の固定と取得

### Quint

`package.json` の devDependencies に `"@informalsystems/quint": "0.32.0"` を足し、`package-lock.json` を `npm install` で作り直す。既存の `aislop` の版は変えない。ジョブは `actions/setup-node`（Node 22、`cache: npm`）の後に `npm ci --no-audit --no-fund` を流す。npm の取得は `--fetch-retries=5 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=120000` で再試行する。lockfile に入った包みは、それぞれの `integrity`（sha512）を npm が確かめる。

### 評価器

Quint 0.32.0 の既定の評価器（Rust、v0.6.0）は npm の包みに入っていない。初めて使うときに、認証なしの GitHub releases API の1ページ目から探して `~/.quint/rust-evaluator-v0.6.0/` へ取得する。新しいランナーでは毎回この API を呼ぶので、認証なしの上限（IP ごとに1時間60回）で落ちうる。リリースが増えて v0.6.0 が1ページ目から外れると、毎回落ちるようになる（fraktor-ng-rs#216 の調べ）。そこで fraktor と同じ形にする。

1. 評価器の版は、入った Quint の `QUINT_EVALUATOR_VERSION`（`dist/src/rust/binaryManager.js`）から読む。lockfile と自動で揃う
2. Linux X64 以外のランナーでは、この段で落とす（固定する digest が x86_64 Linux のものだけなので）
3. 版ごとに digest を `ci.yml` に固定する。v0.6.0 の値は、2026-11-26 に `gh release download evaluator/v0.6.0 --repo informalsystems/quint` で取得して確かめた。fraktor-ng-rs#216 の値と同じ
   - tar.gz（`quint_evaluator-x86_64-unknown-linux-gnu.tar.gz`）：`61755a09d5052d93a4e75e840059edfd0d3674aeda164b9d2464be3d6e21b1c2`
   - 実行ファイル（`quint_evaluator`）：`b2efdeac5713d153e41bf2143b94ed75d888fdd5637f4a5d61a04c695313510a`
   - digest の無い版は `::error::` で落ちる。Quint を上げて評価器の版が変わったら、digest を足す
4. `actions/cache` で `~/.quint/rust-evaluator-<版>/` を残す。鍵は OS、CPU、版、実行ファイルの digest
5. キャッシュが無いときだけ、ジョブのトークンで `gh release download` する。失敗したら 30秒、60秒と間をあけて3回まで試す。取得した tar.gz は展開の前に `sha256sum -c` で確かめる。digest が合わないときは再試行せずに落とす（取得の失敗ではなく、資産が差し替わったため）
6. 実行ファイルは、キャッシュから戻したときも含めて毎回 `sha256sum -c` で確かめる。Quint は実行ファイルがあれば API を呼ばない

今日の Maven Central の 429 は、このジョブには関係しない。sbt を使わず、取得するのは npm レジストリと GitHub の releases だけだから。

## 時間の上限と所要

`timeout-minutes: 15`。見込みは次のとおりで、上限は合計の3倍ほどにした。

| 段 | 見込み |
| :-- | :-- |
| checkout と検証済みのテストマージの取り出し | 10〜20秒 |
| `setup-node` と `npm ci`（キャッシュあり） | 15〜30秒 |
| 評価器（キャッシュあり／なし） | 数秒／20秒 |
| typecheck（5本） | 7秒前後（手元で各1.3秒） |
| test（3本） | 15〜25秒（手元で 1.7、10.5、2.0秒） |
| run（6本） | 2.5〜3.5分（手元で約100秒） |
| 合計 | 3〜5分 |

上限に当たるのは、評価器が返ってこない、仕様の変更で状態が増えて run が極端に遅くなった、のどちらか。スクリプトは段ごとに所要時間を出すので、遅くなった段がログで分かる。

## 必須のチェックに入れる

入れる。必須にしないと、仕様が落ちていてもマージできる。ブランチの保護の必須のチェックは `PR CI Required` と9本の test と `Check unresolved comments` で、`quint` は `PR CI Required` の確かめに入れる。ブランチの保護の設定は変えない。

`required` ジョブに足すもの：

- `needs` に `quint`
- 環境変数 `RUN_QUINT: ${{ needs.changes.outputs.quint }}`、`QUINT_RESULT: ${{ needs.quint.result }}`
- 今の `case` とは別の `case` として、`case "$RUN_QUINT:$QUINT_RESULT" in true:success|false:skipped) ;; *) …exit 1 ;; esac`。今の `case` に組み合わせを足すと、パターンの数が倍になるので分ける
- jobs API で確認する式に `(if $quint == "true" then passed("quint"; "<検査の段の名前>") else true end)` を足し、`jq` に `--arg quint "$RUN_QUINT"` を渡す

走る条件に当たらない PR では、`quint=false` で `quint` ジョブは省略され（`skipped`）、`false:skipped` を成功として扱う。`tools` と同じ扱い。`quint=true` なのに省略された、または `quint=false` なのに走った場合は落とす。

tools のテストに足すもの（`test_pr_ci_required.py`）：`quint` が走って通ったときに通る、`quint` が選ばれたのに省略されたときに落ちる、`quint` のジョブか検査の段が失敗したときに落ちる、`quint` が選ばれていないのに走ったときに落ちる。

## わざと壊した仕様で落ちることを確かめる

### 手元（スクリプトを、壊した写しに流す）

ワークフローから取り出したのと同じ段（`yaml.safe_load` で `jobs.quint.steps` の検査の段の `run` を抜き、リポジトリ直下で `bash`）を、壊した写しを置いて流し、どれも非ゼロで終わることを確かめる。壊した写しはコミットしない。

| 壊し方 | 落ちるはずの段 |
| :-- | :-- |
| 型の誤り（1行） | typecheck |
| `keyMigration_test.qnt` の `expect` を成り立たなくする | test |
| `run` の名前の `Test` を外す | test の決まり（名前を出して落ちる） |
| `*_test.qnt` を消す | test の決まり（ディレクトリに test が無い） |
| `settleOutcome` の `if (not(source) and target)` を `if (target)` にする（下） | run の 1（test は通る） |
| `initGStale` の `staleReads` を偽にする | run の 6（違反が出なくなる） |
| `ci-runs.txt` から `--max-samples` を消す | 一覧の決まり |
| `--witnesses` に通らない witness を足す | witness の決まり |
| `ci-runs.txt` を消す、または `.qnt` を1本足して一覧に書かない | 一覧の決まり |
| `specs/` を空にする | 仕様が1本も無い |

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
3. `git revert` でそのコミットを戻して push する（履歴は書き換えない）。`concurrency` の `cancel-in-progress` で、壊したコミットの実行のほかのジョブは止まるが、`quint` ジョブの結果は残る。戻した後の CI が通ることを確かめる
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
| `ci-runs.txt` の行に `--seed`・`--max-samples`・`--max-steps` が無い | 落とす |
| 不変条件や init の名前の誤り | Quint が `QNT404` で落ちる。`violation` の行でも、`[violation]` が無いので成功にしない |
| どのトレースも1手も進まない | `max` が 1 以下で落とす。`Trace length statistics` の行が出ない（Quint の出力が変わった）場合も落とす |
| witness が 0 回 | 落とす |
| 新しい不変条件を、`safety` にも `ci-runs.txt` にも入れない | 見つけられない（下の「限界」） |
| Quint の版が lockfile と違う | 版の確認で落とす |
| 評価器の取得の失敗 | 3回まで試し、それでも失敗したら `::error::` で落とす |
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

仕様の宇宙でこの違いが起きないのか、不変条件が見ていないのかは、まだ調べていない。仕様の検査の強さの問題で、このジョブの範囲を超えるので、別の issue にすることを提案する。

## 守る不変条件

- Quint は `package-lock.json` の 0.32.0、評価器は digest を固定した v0.6.0 だけを使う
- 検査を弱めない：typecheck の失敗、test の失敗、`pass` の行の違反、`violation` の行の違反が無いことは、どれもジョブを非ゼロで終わらせる
- 黙って外れる検査を作らない：`quint test` が拾わない `run`、`run` の無いテスト、1回しか見ない run、1手も進まない run、通らない witness、一覧に無い仕様は、どれも落とす
- 種を固定し、同じコミットでは同じ結果になる
- ほかの CI のジョブの条件と結果は変えない。走る条件に当たらない PR では、`PR CI Required` の結果は今と同じ

## 影響と戻し方

- 影響：CI にジョブが1つ増える。走る条件に当たる PR だけで、Scala のジョブと並行に終わる。上流、保存済みのデータ、公開 API、MiMa への影響は無い
- 戻し方：`ci.yml` の `quint` ジョブと、`changes`・`required` に足した行を消す。`package.json` の1行を消して `npm install` で lockfile を作り直す。`scripts/quint-check-specs.sh`、`specs/key-migration/ci-runs.txt`、`tools/tests/test_quint_check_specs.py` を消し、`test_pr_ci_required.py`・`test_pr_test_merge.py` に足したものと `tools-tests.yml` の `expected` を戻す

## 状態遷移

ワークフローは仕様を検査するだけで、モデルにする状態遷移を増やさない。Quint の新しいモデルは作らない。
