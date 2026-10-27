# Audipp 完成・一般公開版 基本設計書（System Architecture / HLD）

| 項目 | 内容 |
|---|---|
| 文書ID | AUD-HLD-001 |
| 文書版・作成日 | 1.0.0-draft / 2026-10-26 |
| 準拠 | [要件定義書](01_requirements.md)、原典 `docs/audio_app_feature_list.md` |
| 対象 | PoCを拡張して一般公開する**完成版Androidアプリ** |
| 設計状態 | **提案アーキテクチャ**。外部権利・Native DSD/USB構成・同期基盤は決定ゲートを要する。 |

## 1. 設計目標と基本方針

1. **コアのユーザー体験は統一、再生責務は分離**。ローカル、許諾済みストリーミング、公式アプリ遠隔制御、ネットワークレンダラーを同じUIから扱いつつ、許される操作はソースごとに異なる。
2. **再生主体はActivityから独立**。バックグラウンド再生、通知、ロック画面の状態はServiceに所有させる。
3. **音声経路の正確性を最優先**。ユーザーが設定した値と、OS申告、再生中の観測値、外部実測を別のデータ型・UIに保持する。
4. **高音質経路とDSPは明確に排他管理**。Bit-perfect出力と信号を書き換えるDSPは同時に保証できない。
5. **権限最小化、ローカル優先**。アカウント・通信不通でもローカル再生は成立する。
6. **将来機能を見越しつつ過剰設計しない**。現状の `app` 単一Gradleモジュールを維持し、責務の境界をパッケージで作る。分離が必要になった実測上の理由でモジュール化する。
7. **正式公開可能な権利の範囲で動作**。外部ストリーミングの非公式なPCM取得を共通再生エンジンの前提としない。

## 2. 開発基盤（確認済み現状と採用案）

| レイヤ | 現状 | 完成版の採用案 |
|---|---|---|
| OS | Android、minSdk 23、targetSdk 36 | Androidスマートフォン／DAP（最低OS最終確定は互換試験後） |
| 言語／UI | Kotlin 2.3.10、Compose Material 3 | Kotlin、Compose、Navigation、ViewModel＋StateFlow |
| ビルド | Gradle 8.13、AGP 8.13.2、JDK17 | 既存Wrapperとバージョンカタログ／lockfileを維持 |
| 再生 | 未実装 | **Media3 ExoPlayer + MediaSessionService/MediaLibraryService（採用提案）** |
| 永続化 | 未実装 | Room（曲・キュー・履歴）＋DataStore（小規模設定）。認証情報は適切な保護ストレージ |
| 同期／バックエンド | 未実装 | 必要になった場合のみ別途API／DBを設計（クラウド基盤は未選定） |
| 高度なPCM/DSD/USB | 未実装 | ハード能力・公式API・配布許諾の成立後に専用バックエンドを追加 |
| 自動検証 | ユニットテスト3件、ビルド手順あり | CI: Unit/Integration/Lint/Release Build、実機音声検査は別経路 |

**Media3採用理由**：Android向けメディア再生・セッション・OS統合の既存実装を再利用できる。Media3だけでNative DSD／全DAC Bit-perfectを実現できるという意味ではない。形式のサポートは端末デコーダー等に依存する。参照：<https://developer.android.com/media/media3/exoplayer/supported-formats>。

## 3. システムコンテキスト

```mermaid
flowchart LR
    User[ユーザー]
    App[Audipp Androidアプリ]
    Storage[端末ファイル / SAF / MediaStore]
    DAC[内蔵 / BT / USB DAC]
    Services[正規API・SDK・外部プレーヤー]
    Renderer[NAS / MPD / UPnP / OpenHome]
    Backend[任意の同期・ソーシャルバックエンド]
    User --> App
    Storage <--> App
    App --> DAC
    App <--> Services
    App <--> Renderer
    App -. 明示同意 .-> Backend
```

### 信頼境界

- **TB-01** 端末内部：アプリ保存領域、SQLite、再生ServiceはAudipp管理下。
- **TB-02** SAF／USB：OSが権限を仲介。ファイルURIは任意の`content://`を含み、実ファイルパスとは限らない。
- **TB-03** 外部サービス：認証・再生許諾・利用制限・サービス側データ品質を信頼境界の外に置く。
- **TB-04** LAN：SMB/WebDAV/UPnP等の端末・家庭内機器が返す情報を無条件に信用しない。
- **TB-05** クラウド：アカウント／同期が選択される場合のみ送信され、個別同意を必須とする。

## 4. 論理アーキテクチャ

```mermaid
flowchart TB
    subgraph UI[Presentation / Compose]
      Home[Home / Library / Search / Playlist]
      PlayerUI[Now Playing / Queue / Signal Path]
      AdvancedUI[Output / EQ / Profile / Visualizer / Settings]
    end
    subgraph Application[Application / Use Cases]
      LibraryUC[Library & Metadata Use Cases]
      QueueUC[Queue Orchestrator / Source Resolver]
      AudioUC[Playback Commands / Audio Policy]
      HistoryUC[History / Stats / Recommendations]
    end
    subgraph Domain[Domain / Contracts]
      Source[Source & Track descriptors]
      Session[Playback / Queue state]
      Proof[Audio evidence / Capability]
    end
    subgraph Infrastructure[Infrastructure / Android Adapters]
      Room[(Room Database)]
      File[SAF / MediaStore / Metadata reader]
      Media3[Media3 Player & SessionService]
      Output[AudioManager / USB Host / optional native output]
      Provider[Authorized Stream / Remote / Handoff adapters]
      Lan[SMB / WebDAV / DLNA / MPD]
      Sync[Optional Sync / Scrobble]
    end
    UI --> Application --> Domain
    Application --> Room
    Application --> File
    Application --> Media3
    Application --> Output
    Application --> Provider
    Application --> Lan
    Application -. opt-in .-> Sync
```

**依存規則**：UIは低レベルAndroid APIを直接呼ばない。UseCaseは契約を通じInfrastructureにアクセスする。例外はUI固有のAndroid Picker起動などPresentationレイヤ固有処理で、結果のURIはUseCaseへ渡す。

## 5. 音源モデルと統合ソース戦略

### 5.1 再生方式（4種類）

| モード | 所有する再生主体 | 典型例 | Audippが可能なこと | 制限 |
|---|---|---|---|---|
| `LOCAL_DIRECT` | Audipp | 端末内音源、許可されたネットワーク音源 | デコード／再生、キュー、可能ならDSP・Visualizer | OS・DRM・形式・出力機器制約 |
| `PARTNER_DIRECT` | Audipp（権利がある場合） | 許諾済みストリーミング | 契約範囲で同エンジン制御・DSP等 | API認証、ストリーム形式、契約上のDSP可否 |
| `PARTNER_REMOTE` | 提供元公式アプリ／SDK | Spotify App Remote候補 | 公式SDKが許す再生操作と情報表示 | PCMへのアクセス不可、外部キュー操作制限 |
| `EXTERNAL_HANDOFF` | 提供元公式アプリ／Web | サービスへのdeep link | 遷移、対応情報表示 | Audippから状態取得・連続制御できない場合あり |
| `NETWORK_REMOTE` | 外部レンダラー | MPD / OpenHome / UPnP | 許可された遠隔制御・状態参照 | 実際の音声出力は外部機器。音声解析不可の場合あり |

`PARTNER_DIRECT` は公式規約に基づいて独立評価する。`yt-dlp`のような許諾のない抽出手段をリリース経路として設計しない。

### 5.2 統合キューの意味

統合キューは**曲の順序と実行可能性を管理する論理オブジェクト**。すべての曲をAudippのPCMパイプラインで再生する保証ではない。

- 各`QueueItem`は`SourceRef`と`executionMode`、`capabilities`、`availability`を持つ。
- `PARTNER_REMOTE`曲へ切替時は公式プレーヤーの同期完了・許諾・必要なユーザー操作を確認する。
- `EXTERNAL_HANDOFF`は自動で次曲へ進める前提にしない。ユーザー確認を促し、時間予測の不確かさを表示する。
- `LOCAL_DIRECT`内など連続制御できるグループのみギャップレスを検証する。
- 提供元やキューの内部状態に関する齟齬は、明示的な再同期で回復する。

## 6. 再生・信号処理の基本設計

```mermaid
flowchart LR
  Source[音源: Local/Authorized Network]
  Decoder[Decoder]
  DSP[ReplayGain/EQ/FIR/Crossfeed]
  Resample[Resampler/Filter]
  Router[Output Policy/Backend]
  Std[Android Standard Audio]
  Direct[Android Supported Direct/Bit-perfect]
  Native[Optional Proprietary USB/Native]
  Dev[DAC / Headphone / Speaker]
  Source --> Decoder --> DSP --> Resample --> Router
  Router --> Std --> Dev
  Router -. supported only .-> Direct --> Dev
  Router -. licensed & proven .-> Native --> Dev
```

この図は**概念上の信号経路**。実装時はMedia3がデコードから出力まで内部に保有する経路も存在するため、すべての端末で`Decoded PCM`へ任意のライブラリを差し込めると想定しない。高度なPCM/DSDルートは、検証済みのバックエンド内にまとまった経路として実装する。

### オーディオモード

| モード | 設定原則 | 可否の判定 |
|---|---|---|
| `STANDARD` | Android標準パス、幅広い互換性 | 標準APIで動作確認 |
| `PROCESSED` | 対応PCM経路でDSP/リサンプルが動作 | 実際のDSPグラフ有効時に表示 |
| `DIRECT_ATTEMPT` | OSが許すDirect/Preferred Mixerを要求 | OSのAPI戻り値＋実際の適用状態を区別 |
| `BIT_PERFECT_VERIFIED` | 出力無変更を所定の条件で検証した場合だけ表示 | 端末×DAC×形式×接続方法×設定×試験証跡 |
| `NATIVE_DSD` / `DOP` | DAC・OS・ライセンスに応じて有効化 | 転送形式とDAC挙動を実測する |

Android 14以降のUSB優先Mixer属性は、対応HALによるBit-perfect属性が**オプション**。アプリが出力希望レートを設定しただけでは検証済みにならない。参照：<https://source.android.com/docs/core/audio/preferred-mixer-attr>。

### 情報の証拠階層

`SOURCE`（音源の属性）/ `REQUESTED`（要求）/ `OS_REPORTED`（OS申告）/ `APP_OBSERVED`（アプリ観測）/ `EXTERNAL_VERIFIED`（外部実測）/ `UNKNOWN`（未取得）。別の階層で取得した値を、上位の確証へ自動昇格させない。

### デバイス安全性

- USB DACの再接続、Bluetooth→有線、プロファイル切替時は、音量の適用順序を制御。元の音量を無条件に戻さない。
- 物理アンプの利得をアプリが調整したと偽らない。高感度イヤホンの警告を表示。
- 出力機器のVID/PIDと名前だけで機器の完全一致を断定しない。接続IDや利用者選択を組み合わせる。
- リモート再生時の安全音量もレンダラーのAPI範囲に限り制御可能とする。

## 7. データ設計（論理モデル）

```mermaid
erDiagram
  MEDIA_SOURCE ||--o{ TRACK : contains
  TRACK ||--o{ TRACK_VARIANT : has
  TRACK ||--o{ QUEUE_ITEM : scheduled
  QUEUE ||--o{ QUEUE_ITEM : contains
  PLAYLIST ||--o{ PLAYLIST_ITEM : contains
  TRACK ||--o{ PLAYLIST_ITEM : linked
  TRACK ||--o{ PLAY_EVENT : played
  DEVICE_PROFILE ||--o{ PROFILE_COMPONENT : includes
  DEVICE_PROFILE ||--o{ AUDIO_PRESET : owns
  PROVIDER_ACCOUNT ||--o{ REMOTE_MAPPING : maps
  TRACK ||--o{ REMOTE_MAPPING : relates
  SYNC_ACCOUNT ||--o{ SYNC_CURSOR : syncs
```

- **Track**：楽曲メタデータ。再生可能な物理ファイルまたは権利許諾済みの配信アイテムとは1対1に固定しない。
- **TrackVariant**：ファイル形式・マスター・解像度・サービス版を識別。誤同定を修正できる。
- **MediaSource**：URIアクセス・公式SDK・ネットワーク取得の種類、可否と期限を持つ。
- **Queue / QueueItem**：履歴として並びを保存し、進行中のエントリーIDを追跡。順番は再生とは別管理。
- **Playlist / PlaylistItem**：キューとは異なり、永続的にユーザーが保存したコレクション。
- **PlayEvent**：再生回数と時間の集計元。後で再計算可能な事実のみ保存。
- **DeviceProfile / AudioPreset**：デバイス組合せと音声設定の論理分離。
- **ProviderAccount / RemoteMapping**：サービス固有IDと権限、期限、同一曲紐付け。
- **SyncAccount / SyncCursor**：同期を使う場合のみ使用。バックエンド導入は未決定。

正規化・主キー・制約・永続化／マイグレーションは[詳細設計書](03_detailed_design.md)で定める。

## 8. 主要画面とナビゲーション

```mermaid
flowchart TD
  Start[初回起動 / 許可案内] --> Home[ホーム]
  Home --> Library[ライブラリ]
  Home --> Search[統合検索]
  Home --> Playlists[プレイリスト]
  Home --> Queue[再生キュー]
  Home --> Settings[設定]
  Library --> Now[Now Playing]
  Search --> Now
  Queue --> Now
  Now --> Output[出力先・Signal Path]
  Now --> Viz[Visualizer / Analysis]
  Settings --> Profiles[機器プロファイル]
  Settings --> Providers[サービス連携]
  Settings --> Privacy[履歴・アカウント・プライバシー]
```

- **Home**：最後の再生・よく使うソース・最近の履歴・対応サービスの状態。
- **Library/Search**：メタデータでの横断検索。結果はソース別に利用可否を示す。
- **Now Playing**：再生主体、出力先、音源形式、操作可否、キュー進捗。
- **Output & Signal Path**：ソース・DSP・経路・DACの各値と証拠ラベル。
- **Visualizer**：対象はAudippがアクセスできる許諾済みPCMのみ。外部アプリの内部音声を取得したかのようなUIを禁止。
- **Settings**：一般設定と高度な音声設定を分け、危険操作を確認する。
- **Accessibility**：主要アクションはTalkBackラベル／状態読み上げ、文字拡大、非色情報で区別。

## 9. 動作シーケンス（基本）

### 9.1 ローカル再生

```mermaid
sequenceDiagram
  actor U as ユーザー
  participant UI as Compose UI
  participant UC as Library/Playback UC
  participant DB as Room/URI Store
  participant S as MediaSessionService
  participant A as Audio Backend
  U->>UI: ファイル/フォルダを許可
  UI->>UC: grantUri(uri)
  UC->>DB: メタデータ保存/インデックス
  U->>UI: 楽曲を再生
  UI->>S: MediaController command
  S->>UC: ソース可用性を解決
  UC->>A: content URI / Audio Policy
  A-->>S: state + route evidence
  S-->>UI: playback state
  UI-->>U: 曲情報・位置・経路・エラー
```

### 9.2 サービス統合キュー

```mermaid
sequenceDiagram
  actor U as ユーザー
  participant Q as Queue Orchestrator
  participant R as Source Resolver
  participant P as Provider Adapter
  U->>Q: 次の曲へ
  Q->>R: source + capabilities を照会
  R-->>Q: DIRECT / REMOTE / HANDOFF
  alt DIRECTで許可済み
    Q->>P: stream URI / token付き許可
    P-->>Q: 再生可能
  else REMOTE SDK
    Q->>P: 操作要求
    P-->>Q: acknowledged / pending / failed
  else HANDOFF
    Q-->>U: 外部アプリを開く確認
  end
```

**非同期遷移規則**：remoteで「指示受理」と「実際の再生開始」は異なる。タイムアウト／取消／失効を持ち、ユーザーへ状態を表示する。

## 10. 外部インターフェース・権限方針

| 先 | 主なインターフェース | 権限・安全条件 |
|---|---|---|
| 端末ファイル | Storage Access Framework / MediaStore | URIの許可・永続化・失効処理。Android 11以降の制限を遵守 |
| Android OS | Media3 Session, AudioManager, AudioTrack系, AudioDevice callback | `mediaPlayback` foreground serviceと適切なManifest。出力観測できるAPI範囲内 |
| USB DAC | UsbManager / OS Audio API / 任意の承認済み独自Backend | 接続・通信時の許可。OSドライバーとの競合回避は実機検証 |
| Streaming | OAuth・公式SDK・公式API | PKCE等の公式フロー、利用規約、DRM、データ制限、トークン保護 |
| LAN | SMB/WebDAV / UPnP / OpenHome / MPD | ユーザー明示設定、ネットワークと資格情報保護 |
| Backend | 将来のHTTPS API | 最小データ、認証、退会・削除、可監査な契約 |

Android公式：<https://developer.android.com/training/data-storage/shared/documents-files>、<https://developer.android.com/media/media3/session/background-playback>、<https://developer.android.com/develop/connectivity/usb/host>。

## 11. 非機能・運用アーキテクチャ

### 11.1 オフライン

- 曲・キュー・ローカル履歴・デバイスプロファイルは端末内永続化。
- ネットワークが切れてもローカル再生は維持する。外部ソースの未取得データは明示的なエラー／一時停止扱い。
- サービスの自動再試行は指数バックオフとAPIレート制限を尊重し、重複課金・二重コマンドに注意。

### 11.2 プライバシー

- データ区分：公開不要（端末内部）／利用者が共有許可／外部SDKが直接保持するデータ。
- 同期・フレンド・ランキングを使う場合、機能別同意と公開範囲、送信先・データ保持期間、削除手段を明示。
- ログ・障害報告のデフォルトはローカル。アカウント名／絶対パス／アクセストークン／フルジャケット画像の収集を抑止。

### 11.3 可観測性

- `PlaybackEvent`, `RouteEvent`, `ProviderEvent`, `ErrorEvent` は単調増加のイベント順序とタイムスタンプを持つ。
- OSとDACの値は「取得時点・出典・信頼性」を記録。メトリクスの欠損を0値に補完しない。
- デバッグログはサンプリング・上限・マスキングを実装し、共有時は利用者がプレビューする。

### 11.4 リリース・CI

- **高音質価値の公開ゲート案**：ロスレス音源の実機再生に加え、ReplayGain／PEQ／検証済みDirect経路のいずれか1つ以上を実装・測定。一般的なOS再生をBit-perfectと呼ばない。
- `main`はPR経由、Debug Build/Unit Test/Lint/Release BuildをCIで実行。
- 公式署名鍵・OAuth秘密情報はGitHubに平文コミットしない。ストア提出AAB、SBOM、第三者ライセンス一覧を作成。
- 自動テストと、USB DAC／高感度イヤホン／ビット忠実性等の実機検査は別判定。
- ストア公開前は`targetSdk`、権限、プライバシーポリシー、SDK規約を再確認する。

## 12. セキュリティ・障害時の基本対応

| 障害 | 基本方針 | 利用者への挙動 |
|---|---|---|
| URI権限喪失 | 失効ソースを再要求・再スキャン | 「再選択してください」 |
| ファイル破損・形式不可 | 問題の曲のみスキップ／停止 | 理由・回復ボタン・ソース表示 |
| USB/BT切断 | 安全にPauseまたは対応済み経路へフェイルセーフ切替 | 音量変更防止、出力先表示 |
| PCM/DSDバックエンド非対応 | 利用可能方式の候補を再評価 | 自動フォールバックは明示設定・安全条件に限定 |
| Provider認証失効 | Refresh可能なら実行、それ以外は再ログイン | ローカル再生は継続可 |
| API利用停止・DRM不可 | 機能を無効化し代替公式アプリへ誘導 | 使えない理由と公式遷移 |
| LAN切断 | 再接続可能状態に保持、履歴やキューは保持 | 端末選択・再試行 |
| DB破損 | 整合性チェック・安全な復旧・バックアップへ退避 | 無断のデータ全消去禁止 |
| 端末処理負荷 | Visualizer品質低下、重いDSPをオフにできる | 再生安定性を優先 |

## 13. 完成に向けた実装順序（リリース範囲を縮小しない）

| 先行順 | 設計・実装する領域 | 完了判定の観点 |
|---|---|---|
| W1 | ローカル音源、Session、ライブラリ、キュー | エラーを含むローカル再生が安定 |
| W2 | 出力観測、安全性、Signal Path、実機マトリクス | 不明値を正しく扱い、切断で安全 |
| W3 | 正規サービス連携、統合キューの能力制約 | 権利確認済みの1〜複数サービス連携を検証 |
| W4 | DSP・PCM/DSD・USB高度機能の個別バックエンド | 機種別に実証されたものだけ提供 |
| W5 | ネットワーク再生、Visualizer、履歴／推薦、機器プロファイル | 各種統合・電力・プライバシー試験 |
| W6 | 同期・アカウント・ソーシャル（採用時） | 本人データ管理・バックエンド運用準備 |
| W7 | 性能・アクセシビリティ・セキュリティ・公開手続 | UAT＋端末実測＋ストア審査を満たす |

これは**作業順序であり、「W1までが完成製品」という意味ではない**。

## 14. 設計の決定ゲート（ADR）

| ADR ID | 判断を要する内容 | 現時点の推奨 |
|---|---|---|
| ADR-001 | MediaSessionService vs MediaLibraryService | 公開版で外部ブラウズが必要ならLibraryServiceを選ぶ。最初はSessionServiceで着手可 |
| ADR-002 | USB Direct／Nativeバックエンド | Android標準の能力検証を先行し、成功しないものだけ独自実装を審査 |
| ADR-003 | DSP・リサンプラーのライブラリ | 処理品質・電池・ライセンス・ソースPCM取得可能性で比較 |
| ADR-004 | Streaming provider別の連携形態 | 契約に従い `DIRECT/REMOTE/HANDOFF/UNAVAILABLE` を確定 |
| ADR-005 | Backend採用と運用責任 | アカウント・同期要件が確定した時点でAPIを定義 |
| ADR-006 | DB競合・曲の一意性 | Provider ID と Local URI を分離し、誤同定を避ける |
| ADR-007 | OS/ABI互換性維持 | minSdk23のコストと検証結果で変更判断 |
| ADR-008 | 観測結果・Bit-perfect表示基準 | 実証された端末とテスト条件を公開一覧に紐づける |

## 15. 要件への対応表

| 要件群 | 主要コンポーネント | 詳細設計 |
|---|---|---|
| FR-SRC | Source Repository, LibraryIndexer, ProviderAdapter | DD-SRC、DD-DB、DD-PROV |
| FR-PLY | QueueOrchestrator, MediaSessionService | DD-PLY、DD-QUEUE |
| FR-AUD | OutputPolicy, AudioBackend, DSPGraph | DD-AUD、DD-DSP |
| FR-DEV | RouteMonitor, DeviceRegistry, ProfileMatcher | DD-DEV |
| FR-NET | RemoteRendererAdapter, NetworkLibrary | DD-NET |
| FR-USE | HistoryRecorder, SyncGateway, RecommendationEngine | DD-HIST、DD-CLOUD |
| FR-OBS | SignalPathSnapshot, VisualizerPipeline | DD-OBS |
| FR-UI | Compose Screen/ViewModel | DD-UI |
| FR-PLT / NFR | CI、互換性・品質マトリクス、リリースゲート | DD-TEST、DD-RELEASE |
