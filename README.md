# Audipp

Androidスマートフォン・Android搭載DAP向けのオーディオプレーヤー。
現在はKotlin / Jetpack Compose / Material 3による最小アプリとビルド環境を整備した段階です。

## 開発を始める

JDK 17とAndroid SDKを用意し、Android Studioでこのディレクトリを開いてGradle Syncを実行します。SDKの導入、OS別のビルドコマンド、実機・エミュレーターでの起動方法は [開発ガイド](docs/development.md) を参照してください。

Linuxの補助手順で環境を構築した場合は、ターミナルを開くたびに環境設定を読み込みます：

```bash
source scripts/linux/android-env.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS / Linux（JDK 17・SDK設定済み）：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows（PowerShell）：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

これらはビルド・テスト用のコマンドです。アプリを画面で確認するには、開発ガイドの手順でエミュレーターを起動し、アプリをインストール・実行します。

## エミュレーターを起動する

**Android Studio（全OS共通）**：**Device Manager** で仮想端末（AVD）を作成し、一覧の ▶ を押すと起動します。起動した端末を選んで `app` を **Run** すると、アプリのビルド・インストール・起動まで行えます。

コマンドラインの場合は、作成済みのAVDを一覧から選び、`AVD_NAME` をその名前に置き換えます。macOS / WindowsはSDKの標準配置先の例です。SDKを別の場所に導入している場合は、Android StudioのSDK Managerで場所を確認し、パスを置き換えてください。

macOS：

```sh
"$HOME/Library/Android/sdk/emulator/emulator" -list-avds
"$HOME/Library/Android/sdk/emulator/emulator" -avd AVD_NAME
```

Windows（PowerShell）：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd AVD_NAME
```

Linux（本リポジトリの補助環境・Bash）：

```bash
source scripts/linux/android-env.sh
emulator -list-avds
QT_QPA_PLATFORM=xcb emulator -avd AVD_NAME -gpu swiftshader
```

上記コマンドはエミュレーターを開きます。アプリのインストール・起動はAndroid Studioの **Run**、または [開発ガイドのADB手順](docs/development.md#adbでインストールする) を使います。終了はエミュレーターのウィンドウを閉じます。[公式の起動手順](https://developer.android.com/studio/run/emulator-commandline)

## 資料と構成

- [機能一覧](docs/audio_app_feature_list.md)
- [開発ガイド](docs/development.md)
- [UIの基準](docs/design.md)
- `app/`：アプリとユニットテスト
- `gradle/`：Wrapperと依存バージョンカタログ
- `scripts/linux/`：Linux x86_64向けの任意の環境構築補助

再生・USB DAC制御は未実装です。
