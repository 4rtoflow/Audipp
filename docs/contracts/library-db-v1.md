# ライブラリDB v1（Issue #29）

対象: [Issue #29](https://github.com/4rtoflow/Audipp/issues/29)。前提の共通domain契約はmainの `a237325`（#16 / PR #161）。以下はDD-DB-01/02のSQL型・索引・配置を具体化した実装案で、製品条件の変更ではない。

## 保存と所有権

単一 `:app` の `com.audipp.data.db` に `LibraryDatabase`、6 Entity、`LibraryDao` を配置する。`LibraryDatabase.open(context)` でアプリ専用領域の `library.db` を開く。呼出側がDBの寿命を所有し、同じアプリプロセス内で共有し、不要時に閉じる。UI、SAF走査、Provider通信、Resolver、キュー/プレイリストのテーブルは本Issueでは追加しない。

`LibraryDao.save(LibraryBatch(...))` はSource・Track・Album・Artist・Variant・TrackArtistの順で単一トランザクションに保存する。同じ主キーは属性を更新し、IDを採番し直さない。省略した行は保持する。部分走査の未訪問行を消失とみなさず、走査完了の判断・同一ルート走査の直列化は #35 側が所有する。失敗は例外、取消はキャンセルとして呼出側へ伝え、バッチ全体をrollbackする。失敗後は同じIDで再試行できる。IOはRoomのsuspend DAOで行い、Main上のDBアクセスは許可しない。

SourceとTrackArtistでは、主キーに一致する行をUPDATEし、更新件数0なら通常INSERTする。Room 2.8.5の `@Upsert` は副一意索引の衝突で主キーUPDATEが0件でも成功扱いになるため、この2表には使用しない。API 36試験で失敗を再現してから変更し、INSERT側・UPDATE側の衝突を検査する。主キーのみが一意制約の残る4表は `@Upsert` を使う。`REPLACE` による親行の削除・再作成は行わない。

`SourceEntity.from` / `toDomain` と `VariantEntity.from` / `toDomain`、`LibraryDao.trackVariant(VariantId)` を #16 の実装型へ接続する。後者はVariantとSourceを同じ読取トランザクションで復元する。曲長・形式・収録先・ディスク番号・曲順・マスタリング名は `track_variants` に属する。不明値はNULLのまま保持し、同じTrackの別Variantから補わない。タグ欠損時の表示上の代替文言や分類は後続UI/走査Issueの責務とする。

## v1 schemaの実装案

[Room生成schema](../../app/schemas/com.audipp.data.db.LibraryDatabase/1.json) を版管理する。ID・enum・URI・タグ・外部IDはTEXT、日時（UTC epoch ms）・曲長（ms）・年・ディスク/曲番号・Hz/bit/ch・ordinalはINTEGERとする。domainで未知を許す属性と欠損タグはNULLを許す。主キーは空白を拒否し、数値はdomainの境界を適用する（曲長は0以上、既知Hz/bit/chとディスク/曲番号は正数、ordinalと日時は0以上）。

| 表 | 一意性・参照 |
|---|---|
| `sources` | `source_id` 主キー。nullableな `(provider_key, provider_item_id)` の複合UNIQUE索引 |
| `tracks` | `track_id` 主キー。曲長・形式・アルバムを持たない |
| `track_variants` | `variant_id` 主キー。Track/Source/AlbumへFK、各FK列に索引。AlbumはNULL可 |
| `albums` | `album_id` 主キー。同名を許す。release namespace/idは両方既知、または両方NULL |
| `artists` | `artist_id` 主キー。同名を許す |
| `track_artists` | `(track_id, artist_id, role)` 複合主キー。Track/ArtistへFK。`(track_id, role, ordinal)` 一意、artist_id索引 |

Providerの部分一意（NULL除外）は、Roomがschemaに出力できる通常の複合UNIQUE索引で実現する案とする。`WHERE` 付きの物理的なpartial indexは追加しない。[SQLiteのUNIQUE](https://www.sqlite.org/lang_createindex.html#unique_indexes) はNULL同士を異なる値とみなすため、同じ制約になる。SourceRefの既存条件により、Providerの組は両方必須、LOCAL/NETWORKは両方NULLであり、LOCAL複数行とProvider間の同じitem IDを許す。組が同じ別source_idはMISSING状態でも拒否する。Provider IDは楽曲/マスター同一性の根拠にしない。

FKはすべて既定のNO ACTIONで、削除を連鎖させない。`markSourceMissing` はSourceのavailabilityとupdated_atだけを更新し、Variantとの参照と全メタデータを保持する。存在しないSourceにはfalseを返す。再登録時は同じIDで保存して状態を回復できる。キュー/プレイリストを実装する #38 / #39 / #44 もVariantの参照を連鎖削除せず、消失状態を扱う必要がある。

破壊的fallbackを有効化せず、未対応のDB版では開く処理を失敗させる。初期版v1に既存製品の旧schemaはないため、架空のmigrationは追加しない。端末DBのversionを未対応の2へ変更し、v1コードでの再オープン失敗と行が残ることを試験する。次のschema変更ではv1 JSONを移行元に使い、明示的migrationと旧版試験を追加する。

## 依存・安全性

[Room公式2.8.5](https://developer.android.com/jetpack/androidx/releases/room#2.8.5) のruntime/compilerとGradle pluginを同版へ固定し、[KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12) を導入する。既存Kotlin 2.3.10・AGP 8.13.2・JDK 17との互換性は実ビルドで検査する。KSP2側のKotlin依存はビルド用であり、アプリ側のKotlin plugin版を変更しない。Android試験はAndroidX Test runner 1.7.0 / ext.junit 1.3.0を使う。Room/KSP/AndroidX TestのライセンスはApache-2.0。解決した推移依存と構成は `app/gradle.lockfile` へ固定する。新規座標のPOM/同梱LICENSEと親POMを確認し、コンパイラ専用のroom-external-antlrはBSD-3-Clause、checker-qualはMIT、その他はApache-2.0だった。KSP用stdlib 2.3.20とRoom processor用2.2.0はビルド構成に限られ、アプリDebug/Releaseのstdlibは2.3.10を維持する。API下限23はAAR metadata/manifestのマージと端末実行を区別して確認する。

URIと外部IDはアプリ専用DBだけに保存する。URI等を含み得るEntityやdomain JSONを診断ログ・共有・同期へ渡さない。DBにトークン・パスワード・署名付きURLを入れず、URI読取やネットワーク通信・権限取得は行わない。grant_modeは呼出側から受け取った記録であり、OSの実際の許可を証明しない。既存のbackup無効設定を維持し、音源・音声sampleは変更しない。

## 要求と試験の追跡

| 要求 → 設計 → 試験 | 本Issueの検査 | 自動試験 |
|---|---|---|
| FR-SRC-01 → DD-DB-01 → TEST-FORMAT-01 | Source/Variantのdomain往復、5分/7分/未知の曲長・形式、所在の一意性 | LibraryEntityTest / LibraryDatabaseTest |
| FR-SRC-05 → DD-DB-01 → TEST-LIB-02 | 欠損タグ、同名Album/Artist、複数ディスクの版ごとの保存と再オープン | LibraryDatabaseTest |
| NFR-REL-01 → DD-DB-02 → TEST-LIB-02（保存部分） | 全FK、NULL除外一意、途中失敗/取消のrollback、再試行、MISSING保持、未対応DB版の非破壊失敗 | LibraryDatabaseTest |
| NFR-MAINT-01 → DD-DB-01/02 → 上記試験の契約部分 | schema export、版/lock固定、境界検査、既存共通型への接続 | LibraryEntityTest / ビルド・Lint |

検証対象commit、件数・結果とCIは対応PRの「証跡」へ記録する。端末試験は合成fixtureと専用 `library-test.db` のみを使用し、通常の `library.db` を削除しない。物理端末・DAP・DAC、実ファイル走査、実再生・コーデック、画像デコード、UI、後続機能との接続は未検証であり、TEST-LIB-02 / TEST-FORMAT-01の試験ファミリー全体の合格とは扱わない。外部レビューは別途必要。

DB試験の再実行は `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` で両APKを生成し、開発ガイドのADB手順に従い指定シリアルへ両APKをインストールしてから、次を実行する（実機は利用者が指定したシリアルのみ）。

```sh
adb -s SERIAL shell am instrument -w -r -e class com.audipp.data.db.LibraryDatabaseTest com.audipp.test/androidx.test.runner.AndroidJUnitRunner
```

2026-10-31: 最新main `ba0628b` を取り込んだ構成で、Debug/Release、全Unit 75件（本IssueのEntity試験3件を含む）、Lint（エラー0・更新通知9件）、API 23/36のx86_64専用エミュレーター上のDB試験各8件が成功した。DB試験はFK/NULL除外一意/副一意のINSERT・UPDATE衝突/rollback/取消・再試行/再オープン/MISSING保持/未対応版での非破壊失敗を含む。PR本文検査の既存Unit 9件も成功。CI・対象commitはPRの証跡へ記録し、外部レビューの完了とは区別する。
