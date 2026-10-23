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

macOS / Linux：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows（PowerShell）：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APKは `app/build/outputs/apk/debug/app-debug.apk`、テスト結果は `app/build/reports/tests/testDebugUnitTest/`、Lint結果は `app/build/reports/lint-results-debug.html` に出力されます。

依存を更新した場合だけ同じコマンドに `--write-locks` を追加し、カタログとlockfileを一緒に更新します。新たにReleaseなどの構成を使う場合は、その構成も検証・ロックします。初回の依存取得にはネットワーク接続が必要です。

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
