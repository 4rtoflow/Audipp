# Issue #18 検証記録

実施日：2026-10-31 JST。実装commit：`a46726184ee8ccedb669454b708451795b64e09e`。ベース：`ba0628b`（開始時に取得した最新origin/main）。専用ブランチ`codex/issue-18`と隔離worktreeで実施し、元checkoutのファイルは編集していません。

対応はNFR-MAINT-01 / NFR-TEST-01 / FR-PLT-02 → DD-TRACE / DD-DECISIONS / DD-RELEASE-01 → TEST-GOV-01の台帳部分です。FR-PLT-02は対象外OSの範囲を守る検査であり、OS実装の成功を表しません。

## 入力・操作・期待値と結果

| 検査 | 入力・操作・期待値 | 観測結果 |
|---|---|---|
| 台帳再集計 | 固定原文を読み、518 IDの定義/行/参照/適用区分と照合。90要求・229原典・43試験ほか全台帳の件数を比較 | 一致。原典229箇条書きと対応表の原文/行も1対1で一致 |
| 障害注入 | 一時ディレクトリで要求/F/TESTを増減、同数でID置換、DD参照を削除/不明IDへ置換、原典リンクを破壊 | すべて検査失敗として検出 |
| 適用範囲 | FR-PLT-02・対象外OS F・廃止F・P-01をCURRENTへ変更、別の要求から廃止DDを参照 | すべて拒否。既存のFR-SRC-09→廃止DD-PROV-06だけをT18-02として毎回表示 |
| 結果の判定 | NOT_RUN/UNESTABLISHED、UNKNOWN/DENIED、証跡なしでPASSを記録 | すべて拒否。FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLEは理由と制約付きで保存できる |
| その他の記録不備 | 承認履歴/固定commit欠落、ID重複、不明/対象外/無関係の要求・試験、短縮commit、ケースID重複 | すべて拒否。WindowsのCRLFは製品条件の変更として誤検出しない |

実装commit上で次を実行し、17件成功・台帳検査終了コード0を確認しました。

```sh
python3 -m unittest discover -s scripts/traceability -p 'test_*.py'
python3 scripts/traceability/check.py
```

既存のPR検査9件、音声基盤のホスト検査22件と結果テンプレート検査も成功しました。これらの実装には変更を加えていません。

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py'
python3 -m unittest discover -s scripts/audio -p 'test_*.py'
python3 -m scripts.audio.evidence tests/audio/result-template.json
```

## Androidの指定検証

Linux補助環境を読み込み、隔離したGradleキャッシュで次を実行しました。Androidソース・Gradle入力はベースcommitから無変更です。上記Python検査の最終調整に先行して実行しており、ビルドは台帳ロジックの検査を代替しません。

```sh
source scripts/linux/android-env.sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- `BUILD SUCCESSFUL`（1分3秒、53タスク実行）。Unitは72件、failure/error/skippedは0。
- Lintはエラー0、警告9。警告を0件にしたという報告ではありません。
- ローカルログ：隔離worktree内`.tools/verification/issue18-gradle.log`、`.tools/verification/issue18-traceability.log`。生成レポートは`app/build/reports/`と`app/build/test-results/`。公開資料には個人環境のパスやログ全文を含めません。
- 依存・lock・音声処理の変更なし。ReleaseはリモートCIで確認し、ローカル実施済みにはしません。

## 未確認・再開箇所

- PRレビュー承認とリモートCIの最終結果は対応PRで追跡します。このローカル記録は承認の代替ではありません。
- T18-02〜06は[未確認台帳](README.md#決定履歴と残る確認)に保存しました。ナビ最終案・履歴計測表現・私用配備・機器/用途許可は担当Issueで再開します。
- 実機操作、エミュレーター、NDK/ABI導入、DAC出力・音質測定、サービス認証/用途許可、配備/バックアップ復旧は実施していません。ホスト検査のPASSをこれらへ流用しません。
- TEST-GOV-01ファミリー全体や個人間利用版の完成判定は保留のままです。マージ・auto-mergeは行いません。
