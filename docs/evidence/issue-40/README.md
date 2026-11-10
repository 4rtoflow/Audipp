# Issue #40 検証証跡

2026-10-31 JST。FR-UI-03 / NFR-UX-01 / NFR-A11Y-01 → DD-UI-02 → TEST-UI-01のテーマ部分。実装契約は [テーマと共通トークン v1](../../contracts/theme-v1.md)。対象commit・CIは対応PRに記録する。

## 環境と結果

- 最新 `origin/main` の `ba0628b` から専用worktree/branchを作成。JDK17、既存Gradle Wrapper、依存追加なし。
- `source scripts/linux/android-env.sh` → `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: PASS。Unit 80件（今回追加8件）、失敗0。Lintエラー0、更新通知の警告9件。
- 一時AOSP x86_64 AVD: API36=`emulator-5580`、API23=`emulator-5582`。両方とも320×640px/160dpi、横画面640×320px。接続済み実機は操作しない。
- ADB/UIAutomatorの画面階層でラベルとRadioButtonのchecked状態を検査し、screencapで背景を採取。Light=`247,248,250`、Dark=`11,13,18`で想定tokenと一致。これは音声出力の計測ではない。

| ケース | API23 | API36 |
|---|---|---|
| 新規保存領域でSYSTEM既定、OS Light→Dark追従 | PASS | PASS |
| OS Darkで固定Lightを選択、force-stop/再起動後もLight | PASS | PASS |
| 固定LightのままOSをLightへ変更、選択と外観を維持 | PASS | PASS |
| 固定Dark、回転後に外観画面と選択を維持 | PASS | PASS |
| 複数回切替の前後でUI階層のView数が一定 | PASS | PASS |
| 日本語・font_scale=2.0、長いSystemラベルの折返し、両配色 | PASS | PASS |
| 書込拒否: 旧選択を維持、エラー表示、権限復旧後の再試行成功、両配色 | PASS | PASS |
| 破損: 読取失敗、未選択・選択拒否、データ復旧後の再購読成功、両配色 | PASS | PASS |

UI上の選択変更だけでActivityを再起動しない。OSモード変更・回転でAndroidがActivityを再生成する場合も外観画面と選択を保持する。force-stopからの新規起動ではテーマ永続化を検査し、画面階層の保持とは混同しない。API23 AVDはハードウェアナビゲーション設定のため画面内のnavigation barは現れない。API36は両配色でsystem barアイコンを確認した。API23のソフトnavigation bar、API26境界、実機のedge-to-edgeは未検証。

## 再現手順

専用AVDでAPKをインストールして起動し、設定→外観→テーマを操作する。ADBは常に `adb -s SERIAL` とし、上記の専用serial以外を対象にしない。

1. 新しいアプリ領域でSystem選択を確認する。API36では `shell cmd uimode night no` / `yes`、API23では `shell service call uimode 4 i32 1` / `2` でOSモードを変更する。API23の呼出しは [Android 6.0.1のIUiModeManager](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r1/core/java/android/app/IUiModeManager.aidl) の順序に限定した試験手順で、アプリ実装には使用しない。
2. Light固定を選択し、`shell am force-stop com.audipp` と `shell am start -W -n com.audipp/.MainActivity` 後に再び外観を開く。OSを変えても固定値が優先されることを確認する。
3. Dark固定で `shell settings put system accelerometer_rotation 0`、`shell settings put system user_rotation 1` / `0` を適用し、外観画面と選択が維持されることを確認する。
4. 日本語と `font_scale=2.0` を適用し、`dumpsys activity` のconfigurationでも2.0を確認する。API36はapp locale変更、API23は一時AVD内のlocale設定とframework再起動で適用した。ラベルや本文が折り返され、必要なエラー末尾と再試行までスクロールできることを確認する。
5. テスト専用データを使い、`run-as com.audipp` でdatastoreディレクトリの元modeを取得して一時的に書込を拒否する。別テーマを選んでも旧選択を維持し、保存失敗を表示することを確認する。必ず元modeへ戻してから再試行する。
6. テスト専用の `files/datastore/settings.preferences_pb` をバックアップし、アプリ停止中だけ不正内容へ置換する。読取失敗時に選択を成功扱いしないことを確認し、元バイト列へ復旧してから再試行する。実ユーザーデータに対してこの障害注入を行わない。

読取待ち・保存待ち・取消の境界は単体試験の保留fixtureで検査する。UIの瞬間的なloading/saving画面や実ストレージのtimeout時間を計測したとは扱わない。

## 画面

既存文言の削減は行っていない。Welcomeの固定Lightを共通テーマへ移し、設定への導線と必要な設定・状態・エラー文言だけを追加した。

| 内容 | API23 | API36 |
|---|---|---|
| System / OS Light | [画面](api23-en-system-light.png) | [画面](api36-en-system-light.png) |
| System / OS Dark | [画面](api23-en-system-dark.png) | [画面](api36-en-system-dark.png) |
| 固定Light / OS Dark | [画面](api23-en-fixed-light.png) | [画面](api36-en-fixed-light.png) |
| 回転・固定Dark | [画面](api23-en-dark-landscape.png) | [画面](api36-en-dark-landscape.png) |
| 日本語200%・Dark | [画面](api23-ja-dark-font200.png) | [画面](api36-ja-dark-font200.png) |
| 日本語200%・Light | [画面](api23-ja-light-font200.png) | [画面](api36-ja-light-font200.png) |
| Light保存失敗・日本語200%・末尾へスクロール | [画面](api23-ja-write-error-font200.png) | [画面](api36-ja-write-error-font200.png) |
| Light読取失敗・日本語200%・末尾へスクロール | [画面](api23-ja-read-error-font200.png) | [画面](api36-ja-read-error-font200.png) |
| Dark保存失敗 | [画面](api23-en-dark-write-error.png) | [画面](api36-en-dark-write-error.png) |
| Dark読取失敗 | [画面](api23-en-dark-read-error.png) | [画面](api36-en-dark-read-error.png) |

## 未検証・不適用

- Galaxy S25 / FiiO M21 / S20: serial未指定、NOT_RUN。
- Player・queue・再生位置、実音声無中断: 再生系未接続、NOT_RUN。UI切替が再生APIを呼ばないことと実再生の検証を区別する。
- TalkBack実読み上げ、Accessibility Scanner、将来の再生画面・アートワーク・モーション設定: NOT_RUN。ラジオのラベル・checked/disabledとエラー状態のliveRegionは実装済み。
- 音声sampleを扱わず新しい送信・権限・依存もないため、DAC測定・DSP精度・秘密送信試験は不適用。
- 本結果はTEST-UI-01全体の完了や、PRの人間による承認レビュー完了を意味しない。
