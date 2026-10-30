# 再生計画の解決 v1（Issue #31）

対象: [Issue #31](https://github.com/4rtoflow/Audipp/issues/31)。要件1.0.1と[共通domain契約](domain-model-v1.md)に従い、単一`:app`の`playback/source`へ実装する。以下の公開型・登録方式・期限・URL制限は実装案であり、承認済み製品条件の変更ではない。

## 入力と登録

`SourceResolver.resolve`へ、選択した`TrackVariant`と、出力先ID/種別・経路revision・遷移IDを含む`ResolutionContext`を渡す。`currentContext`は所有者の現在値を返す読み取り専用callbackで、IOをせず、workerからも安全に読める値を返す。古い経路・出力先・Stop等で更新された遷移IDの要求は拒否する。

登録キーは`SourceKind + TargetKind + providerKey`。具体アダプターを登録した組合せだけを解決し、汎用URL fallbackや別Providerへのfallbackは設けない。登録Mapは構築時にコピーする。アダプターは選択版/Source IDと、現在の技術的能力、記述子を返す。ID不一致・再生能力なしを拒否する。

`LocalDocumentSourceAdapter`は端末用の具体アダプターで、指定版の`content://` URIを検査し、注入された`access`で現在のアクセス可否を確認する。能力は利用するbackendから注入する。ファイルの存在だけでseek・PCM取得等を推定しない。URI権限・実open/closeを行う`access`のAndroid接続は #30 / #35 が所有する。API接続時は[ContentResolverの契約](https://developer.android.com/reference/android/content/ContentResolver#openAssetFileDescriptor(android.net.Uri,%20java.lang.String,%20android.os.CancellationSignal))に従い、返却FDの解放と取消を実装する。本変更はContentResolverを実行した証拠ではない。

ProviderとNETWORKの製品アダプターは未実装であり、未登録なら`ADAPTER_UNAVAILABLE`を返す。アプリのServiceへの登録・再生開始は #30 / #61、NASやRendererのプロトコルと認証は #49 / #73 / #74 / #105 / #106 等で接続する。本Issueの試験用アダプターを実接続成功とは扱わない。

## 出力の意味

| 音源 | 選択出力先 | モード | 結果 |
|---|---|---|---|
| LOCAL / NETWORK | DEVICE | LOCAL_DIRECT | Playback |
| PROVIDER | DEVICE | PARTNER_DIRECT | Playback |
| LOCAL / NETWORK | RENDERER | NETWORK_REMOTE | Playback（選択した外部機器の再生計画） |
| PROVIDER | PROVIDER_REMOTE | PARTNER_REMOTE | Auxiliary |
| PROVIDER | EXTERNAL_APP | EXTERNAL_HANDOFF | Auxiliary |

表にない組合せは拒否する。`Auxiliary`は直接再生達成として扱わない。`Playback`も再生計画の解決成功であり、開始ACK・実再生・音声出力の確認を意味しない。NETWORK_REMOTE/REMOTE/HANDOFFがAudipp内PCM取得を申告した場合は拒否する。端末側のPCM処理・解析は既存の`ExecutionPlan.rejectionFor`と現在の`PcmPermissions`でも検査する。

`Unavailable`は認証要求、権限要求、消失、未許諾、未実装、到達不可、期限切れ、IO、timeout等の分類を返す。元例外の本文・cause・URLは含めない。代替候補は呼出側が提示可能と判定して渡した同一TrackのPLAYABLEな別版IDを重複排除して返すだけで、解決・再生しない。候補の許諾・到達性・backend可否は利用者が選び直した時点で再検査する。古い経路/遷移では候補も返さない。

## 許諾と失効

Providerでは`PlaybackPermissionChecker`が`DIRECT_PLAYBACK`または`PLAYBACK_CONTROL`を確認する。欠落・DENIED・UNREVIEWEDは拒否し、アダプター呼出前と応答後に再検査する。checkerへ渡すrequestは選択版・Source・出力先を含み、アカウント・地域・用途・期限等を適用するための入口となる。完全なProviderUse台帳・根拠・期限・その他用途の検査は #33 の責務であり、本人同意をProvider許諾の代用にしない。

開始直前には`SourceResolver.rejectionFor`で現在の経路・遷移・Provider許諾・記述子期限を再検査する。成功結果を将来の許可証にしない。解決後のSource可用性更新・切断・認証イベントの監視と停止の直列化はService/Coordinator側で行う。結果を受け取ってから開始するまでの状態変更を原子的に扱う所有者は #37 / #61 で接続する。

## 記述子・到達性・資源

Planに秘密や期限付きURLを追加せず、非Serializableな`SourceDescriptor`へ分離する。記述子と解決成功結果の`toString`は値を出さない。`DeviceResource`は端末backendのメモリ内handle、`ControlSession`は制御アダプターのhandleとして扱う。記述子の明示的なフィールド読出しをログ・DB・同期へ流すことは許可しない。所有者は取消/終了時に参照を破棄する。

`RendererResource`はアダプターが到達性を確認したHTTP(S) URIに限り、確認したtarget IDと経路revisionの一致を必須にする。content/file URI、userinfo付きURL、fragment付きURL、未確認到達性を拒否する。端末の資格情報を自動付与せず、HTTP配信サーバーも起動しない。署名URLを返す場合はアダプターが有効期限を付け、`now >= expiresAt`で拒否する。実際の外部到達性確認と認証情報を含めない配信方式の選択は各Rendererアダプターの責務。MPD固有パス等は、該当アダプター導入時に別の検査済み記述子として追加する。

アダプターは`Dispatchers.IO`で呼ぶ。suspend処理には既定10秒の解決/再検査timeoutを設け、正の値で注入できる。取消は呼出側へ伝播し、取消を無視して到着した応答も成功にしない。IO/SecurityExceptionは分類へ変換する。アダプター/checkerは取消に協調し、独自資源を解放すること。コルーチンtimeoutだけで非協調なblocking I/Oを強制終了する保証はない。具体的なsocket/ContentProviderの取消・timeout・FD所有権は接続先Issueで実装・検証する。

## 要求と検証の追跡

| 要求 → 設計 → 試験 | SourceResolverTestの観測対象 |
|---|---|
| FR-SRC-01 / FR-SRC-08 → DD-SRC-02 / DD-MODEL-01 → TEST-QUEUE-01 | 指定版/Source維持、候補提示のみ、別版応答拒否、同一NASの端末/Renderer計画分離 |
| FR-PLY-07 → DD-NET-03 → TEST-QUEUE-01 | content URI拒否、現在の到達性・経路/遷移照合、取消・古い成功、期限切れ |
| FR-SRC-08 → DD-MODEL-01 → TEST-PROV-01 | Provider未許諾の入口拒否と再検査、REMOTE/HANDOFFのAuxiliary分類、技術的PCM能力との区別 |

2026-10-30: 固定fixtureとローカル文書アダプターによるJVM試験を追加。引き渡しの全Unit・Debugビルド・Lint結果、対象commitとCIは対応PRへ記録する。API23/36、実ContentResolver、Service/実プロトコル接続、スマートフォン/DAP・DAC試験は未実施。ADB serialの指定がないため端末操作は行わない。この層は音声sampleを処理せず、DSP数値精度・Bit-perfect実測は不適用。試験ファミリー全体とIssueの最終レビューの完了は別途判定する。
