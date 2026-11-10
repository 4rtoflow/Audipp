# 設定保存 v1（Issue #27）

対象: [Issue #27](https://github.com/4rtoflow/Audipp/issues/27)。要件1.0.1のFR-UI-03 / NFR-PRIV-01 / NFR-MAINT-01 → DD-DB-03 / ADR-007 / IMP-02 → TEST-UI-01 / TEST-SEC-01のうち、設定・同意の保存部分を実装する。前提の共通domain契約は #16 / PR #161。

## 保存と利用

`Context.preferenceStore`から同じapplication ContextのDataStoreを利用する。トップレベルの`preferencesDataStore` delegateで、プロセス内の`files/datastore/settings.preferences_pb`に対するDataStoreを1つに保つ。ストアは画面やActivityを保持しない。IOはDataStore既定のIO scopeで実行し、更新は`edit`のtransactionで対象キーだけを変更する。複数の設定変更が競合しても、無関係な項目を古いSnapshotで上書きしない。

```kotlin
val store = context.preferenceStore
store.setTheme(ThemeMode.DARK)
store.setVisualizer(VisualizerPreferences(enabled = true, framesPerSecond = 30))
store.setConsent(ConsentPurpose.ACOUSTIC_FINGERPRINT, ConsentDecision(version = 1, granted = true))
val settings = store.data.first()
```

初回値はSystem / Visualizer無効・30fps / PRIVATE / 同意なし。テーマはSYSTEM・DARK・LIGHTを保存し、OSに応じた実効配色はUIで解決する。Visualizerの15/30/60fpsは設計決定に従う。**キー名・schema_version=1・Visualizer無効の初期値・用途別の同意enumと正整数の版・PRIVATE/FRIENDSの型は本PRの実装案**で、画面構成や送信処理の追加ではない。感度・色・品質等の詳細はVisualizer側のIssueで具体化する。

`permitsExternalTransmission(purpose, requiredVersion)`は、指定用途の同意がtrueで、要求版と保存版が完全一致するときだけtrueを返す。未同意・撤回・他用途・古い版・将来版ではfalse。同意は`setConsent(purpose, ConsentDecision(version, false))`で撤回する。公開範囲をFRIENDSにしても外部送信同意は成立しない。送信する機能は送信直前にも最新の同意と用途許諾を検査する。既存のPCM技術能力や`PcmPermissions`の代用にしない。

このストアが扱うのは小規模設定と同意の版・可否のみ。キュー・再生位置・token・URI・秘密を受け取る保存APIや汎用import/export APIは設けない。キューはRoom、暗号秘密は #28 が所有する。

## 失敗と復旧

`data`は`Flow<AppPreferences>`。読取時と保存時の失敗は`PreferenceStorageException(operation, failure)`で区別する。failureはIO / CORRUPT / UNSUPPORTED_VERSION。空の新規ストアだけを既定値として扱い、破損・型違い・不正enum・不正FPS・不完全な同意・版の欠落や不一致を既定値へ置換しない。不正な既存値がある場合は他の設定の更新も拒否する。

自動のcorruption replacementやファイル削除は行わず、元データを保持する。IO障害の原因を取り除いた後、Flowを再購読、または更新を再実行できる。保存成功前に呼出元が成功表示しない。読取失敗を「同意済み」や「設定が消えた」と扱わない。例外には分類だけを含め、元例外のパス・本文・causeを持ち出さない。Coroutineの取消はストレージ障害へ変換しない。取消とディスクcommitが競合した場合、取消だけでロールバックを断定せず再読込する。

## 依存と試験

設計採用済みの[DataStore 1.2.1](https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1)を導入する。[公式ガイド](https://developer.android.com/topic/libraries/architecture/datastore)のsingleton delegate・transaction・破損時例外の契約を使う。AndroidX DataStore / Kotlin Coroutines / OkioはApache-2.0、推移するAndroidXの版と同梱protobuf runtimeもGradle lockで固定する。protobuf runtimeにはBSD-3-Clauseの通知が必要で、配布物のライセンス収録は #151、最終監査は #150 で照合する。

`PreferenceStoreTest`の13件は、実DataStoreと一時ファイルで初回値・close後の再読込・全テーマ/FPS・同意の版と撤回・同時更新・読取IO障害からの再試行・破損/不正値/未知版の保持を検査する。書込失敗と取消はDataStore境界の障害注入で、旧値保持・再試行・同意が成立しないことを検査する。保存キー集合と分類例外を検査し、設定以外の情報のコピーや例外内容の露出を防ぐ。

2026-10-30: 上記13件はローカルJVMで成功。引き渡しのDebug/Release・全Unit・Lint・lock検査と対象commit/CI URLは対応PRへ記録する。

未実施: API23/36上の実行、実プロセス再起動、Activity再生成。ADB serial未指定のため実機操作は行わない。画面からの設定変更・保存失敗表示・テーマのOS追従は [テーマと共通トークン v1](theme-v1.md)（#40）で追跡する。Visualizer処理、外部送信、Room、秘密保管は未接続。音声sampleを変更しないためDAC・音質測定は本変更には不適用。TEST-UI-01 / TEST-SEC-01全体やIssue全体の最終レビュー完了とは扱わない。
