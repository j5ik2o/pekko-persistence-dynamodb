# キー移行の Quint 仕様：検査の結果

仕様は `specs/key-migration/`、設計は `docs/design/key-migration.md`、公開の前の直しの設計は `docs/design/key-migration-fix-before-1.4.0.md`。仕様が説明するのは、直しの後のコード（[#1033](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1033)）。

Quint 0.32.0、2026-11-26、Apple Silicon の手元の機械（ほかの作業で負荷あり、最大9本を並べて実行）。所要時間は実時間。すべて `specs/key-migration/` で実行し、シードは `0x7`。

## 直しの前の結果との関係

5ff2d62d の仕様の `converges` は、あきらめて終わった実行の後に比べる起点を切り直していた。このため、勝者があきらめた後で負けた候補が行き先を取る反例（レビュー C-F1）を見逃していた。直しの後の仕様では、起点を切り直さず、除外なしの `withinReference` と `finishedWithinReference` を足した（設計 2.6）。

直しの前の規則（予約しない）で同じ決まった筋書きが落ちることは、仕様の写しで `reserves` を常に偽にして確かめた（下の「突然変異」）。直しの設計の枝（0511d0a3）では、規則を変数 `fixed` で切り替えていた。直しのコードが入るので、この仕様は `fixed` を外し、直した後の規則だけにした。

## typecheck

5つのファイル（`keyMigration.qnt`、`keyMigration_test.qnt`、`keyMigrationGaveUp_test.qnt`、`keyMigrationWriters.qnt`、`keyMigrationWriters_test.qnt`）とも通った。

## test

| コマンド | 結果 |
| :-- | :-- |
| `quint test keyMigration_test.qnt --main keyMigration_test` | 12件とも通った（0.4秒） |
| `quint test keyMigrationGaveUp_test.qnt --main keyMigrationGaveUp_test` | 4件とも通った（7.4秒）。`finishThenRerunTest`、`stopThenRerunTest`、`rejectedOnSharedTargetTest` は各 10,000 回、`cleanRunTest` は1回 |
| `quint test keyMigrationWriters_test.qnt --main keyMigrationWriters_test` | 8件とも通った（0.4秒）。このうち5件は、契約を外すか読み取りが古い値を返すと不変条件が破れることを示すテストで、通ることは「破れた」を意味する |

決まった筋書き（`keyMigrationGaveUp_test.qnt`）は、逆向き、共有の行き先（h と i が h130 を求める）、勝者 h が行き先に着かない、流し直し、を必ず通る。

| テスト | 筋書き | 確かめること |
| :-- | :-- | :-- |
| `finishThenRerunTest`（O-F6） | h が2回とも書かずに失敗し、あきらめる。実行1を最後まで流し、流し直す | 実行1の後：`withinReference`、`finishedWithinReference`、表が起点のまま、残りが h と i。実行2の後：`converges`、`wCleanAfterGaveUp`、表が `{h130: h, iP: i}` |
| `stopThenRerunTest`（C-F1） | b と a も動く表（`START2`）。h があきらめた後の次のラウンド（b を b130 へ）の途中で止め、流し直す | 止める前と後に `withinReference`、`wGaveUpShared`。最後に `converges` と表 `CLEAN_END2` |
| `rejectedOnSharedTargetTest`（R3） | DynamoDB が h を h130 で拒否する（`rejectShared`）。実行1と、流し直しの実行2 | 2回とも：i が動かず、表が起点のまま、残りが h と i。`withinReference`、`finishedWithinReference`、`converges` |
| `cleanRunTest` | 純粋な基準 | 拒否なしでは `{h130: h, iP: i}`、拒否ありでは起点のまま |

### 突然変異（直しの前の規則、案 A′）

仕様の写し（リポジトリの外）で `reserves` を変え、同じテストを流した。

| 写し | `reserves` | 結果 |
| :-- | :-- | :-- |
| 直しの前の規則 | 常に偽（予約しない） | 4件とも落ちた。`finishThenRerunTest` と `stopThenRerunTest` は `assert(withinReference)`（`stopThenRerunTest` は、次のラウンドで i が先に動いた試行で）、`rejectedOnSharedTargetTest` は `assert(tables.get(Journal) == START)`（i が h130 に入った）、`cleanRunTest` は拒否ありの基準 |
| 案 A′（あきらめと `Gone` だけ予約） | `gave up`、`outcome unknown`、`Gone` | `rejectedOnSharedTargetTest` と `cleanRunTest` が落ちた。あきらめの2件は通った |

`rejectedOnSharedTargetTest` が落ちるのは不変条件ではなく表の比較で、直しの前の規則では基準の実行も同じ規則で i を h130 に入れるので、モジュールの不変条件はどれもこの違いを見ない。違いは「dry-run の示す勝者と `--execute` の結果が合う」ことで、dry-run は仕様に入れていない（設計 8.4 の6、C-F8 で別件）。

## run（書き手のいない本体）

### 不変条件ごと

`quint run keyMigration.qnt --max-samples 500 --max-steps 120 --invariant <名前> --seed 0x7`（何でもありの表、`init`）と、`--init initSharedPair --max-samples 1000 --max-steps 60`（共有の組に重みをつけた表）。

| 不変条件 | `init`、500 × 120 | `initSharedPair`、1,000 × 60 |
| :-- | :-- | :-- |
| `noLoss` | 違反なし（5秒） | 違反なし（2秒） |
| `noDuplicate` | 違反なし（8秒） | 違反なし（3秒） |
| `exactlyOnce` | 違反なし（8秒） | 違反なし（3秒） |
| `contentsKept` | 違反なし（6秒） | 違反なし（3秒） |
| `keysKnown` | 違反なし（8秒） | 違反なし（3秒） |
| `unknownStays` | 違反なし（2秒） | 違反なし（2秒） |
| `selectionRespected` | 違反なし（3秒） | 違反なし（2秒） |
| `finishedReported` | 違反なし（2秒） | 違反なし（1秒） |
| `converges` | 違反なし（3秒） | 違反なし（2秒） |
| `roundTrip` | 違反なし（2秒） | 違反なし（1秒） |
| `verifySound` | 違反なし（2秒） | 違反なし（2秒） |
| `verifyReverseShared` | 違反なし（2秒） | 違反なし（2秒） |
| `unreversibleStays` | 違反なし（2秒） | 違反なし（2秒） |
| `withinReference` | 違反なし（15秒） | 違反なし（6秒） |
| `finishedWithinReference` | 違反なし（5秒） | 違反なし（3秒） |
| `versionsKept` | 違反なし（9秒） | 違反なし（3秒） |
| `progress` | 違反なし（2秒） | 違反なし（2秒） |
| `idempotent` | 違反なし（42秒） | 違反なし（27秒） |

### 全部の不変条件（`safety`）と witness

| 条件 | 結果 |
| :-- | :-- |
| `init`、1,000 × 120、`--invariant safety` | 違反なし（108秒） |
| `initSharedPair`、3,000 × 60、`--invariant safety` | 違反なし（55秒） |
| `init`、500 × 60、`--invariant idempotent` | 違反なし（20秒） |

witness は、その経路をいくつのトレースが通ったかの数で、その経路を広く確かめたという意味ではない。

| witness | `init`（1,000 のうち） | `initSharedPair`（3,000 のうち） |
| :-- | :-- | :-- |
| `wMoved` | 1,000 | 2,936 |
| `wMerged` | 607 | 0 |
| `wBlocked` | 852 | 2,253 |
| `wConflict` | 311 | 0 |
| `wRejected` | 729 | 707 |
| `wGaveUp` | 13 | 34 |
| `wGaveUpShared` | 0 | 18 |
| `wRejectedShared` | 0 | 702 |
| `wCleanAfterGaveUp` | 2 | 5 |
| `wProgress` | 487 | 2,034 |
| `wFinishedAfterStop` | 33 | 134 |
| `wRoundTrip` | 453 | 0 |
| `wVerifyForward` | 24 | 78 |
| `wVerifyReverse` | 440 | 0 |
| `wIndexLag` | 1,000 | 2,936 |

- `wGaveUpShared` と `wCleanAfterGaveUp` は、レビュー R6 で定義を直した。`wGaveUpShared`（と `wRejectedShared`）はその実行の候補に h130 を求めるものが2つあること（`--persistence-id h` だけの実行は数えない、#1034 のレビュー）を、`wCleanAfterGaveUp` は同じ起点の前の実行があきらめたこと（`epochGaveUp`）を条件にする。直す前の定義の数（設計 7.6）は、狙った経路より広いものを数えていた。
- `init` の 1,000 トレースは、共有の行き先であきらめる経路（`wGaveUpShared`）を一度も通らなかった。何でもありの表に頼ると見逃す経路で、`initSharedPair` と決まった筋書きで通している。この2つが少ない一因は、`attemptOk` に試みの上限が無く、試みが上限に着いた後も成功しうること（C-F7、別件）。どちらの経路も決まった筋書きが必ず通す。
- `rejectShared`（DynamoDB が h を h130 で拒否する）は `initSharedPair` の4回に1回だけ真で、`init` では偽。`progress` と `roundTrip` の前提が「h は 1.3.0 のキーへ戻れる」だからで、`init` での `wRejectedShared` は 0。共有の行き先での拒否は、`initSharedPair` と `rejectedOnSharedTargetTest` で確かめた。

`quint verify` は流していない。`fullRun` の範囲が定数でなく、Apalache が受け付けない（C-F7、別件）。

## run（書き手のモデル、レビュー O-F3）

`quint run keyMigrationWriters.qnt --main keyMigrationWriters --init <init> --step <step> --max-steps 80 --invariant <名前> --seed 0x7`。アプリは文書の手順を守る：両方の表で最後の `--verify` が一致し、その後にツールが一歩も走っておらず、表が変わっていないときだけ起動し、起動の後はツールを流さない。書き手は journal だけで、snapshot の書き手は無い（C-F8）。確かめられるのは journal の表の側だけ。

| モデル | 条件 | 結果 | アプリが起動したトレース |
| :-- | :-- | :-- | :-- |
| インデックスが単調に追いつく | `--init initG --step stepG`、2,000、`noLossW`、`noDuplicateW`、`deletedStayGone`、`verifySoundW` をそれぞれ | 4つとも違反なし（各3〜4秒） | 291 / 2,000 |
| 読み取りが前の値を返しうる、JournalHead 無効 | `--init initGStale --step stepG`、2,000、`noLossW` | **違反**（636 トレース目）。予想どおり | 83 / 636 |
| 同じ、JournalHead 有効、PlainText のノード | `--init initGStaleHead --step stepGForward`、20,000、4つをそれぞれ | 4つとも違反なし（各30〜38秒） | 106 / 20,000 |

決まった筋書き（`keyMigrationWriters_test.qnt`）：

- `guardedStartTest`：両方の表を移し、インデックスが追いつき、`--verify` が2回一致した後に起動すると、どの読み取りも b を見るので、上書きは起きない。
- `staleReadAfterTwoMatchesTest`：同じ手順の後でも、古い値を返す読み取りで復旧すると、移った b@1 を上書きする（A1）。文書の「2回の一致は確率を下げるだけで、遅れが無いことの証明ではない」と合う。
- `staleReadWithHeadTest`：JournalHead が有効なら、同じ読み取りでも上書きは起きない。文書の「With the JournalHead enabled, … this replacement does not happen」と合う。

JournalHead が有効なときのモデルは、PlainText のノードの復旧の書き込み（同じ番号での書き込み）を起こさないだけで、次の番号で正しく書けることは表していない。確かめたのは「head の番号を読む復旧は、移った行を同じ番号で上書きしない」ことまで。`staleReadWithHeadTest` も、起動の後に書き込みをしない（#1034 のレビュー）。

### 起動の条件の「その後にツールが走っていない」

最初に書いた条件は「最後の `--verify` の表と今の表が同じ」だけで、`initG` の run が `noLossW` の違反を見つけた（2,000 のうち早い段階）。ツールが行を順方向に移して逆向きに戻すと、表は `--verify` のときと同じ値に戻るが、インデックスはまだ遅れている。条件に「最後の `--verify` の後にツールが一歩も走っていない」（`toolsSinceVerify`）を足すと、違反は消えた（上の表）。この条件を外した写しでは、同じ run が再び違反を見つける。文書の手順は「`--execute` の後に `--verify`」の順で書いてあり、この条件を満たす。手順の最後に、ツールを流したら `--verify` をやり直すことを1文足した（`customize.md` の手順 6）。
