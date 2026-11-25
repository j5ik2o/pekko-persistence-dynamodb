# キー移行ツールの設計（出来上がった時点）

対象は [#1025](https://github.com/j5ik2o/pekko-persistence-dynamodb/pull/1025)（main の a5d28a3d）で入ったキー移行ツールで、読んだのは main の edfce815 の時点のコードと `docs/src/sphinx/customize.md` の移行の節。issue は [#1014](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1014)。この文書は、すでにあるコードを説明する。変更の提案は書かない。コードとの食い違いや、確かめられていない点は、それぞれの節に書く。

状態遷移の仕様は `specs/key-migration/` の Quint にある（「Quint の仕様」の節）。

## 1. 何をどう変えるか

### 変えるもの

journal と snapshot の表の行の、主キー（`pkey`, `skey`）だけを変える。1行ずつ、今のキーから「設定されたリゾルバが与えるキー」へ移す。

| 向き | 移す行 | 移す先 |
| :-- | :-- | :-- |
| 順方向、PlainText リゾルバを設定 | 1.3.0 のキーの行、#831 のキーの行 | PlainText のキー |
| 順方向、1.3.0 のリゾルバを設定 | #831 のキーの行だけ | 1.3.0 のキー（この id では両方の形式で同じ） |
| `--reverse` | PlainText のキーの行、#831 のキーの行 | 1.3.0 のキー |

- 行の中身（キー以外の属性）は変えない。例外は「同じ行の写し」を1つにまとめるとき（2.4）で、journal は `deleted` をどちらかが真なら真にし、snapshot は `created` の大きい方を残す。
- 1行の移動は1つの `TransactWriteItems` で行う。新しいキーへの `Put`（キーに行が無いことが条件）と、古いキーの `Delete`（読んだときの属性がすべてそのままであることが条件）の2つ。

### 変えないもの（範囲の外）

- JournalHead の表。キーが persistence id だけなので動かさない。
- state（Durable State）の表。移行ツールは無い。
- 表とインデックスの定義。`--verify` はインデックスの定義を読むが、変えない。
- 1.3.0 と PlainText 以外のリゾルバ、既定以外のリゾルバプロバイダ、sort key が sequence number の journal、`legacy-table-format` の snapshot。どれも拒否して始めない（`JournalKeyMigration.configuredForm`、`SnapshotKeyMigration.configuredForm`）。
- 1.2.0 までに上書きで失われた行は戻せない。キーを共有していた組を一覧に出すだけ。
- 書き込む側（`JournalRowStore`、`SnapshotStore`）。#1025 の途中で削除の順を変えたが、最後のコミットで main の順に戻した。最終の差分に store の変更は無い。
- `legacy-keys-enabled` の値。ツールは変えず、`--verify` が「false にしてよいか」を表示するだけ。

## 2. 動き

### 2.1 置き場所の判定（`ResolverKeyPlan.placement`）

行ごとに、行の属性にある persistence id と sequence number から、1.3.0 のキー（`release130`、作れなければ無し）と PlainText のキー（`plainText`）を、リゾルバと同じ関数で計算する。結果は次の3つのどれか。

- `InPlace`：今のキーが行き先と同じ。
- `Move(target, from)`：1.3.0 のキー、または #831 のキーにあり、`target` へ移す。
- `Unmovable(reason)`：どの形でもないキー、順方向で PlainText でない設定なのに新しい形式のキーにある行、`--reverse` で 1.3.0 がキーを作れない id の行。

#831 のキーは、行き先と同じ `pkey` で、`skey` が `"%019d".format(sequenceNr)` のもの（`isIssue831Key`）。1.3.0 のキーも PlainText のキーも、`skey` に必ず `-` を含むか、ハッシュの形（`#h1#…#`）なので、#831 のキーとは重ならない。

### 2.2 走査（`KeyMigration.Session.scanTable`）

表全体を強い整合の Scan で読み、選んだ persistence id（`--persistence-id`）の行だけを判定する。`Move` は候補（`Candidate`）、`Unmovable` は一覧に入る。すべての id について行数・最大の sequence number・抜けている番号を数え、1.3.0 でキーを共有していた組（`sharedKeyGroup` が同じ値の id の組）を一覧にする。

### 2.3 dry-run（`dryRun`）

書き込まない。候補ごとに行き先のキーを GetItem で読み、行き先を持つ行をたどって、直接移せる・別の移動の後・循環・同じ行の写し（まとめる）・ほかの id の動かない行にふさがれている・ふさがれた行を待つ、に分ける。同じ行き先を複数の候補が求めるときは、今のキーが最も小さいもの（`winnerOf`）が行き先を得る。写しの中身が違えば「まとめない」として一覧に出す。

### 2.4 実行（`execute`、`--execute --writers-stopped`）

ラウンドを繰り返す。各ラウンドの始めに、保留中の候補から `ready` を作る：行き先ごとに `winnerOf` で1つ選び、行き先を保留中のほかの候補が持っているものは外す。`ready` を並行に `moveOne` し、全部の結果が出たら保留から外す。`ready` が空になったら止め、残りは循環か循環を待つ行として残す（`findCycles`）。

`moveOne` の1回の試み：

1. 元のキーを読む。行がこの候補の行（同じ id と番号）なら、移動のトランザクションを送る。
2. 成功すれば `Moved`。
3. 失敗（条件で取り消し、応答が無い）なら、行き先を読む。
   - 同じ行なら、元をもう一度読み、キーとルールの列以外を比べる（`differences`）。違えば `Conflict`。同じなら、まとめるトランザクション（`merge`：行き先を残す条件つきの書き換えか確認と、元の条件つき削除）を送り、成功で `Merged`。
   - ほかの行なら `Blocked`。
   - 空なら次の試みへ。
4. 元のキーに行が無い（前の試みが応答を失ったが反映された）なら、行き先にこの行があれば `Moved`（まとめの途中なら `Merged`）、無ければ `Gone`。
5. `ValidationException`（400 KB を超えるなど）は `Failed` で、やり直さない。そのほかの例外は次の試みへ。
6. `maxAttempts`（10）回で決まらなければ `settle` が両方のキーを読んで決める。

結果が `Blocked`、`Conflict`、`Failed` の行は「残り」として報告する。`--execute` は、残りか動かせない行か、ほかの書き手の兆候（`writerSigns`）があれば、終了コード 1 で終わる（`MigrationReport.incomplete`）。

### 2.5 確かめ（`KeyVerification.run`、`--verify`）

`--persistence-id` を受け付けない。インデックスの定義（キーと型、ACTIVE、読まれる属性の射影）を確かめ、表全体を Scan して全行を判定し、インデックス全体を Scan して、persistence id ごとに表とインデックスの行を SHA-256 の要約で比べる。違う id は `--verify-timeout` まで Query し直す。`--reverse` では、1.3.0 でキーを共有する id が2つ以上表にあれば一致としない。一致は「見た時点で」の観察で、後の読み取りが同じものを見ることは保証しない。

## 3. 守る不変条件

書き手が止まっている（契約）前提で守るもの。括弧は Quint の名前。

1. 行が消えない（`noLoss`）。初めにあった論理的な行（persistence id と sequence number の組）は、どの時点でも少なくとも1つ写しがある。
2. 行が増えない（`noDuplicate`）。論理的な行の写しの数は初めの数を超えない。初めに無かった行は現れない。
3. 写しが1つの行は、どの時点でも、ちょうど1つのキーにある（`exactlyOnce`）。初めから写しが2つある行（1.3.0 のキーと新しいキーの両方）は2から1に減ることだけがある。
4. 中身が保たれる（`contentsKept`）。写しの属性は初めのどれかの写しのもの。journal の `deleted` が真になるのは、初めのどれかの写しが真のときだけ。snapshot の `created` は初めのどれかの値。
5. 行は、自分の形のキー（1.3.0、PlainText、#831、どの形でもないキー）か、初めにあったキーにだけある（`keysKnown`）。
6. どの形でもないキーの行は動かず、変わらない（`unknownStays`）。
7. `--persistence-id` で選ばなかった id の行は、その実行の間に変わらない（`selectionRespected`）。
8. 実行が終わったとき、選んだ id の行は、置き場所にあるか、残りか動かせない行として一覧に出ている（`finishedReported`）。
9. 収束（`converges`）。同じ条件の実行を何度途中で止めても、応答を何度失っても、一時的な失敗が何度あっても、あきらめ（`settle` の `Failed`）なしで終わった実行の結果は、最初の実行の開始時の表から一度も止めずに実行した結果と同じ。
10. 冪等（`idempotent`）。止めずに実行した後、もう一度実行しても表は変わらない。向きと設定の4通りすべて。
11. 往復（`roundTrip`）。1.3.0 だけが書いた表から始め、順方向の実行（途中で止めても、id を選んでも、どちらの設定でも）の後に、全 id の `--reverse` があきらめなしで終われば、表は 1.3.0 の表に戻る。#831 の行は、元の #831 のキーではなく 1.3.0 のキーに戻る（両方の形式がこの id に与えるキー）。
12. 順方向・PlainText の `--verify` が一致したなら、初めのすべての論理的な行が新しいキーに1つだけある（`verifySound`）。
13. `--reverse` の `--verify` は、キーを共有する組の id が2つ以上表にある間は一致しない（`verifyReverseShared`）。
14. 1.3.0 がキーを作れない id の行は、`--reverse` で動かない（`unreversibleStays`）。

### 往復から除く組と、その中で守るもの

往復（11）は「1.3.0 だけが書いた表」から始めた場合に限る。除く組とその理由：

| 除く組 | 理由 | その中でも守るもの |
| :-- | :-- | :-- |
| 1.3.0 がキーを作れない id（`order-`、`.` を区切りにした id など） | 戻す先のキーが無い。1.3.0 の表には無い行 | 消えない・増えない（1〜3）、動かない（14）、一覧に出る（8） |
| 1.3.0 でキーを共有する組の両方に行がある場合 | 戻すと同じキーを求め、片方しか戻れない | 1〜3、`winnerOf` の1つだけが戻り、ほかは `Blocked` で一覧（8）、`--verify --reverse` は一致しない（13） |
| 両方のキーに写しがある行 | まとめると1つになり、元の2つには戻らない | 1〜4（まとめは減るだけで、中身は規則どおり） |
| 写しの中身が違う行 | まとめない | 1〜4、動かず一覧（8） |
| #831 の行 | 1.3.0 のキー（両形式で同じ）へ移る | 1〜4、往復の結果は 1.3.0 のキー |
| 新しいキーで書かれた行（1.4.0 が書いた） | 元が 1.3.0 のキーではない | 1〜4。`--reverse` は 1.3.0 のキーへ移す（巻き戻しの目的どおり） |

キーを共有する組のうち、片方の行しか無い場合（1.3.0 の表ではこれしか起きない）は往復の対象に入れていて、戻る。

### 収束から除くもの

`settle` が「あきらめ」（10回で決まらない）を返した実行は、収束（9）の対象から外す。その行は動かずに残り、次の実行で続きを行う。止まらずに終わった実行と同じ結果になるのは、あきらめなしで終わった実行。

## 4. PBT の性質

この PR のテストに、ScalaCheck などの生成による性質のテストは無い。性質に近いものは、`KeyMigrationPlanSpec` の全数の列挙だけ。

### いまのテストで確かめている性質

| 性質 | テスト | 範囲 |
| :-- | :-- | :-- |
| 順方向は、1.3.0 と PlainText のキーが違うときにちょうど `Move`、同じなら `InPlace`。PlainText のキーの行は常に `InPlace` | `KeyMigrationPlanSpec`「move a row exactly when…」 | 文字 `a`、`b`、区切りからなる4文字以下の全 id、区切り `-`、`\|`、`::`、`.`、`-+`、journal と snapshot、sequence number 1 だけ |
| `--reverse` は、1.3.0 がキーを作れるときに 1.3.0 のキーへ `Move`、作れなければ `Unmovable`。自分自身のキーへは動かない | 同上 | 同上 |
| #831 の行は両方向で、両形式が与えるキーへ移る。ゼロの無い番号は認めない | `KeyMigrationPlanSpec`、`ReleasedKeysSpec` | `Plain123` など固定の id |
| 公開版が書いたキー（1.0.2〜1.0.59、1.3.0） | `ReleasedKeysSpec` | 固定値。区切り `-`、shard 数 1024 と 64 |
| 長さの境界（2,048 バイトと 1,024 バイト）とハッシュの形 | `KeyMigrationSpec`、`KeyMigrationPlanSpec` | `あ`、`€` を並べた固定の id |

ほかは例によるテスト（`KeyMigrationSpec` 約40件、`JournalKeyMigrationSpec` 約20件）で、1回の実行、順序つきの移動、循環、ふさがれた行、まとめ（journal、snapshot）、応答の消失、途中停止（停止点は1つか2つ）、ほかの書き手の兆候、`--verify` の各条件を確かめている。

### 確かめていない性質

テストでは確かめていない。Quint で確かめた範囲を右に書く。

| 性質 | Quint |
| :-- | :-- |
| 任意の停止点・任意の応答消失・一時的な失敗の組み合わせで、結果が同じになる（収束） | `converges`（抽象した13行の宇宙で） |
| 任意の表で、2回目の実行は何も動かさない（冪等） | `idempotent` |
| 任意の 1.3.0 の表で、順方向の後に戻すと元に戻る（往復） | `roundTrip` |
| 任意の表・任意の時点で、行が消えない・増えない・中身が保たれる | `noLoss`、`noDuplicate`、`exactlyOnce`、`contentsKept` |
| `--persistence-id` の外の行は変わらない | `selectionRespected` |
| dry-run の分類と `--execute` の結果が一致する | 確かめていない（dry-run は仕様に入れていない） |
| 4文字を超える id、ほかの文字（Unicode の結合文字、サロゲートペア、正規化の違う同じ見た目の文字）、区切りの全種類 | 確かめていない |
| sequence number が 1 以外（桁数の境界 `Long.MaxValue` を含む） | 確かめていない |
| 区切りの数字が ASCII でないロケール（`ar-EG` など）で「動かせない」と一覧に出る | 確かめていない。ドキュメントの記述はテストが無い |

### 除外

性質の除外は、3節の「往復から除く組」と「収束から除くもの」だけ。どちらも、その組の中で守るものを別の不変条件で確かめている。

## 5. 壊しうる入力

| 入力 | 起きること | 扱っている場所 | テスト | Quint |
| :-- | :-- | :-- | :-- | :-- |
| 区切りが正規表現の記号（`\|`、`.`、`-+`、`a.` など） | 1.3.0 の分割が違う。`.` などは 1.3.0 が例外で書けず、行は無い | `PersistenceIdKeyParts.legacyPrefix`、`release130Key` の `Try` | `KeyMigrationPlanSpec`（5種） | キー関数は抽象。関係の型（下の「キーの関係」）だけを持つ |
| 区切りで終わる id（`order-`） | 1.3.0 が書けない。`--reverse` で `Unmovable` | `placement` | `JournalKeyMigrationSpec`「--reverse」 | j |
| 区切りが2つ以上（`shopping-cart-1`） | `skey` だけ変わる。新しいキーがほかの id の古いキーになりうる（`Order-` と `Order--23`） | `ready` の順、`Blocked` | `KeyMigrationSpec`、`JournalKeyMigrationSpec`「Order-」 | a、b |
| 長さの境界（`pkey` 2,048 バイト、`skey` 1,024 バイト）とハッシュの形 | 1.3.0 で長すぎた id は行が無い。新しいキーがハッシュの形になる | `fitsDynamoDB`、`PersistenceIdKeyParts.sortKey` | あり（固定値） | 抽象 |
| 新しいキーで 400 KB を超える行 | `Put` が `ValidationException`。`Failed` で残る | `moveOne` | `KeyMigrationSpec` | f |
| Unicode | 長さは UTF-8 のバイトで測る。正規化はしない。見た目が同じで違う id は別の id | `PersistenceIdKeyParts` | 3バイト文字の境界だけ | 範囲外 |
| ロケール | 番号と shard の数字は既定の書式ロケール。書いたアプリと違うロケールでは全行が「どの形でもない」 | `"%019d".format`、起動時にロケールを表示 | 表示だけ | 範囲外 |
| #831 の行 | 両方向で、両形式が与えるキーへ。同じ shard の区切りの無い2つの id は #831 のキーを共有し、表には片方しか無い | `isIssue831Key` | `ReleasedKeysSpec`、`JournalKeyMigrationSpec` | g、x |
| 1.3.0 でキーを共有する組 | 順方向はそれぞれのキーへ。`--reverse` は1つだけ戻り、ほかは `Blocked`。`--verify --reverse` は一致しない | `winnerOf`、`KeyVerification` | `KeyMigrationSpec`、`JournalKeyMigrationSpec` | h、i |
| 両方のキーに写しがある行 | 中身が同じならまとめる（journal は `deleted` の論理和、snapshot は `created` の大きい方）。違えば両方残して一覧 | `differences`、`merge` | あり | k |
| ソフト削除された行（`deleted = true`） | そのまま移る。まとめでは真が勝つ | `merge` | あり | `deleted` |
| snapshot | 同じ動き。重複の規則だけ違う。番号の抜けは数えない | `SnapshotKeyMigration.table` | あり | 2つ目の表 |
| 途中停止 | トランザクションは原子的なので、行は古いキーか新しいキーのどちらかにある。次の実行は走査からやり直す | `transact` | 停止点1〜2個 | 任意の停止点 |
| 応答の消失 | 次の試みで両方のキーを読んで決める。最後の試みなら `settle` | `moveOne`、`settle` | あり | `attemptLost`、`settle` |
| 二重起動（同じ表に2つの `--execute`） | 防ぐ仕組みは無い。条件つきの書き込みなので、同じ行を2つが動かしても、片方が `Moved`、他方が「行がキーを離れた」（`Gone` か `Moved`）になる。行は消えない | `moveOne` の条件 | 無し | 無し（ツールは1つ） |
| 索引（GSI）の遅れ | `--execute` の後しばらく、インデックスは移った行を2回見せるか見せない | `--verify` の再問い合わせ、ドキュメント | DynamoDB Local は即時更新で再現できない | `propagate`、書き手のモデル |
| `--persistence-id` | 選んだ id の行だけ動く。行き先を選ばなかった id の行が持てば「選択の外」として一覧 | `selected` | あり | `sel` |
| サイズの大きい表 | 1回の Scan と候補ごとの GetItem、TransactWriteItems | `--max-rate`、`--parallelism` | DynamoDB Local で 10万行 | 範囲外 |

### キーの関係

Quint のキーは文字列の名前で、リゾルバの計算はしない。代わりに、実物のキーの間に起きうる関係を、行の組として全部入れている。

| 関係 | 実物の例 | Quint の行 |
| :-- | :-- | :-- |
| 1.3.0 のキー = 新しいキー | 区切りが1つの id、区切りの無い id | g、x |
| ある id の新しいキー = 別の id の1.3.0 のキー | `Order-` と `Order--23` | a、b |
| 上の関係が循環 | 理屈の上で | c、d |
| 新しいキーを、動けない別の id の行が持つ | 400 KB を超える行、選択の外の行 | e、f |
| 2つの id の 1.3.0 のキーが同じ | #1009 | h、i |
| 1.3.0 のキーが無い | `order-` | j |
| #831 のキーが同じ | 区切りの無い id、同じ shard | g、x |
| どの形でもないキー | 別の shard-count | u |

起きない関係も確かめた：新しいキーは id ごとに違う（PlainText は id 全体をキーに入れる）。#831 のキーは、1.3.0 のキーとも新しいキーとも重ならない（2.1）。

## 6. 影響

- 保存済みのデータ：`--execute` を流したときだけ行のキーが変わる。dry-run と `--verify` は書かない。動かした行は DynamoDB Streams と Kinesis Data Streams に、挿入と削除として流れる。
- 公開 API：`JournalKeyMigration` と `SnapshotKeyMigration` の `main` だけが公開（`schema-check/public-api-baseline.txt` に4行）。ほかは `private[dynamodb]`。MiMa は追加なので影響しない。
- 守りの仕組み：
  - `--execute` は `--writers-stopped` が無ければ始めない（`MigrationOptions.parse`）。止まっていることは確かめられない。
  - 移動の条件（`Put` は行き先が空、`Delete` は読んだときの属性すべて）。ほかの書き手が間に入るとトランザクションが取り消され、読み直して今の行を移す。
  - ほかの書き手の兆候（行がどちらのキーにも無い、読んだ後に変わった、走査で見なかった行が行き先にある）を警告し、終了コード 1。兆候の無いことは、書き手が止まっていた証明にならない。
  - `--verify` が、`legacy-keys-enabled = false` にしてよいか、アプリを起動してよいかの最後の確かめ。
  - アプリ側の守り：書き込みは「行が無いか、同じ persistence id の行」が条件（`JournalRowStore.sameRowOrNone`）。ほかの id の行は上書きしないが、同じ id の行は上書きする。

## 7. 分かっている限界

- オフラインの契約。`--execute` の間と、インデックスが追いつくまでは、書くアプリも読むアプリも止めておく。破るとどの不変条件が破れるかは、Quint の書き手のモデルで反例を作った（8.5）。
- GSI の結果整合。`--verify` の一致は見た時点のもの。2回の一致を間を空けて取ることをドキュメントは勧めるが、遅れが無いことの証明にはならない。DynamoDB がインデックスを更新する時間は測っていない。Quint のインデックスは、キーごとに古い値から新しい値へ一度だけ変わる（単調）。読むたびに古い値と新しい値が入れ替わる読み取りは入れていない。
- JournalHead を有効にしていれば、復旧で番号を取り違えた上書きは起きない（ドキュメント）。無効なら起きる。書き込み側の守りは [#1026](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1026) で追っている。
- DAX。ツールは DAX を通らない（v2 のクライアントを作り直す）。アプリは起動前に DAX の項目キャッシュとクエリキャッシュを捨てる必要がある。DAX では測っていない。
- DynamoDB Local でしか測っていない。公開版の JAR が書いた行での試験は [#1032](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1032) と [#1027](https://github.com/j5ik2o/pekko-persistence-dynamodb/issues/1027)。
- 二重起動を防ぐ仕組みは無い（5節）。
- 循環は手で解く。ツールは入れ替えない。

## 8. Quint の仕様

### 8.1 ファイル

| ファイル | 中身 |
| :-- | :-- |
| `specs/key-migration/keyMigration.qnt` | 本体。表、インデックス、ツールの実行、停止、`--verify`。書き手はいない |
| `specs/key-migration/keyMigration_test.qnt` | 純粋な関数のテスト（判定、ラウンド、まとめ、往復） |
| `specs/key-migration/keyMigrationWriters.qnt` | 契約を外したモデル。アプリが書き込み・復旧・削除をする |
| `specs/key-migration/keyMigrationWriters_test.qnt` | 契約を外したときの反例を、固定のトレースで示すテスト |

### 8.2 状態と遷移

- 状態：`tables`（journal と snapshot、キー → 行）、`idx`（それぞれのインデックスの写し、遅れる）、`tools`（ツールの実行の状態：保留、今のラウンド、試みの回数、まとめ中の候補、結果、残り、動かせない行）。検査のための履歴として、初めの表、収束の比較の起点、最後に終わった実行、最後の `--verify`。
- 初めの表は3種類から選ぶ：何でもありの表、1.3.0 だけが書いた表、全行が動ける表。各論理的な行の置き場所（無し、1.3.0 のキー、新しいキー、両方、#831 か不明のキー）、写しの中身、`deleted`、`created` を非決定的に選ぶ。
- 遷移：`startRun`（向き・設定・選択を選んで走査）、`nextRound`（`ready` を作るか、終える）、`attemptOk`（試みの応答が届く）、`attemptLost`（反映されたが応答が失われる）、`attemptTransient`（書かずに失敗）、`settle`、`stop`（プロセスが止まる）、`propagate`（インデックスが1つのキーで追いつく）、`verify`。
- 停止・応答の消失・一時的な失敗の合計は、1つのトレースで6回まで（`budget`）。

### 8.3 コードとの対応

| Quint | コード |
| :-- | :-- |
| `placement` | `ResolverKeyPlan.placement`（ResolverKeyPlan.scala:50-77） |
| `is831` | `ResolverKeyPlan.isIssue831Key`（:47-48）。Quint は `pkey` の比較をせず、#831 のキーを行ごとに与える |
| `GROUP`、`sharedGroupsPresent` | `ResolverKeyPlan.sharedKeyGroup`（:80-84）、`KeyVerification.run` の `shared`（KeyVerification.scala:136-145） |
| `scanCandidates`、`scanUnmovable` | `Session.scanTable`（KeyMigration.scala:553-641）、`selected`（:508） |
| `winnerOf` | `Session.winnerOf`（:750-751） |
| `readyOf` | `execute` の `ready`（:776-782） |
| `nextRound` | `execute` のループ（:772-819）と、残りを循環・待ちとする部分（:822-827） |
| `attempt` | `moveOne` の1回の試み（:1037-1082）、`put`・`delete` の条件（:917-941） |
| `mergeTx` | `merge`（:1123-1135）、`markDeleted`（:956-969） |
| `differ` | `differences`（:1011-1024） |
| `tooBig` | `ValidationException` の `Failed`（:1084-1085） |
| `attemptLost` | `transact` の `Left`（応答が無い）と、次の試みで元が無いときの分岐（:1067-1069、:1076-1081） |
| `settleOutcome` | `settle`（:1098-1110） |
| `MAX_ATTEMPTS` | `MigrationSettings.maxAttempts`（:110）。Quint は 2、コードは 10 |
| `verifyPass` | `KeyVerification.run` と `VerifyReport.verified`（KeyVerification.scala:70-72、:91-205） |
| `propagate` | DynamoDB の GSI の非同期の更新（コードには無い） |
| `recoverAndWrite` | `JournalRowStore.writeRows` の条件 `sameRowOrNone`（JournalRowStore.scala:222-223、:347-348）と、`highestSequenceNr` がインデックスを読むこと（:118-140） |
| `hardDeleteCurrent`、`hardDeleteLegacy` | `JournalRowStore.delete` = `deleteRows` の後に `deleteLegacyRows`（:288-340） |

### 8.4 実物と合わない点（抽象にしたところ）

1. `moveOne` の「読む → トランザクション」を1つの原子的な遷移にした。書き手がいなければ間に表は変わらない。書き手がいても、`Delete` が読んだ属性すべてを条件にするので、間に変わればトランザクションは取り消され、次の試みで読み直す。このため、原子的にしても結果は変わらない、と判断した。書き手のモデルでも同じ抽象を使っている。
2. 1つのラウンドの候補は、コードでは並行に動く。Quint では1つずつ任意の順に動かす。1つのラウンドの候補は互いに違うキーを触る（行き先は行き先ごとに1つ、元のキーは別々、行き先を保留中の候補が持つものは外す）ので、順は結果を変えない。
3. 試みの上限は 2（コードは 10）。上限の値は、`settle` に着くまでの長さだけを変える。
4. `--verify` は表とインデックスを同時に1回見る。コードは表を Scan し、インデックスを Scan し、違う id を時間切れまで問い合わせ直す。時間切れ、問い合わせの失敗、インデックスの定義の検査、SHA-256 の衝突は入れていない。要約の一致は、行の完全な一致として扱った。
5. インデックスはキーごとに単調に追いつく（7節）。
6. dry-run は入れていない。dry-run の予測と `--execute` の結果が一致するかは確かめていない。
7. 宇宙は13の論理的な行で、sequence number は 1 だけ。番号の抜けの報告（`IdSummary`）、`--segments`、`--max-rate`、`--scan-page-size`、進捗の表示、使用量は入れていない。
8. `--persistence-id` は、全 id か1つの id。
9. 400 KB の超過は、新しいキーへの `Put` だけ。snapshot のまとめで行き先へ `Put` するときの超過は入れていない。
10. ほかの書き手の兆候（`writerSigns`）は入れていない。書き手のいないモデルでは出ない。
11. journal と snapshot の実行は互いに独立で、`legacy-keys-enabled` の設定とアプリの読み取りは、書き手のモデルの `recoverAndWrite` 以外では入れていない。
12. 収束と冪等の基準 `fullRun` は、ツールと同じ `readyOf` と `attempt` で書いた。比べているのは「止まらない1回の実行」と「止まり、応答を失い、失敗する実行」の違いで、ラウンドの規則そのものの正しさではない。ラウンドの規則は、ほかの不変条件（消えない、増えない、一覧に出る、往復）で確かめている。

### 8.5 契約を外したモデル（`keyMigrationWriters.qnt`）

アプリは、journal の行 b@1（直接移る行）だけに書く。b は新しいキーが空いていて、キーを誰とも共有しないので、破れはどれも移行から来る。

| 破れる不変条件 | 反例 | コードの場所 | 実データで起きうるか |
| :-- | :-- | :-- | :-- |
| 消えない・中身が保たれる（`noLossW`） | ツールが b130 → bP を移す。インデックスが b130 を消し、bP をまだ足していない。PlainText のアプリが復旧し、インデックスから最大の番号を 0 と読み、b@1 を bP に書く。条件は「空か同じ id」なので、移った行を上書きする | `JournalRowStore.writeRows` の `sameRowOrNone`、`highestSequenceNr` | 起きうる。JournalHead が無効のとき。ドキュメントに書いてある（customize.md「Stop the application」と「Before you start」） |
| `--verify` の一致の意味（`verifySoundW`） | 上の後でインデックスが追いつくと、順方向の `--verify` は一致する。表には元の b@1 が無い | `KeyVerification` は今の表とインデックスしか比べない | 起きうる。ドキュメントに「an overwrite after a recovery leaves a table that --verify matches」とある |
| 増えない（`noDuplicateW`） | 同じ遅れの間に 1.3.0 のノードが復旧し、b@1 を b130 に書く。b@1 が2つになる（中身は別） | 1.3.0 の書き込み | 起きうる。ドキュメントの「a node of 1.3.0 keeps writing under the old keys」 |
| 削除した行が消える（`deletedStayGone`） | アプリの hard delete が bP を消す（まだ空）→ ツールが b130 → bP を移す → アプリが b130 を消す（もう空）。削除した b@1 が bP に残る | `JournalRowStore.delete` の2つの `DeleteItem` | 起きうる。ドキュメントの「a hard delete … can miss the row that moves between its requests」 |

`--reverse` の後に 1.3.0 のアプリがインデックスの遅れの間に起動しても、同じ形で上書きが起きる（ランダムな実行で見つかった反例）。

書き手のいない同じ手順（`withoutTheApplicationTest`）では、本体の不変条件はすべて成り立つ。

### 8.6 検査の結果

`docs/design/key-migration-quint-results.md` に、コマンド、回数、所要時間、結果を残した。
