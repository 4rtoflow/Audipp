# 共通domain契約 v1（Issue #16）

対象: [Issue #16](https://github.com/4rtoflow/Audipp/issues/16)。承認基準は要件1.0.1（`2adc276`）。実装は単一`:app`内の`com.audipp.domain.model`へ配置する。以下は設計擬似コードを具体化した実装案で、製品条件の変更ではない。

## 識別子と選択版

- `TrackId`は楽曲、`VariantId`は音源版、`SourceId`は所在、`QueueEntryId`は追加ごとの項目を識別する。IDの採番・保存は各データ所有者の責務で、型は空白IDを拒否する。
- `TrackVariant`は選択版自身の曲長・形式を持つ。同じTrackの5分版と7分版は別Variantであり、不明な曲長を他の版から補わない。`QueueEntry`はVariantを参照し、重複追加でも項目IDを分ける。
- `SourceRef`は出力先を固定しない。Provider識別子の組は楽曲・マスター一致の証拠にしない。URIは所在属性でありIDの代用にしない。資格情報・署名URLの格納には使わない。

## 再生計画と消費者

`ExecutionPlan`はVariant・Source・出力先・モード・技術的能力と、解決時の`routeRevision`・`TransitionId`を保持する。`rejectionFor`は呼出時の経路・遷移との不一致、再生不可、PCM能力不足、PCM用途未許可を返す。戻り値が`null`の場合だけこの契約の検査を通過する。Stop等の取消では所有者が遷移IDを更新し、古い応答も同じ検査へ通す。

`PcmPermissions`は加工と解析を分け、既定を`UNREVIEWED`にする。技術的にPCMを取得できても、現在の用途許可が`ALLOWED`でなければ処理を拒否する。Planに保存した過去の許可を再利用しない。Provider全用途の証跡・期限・保存・入口検査は #33、出力先の到達性とPlan解決は #31 が所有する。

`PlaybackSnapshot`はServiceとUIの共通値で、現在項目をQueueEntry IDで示す。`PlaybackError`には表示文や例外本文ではなく分類・コード・再試行可否・復帰操作・診断IDを持たせる。`UserAction`の具体的なenum値は本実装の提案であり、UI側で日英文言へ対応付ける。

契約試験の`FakePlaybackConsumer`はPlanを検査してSnapshotを返し、UI役がJSONを復元して同じ項目・版・曲長を参照する例である。実Service、StateFlow、Reducer、停止確認、キューrevisionの管理、遅延成功時の停止再要求は #30 / #37 / #38 / #61 で実装する。このFakeを実再生の証拠にはしない。

## 証拠とシリアライズ

`Measured<T>`は値・6区分・出典・記録時刻・試験証跡IDを保持する。`UNKNOWN`は`value=null`を必須とし、既知区分には値を要求する。`EXTERNAL_VERIFIED`には空でない試験証跡IDを要求する。AudioFormatおよび経路のHz/bit/chは、未知なら`null`、既知なら正数とする。`dspEnabled`も観測不能なら`null`である。

`AudioRouteSnapshot`は音源の形式と分離する。44.1kHzのSOURCE、48kHzのOS_REPORTED、外部未測定のUNKNOWNを同時に保持できる。値の一致でBit-perfect状態を昇格させない。`VERIFIED`には外部の成功証拠と機器・経路revision・形式・設定条件が必要で、経路や形式の異なる証拠を添付できない。`FAILED`にも同じ条件を持つ外部の不成立証拠を必須とし、未測定と区別する。証跡の真正性や実測内容の評価自体は #69 / #145 の責務である。

保存・受渡しでは`ModelJson.encode<T>` / `decode<T>`を使用する。JSONの外側は`{"value":...,"schemaVersion":1}`、IDは文字列、enumは宣言した大文字名、数値は整数、未知値は明示的な`null`とする。既定値も出力し、時刻・証跡IDを落とさない。版欠落・未対応版・未知enum・未知フィールド・必須フィールド欠落・型や不変条件の違反は復元失敗とする。失敗を0や確認済みへ補正しない。将来の移行処理は版ごとに別途追加する。

JSONには所在URI等が含まれ得るため、診断ログ・共有・同期へそのまま出力しない。公開用の除去処理は #126、同期対象の選別は #115 が所有する。

## 依存と検証の追跡

JSON契約の実際の往復検証のため、既存Kotlin 2.3.10と同版のserialization plugin、`kotlinx-serialization-json` 1.10.0を導入する。[公式1.10.0リリース](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.10.0)はKotlin 2.3.0ベース。runtimeのcore/jsonはApache-2.0で、Gradle解決結果をlockfileへ記録する。SDK/ABI、Manifest権限、音声処理、UIは変更しない。

| 要求 → 設計 → 試験 | 本Issueの検査対象 | 自動試験 |
|---|---|---|
| FR-SRC-01 → DD-MODEL-01 → TEST-FORMAT-01 | 版の独立性、未知曲長、形式境界 | PlaybackContractTest |
| FR-PLY-01 → DD-MODEL-01 / DD-PKG-02 → TEST-QUEUE-01 | 重複項目、経路/遷移失効、FakeのSnapshot消費 | PlaybackContractTest |
| FR-AUD-01 / NFR-AUD-01 → DD-MODEL-02 → TEST-AUD-01 | 6区分、異なるレート、未知値、外部証拠条件 | EvidenceContractTest |
| NFR-MAINT-01 → DD-PKG-01 / DD-ERROR → 上記3試験の契約部分 | Androidに依存しない公開型、分類エラー、版付きJSON | 両ContractTest |

2026-10-30: 対象の契約試験18件がローカルで成功。引き渡しのDebug/Release・全Unit・Lint結果と対象commit/CI URLは対応PRの証跡へ記録する。API23/36実行、実Service接続、実機・DAC・音声測定は未実施であり、上記の試験ファミリー全体の合格や製品完成を表さない。
