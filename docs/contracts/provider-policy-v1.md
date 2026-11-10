# Provider用途別許諾 v1（Issue #33）

対象: [Issue #33](https://github.com/4rtoflow/Audipp/issues/33)。FR-SRC-08 / NFR-LEGAL-02 / NFR-SEC-03 → DD-PROV-01/02/03 → TEST-PROV-01 / TEST-SEC-01 の用途別入口部分。実サービスの許諾取得・成立判定、UI、再生Service、履歴DB、送信worker、server APIは各担当Issueに残す。本契約のFake成功を実サービス・試験ファミリー全体の成功とは扱わない。

## 実装案と保存

単一`:app`内の`domain/model/ProviderPolicy.kt`へ12用途（SEARCH / MIXED_DISPLAY / MIXED_QUEUE / PLAYBACK_CONTROL / DIRECT_PLAYBACK / HISTORY_STORAGE / STATISTICS / RECOMMENDATION / SCROBBLE / SYNC_EXPORT / PCM_PROCESSING / PCM_ANALYSIS）と既存`PermissionState`を使う許諾台帳を追加する。以下の型・配置・保存形式・有限の期限・revision方式は本PRの実装案で、製品要件や実サービスの利用条件を変更しない。

- `ProviderScope`はProvider・アカウント・アプリ・地域・契約IDの完全一致。空値・ワイルドカードによる包括許可は設けない。IDは秘密を含まない内部識別子とする。
- `UsePermission`はALLOWED / DENIED / UNREVIEWED。欠落はUNREVIEWED。ALLOWEDには`ProviderPermissionEvidence`（根拠参照・条件参照・確認時刻・有効期限）が必須。`reviewedAt <= now < expiresAt`だけ有効。保存期限や送信先等の詳細条件は私有の条件文書に記録し、その適用範囲に一致するscopeだけを承認する。無期限の暗黙許可は設けない。
- 参照は私有保管された証跡を識別するID。生の契約書、token、Cookie、署名URLを受け渡すAPIではない。秘密の実体は #28 の暗号保管等で所有し、UI・ログ・同期に台帳を丸ごと渡さない。型の`toString`とエラーは参照・scope・payloadを出さない。`ModelJson`による保存用JSONは公開診断データではない。
- `ProviderCapabilities`はAdapterが現在申告した技術上の対応用途のみで、台帳とは別。能力の存在、再生成功、本人同意、認証更新で他用途を許可しない。外部送信時は別途最新の本人同意も必要。
- `context.providerPolicyStore`は専用のsingleton DataStore delegate（`provider_policy`）を利用する。キー`provider_policy_v1`に`ModelDocument`のschemaVersion=1付きJSONを保存する。SQL・Room・新規依存は追加しない。`setPermission`は用途単位のtransactionであり、他用途の同時更新を失わない。変更ごとに台帳revisionを増やす。

初回の空ファイルだけを空台帳とする。破損、未知schema/enum、必須証跡欠落、重複scope、型違いは拒否し、元データを置換しない。読取・保存失敗は内容/causeを含まない`ProviderPolicyStorageException`へ変換し、ゲートはSTORAGE_UNAVAILABLEで拒否する。原因解消後に明示的に再読込・更新できる。保存完了前に許可変更の成功を表示しない。取消とcommitの競合時は再読込して判断する。

## 入口と失効

`ProviderAccessGate.execute(scope, use, liveCapabilities) { operation }`を、検索・表示・キュー投入・履歴保存・集計・推薦・Scrobble・同期・PCM処理/解析それぞれの入口に使う。scopeは所有者が認証中の本人・アプリ・地域・契約から構成し、payloadに自己申告させない。呼出側は操作に対応する正しい用途と、その時点の技術能力を渡す。未許可の場合はoperationを呼ばず、データを処理先へ渡さない。

処理はDispatchers.IOで実行し、正値で注入可能な既定10秒timeoutと協調取消を使う。呼出直前に台帳・期限・技術能力を検査し、結果を返す直前にも再読込する。途中の失効・期限切れを拒否し、revisionが変わった古い応答も破棄する。別用途の変更でも安全側に古い結果を拒否する。取消を無視した遅延応答も成功にしない。非協調blocking I/Oの強制終了は保証せず、実Adapterはsocket/SDKの取消・timeout・資源解放を実装する。

`ProviderCallResult.HttpFailure`の401はAUTH_REQUIRED、403はその用途をDENIEDとしてtransaction保存、429はRATE_LIMITED、その他はHTTP_FAILUREを返す。いずれも自動再試行・認証更新しない。403保存は期待revisionと一致する場合だけ行い、古い応答で新しい審査を上書きしない。403以降は再審査・明示更新まで操作を呼ばない。保存失敗を成功と扱わずSTORAGE_UNAVAILABLEを返す。429の待機時間や実認証フローは実Adapterで扱う。

再生は`gate.playbackPermissionChecker { request -> currentScope }`を既存`SourceResolver`へ注入する。Provider不一致・scope不明は拒否し、DIRECT_PLAYBACK / PLAYBACK_CONTROLを分けてアダプター呼出前・応答後・開始直前の既存再検査へ接続する。REMOTE/HANDOFFを直接再生へ昇格しない。

ProviderのPCM入口には`gate.pcmRejectionFor`で最新の直接再生許可・処理/解析別許可・技術能力と既存Planの経路/遷移/PCM能力を照合する。実処理は`execute`でも該当用途を検査する。Plan保存時の能力や再生許可だけでPCMを処理しない。

ゲートの事後検査は既に完了した外部送信やDB書込を取り消せない。後続の所有者は、準備処理と実際の副作用を分け、送信・transaction・音声開始の直前に再検査し、失効監視と停止・既存データの削除/保持条件を直列化する。現在のcheckoutにはこれらの本体がなく、空のserver/UI/DB/音声層は追加しない。#45 / #71 / #101 / #102 / #103 / #122 / #76 / #78 / #112 / #132 等の入口に本ゲートを接続し、実際の副作用境界の競合・保持/削除を各Issueで検証する。

## 検証と追跡

`ProviderAccessGateTest`は実DataStore一時ファイルと固定Fakeで次を検査する。

| 要求・試験 | 観測対象 |
|---|---|
| FR-SRC-08 / TEST-PROV-01 | 再生だけ許可して他11用途へデータが渡らない、用途別3状態の再読込、scopeの本人/アプリ/地域/契約/Provider分離、技術能力と許諾の独立性 |
| FR-SRC-08 / DD-PROV-01/02 / TEST-PROV-01 | 確認・期限境界、失効中の遅延応答、取消・timeout、撤回後の再承認と旧403、401/403/429/500と明示再試行、SourceResolver/PCMとの接続 |
| NFR-LEGAL-02 / NFR-SEC-03 / TEST-SEC-01 | 証跡欠落拒否、秘密を含まないログ表現/例外、破損/未知版保持、IO障害と保存失敗復旧、用途別同時更新と古いrevision拒否 |

2026-10-31（JST）: ローカルのDebugビルド・全91件のJUnit（本契約19件）・Lintが成功。対象commitとCI URLは対応PRの「証跡」へ記録する。API23/36での実行、実プロセス再生成、実Provider・server・Service・送信先との接続は未実施。ADB serialの指定がないため端末操作をしない。DAC・音声sample・DSP精度の実測はこのデータ契約には不適用。第三者レビューと実サービス許諾取得は別途必要。
