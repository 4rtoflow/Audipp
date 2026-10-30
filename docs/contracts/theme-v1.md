# テーマと共通トークン v1（Issue #40）

対象: FR-UI-03 / NFR-UX-01 / NFR-A11Y-01 → DD-UI-02 → TEST-UI-01のうち、Welcome・設定・外観のテーマ選択と保存、実効配色、共通トークン。設定保存は #27 の `Context.preferenceStore` と既存 `ThemeMode` を利用する。カスタムテーマ、ArtworkPalette、再生UI、モーション削減の設定画面は各担当Issueで接続する。

## 動作と実装案

- `ThemeMode` は保存する選択、`isDark(systemDark)` は実効配色を表す。既定はSYSTEM。SYSTEMだけが `isSystemInDarkTheme()` の変化に追従し、DARK/LIGHTはOSより優先する。
- 設定 → 外観 → テーマに3つのラジオ項目を置く。読み上げは項目のラベル・RadioButton role・selected状態を利用する。日英ラベルをリソース化し、48dp以上の行、折り返し、縦スクロールを用いる。色だけで選択を区別しない。
- DataStoreをCompositionのscopeから購読・更新する。読み込み未完了では保存値を推測せず選択を無効にし、実効配色は一時的にOSに従う。保存中は旧選択と「保存中」を表示し、commit成功後に変更を反映する。人工的な遅延や再起動はない。
- 読取失敗は「読み込めない」、書込失敗は「保存できない」を表示し、選択を成功扱いしない。再試行は読取Flowの再購読または失敗した選択の再保存。破損・未知版を初期化しない。保存前の取消は旧値を維持し、commitと取消が競合した場合は再購読で保存結果を確認する。
- テーマはrootのMaterialThemeだけを変更する。Activity再起動や再生APIの呼出しを行わない。画面階層は `rememberSaveable`、各画面のスクロールは `SaveableStateHolder` で保持する。OSの構成変更による再生成でも復元する。Player/queue/再生位置を新設・模擬しない。
- system barの明度は保存選択から解決した配色を `WindowCompat.getInsetsController` に渡す。`enableEdgeToEdge()` は初期化時の一度だけにし、Activity 1.12/API35以上で反復呼出しによって補助Viewが増えるのを防ぐ。API23などナビゲーションバーの暗いアイコンに非対応のOSではDarkのbase色をバーに用いる。起動ウィンドウはOSのnight qualifierによる背景・status barを使い、DataStore読込後に固定選択を適用する。

**本PRの実装案（新しい製品条件の決定ではない）:**

`ui/theme/AudippTheme.kt` を共通tokenの配置とする。`docs/design.md` §3.2の暫定配色を起点に、base→background、surface→surface、elevated→surfaceContainer/surfaceVariant、主/補助文字→onSurface/onSurfaceVariant、subtle border→outlineVariant、fallback accent→primary、主操作/onPrimary→secondary/onSecondaryと対応付ける。重要なラジオの非選択枠にはsubtle borderを流用せず、3:1以上を満たすoutlineを使う。エラーは文字と再試行で示し、TBDのwarning色は独断で確定しない。

文字は共通 `AudippTypography`（Material 3のspベースの既定scale）、余白は4/8/12/16/24/32dpを使用する。600dpの本文最大幅は既存Welcomeの制約を引き継ぐ。モーションtokenは設計の暫定範囲内の150/270msを提案値として置く。今回の切替・画面遷移には追加アニメーションを付けず、削減設定でも待ちや明滅を発生させない。後続のアニメーション利用側はOSのduration scaleを尊重する。壁紙Dynamic Colorやジャケット色を暗黙に混ぜない。

## 検証と追跡

2026-10-31、対象commitとCI URLは対応PRの「証跡」で追跡する。

- `AudippThemeTest` 2件: OSのDark/Light変化と固定選択の全組合せ、および両paletteの主/補助文字・accentとbase/surface/elevated背景の4.5:1以上、操作枠の3:1以上、主操作上の文字の4.5:1以上。
- `ThemeSettingsControllerTest` 6件: 実DataStoreによる既定値・3選択・store再作成後の保持、保存IO失敗と再試行、読取IO失敗と再購読、不正enum/未知版の保存拒否と元データ保持、保留中の追加選択拒否・取消、commit後に取消された場合の新observerによる復旧。
- #27の `PreferenceStoreTest` 13件も既存契約の回帰確認に含む。timeoutは新しい製品値を追加せず、保留した更新の取消で中断を検査する。
- `source scripts/linux/android-env.sh` 後、`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` を実行。依存追加・版変更なし。端末試験の結果と画面は [Issue #40の証跡](../evidence/issue-40/README.md) を参照。

Player/Service未接続のため、実音声の無中断・再生位置・queue保持はNOT_RUN。画面の再構成で再生系を再生成しないことと、実際に音声が途切れないことは区別する。Galaxy S25/FiiO M21/S20はADB serial未指定のためNOT_RUN。TalkBackの実際の読み上げ、Accessibility ScannerはNOT_RUN。音声sample変更がないのでDAC/DSP精度測定は不適用。TEST-UI-01全体や将来画面の全状態の完了を意味しない。
