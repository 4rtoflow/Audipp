# LAN Library / Renderer契約 v1（Issue #49）

対象: [Issue #49](https://github.com/4rtoflow/Audipp/issues/49)。FR-NET-01〜03 / NFR-SEC-02 → DD-NET-01〜03 → TEST-NET-01 / TEST-SEC-01の共通契約部分。単一`:app`の`integrations/network`と`playback/source`へ実装する。[共通domain契約](domain-model-v1.md)、[音源解決](source-resolution-v1.md)、[秘密保管](secrets-v1.md)を利用する。実プロトコル、Service、UIの実装完了は表さない。

## Libraryと再生所有者

`NetworkLibraryAdapter`は`listDirectory` / `listMedia` / `resolveStream`を提供する。`NetworkLibraryClient`がページ上限・ID・解決応答を検査し、`NetworkLibrarySourceAdapter`から既存`SourceResolver`へ接続する。登録キーは`NETWORK + DEVICE`、`NETWORK + RENDERER`。アプリへの製品アダプターの登録は接続先Issueで行う。

同じNASの`TrackVariant`でも、選択した端末出力なら`LOCAL_DIRECT`、選択した外部機器なら`NETWORK_REMOTE`を解決する。NETWORKのSource自体からremote所有者を推定しない。Libraryの切断は音源IOの失敗であり、端末の出力機器を切り替える指示にしない。別音源・別Rendererへのfallbackを行わない。

端末用の記述子はメモリ内の`DeviceResource` handle。外部用は、対象ID・経路revision・到達性・期限を検査した`RendererResource`だけを認める。SAF/file URIやuserinfoを拒否し、端末資格を自動付与しない。署名URLを利用する場合は明示的な到達性確認と期限が必要で、ログ・保存・同期には渡さない。HTTP配信サーバーを暗黙に起動しない。

## Rendererと観測

`RendererAdapter`は`discover` / `connect` / `getCapabilities` / `sendCommand` / `observeState` / `disconnect`を提供する。`RendererDiscovery`は件数と重複IDを検査する。利用者が選択した1台の`RendererDevice`と`ResolutionContext`で`NetworkRendererClient`を構築する。接続応答IDが異なる場合、期限切れ、現在の出力先・経路・遷移が変わった場合は拒否する。各再試行も固定した同じIDだけを使う。

所有者は1接続に1クライアントを割り当て、出力切替・Stop時にcontextの遷移を更新し、不要な要求を取消し、旧クライアントをdisconnectする。同じAdapterの同じ接続を複数所有者から操作しない。Adapterは機器IDを接続先と照合し、制御/監視用の資源を所有する。MPD等の実装では制御と監視を別接続にし、失敗・取消時のsocket/streamを解放する責務を持つ。切断後の遅延応答は接続世代が違うため状態に反映しない。旧接続のcleanupは出力選択が変わっていても旧IDに限定する。

`RendererCapabilities`は提供された操作、状態取得、独立キューの能力を分ける。未取得の機能を有効にしない。`remoteVolume`は0〜1の要求値であり、物理音圧・アンプ利得を保証しない。実機器の音量単位への変換と限界値確認はプロトコル実装が担う。

`state: StateFlow<RendererSnapshot>`は対象のcontext、機器の申告である`RendererObservation`とローカルの取得時刻、操作の`CommandReceipt`、失敗を分離する。`ACKNOWLEDGED`も`UNCONFIRMED`もPLAYINGへ変換しない。再生操作を送っただけでは`observation`はnullのまま。対象IDが一致する新しいsequenceの観測だけを採用する。古い/別機器の応答で新しい観測を上書きしない。queue versionが変わった場合は`requiresQueueResync`を立て、内部キューを自動的に押し戻さない。

`observeState`は1回の有限な取得であり、自動的に常設監視を始めない。呼出側workerは必要な間だけ取得し、未取得・失敗・失効した状態を成功表示しない。過去の観測は将来の再生状態の保証ではない。UIはcontextと取得時刻を表示/鮮度判断に使い、切断やtimeout後に観測を推測で埋めない。継続監視・イベント欠落時の再取得・実キュー照合は各プロトコルとCoordinatorの接続範囲。

## 資格情報と入力

`NetworkEndpoint`はprotocol、host、port、rootを持ち、userinfo/query/fragment、制御文字、不正なUTF-8、無効portを拒否する。資格・ログイン名をURIに入れない。`NetworkCredentials`は既存`SecretStore`を使い、protocol + 完全なendpoint URI + account keyに束縛したアカウントを作る。保存名にはSHA-256のスコープを使い、平文は既存のAES-GCM/Keystore層で保護する。異なるhost・protocol・rootへの資格流用はtransport呼出前に拒否する。URIの表記が異なる場合は保守的に別スコープとする。

`authorize`は本人による認証成功時だけ使う。`resume`は保存済みhandleの取得であり認証成功を表さない。`PASSWORD`レコードのbyte列はプロトコル側の資格bundle（必要な場合はユーザー名を含む）で、transportはcallback内だけで読み、保持・ログ出力しない。形式の符号化は各プロトコルが定義する。復号bufferは成功・失敗・取消でクリアされる。Transportはredirect先へ資格を転送せず、アカウントや秘密をSourceRef/Plan/Snapshotへ入れない。

`AUTH_REQUIRED`応答ではそのセッションを失効し、同じプロセスのresumeで有効化しない。失効は保留中の利用を取消す。古い認証要求の失敗から、新しい本人再認証を削除しないよう、セッションを指定した失効を使う。削除失敗をログアウト完了としない。Network側に平文保存・同期・export経路は追加しない。LAN利用許諾・OSネットワーク権限・マルチキャスト制約は実通信側の必須入口であり、Fakeの成功をその許可としない。

IDとdirectory入力の制御文字・不正UTF-8を拒否する。Renderer commandは操作enumと数値/検査済みresourceに分け、任意のMPDコマンド文字列を受け取らない。プロトコル実装でも引用/改行の符号化、XML外部実体の無効化、応答byte数/件数の制限を行う。既に巨大な応答を生成した後のページ検査だけでparserの資源上限を保証しない。

## Timeoutと取消の実装提案

以下の型名・数値・配置は本PRの実装提案であり、製品要件の変更ではない。

| 項目 | 値・意味 |
|---|---|
| 呼出worker | `Dispatchers.IO`。呼出元のJob取消を伝播 |
| 接続 / 操作timeout | 各試行10秒 / 30秒。DD-NETの接続方針に対応 |
| 調整可能範囲 | 各timeout 1〜60000ms、試行回数1〜5、backoff 1〜10000ms |
| 既定再試行 | 最大3試行。250ms、500msと指数増加し上限2000ms |
| 再試行対象 | 読取/解決/探索/接続/能力/観測のOFFLINE・IO・TIMEOUTだけ |
| 操作 / 切断 | 最大1試行。ACK喪失・timeout時も操作を自動再送しない |
| 入力/応答上限 | endpoint 2048文字、ID 1024文字、resource URI 8192文字、ページ/探索1〜1000件 |
| 失敗 | 例外本文/cause/URIを含めない`NetworkFailure`。取消は呼出元へ伝播 |

外側の`SourceResolver`等のtimeoutが短い場合はそちらで先に取り消される。取消を無視して届いた成功も採用しない。ただしコルーチンtimeoutだけで非協調なblocking IOを強制終了する保証はない。実Adapterはsocket/HTTPクライアントにも有限timeoutを設定し、取消でcloseすること。既に機器に届いた操作の撤回は保証しない。

## Fake、要求追跡、検証の限界

`FakeNetworkLibraryAdapter`は固定directory/mediaのページ取得と端末/対象機器別のstream解決に使える。`FakeRendererAdapter`は発見・ID照合・能力・接続・操作・明示的な`report`による状態取得・切断に使える。両方で切断/認証失敗と取消可能な遅延を注入できる。Fakeは資格を認証せず、実ネットワーク、許諾、権限、再生音声を成功したと表示する製品登録は行わない。Renderer FakeもPlayでPLAYINGを生成しない。

| 要求 → 設計 → 試験 | 検証対象 |
|---|---|
| FR-NET-01 / FR-NET-03 → DD-NET-01 / DD-NET-03 → TEST-NET-01 | Fakeのlist/resolveから実SourceResolverへ接続し、同じNASを端末/選択Rendererで別計画にする。SAF・資格付きURI・未到達・期限切れを拒否 |
| FR-NET-02 / FR-NET-03 → DD-NET-02 / DD-NET-03 → TEST-NET-01 | 固定ID・能力・ACK/観測の分離、キュー不一致、有界再試行、timeout・切断・明示再接続、Stop/経路/接続世代後の遅延応答拒否 |
| NFR-SEC-02 → DD-NET-01 → TEST-SEC-01 | 実JVM AES-GCM/一時ファイルの既存保管へ接続。別endpoint/アカウント分離、失効/取消/再認証競合、復号buffer清掃、秘密を含むtransport例外の除去、入力上限/改行拒否 |

2026-10-31: JVM固定fixtureで本契約範囲を検査。対象commit・件数・引き渡しのDebug/Unit/LintとCI結果は対応PRの「証跡」へ記録する。試験ファミリー全体の完了を表さない。

API23/36での実Keystore・プロセス寿命・ネットワーク権限、SMB/WebDAV/MPD/UPnP/OpenHomeの実通信・相互運用、Galaxy/FiiO等の実機はNOT_RUN。実プロトコル未導入・指定ADB serialなしのため操作しない。音声sampleを変更せず、DAC音質・Bit-perfect測定は本契約には不適用。実プロトコル/Android接続は #73 / #74 / #75 / #105 / #106 / #107 / #108、再生所有者と画面は各Coordinator/UI Issueで接続し、本契約のFake成功を実環境の許可や再生成功へ昇格しない。
