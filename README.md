# Audipp

Androidスマートフォン・Android搭載DAP向けのオーディオプレーヤー。
現在はKotlin / Jetpack Compose / Material 3による最小アプリとビルド環境を整備した段階です。

## 開発を始める

JDK 17とAndroid SDKを用意し、Android Studioでこのディレクトリを開いてGradle Syncを実行します。SDKの導入、OS別のビルドコマンド、実機・エミュレーターでの起動方法は [開発ガイド](docs/development.md) を参照してください。

macOS / Linux：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows（PowerShell）：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## 資料と構成

- [機能一覧](docs/audio_app_feature_list.md)
- [開発ガイド](docs/development.md)
- [UIの基準](docs/design.md)
- `app/`：アプリとユニットテスト
- `gradle/`：Wrapperと依存バージョンカタログ
- `scripts/linux/`：Linux x86_64向けの任意の環境構築補助

再生・USB DAC制御は未実装です。
