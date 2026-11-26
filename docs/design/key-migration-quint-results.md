# キー移行の Quint 仕様：検査の結果

仕様は `specs/key-migration/`、設計は `docs/design/key-migration.md`。Quint 0.32.0（`~/.local/bin/quint`）、2026-11-26、Apple Silicon の手元の機械（ほかの作業で負荷あり）。所要時間は実時間。

すべて `specs/key-migration/` で実行した。

この結果は 5ff2d62d の仕様のもの。`fix/key-migration-before-1.4.0` で足した性質と決まった筋書きの結果は、`docs/design/key-migration-fix-before-1.4.0.md` の 2.7 と 7.6 にある。そこで、ここの `converges` の「違反なし」は、あきらめの後に比べる起点を切り直していたためで、反例（レビュー C-F1）を見逃していたと分かった。

## typecheck

```
quint typecheck keyMigration.qnt
quint typecheck keyMigration_test.qnt
quint typecheck keyMigrationWriters.qnt
quint typecheck keyMigrationWriters_test.qnt
```

4つとも通った。

## test

| コマンド | 結果 |
| :-- | :-- |
| `quint test keyMigration_test.qnt --main keyMigration_test` | 12件とも通った（約0.4秒） |
| `quint test keyMigrationWriters_test.qnt --main keyMigrationWriters_test` | 5件とも通った（約0.2秒）。このうち4件は、契約を外すと不変条件が破れることを示すテストで、通ることは「破れた」を意味する |

## run（書き手のいない本体、不変条件ごと）

`quint run keyMigration.qnt --max-samples 3000 --max-steps 120 --invariant <名前> --seed 0x7`。不変条件ごとに3,000トレース、各120ステップ。`idempotent` は1回の評価が重いので1,000トレース・60ステップ。

| 不変条件 | 結果 | 所要 |
| :-- | :-- | :-- |
| `noLoss` | 違反なし | 32秒 |
| `noDuplicate` | 違反なし | 52秒 |
| `exactlyOnce` | 違反なし | 50秒 |
| `contentsKept` | 違反なし | 41秒 |
| `keysKnown` | 違反なし | 59秒 |
| `unknownStays` | 違反なし | 16秒 |
| `selectionRespected` | 違反なし | 21秒 |
| `finishedReported` | 違反なし | 16秒 |
| `converges` | 違反なし | 23秒 |
| `roundTrip` | 違反なし | 16秒 |
| `verifySound` | 違反なし | 17秒 |
| `verifyReverseShared` | 違反なし | 18秒 |
| `unreversibleStays` | 違反なし | 16秒 |
| `idempotent`（1,000 × 60） | 違反なし | 44秒 |

`winnerOf` を `getOnlyElement` から `fold` に書き換える前（意味は同じ）にも、seed 0x2a で同じ回数を流し、すべて違反なしだった。

## 到達の確かめ（witness）

`quint run keyMigration.qnt --max-samples 1000 --max-steps 150 --seed 0x7 --invariant safety --witnesses …`（87秒、`safety` は全不変条件の論理積で違反なし）。

| witness | 到達したトレース |
| :-- | :-- |
| `wMoved`（移動） | 1000 / 1000 |
| `wMerged`（写しのまとめ） | 644 |
| `wBlocked`（ふさがれた行） | 878 |
| `wConflict`（中身の違う写し） | 331 |
| `wRejected`（400 KB 超過） | 760 |
| `wGaveUp`（`settle` のあきらめ） | 15 |
| `wMergedAfterLost`（まとめの応答が消えた後の `Merged`） | 52 |
| `wCycleRemaining`（循環が残る） | 625 |
| `wFinishedForward`（順方向の実行の完了） | 993 |
| `wFinishedAfterStop`（途中停止の後の完了。収束の比較が働く場合） | 46 |
| `wRoundTrip`（順方向の後の `--reverse` の完了。往復の比較が働く場合） | 498 |
| `wVerifyForward`（順方向 `--verify` の一致） | 20 |
| `wVerifyReverse`（`--reverse --verify` の一致） | 399 |
| `wIndexLag`（インデックスの遅れ） | 1000 |

到達しないもの（0件）は無い。収束と順方向 `--verify` の比較が働くトレースは数%なので、3,000トレースの run では、それぞれ百件前後で比較が働いた見込み。

## 契約を外したモデル

固定のトレース（`keyMigrationWriters_test.qnt`）で、4つの不変条件がすべて破れた。

| テスト | 破れた不変条件 |
| :-- | :-- |
| `recoveryOverwritesMovedEventTest` | `noLossW`（移った行を、復旧したアプリが同じ番号で上書き） |
| `verifyMatchesAfterOverwriteTest` | `verifySoundW`（その後、順方向 `--verify` が一致する） |
| `oldNodeDuplicatesMovedEventTest` | `noDuplicateW`（1.3.0 のノードが古いキーに書き、写しが2つ） |
| `hardDeleteMissesMovedEventTest` | `deletedStayGone`（hard delete の2つの DeleteItem の間に移動が入り、削除した行が残る） |
| `withoutTheApplicationTest` | 同じ手順でアプリが動かなければ、本体の `safety` と4つともが成り立つ |

ランダムな実行（`quint run keyMigrationWriters.qnt --main keyMigrationWriters --init initW --step stepW --invariant <名前>`）：

- アプリが全部の行に書くモデル（最初の版）、seed 0x1、2,000 × 80：`noLossW` は4秒で違反（`--reverse` で bP → b130 と移った後、インデックスの遅れの間に 1.3.0 のアプリが b130 に書き、上書き）。`noDuplicateW` と `deletedStayGone` も違反したが、#831 のキーや不明のキーの行、つまり移行と関係の無い原因だったので、アプリが b@1 だけに書く形に絞った。
- 絞った後、seed 0x2a、3,000 × 80：4つとも違反は見つからなかった（各10〜27秒）。破れるには、移動とインデックスの更新とアプリの手順が特定の順に並ぶ必要があり、ランダムな実行ではその順に当たらなかった。反例は上の固定のトレースで示した。

## verify（Apalache）

`quint verify keyMigration.qnt --max-steps 2 --invariant noLoss`：

- 1回目：Apalache への変換で内部エラー（`getOnlyElement` の変換で `No entry found for id … in lookup table`）。`winnerOf` を `fold` に書き換えた。
- 2回目：（結果は下に追記）
- 2回目（2026-11-26 08:16〜08:25 JST、依頼元の pekko-persistence中将が流した。担当は Claude の週の上限で止まったため）：`timeout 540 quint verify keyMigration.qnt --max-steps 2 --invariant noLoss`。変換は通り（BoundedChecker まで PASS）、Apalache が State 0 の不変条件の検査に入ったまま、9分の打ち切りで終わった。**結果は出ていない**（違反も、成り立つことも示せていない）。状態の大きさ（表の行と写しの集合）が、記号的な検査には重いと見られる。有界の網羅的な確かめは、仕様を小さくした別のモデル（行を2つ程度に固定）で行う余地がある。いまの保証は、上の `quint run` と `quint test`、witness による到達の確かめまで。
