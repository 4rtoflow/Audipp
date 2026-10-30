# 開発ガイド

## 必要な環境

- JDK 17
- Android SDK Platform 36、Build Tools 35.0.0、Platform Tools
- Android Studio、またはAndroid SDK Command-line Tools

GradleはリポジトリのWrapperを使います。AGP・Kotlin・Composeなどの版は [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)、SDKとJavaの設定は [`app/build.gradle.kts`](../app/build.gradle.kts) を参照してください。minSdkは23（Android 6.0）です。

## Android Studioで開く

1. [Android Studio](https://developer.android.com/studio)とJDK 17を用意します。
2. **Open** からリポジトリのルートを開きます。
3. **SDK Manager** で必要なSDKパッケージを導入します。Build Toolsは **Show Package Details** から35.0.0を選びます。
4. 設定の **Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** にJDK 17を指定し、Gradle Syncを実行します。Android Studio自身の実行JDKを変更する必要はありません。[JDK設定の公式資料](https://developer.android.com/build/jdks)
5. **Device Manager** で仮想端末を作成するか、USBデバッグを有効にした実機を選び、`app` を **Run** します。システムイメージは開発PCのCPUに合うABIを選びます。

SDKの場所はAndroid Studioが生成する `local.properties` の `sdk.dir`、または環境変数 `ANDROID_HOME` で指定します。個人のSDKパスやIDE設定はコミットしません。

## コマンドラインで検証する

`JAVA_HOME` をJDK 17に設定します。Android Studioで選択したGradle JDKとターミナルのJDKは別設定です。`java -version` とWrapperの `--version` で確認してください。

Linuxの補助手順で環境を構築した場合は、新しいターミナルごとに次を実行します：

```bash
source scripts/linux/android-env.sh
java -version
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`java -version` は17であることを確認します。Gradleが `What went wrong: 27` で失敗する場合は、既定のJDK 27が選ばれています。上記の環境設定を読み込んで再実行してください。

macOS / Linux（JDK 17・SDK設定済み）：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows（PowerShell）：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APKは `app/build/outputs/apk/debug/app-debug.apk`、テスト結果は `app/build/reports/tests/testDebugUnitTest/`、Lint結果は `app/build/reports/lint-results-debug.html` に出力されます。

ビルド・テストのコマンドではエミュレーターは起動しません。GUIで確認するには、Android StudioのRunか、エミュレーターの起動後に下記のADB手順を使います。

依存を更新した場合だけ同じコマンドに `--write-locks` を追加し、カタログとlockfileを一緒に更新します。新たにReleaseなどの構成を使う場合は、その構成も検証・ロックします。初回の依存取得にはネットワーク接続が必要です。

## CIとPRの品質ゲート

[Issue #15](https://github.com/4rtoflow/Audipp/issues/15) のCIは `.github/workflows/ci.yml` で定義します。main宛てのPR（本文編集を含む）、mainへのpush、手動実行が対象です。資料のみのPRも同じチェックを実行します。

- `PR requirements`: PR本文に「対応要件」「変更範囲」「成果」「証跡」「安全・権利・プライバシー」「未確認」の記入を求めます。「対応要件」にはFR/NFR・DD・TESTの各IDが必要です。テンプレートのコメントは記入扱いにしません。IDの実在と対応の妥当性、証跡の内容はレビューで確認します。
- `Android build, unit tests, lint, release`: Ubuntu 24.04、JDK 17、既存Wrapperで次を実行します。minSdk 23・compileSdk 36を維持します。

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Gradle設定・カタログ・Wrapper・lockfileを変更した場合だけ、CIでも `--write-locks` を追加し、再生成したlockfileとコミット済みの内容が一致することを検査します。依存変更時は同じコマンドに `--write-locks` を付けてローカル検証し、生成差分をコミットしてください。解決結果が変わらなければlockfileの差分は不要です。CIの通常実行でlockfileを更新することはありません。

ビルド・テスト・Lint・lock検証が失敗した場合はジョブが失敗し、APKはアップロードしません。テスト・Lintレポートは失敗時も取得可能なものを7日保存します。成功時の `ci-apks-not-for-distribution` にはDebug APKと**未署名Release APK**を7日保存します。配布用の署名鍵・API秘密・個人SDKパスをCIへ渡す必要はありません。実署名APKと同一鍵更新の検証は #151 の責務です。

PR本文チェックのローカル検証:

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py'
```

### main保護の管理者向け設定依頼

2026-10-29の確認ではrulesetはなく、接続アカウントの権限はpushまででadmin/maintainはありません。管理者はCIの初回実行後、Settings → Branchesのmain保護、または同等のrulesetで次を設定してください。この設定が確認できるまでは、CI成功をマージ強制の導入完了とは扱いません。

1. PR経由の変更と承認レビュー1件を必須にし、追加コミットで古い承認を無効化する。
2. 必須チェックに `PR requirements` と `Android build, unit tests, lint, release`（GitHub Actions）を指定し、マージ前に最新のmainとの整合を要求する。
3. 未解決のレビュー会話があるマージ、force push、main削除を禁止し、管理者にも同じルールを適用する。
4. 未記入のPR、失敗するビルド/テスト/Lint、承認前のPRがマージ不可で、チェック成功・承認後にマージ可能になることを確認する。

### Issue #15の要求・検証の対応

対象は NFR-MAINT-01 / NFR-TEST-01 / NFR-TEST-02 → DD-TEST-01 / DD-RELEASE-02 → TEST-GOV-01（CI・PRゲート部分）、およびTEST-COMP-01（API設定・ビルド部分）です。結果・対象commit・実行URLは対応PRの「証跡」に記録します。API 23での実行、スマートフォン/DAP/DAC実機、音声品質、管理権限が必要なmain保護設定は、ビルド成功から推定しません。これらの試験ファミリー全体の完了を表すものではありません。

2026-10-29の検証証跡（最終構成の結果とレビューは [PR #160](https://github.com/4rtoflow/Audipp/pull/160) で追跡）:

- `cceca10`: [GitHub Actionsの正常実行](https://github.com/4rtoflow/Audipp/actions/runs/40827712138)。Debug/Release、既存JUnit 3件、Lint（エラー0件）、依存lock検査、PR本文検査が成功し、APKとレポートを保存。
- `7ed4f6b`: [一時的な3ケースの障害注入](https://github.com/4rtoflow/Audipp/actions/runs/40828233303)。未定義の型でcompileDebugKotlin、失敗するJUnitでtestDebugUnitTest、重複View IDでlintDebugがそれぞれ失敗。全ケースでAPK保存をスキップし、Unit/Lintの失敗レポートだけを保存。障害注入は検証後に削除。
- ローカル: Debug/Release・Unit/Lint、PR本文検査9件、actionlint 1.7.12が成功。Release APKのminSdk 23 / targetSdk 36を確認し、Release lock追加前後で依存座標・版に変更がないことを比較。

## 共通domain契約

Source・Variant・再生Snapshot・Evidenceの公開型、版付きJSON、後続Issueとの責務分担と契約試験の対応は [共通domain契約 v1](contracts/domain-model-v1.md) を参照してください。

## 音声試験の生成信号と証跡

Issue #17の生成音源・Float64独立比較・版付き測定結果の使い方、要求/試験との対応と未検証範囲は [音声試験基盤 v1](../tests/audio/README.md) を参照してください。Python標準ライブラリのホスト検査をCIでも実行します。音声処理や実機測定の成功とは区別します。

## 端末内の秘密保管

Issue #28のAndroid Keystore / AES-GCM、アカウント別の秘密と失効・取消、要求/試験と未検証範囲は [秘密保管 v1](contracts/secrets-v1.md) を参照してください。実providerの認可・期限監視との接続は各AdapterのIssueで行います。

## ADBでインストールする

SDKの `platform-tools` をPATHに追加します。`adb devices -l` で接続・認証状態を確認し、以下の `SERIAL` を対象のシリアルに置き換えます。

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -W -n com.audipp/.MainActivity
adb -s SERIAL logcat -b crash -d
```

## Linux x86_64向けの補助手順

Android Studioを使わずに構築する場合の任意の手順です。Bash、curl、tar、unzip、sha256sumが必要です。

```bash
./scripts/linux/setup-android.sh
source scripts/linux/android-env.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

セットアップは既存のJDK 17とSDKを再利用し、不足分を取得します。SDKライセンスは対話的に確認します。既定の取得先はGit対象外の `.tools/` です。`ANDROID_HOME` が設定済みの場合はそのSDKにパッケージを追加するため、隔離したい場合は実行前に `export ANDROID_HOME="$PWD/.tools/android-sdk"` を指定してください。

環境設定スクリプトはBash用です。変更はsourceしたシェル内だけに適用されます。回帰確認は `bash scripts/linux/test-android-env.sh` で実行できます。

GUIのエミュレーターはDevice Managerで作成するか、SDKの `emulator -list-avds` で既存のAVD名を確認し、`emulator -avd AVD_NAME` で起動します。QtのWaylandプラグインがないLinux環境では、`QT_QPA_PLATFORM=xcb emulator -avd AVD_NAME` とプロセス単位で指定します。

## 検証状況

2026-10-23時点：Linux x86_64でDebugビルド、ユニットテスト3件、Lintが成功しています。Lintはエラー0、固定した依存に対する更新通知6件です。API 36エミュレーターではGUI起動と初期画面の表示を確認しました。

macOS / Windows、API 23、スマートフォン・DAP実機では未検証です。音声再生・USB DAC・音質の検証結果はまだありません。エミュレーターでの起動成功を実機の音声動作の根拠にはしません。
