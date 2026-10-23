# Audipp

- Androidスマートフォン・Android搭載DAP向けオーディオプレーヤー。現段階は最小アプリの基盤構築。iOS・クロスプラットフォーム化は対象外。
- Kotlin / Compose Material 3 / Gradle Kotlin DSL。単一 `app` モジュールを維持し、再生・USB・DSPの空インターフェースや不要な依存を先に作らない。将来のMedia3・C++導入は必要な段階で判断する。
- 製品機能要件は `docs/audio_app_feature_list.md`、開発手順は `docs/development.md`。作業に関係する資料・範囲だけ読む。UI変更時は `docs/design.md` を参照する。
- 既存の開発既定に従い、最小の正しい変更を行う。実装位置が不明な既存コードはSembleで局所化し、既知の名前は `rg` で探す。外部APIの版依存事項はContext7 / 公式資料で確認する。
- 変更に応じた最小のテストを実行する。引き渡し時は `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`（Windowsは `gradlew.bat`）。Linux用の補助環境を使う場合のみ先に `source scripts/linux/android-env.sh` を実行する。成功した同じ検証は根拠なく繰り返さない。依存変更時のみ同じコマンドに `--write-locks` を追加し、lockfileも含める。
- 実機確認は明示したADBシリアルに限定する。ビルド・エミュレーター起動・実機動作・音声出力の実測を混同しない。システム変更、sudo、既存設定の上書きは事前確認する。
- 音源情報、要求した設定、OSの能力申告、再生中に観測した経路、DAC側の実測を区別する。不明値を推測で埋めず、サンプルレートの一致だけでBit-perfectと表示しない。
