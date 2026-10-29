# Audipp 個人間利用の完成版 詳細設計書（LLD / Implementation Specification）

| 項目 | 内容 |
|---|---|
| レビュー状態 | **Complete**（2026-10-28、利用者による全レビュー完了・正式版承認） |
| 文書ID | AUD-LLD-001 |
| 文書版・作成日・更新日 | 1.0.1 / 2026-10-26 / 2026-10-29 |
| 対応文書 | [要件定義書](01_requirements.md)・[基本設計書](02_basic_design.md) |
| 実装状態 | **設計系レビュー終了・実装着手用仕様**（2026-10-28利用者指定）。本設計のコード実装・依存導入・端末試験は未実施であり、該当領域で検証して差分を改訂する。 |
| 適用範囲 | 完成版のローカル／高音質／統合ソース／ネットワーク／統計／私用同期・共有（PUBLICは完成後） |

## 0. 実装の優先ルール

1. 本書のクラス名・型・関数名は**実装時に具体化する設計契約**である。既存の`MainActivity.kt`などを変更した実績を意味しない。
2. ユーザーが観測できる要件は `01_requirements.md` を優先。本書の詳細が権利、Android API、端末能力と矛盾する場合は設計変更ADRを作成する。
3. すべての端末でNative DSD、Bit-perfect、PCM取り出しができるという仮定を禁止する。
4. 未決定のAPI鍵・認証、サーバーDB、暗号化ライブラリ、DSP処理ライブラリ、精度閾値は**実装が必要になる前に採用ADR**を確定する。
5. `FR-` と `NFR-` への対応は「設計対象」であり、実装済みの状態を表さない。

6. 2026-10-28の最新指示により、原典のAndroid機能を個人間利用版で必須とし、一般公開の準備はPUBLICへ分ける。以前のG/O・採用時のみという機能除外は適用しない。未確定の方式・数値・運用は[判断事項・回答シート](06_decisions.md)、全箇条書きの対応は[機能対応表](05_feature_coverage.md)で追跡し、未達のまま完成扱いにしない。

7. 2026-10-27のQ-01〜13回答により、全サービス直接再生、YouTube系yt-dlp/音声のみ、カタログ検索は可能時、指定端末・音声上限、無料/広告なし、自前サーバーと日英UIを確定した。以前のSpotifyリモート案・動画UI案・検索全サービス必須案は置き換える。実現可能性・個別許諾は別途検証する。

## DD-PKG：パッケージと依存関係

### DD-PKG-01 段階的配置

```text
app/src/main/java/com/audipp/
├── MainActivity.kt
├── ui/
│   ├── navigation/             # 画面遷移
│   ├── home/                   # ホーム
│   ├── library/                # 曲・アルバム・検索
│   ├── nowplaying/             # Now Playing、Queue
│   ├── audio/                  # SignalPath、DSP、Visualizer、出力設定
│   ├── providers/              # 公式サービス連携
│   ├── profiles/               # DAC/イヤホンの組合せ
│   └── settings/               # 設定、プライバシー、ヘルプ
├── domain/
│   ├── model/                  # TrackRef、Queue、Evidence、Error
│   ├── repository/             # 依存契約
│   └── usecase/                # 実行ユースケース
├── playback/
│   ├── PlaybackService.kt      # Media3 LibrarySession所有者
│   ├── PlaybackCoordinator.kt  # 一貫したコマンド処理
│   ├── QueueOrchestrator.kt
│   └── source/                 # SourceResolver/Provider Adapter
├── audio/
│   ├── route/                  # AudioManager、デバイス監視
│   ├── policy/                 # AudioMode/Capability/Fail-safe
│   ├── dsp/                    # 必要時に追加、実体のない空層は禁止
│   └── native/                 # 権利・端末試験後のみ追加
├── data/
│   ├── db/                     # Room Entity/Dao/Migration
│   ├── local/                  # SAF/MediaStore/Metadata
│   └── preferences/            # DataStore
├── integrations/
│   ├── streaming/              # 正規OAuth/API/SDK Adapter
│   ├── network/                # MPD/UPnP/SMB/WebDAV
│   └── lastfm/
└── diagnostics/                # Privacy-safe structured logs
```

単一`:app`モジュールを当面維持。実際のコード分量やビルド時間が拡大するまで空の`feature-*`モジュールを作らない。循環参照禁止：`ui -> domain <- data/integrations/audio`、playbackはdomain契約を使用。

### DD-PKG-02 実行スレッド・所有権

- Compose UIは`StateFlow<ScreenState>`を`collectAsStateWithLifecycle`などで購読する（依存導入時に正式バージョン確定）。
- Media3 `Player`の状態更新は、そのPlayerのアプリケーションLooperで逐次処理する。別スレッドからの呼出は専用dispatcherへ中継する。
- ファイルIO・メタデータ抽出・DB・HTTP・FFT分析はMain threadに乗せない。非同期`Dispatchers.IO/Default`でキャンセル可能に実装。
- プレーヤーの唯一の所有者は`PlaybackService`。Activity破棄・画面回転でPlayerを再生成しない。

## DD-MODEL：データ型・契約

### DD-MODEL-01 識別子と再生可否

以下のKotlinは*概念を示す設計擬似コード*。Android/Media3の正確なSDK型や依存関係は08_design_decisions.mdの導入予定版で実装時に検証する。

```kotlin
@JvmInline value class TrackId(val value: String)       // UUID（内部ID）
@JvmInline value class SourceId(val value: String)      // "local:..." / "provider:..."
@JvmInline value class QueueEntryId(val value: String)  // 重複追加ごとに固有

enum class ExecutionMode { LOCAL_DIRECT, PARTNER_DIRECT, PARTNER_REMOTE,
                           EXTERNAL_HANDOFF, NETWORK_REMOTE }
enum class SourceKind { LOCAL, PROVIDER, NETWORK }
enum class Availability { PLAYABLE, AUTH_REQUIRED, EXPIRED, MISSING,
                          UNSUPPORTED, POLICY_BLOCKED, UNKNOWN }

data class SourceRef(
    val sourceId: SourceId,
    val kind: SourceKind,
    val providerKey: String?,
    val providerItemId: String?,
    val localDocumentUri: String?,
    val availability: Availability
)

data class PlaybackCapabilities(
    val canPlay: Boolean,
    val canPause: Boolean,
    val canSeek: Boolean,
    val canSkip: Boolean,
    val canReadPosition: Boolean,
    val canAccessPcm: Boolean,
    val canControlVolume: Boolean,
    val gaplessWithNext: Boolean
)

data class ExecutionPlan(
    val variantId: String,
    val source: SourceRef,
    val targetId: String,
    val mode: ExecutionMode,
    val capabilities: PlaybackCapabilities,
    val routeRevision: Long
)
```

**制約**：`providerKey + providerItemId`はソース側での一意性を表すが、同一楽曲・同一マスターの証拠ではない。`SourceRef`をURLの生文字列だけで識別しない。`canAccessPcm=false`の場合、Audipp内部DSP/Visualizerを提供しない。

SourceRefは音源の所在を表し、出力先・再生主体を固定しない。ResolverはVariant、選択出力先、現在の経路revision、Provider許諾からExecutionPlanを作る。同じNAS音源でも端末再生と外部レンダラーでは別Planになる。出力先変更で古いPlanを無効化し、現在の接続・到達可能性を再評価する。Provider音源のREMOTE/HANDOFFはQ-01の直接再生要件を満たすPlanとして返さない。端末のcontent URIを外部機器が読み出せるURLとみなさない。

### DD-MODEL-02 信号経路の証拠（FR-AUD-01、FR-OBS-01）

```kotlin
enum class EvidenceLevel { SOURCE, REQUESTED, OS_REPORTED,
                           APP_OBSERVED, EXTERNAL_VERIFIED, UNKNOWN }

data class Measured<T>(
    val value: T?,
    val level: EvidenceLevel,
    val sourceName: String,       // decoder metadata / AudioManager / bench test ...
    val recordedAtEpochMs: Long,
    val testEvidenceId: String? = null
)

data class AudioRouteSnapshot(
    val routeId: String?,
    val deviceName: Measured<String>,
    val sampleRateHz: Measured<Int>,
    val bitDepth: Measured<Int>,
    val channelCount: Measured<Int>,
    val dspEnabled: Boolean,
    val bitPerfectState: BitPerfectState
)

enum class BitPerfectState { VERIFIED, NOT_VERIFIED, NOT_APPLICABLE, FAILED }
```

- `VERIFIED`には`EXTERNAL_VERIFIED`の証拠と機種・出力・フォーマット・設定条件が必須。
- OS側のPreferred Mixer属性が受理されても、それだけで`VERIFIED`へ昇格させない。
- `UNKNOWN`の`value`は原則`null`、UIは「不明」を表示。未知情報を0Hz、0bit等と記録しない。
- `FAILED`は明示的な不成立の証拠がある場合だけ使用。未検証と不成立を混同しない。

## DD-DB：永続化の物理設計

### DD-DB-01 Roomテーブル（初期schema v1の設計）

| テーブル | PK | 主なカラム | 主要制約・索引 |
|---|---|---|---|
| `sources` | `source_id TEXT` | `kind,provider_key,provider_item_id,local_uri,availability,grant_mode,updated_at` | `(provider_key,provider_item_id)`は部分ユニーク（NULL除く）、`local_uri`非公開 |
| `tracks` | `track_id TEXT` | `title,artist_display,year,genre,artwork_ref` | 楽曲の論理情報。曲長・収録アルバム・曲順は版ごとに保持 |
| `track_variants` | `variant_id TEXT` | `track_id,source_id,album_id,disc_no,track_no,duration_ms,codec,sample_rate,bit_depth,channels,mastering_label` | Track/Source/AlbumへのFK。音源版を一意に識別し、曲長不明はNULL |
| `albums` | `album_id TEXT` | `title,album_artist,release_namespace,release_id,year,artwork_ref` | アルバム名だけを一意キーにしない |
| `artists` | `artist_id TEXT` | `name,external_id` | 重名・同名アーティストを許容 |
| `track_artists` | 複合 | `track_id,artist_id,role,ordinal` | 複合一意、FK、順序付け |
| `queues` | `queue_id TEXT` | `name,created_at,updated_at,revision,shuffle_enabled,shuffle_seed,repeat_mode` | revisionは項目・順序・再生順設定の変更時に更新 |
| `queue_items` | `entry_id TEXT` | `queue_id,ordinal,variant_id,added_at` | `(queue_id,ordinal)`ユニーク、VariantへのFK。重複追加でもentry_idは別 |
| `playback_checkpoint` | `slot_id INTEGER`（常に1） | `queue_id,queue_revision,entry_id,position_ms,saved_at` | 現在キュー・項目・位置をRoom内で原子的に保存。位置不明はNULL |
| `playlists` | `playlist_id TEXT` | `name,created_at,updated_at,revision` | ユーザー所有で他の端末との競合に備える |
| `playlist_items` | `entry_id TEXT` | `playlist_id,ordinal,variant_id` | VariantへのFK。ソース消失時も参照を保持 |
| `play_events` | `event_id TEXT` | `session_id,entry_id,track_id,source_id,variant_id,started_at,ended_at,played_ms,qualified,history_epoch` | キュー削除後も再生時の版・論理曲IDを保持。event_idで重複防止 |
| `play_segments` | `segment_id TEXT` | `event_id,variant_id,start_utc,duration_ms,source_start_ms,source_end_ms,playback_speed,time_zone,route_snapshot,profile_snapshot` | event_idへのFK。再生中の確定区間のみ保存し、UTCと単調時計の差を混同しない |
| `device_components` | `component_id TEXT` | `kind,vendor,product,vid,pid,usb_serial_hash,notes` | 識別できない/非公開値はNULL |
| `device_profiles` | `profile_id TEXT` | `display_name,output_mode,default_gain_db,safe_volume,created_at` | 設定と実機能力を別管理 |
| `profile_components` | 複合 | `profile_id,component_id,role` | DAC・アンプ・イヤホン組合せ |
| `audio_presets` | `preset_id TEXT` | `profile_id,mode,dsp_config_json,resampler_config_json,revision` | JSON妥当性・内部schemaVersion、変更前確認 |
| `provider_accounts` | `account_id TEXT` | `provider_key,user_alias,auth_state,scope_set,expiry` | **アクセストークン本体はここに保存しない** |
| `track_mappings` | `mapping_id TEXT` | `track_id,provider_key,provider_item_id,match_confidence,source_of_match` | マッピングの取消し可能 |
| `metadata_overrides` | `override_id TEXT` | `variant_id,field,value,original_value,origin,revision` | アプリ内の修正を原タグと分離し、取消可能にする |
| `delivery_outbox` | `delivery_id TEXT` | `destination,event_id,payload_ref,state,attempt_count,next_attempt_at` | `(destination,event_id)`一意、送信前に削除・同意・許諾を再検査 |
| `smart_playlists` | `playlist_id TEXT` | `predicate_json,sort_json,schema_version` | 検索式を保存し、評価時のライブラリrevisionを記録 |
| `custom_themes` | `theme_id TEXT` | `name,dark_tokens,light_tokens,schema_version` | 許可した色トークンのみ保存。外部コードを実行しない |
| `sync_outbox` | `event_id TEXT` | `entity_type,entity_id,operation,revision,created_at,sync_state` | 同期機能は個人間利用版必須。本人が有効化したデータだけを送信 |
| `abx_sessions` | `session_id TEXT` | `stimulus_a,stimulus_b,normalization,test_settings,created_at` | 利用同意・保存期間と測定条件を保持 |
| `abx_trials` | `trial_id TEXT` | `session_id,trial_index,selection,answer,elapsed_ms` | 正答は試行終了までUIに出さない |

**Roomの正式な型・FK・マイグレーションSQLはDB実装PRで確定**。上記はテーブル設計の論理的契約であり、そのまま実行するDDLではない。参照中のSource/Variantは消失時も行を残してMISSING等を記録し、キュー・プレイリストへ削除を連鎖させない。属性更新時にvariant_idを再生成しない。再生時の曲長は選択Variantの値を使い、別版から補完しない。

### DD-DB-02 トランザクションと競合

- Queue順序変更は期待するrevisionを検査し、単一トランザクション内で対象キューの全行を`ordinal = -ordinal - 1`の負数領域へ退避してから、entry_idごとに最終値`0..n-1`を設定する。削除対象を除去し、既存行の更新と新規行の挿入で最終順を構成してから、最後にrevisionを更新する。永続化された通常状態は非負とし、負数を禁じるCHECK制約は付けない。途中失敗は全件ロールバックする。プレイリストも同じ手順を用いる。
- 再生中項目はentry_idで追跡する。現在項目の削除・全消去は先に再生遷移を取り消して停止を要求し、現在項目の参照をNULLへ更新する。順序変更だけでは現在項目・位置を変えない。キュー更新時はcheckpointのqueue_revisionも同じトランザクションで更新し、削除済み項目を参照させない。
- PlayEventの適格判定はDD-HIST-01と08_design_decisions.mdの再生回数規則を使う。Last.fm固有の条件とは分離する。同じイベントを再試行しても重複カウントしない。
- ソースを失った曲はユーザーのプレイリストから無断削除しない。`MISSING`状態で再登録を促す。
- スマートプレイリストは原データの検索式から再生成可能な派生データとして扱う。
- `Room`マイグレーションは本番DBの破壊的fallbackを無効とし、旧バージョンテストを必須にする。

### DD-DB-03 DataStore／機密情報

- DataStore：テーマ、音声初期モード、Visualizer設定、プライバシー同意バージョンなど小規模キー。現在キュー・現在項目・位置・リピートはRoom側を正本とし、二重管理しない。
- 秘密情報：08_design_decisions.mdのAndroid Keystore＋AES-GCM方式を採用する。Keystoreは暗号化保存領域そのものではないことに留意。生のrefresh tokenをバックアップ・ログ・共有しない。
- SAF URI：アプリが取得した`takePersistableUriPermission`を使えるケースに限り永続化。OS制限や権限失効で再選択が必要になり得る。
- ユーザーが原曲の削除を指示しない限り、音源ファイルを上書き・削除しない。

## DD-SRC：ライブラリ登録、検索、音源解決

### DD-SRC-01 `registerLocalTree()`

**入力**：SAFが返すツリーURI、ユーザーによる読み取り許可。**前提**：`ACTION_OPEN_DOCUMENT_TREE`対応API、選択可能なディレクトリ（Android 11以降に選択制限あり）。

1. URIと受領した権限フラグを検査し、永続許可可能なら`takePersistableUriPermission`を実行。
2. `ContentResolver`／`DocumentFile`等で列挙。深さ・件数・メモリ上限を設け、途中キャンセル可能にする。
3. MIME型だけでなく実際のコンテナ解析で対応形式を判定。例外はファイル単位で記録し、全体を中断させない。
4. `title/album/artist/trackNo/discNo/duration/codec`を抽出。可能な範囲で画像はキャッシュし、大きなBitmapをそのままUIへ渡さない。
5. `sources + tracks + variants`をトランザクション単位でupsert。初回・更新・消失を区別。
6. 進捗（件数、スキップ、失敗、完了）をFlowで通知。処理は非同期でUIをブロックしない。

**出力**：`LibraryScanResult(indexed,updated,missing,failed,issues)`。URI失効・SDカード取外し・巨大ディレクトリに耐える。原ファイルはread-only。

走査は登録ルート単位のscan_idを持ち、結果に`COMPLETE/PARTIAL/CANCELLED/ACCESS_LOST`を含める。今回訪問したSource IDと、内容一覧を正常に読み終えたディレクトリを記録する。上限到達、取消、列挙失敗、接続断があれば、そのルートの消失確定処理を実行しない。取得済みの追加・更新は保持し、未訪問曲は以前の状態を維持する。ルート全体を正常に列挙できたCOMPLETEの場合だけ、登録済みで今回存在しなかったSourceをMISSINGへ更新する。解析失敗でも列挙できたファイルは「存在する」と記録し、消失にしない。権限失効はアクセス不可として記録し、削除とは区別する。同じルートの走査と消失確定は直列化し、古い走査が新しい結果を上書きしない。

### DD-SRC-02 source選択の優先度

1. ユーザーが明示した`variant`と`sourceId`を尊重。
2. 指定ソースが`PLAYABLE`でなければ、ソース切替を勝手に行わず、本人設定に従って代替候補を提示。
3. 複数版がありユーザーが`優先形式`を設定した場合のみ`FLAC/WAV/MP3`等を順位付け。レート・ビット深度だけで音質の優劣を断定しない。
4. `PARTNER_DIRECT`はAPI利用許可を再評価。オフラインキャッシュは権利上許可された場合だけ使用。
5. 同一楽曲とみなしてもストリーミング側の再生権限・地域制限を引き継がない。

### DD-SRC-03 アルバムと重複

同じ名前空間の検証済みrelease_idをアルバム同一性の第一候補とし、disc_noは同一リリース内の曲順にのみ使用する。同一リリースと確定したID群、または同じアルバムフォルダ内で整合するアルバムタグ・ディスク構造は自動分類する。外部IDがなく、同名アルバムや矛盾タグがある場合は`album_artist + album_title`と登録ルート・フォルダ構造から候補を提示し、曖昧な一致を自動結合しない。利用者は複数ディスクの結合・誤結合の分割を行える。MusicBrainz等の外部IDは照合証拠として保存する。マスタリング違い・別リリースは別Variantで維持し、関連付けを取り消せる。

### DD-SRC-04 メタデータ編集・外部照合・画像

利用者が照合を要求したら、メタデータ照合処理は利用条件を確認したMusicBrainz等のID・タグ検索、AcoustID等の音響照合を実行し、候補と出典・一致根拠を画面へ返す。音響指紋の外部送信は内容と送信先を表示して同意を得る。採用結果はmetadata_overridesと取消可能なtrack_mappingsへ保存し、確信度だけで同一マスターと断定しない。API・制限は下記を用い、利用資格は導入時に確認する。

タグ編集の既定はアプリ内修正とする。原曲への書込みを利用者が明示選択した場合だけ、書込み可能なURI権限、形式の対応、バックアップ・復旧手段を検査する。安全な置換ができないDocumentProviderでは上書きを拒否し、アプリ内修正を保持する。成功後に再読込してタグ以外の音声データを壊していないことを検査する。書込み失敗をアプリ内表示の成功で隠さない。

画像は出典・利用条件・元画像と差替画像を区別し、表示サイズ別キャッシュとデコード上限を設ける。利用者は取得・手動差替・元に戻す操作を行える。画像URL・ローカルファイルの失効時はプレースホルダーへ戻す。画像上限・メモリ予算・TagLibの書込/復旧方式は09の④に従い、導入時に試験する。

重複候補はURI/提供元ID、ファイル内容のハッシュ、メタデータ・曲長・音響照合を別々の根拠として提示する。自動削除しない。異形式・異マスター・収録時間違いをVariantとして並べ、選択版を再生でき、関連付け解除でもキュー・履歴のVariant IDを変えない。

外部照合はMusicBrainz API v2の検索/lookupと、Chromaprintの指紋を使うAcoustID lookupを対象にする。MusicBrainzは意味のあるUser-Agentを付け、アプリからの呼出しを毎秒1件以下へ直列化する。AcoustIDはアプリ登録とclientキーを必要とし、毎秒3件以下に制限する。要求を統合・キャッシュし、429/一時障害では待機する。無料アプリであることだけで非商用条件を満たすとは判断しない。サービス利用条件・データ/画像の権利は用途別に確認し、本人の選択なく照合結果を原曲へ書き戻さない。[MusicBrainz](https://musicbrainz.org/doc/MusicBrainz_API)、[AcoustID](https://acoustid.org/webservice)。

## DD-PLY：Player状態機械・セッション制御

### DD-PLY-01 状態遷移

| 現状態 | 操作・イベント | 次状態 | 副作用 |
|---|---|---|---|
| `IDLE` | `load(track)` | `RESOLVING` | ソース・権限を解決 |
| `RESOLVING` | ソース可用 | `BUFFERING` | Playerへ設定しprepare |
| `RESOLVING` | 権限切れ／非対応 | `ERROR` | エラー理由と再選択導線 |
| `BUFFERING` | `ready`かつ再生要求 | `PLAYING` | 音声フォーカスを適正管理 |
| `BUFFERING` | `ready`かつ一時停止要求 | `PAUSED` | 再開を待つ |
| `PLAYING` | `pause` / フォーカス喪失 | `PAUSED` | 再生停止、位置保存 |
| `PLAYING` | 一時的buffer不足 | `BUFFERING` | 進捗・原因を表示 |
| `PLAYING` | `ended` | `ADVANCING` | History確定、次候補へ |
| `PAUSED` | `play` | `BUFFERING/PLAYING` | 続行可否を再検査 |
| 任意 | `stop` | `IDLE` | 再生を止め、必要に応じリソース解放 |
| 任意 | デバイス抜去・権限失効 | `PAUSED/ERROR` | 急な音量変更を避ける |
| 任意 | 回復不能例外 | `ERROR` | エラー種別記録、ユーザーに復帰可能操作 |

PlayerのMedia3ネイティブ状態と、Audipp固有の`RESOLVING/ADVANCING`は**変換層を持つ**。UI側に独立した偽の再生状態を作らない。

### DD-PLY-02 公開コマンド

```kotlin
sealed interface PlaybackCommand {
    data class PlayVariant(val variantId: String) : PlaybackCommand
    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Stop : PlaybackCommand
    data class Seek(val positionMs: Long) : PlaybackCommand
    data object SkipNext : PlaybackCommand
    data object SkipPrevious : PlaybackCommand
    data class SetRepeat(val mode: RepeatMode) : PlaybackCommand
    data class SetShuffle(val enabled: Boolean) : PlaybackCommand
}

data class PlaybackSnapshot(
    val revision: Long,
    val phase: PlaybackPhase,
    val currentEntryId: QueueEntryId?,
    val positionMs: Long?,
    val durationMs: Long?,
    val effectiveSource: SourceRef?,
    val executionPlan: ExecutionPlan?,
    val error: PlaybackError?
)
```

入力検証：シーク位置は`0..duration`の範囲（長さ未知時は機能無効）。連続タップでは古いコマンドの遅延応答により新状態を上書きしない。コマンドとPlayerイベントは単一順序でReducerへ入れる。

### DD-PLY-03 Session/ForegroundServiceのライフサイクル

- `PlaybackService`はMediaLibraryServiceとしてPlayer・MediaLibrarySessionを生成し、Service終了時にSessionとPlayerを開放。UIは`MediaController`から接続。
- 再生をバックグラウンドで継続する場合に`mediaPlayback` Foreground ServiceのManifestと必要権限を宣言する。具体的なAndroid API上の制約はtargetSdk更新時に見直す。
- ロック画面・Bluetooth MediaButton・外部コントローラーはSession権限の範囲内で処理。
- 曲・キュー変更、シーク完了、一時停止、停止の確定時と、位置を取得できる再生中の5秒間隔で`playback_checkpoint`へ保存する。保存ジョブは直列化し、古いrevisionの書込みは拒否する。キュー変更とcheckpoint更新は同じRoomトランザクションで行う。5秒は設計上の初期値であり、I/O負荷・保存失敗時の巻戻り幅をTEST-RESTORE-01で測定する。DB書込み遅延中に音声スレッドを待たせない。
- OS終了コールバックは保存の必須契機にしない。起動時は最後にコミット済みのcheckpointを読み、現在の権限・ソース・経路を再検査する。現在項目がない場合はキューだけを復元し、位置は選択版の現在の長さへ丸める。長さや位置が不明なら復元位置を推測しない。**復元だけでは再生を開始せず、利用者の再生操作を待つ**。通知等からの明示的な再生要求は、再検査後に処理する。
- 音声フォーカス・`becoming noisy`などの処理を再生ポリシーに集中させ、UIが勝手に音量を変更しない。

## DD-QUEUE：統合キューの実行アルゴリズム

### DD-QUEUE-01 キューの不変条件

- 1キュー内の`entry_id`はユニーク。**同じTrackの複数追加は許容**。
- コミット済みの`ordinal`は`0..n-1`の重複のない連続整数。更新中の一時番号はDD-DB-02に従い、未コミットの状態をUIへ公開しない。
- 再生位置は「現在のエントリーID」で持ち、順序変更でも曲が突然変わらない。
- `durationMs=null`が一つでも含まれる区間は、合計終了予定を厳密値と表示しない。
- External handoffでは、ユーザーが公式アプリから戻るまでは外部アプリの再生状態を推測しない。

### DD-QUEUE-02 `advance()`擬似コード

```text
advance(trigger, currentEntry):
  if trigger == NATURAL_END: finalizeHistoryOnce(currentEntry.session)
  transition = coordinator.beginTransition()  # 以前の開始待ちを無効化
  snapshot = queue.snapshot()                # revisionと実際の再生順を固定
  candidates = snapshot.candidatesOnce(trigger, currentEntry)
  for candidate in candidates:              # 各entry_idは最大1回
    if transition.cancelled: return CANCELLED
    if queue.revision != snapshot.revision: return QUEUE_CHANGED
    result = resolver.resolveWithTimeout(candidate.variantId, selectedTarget)
    if transition.cancelled: return CANCELLED
    if queue.revision != snapshot.revision: return QUEUE_CHANGED
    if result in [PLAYABLE_DIRECT, REMOTE, HANDOFF]:
      return coordinator.switchOwner(transition, candidate, result)
    markUnavailable(candidate, result.reason)
    if skipUnplayablePolicy == ASK: return REQUIRES_USER_ACTION
  return NO_PLAYABLE_ITEM if candidates not empty else QUEUE_ENDED
```

- `timeout`と`correlationId`を持ち、REMOTEコマンド成功を音声出力の成功と同一視しない。
- プロバイダーごとの同時接続制約・レート制限を順守し、エラーを静かに無限リトライしない。
- 1プレイリストへのソース混在は可能でも、ソース切替を自動化できない場合はUIに「次の曲は手動切替」と表示。
- `candidatesOnce`は最大キュー件数の有限列を返す。自然終了＋リピート1曲では現在項目のみ、明示SkipNextではリピート1曲を無視して次へ進む。リピート全曲は一巡で打ち切り、リピートなしは末尾で終了する。シャッフルは保存したseedとentry_idから再現した実際の順序を用いる。NO_PLAYABLE_ITEMでは停止し、不可理由を表示する。
- ソース解決と開始・停止要求にはAdapterごとの有限タイムアウトを必須とする。QUEUE_CHANGEDでは自動的な再探索を繰り返さず、現在の遷移を終了して最新キューを表示する。次の操作または新たな自然終了イベントで再評価する。

### DD-QUEUE-03 予定時刻計算

```text
order = queue.effectivePlaybackOrder(revision, shuffleSeed)
remaining[current] = max(0, variantDuration - observedPosition) / playbackSpeed
remaining[next..] = Σ (variantDuration[i] / playbackSpeed)
if unknownDuration or unknownPosition or remoteDurationUnreliable or handoffPending:
    result = ESTIMATE_PARTIAL or UNAVAILABLE
else if phase != PLAYING:
    result = REMAINING_DURATION_ONLY  # 再開時刻を仮定しない
else if repeatMode != OFF:
    result = REPEATING                # キュー全体の終了予定なし
else:
    expectedStartEpochMs[i] = now + remaining[current] + Σ previousRemaining
    expectedEndEpochMs = now + remainingAll
```

再生速度、Crossfade、DSPによるレイテンシ、バッファ不足、広告、外部サービス側のキュー差を推定の不確かさとして扱う。再計算イベント：seek、pause/play、remove/reorder、duration更新、再生速度変更、remote ack、経路切替。

未知値の式は評価せず、確定した区間だけを部分値として表示する。REPEATINGでは「繰返し中」と現在曲の残時間のみを表示し、全体の終了予定や後続曲の開始予定を表示しない。一時停止・バッファリング中は時計時刻の予定を消し、取得できた残再生時間だけを表示する。速度・再生順が未取得の外部再生は予定算出不可とする。

### DD-QUEUE-04 再生主体の交代・取消

Coordinatorは、現在所有者、遷移ID、対象entry_id、キューrevision、保留要求を一か所で保持する。再生主体はローカルPlayer、Provider、ネットワークレンダラーのいずれかであり、PARTNER_DIRECTは採用バックエンドの所有者として扱う。

1. 切替開始時に以前の遷移を無効化し、新所有者へ開始を送る前に旧所有者へ一時停止または停止を要求する。旧所有者がいなければ次へ進む。
2. ローカルはPlayerの状態、外部は許可されたAPIの停止状態通知を確認する。要求受理だけでは停止確認にしない。失敗・状態取得不可・タイムアウトでは新しい音声を自動開始せず、手動停止と継続確認を利用者へ提示する。外部状態を確認不能な事実は継続確認後も表示する。
3. 新所有者への開始直前にも遷移ID・revision・機能許諾を検査する。開始要求の受理はSTART_PENDINGとし、実際の再生状態通知でACTIVEへ更新する。HANDOFFは明示的な遷移操作を待ち、開始・終了を推測しない。
4. Stop、別曲指定、現在項目削除、出力先変更、切断、タイムアウトで保留開始を無効化し、取消可能なSDK処理は取り消す。すでに開始要求を送った主体の停止状態を確認するまで、次の自動開始を保留する。遅延成功通知は旧状態を復元せず、停止を再要求する。新しい要求まで停止してしまうSDKでは、旧要求の解決前に同じ主体への新規開始を送らない。
5. 停止を確認できない場合はSTOP_UNCONFIRMEDを表示し、自動次曲を止める。利用者による外部アプリの停止と継続確認で再開できる。Audipp外の音声を強制停止できるとは保証しない。

### DD-QUEUE-05 ギャップレス

同一の連続制御可能なバックエンド内で、次項目のVariantと出力形式・DSP構成が確定したら、バックエンドが次のデコードを先行準備する。エンコーダー遅延・末尾パディングを扱い、自然終了時に不要なstop/configureを挟まない。キューrevisionの変更で先行準備を破棄し、古い項目を開始しない。出力形式が変わり連続性を保証できない場合は再初期化が必要な境界と表示する。

LOCAL_DIRECT内と許諾されたPARTNER_DIRECT内で、それぞれ対応形式の境界試験を行う。PCM/DSD・レート・バックエンド変更は個別に成立条件を記録する。外部プレーヤー間・HANDOFFは原典の制限条件に従って区別し、完全なギャップレスと表示しない。既知の連続信号を分割した試料で欠落・重複・無音挿入を測り、形式ごとの検査は09のQLT-07に従う。個人間利用版には成立するローカル経路を必須とする。

## DD-AUD：音声出力ポリシー、PCM/DSD、Bit-perfect

### DD-AUD-01 Backend能力の分類

```kotlin
interface OutputBackend {
    val backendId: String
    suspend fun probe(route: DeviceRoute): BackendCapabilities
    suspend fun configure(request: OutputRequest): ConfigResult
    suspend fun start(input: AudioInput): StartResult
    suspend fun pause(): OperationResult
    suspend fun stop(): OperationResult
    fun observeRoute(): Flow<AudioRouteSnapshot>
}

data class BackendCapabilities(
    val supportedPcmRatesHz: Set<Int>,
    val supportedChannelCounts: Set<Int>,
    val supportedEncodings: Set<String>,
    val supportsDirect: Boolean,
    val supportsNativeDsd: Boolean,
    val supportsDop: Boolean,
    val provenBitPerfectFor: Set<VerificationKey>
)
```

**注**：標準Media3再生に本インターフェースを強制しない。実際に複数のバックエンドを実装するときに導入する契約案。Media3の内部レンダラーに直接差替可能な型を示すものではない。

### DD-AUD-02 モード選択とフェイルセーフ

1. `STANDARD`は常時候補。端末／形式がデコードできることを検証。
2. `PROCESSED`はPCMデータへ正規にアクセスでき、処理グラフと出力がサポートされた場合にのみ選択。
3. `DIRECT_ATTEMPT`はAndroid APIの対応・出力先・形式の条件を確認して要求。
4. `BIT_PERFECT_VERIFIED`は`VerificationKey(deviceModel,build,routeId,dacId,format,dspOff,volumeCondition,testId)`と一致した**測定記録**がある場合の状態であり、実機の一時状態と試験条件がずれるなら`NOT_VERIFIED`へ戻す。
5. `NATIVE_DSD/DoP`は全候補を個別に`probe`。DoPはPCMにDSDマーカーを埋めて運搬する方式でありPCMへのDSD変換と同一ではない。
6. 条件不成立時に危険なモードへ切替えない。代替`DSD->PCM`等を提示し、音質・処理モードが変わることを明示。
7. モード変更は `pause -> drain/stop -> configure -> volume safety -> start` の安全遷移とし、失敗時は旧状態の復旧か安全停止。

### DD-AUD-03 Android APIと検証方法

- `AudioManager.getDirectPlaybackSupport`（API 33+）、`AudioTrack.getRoutedDevice`、`AudioManager`デバイス通知などを適切なAPI level guardの下で利用。`getPreferredDevice`は指定値であり実ルートと異なり得る。
- API 36の`getRoutedDevices`は利用可能時に複数出力経路を取得。API不足やMedia3内部Track非公開では観測できない値を`UNKNOWN`とする。
- Android 14 USBのPreferred Mixerで`BIT_PERFECT`は端末HAL側のオプション。属性設定の成功とDACに届くビット列の実測を混同しない。
- Native DSDはDAC/ドライバー依存。SDKに一般的Native DSD保証APIがある前提で実装しない。
- ビット忠実性試験：既知のPCMパターン→検証可能なUSB経路→DAC/USBモニタ等の取得可能な手段で比較。条件固定・再現手順・校正結果がない場合は未検証。

参考：<https://developer.android.com/reference/android/media/AudioManager>、<https://developer.android.com/reference/android/media/AudioTrack>、<https://source.android.com/docs/core/audio/preferred-mixer-attr>。

### DD-AUD-04 全音声方式の成立表

| 必須機能 | 入力・設定・出力の契約 | 確定事項と残る検証 |
|---|---|---|
| PCMアップサンプル／指定レート | 2倍・4倍またはHz指定をOutputRequestへ渡し、出力能力を検査。要求値と実際の処理レートを別表示 | 上限はQ-04確定。対応レートと遅延・電力はR-02/05 |
| 複数リサンプラー・品質選択 | 少なくとも2つの実装を選択でき、それぞれ品質設定と負荷を表示する。libsamplerate 0.2.2とlibsoxr 0.1.3を採用先とし、品質・対応位相を別に表示 | R-02、配布条件・費用Q-05/06 |
| Linear/Minimum/Apodizing | 各特性のフィルターを選択し、適用レート・応答・遅延を提示。名称だけを変えた同一処理にしない | 09の②③で定義・応答検査 |
| NOS相当 | アプリ側のリサンプルをバイパスし、OS/DAC内部の処理は別の情報として表示する | R-03で経路観測 |
| DSD→PCM | 入力DSD形式・倍率を解決し、選択したPCMレートへ変換。変換をSignal Pathへ記録 | 上限はQ-04確定。実装・品質はR-02/05 |
| PCM→DSD | 再生中に変換し、選択したDSD倍率を対応出力へ送る。処理不能なら安全停止して理由を表示 | DSD256上限はQ-04確定。実装・経路・品質はR-02/03/05 |
| Native DSD / DoP | 各方式が成立するDAC・接続・ドライバーを検証し、転送方式と実レートを表示する | 端末・上限はQ-03/04確定。方式別対応はR-03 |
| Direct / Bit-perfect / 独自USB | 標準・Direct・独自USBを選択可能な対応経路を用意し、Bit-perfectは外部証拠と条件一致で表示 | Q-03、R-03 |
| 出力・バッファ・音量 | PCM/DSD形式、ソフトウェア／プリバッファ、公開された転送設定を要求し、適用結果を返す。音量ステップ・デジタルゲイン・機器音量を分離する | buffer/音量の初期範囲は09の②。機器APIは導入時に検査 |

本表は個人間利用版で選ぶ機能の候補一覧ではなく、すべてを成立させるための契約である。利用可能な機器で成立することと全機器で成立することを分け、未検証の必須方式が残れば完成判定を保留する。全DSPはDD-DSPの順序・ヘッドルーム・排他規則と合わせて検査する。

### DD-AUD-05 製品上限・能力に応じた変換

**確定値：** PCMの音声サンプルは最大384000 Hz・32-bit、DSDはDSD256。通常出力は2ch。最大5.1ch（6ch）は試験機能とし、対応経路でのみ有効化する。元音源が製品上限や出力機器の能力を超えても、デコード可能なPCMは対応形式へ変換して再生する。

1. 音源のコンテナ・符号化形式・実サンプルレート・精度・チャンネル配置を解決する。格納幅、整数PCMの有効精度、浮動小数形式は別属性とし、32-bit floatを32-bit整数の精度保証にしない。
2. 利用者の出力指定と製品上限を照合し、Backendがサポートする候補からターゲット形式を選ぶ。PCMは384 kHzと32-bitを超えず、出力先が低い上限ならそちらへ合わせる。要求形式を使えなければ、同じ44.1/48 kHz系列の対応レートを優先し、系列が合わなければ対応候補へ変換する。候補と優先順の具体値はBackend選定時に固定する。レートや精度をラベルだけ書き換えず、実データを変換する。
3. 384 kHz超のPCMは選定したレートへアンチエイリアス処理を伴ってダウンサンプリングする。32-bit超の入力は32-bitまたは機器の対応精度へ変換する。整数への量子化、dither、クリップ防止とFloat変換の精度はR-02/05で検証する。OS申告が不明な値は推測で埋めず、アプリ内で確定した変換形式と実出力の観測を分ける。
4. 5.1chを選んだ場合は入力のチャンネルマスクと出力能力を確認し、FL/FR/FC/LFE/Surround等の位置を保って送る。対応しない経路ではステレオへダウンミックスし、実験機能であることと変換を表示する。単純な先頭2ch切出しや位置不明の6ch送出は行わない。係数・LFE・ヘッドルームは09の②の初期方式で固定し、QLTで検査する。
5. 変換中はPROCESSEDとして、SOURCE、変換理由、処理後形式、REQUESTED、観測出力を表示する。DSPオフでもレート・精度・チャンネルを変更した経路はBIT_PERFECT_VERIFIEDにしない。切替・再接続時も同じ能力検査と安全音量手順を通す。

DSD64/128/256のNative/DoP/PCM変換は経路ごとの対応表で管理する。DSD256超をNative/DoPで対応済みと表示しない。デコードできる場合は明示したDSD→PCM経路へ変換し、PCM側の上限に従う。デコード不可は理由を示す。DoPのDSDレートとPCM形式での搬送レートは別属性とし、384 kHzというPCM音声処理上限をDACのDoP能力申告へ流用しない。搬送能力と上限の適用条件はR-03の対応表で検証する。

## DD-DSP：処理グラフとレベル管理

### DD-DSP-01 グラフモデル

`decoder -> channel map/downmix -> ReplayGain/R128 -> preamp -> PEQ -> IR -> Crossfeed -> Apodizing -> SRC/phase -> clip guard -> quantized PCM or DSD modulation`

- **実際の処理順**は09の②へ固定し、有効にしたDSPの種類と出力モードで依存・可換性を判断する。
- トラック切替時に音量が飛ばないよう、gain変更とフィルター再初期化に平滑化／短いフェードを検討。
- DSPはFloat32バッファを基準に、係数・蓄積の必要箇所はFloat64を使う。整数Bit-perfect経路はDSP/Float変換を迂回。dither・chとDSD変調の順序は09に従う。
- ヘッドルームは利用可能な最大正ゲインを考慮し、正規化とEQの累積利得を監視。リミッターはデータを変更するのでBit-perfect経路から隔離。
- `CONVOLUTION`はIRの形式、チャンネル数、長さ、レート、遅延、CPU予算を検証してから採用。IR差替中はバッファ不足に注意。

### DD-DSP-02 EQ契約・計算

```text
ParametricBand = {type: PEAK|LOW_SHELF|HIGH_SHELF|LOW_PASS|HIGH_PASS,
                  centerHz: >0, gainDb: finite, q: >0, enabled: boolean}
EQPreset = {sampleRateHz, bands[], preampDb, version}
```

- `centerHz < sampleRate/2`とし、異常Q・NaN・INF・不正ゲインは受け付けない。
- BiQuad等の係数更新はオーディオスレッドをロックしない方式（double buffer等）とし、係数を計算するのはバックグラウンド。
- 左右チャンネル処理はステレオ・モノラル・マルチチャンネルごとに適用可能性を明確化。
- ストリーミングSDKにPCMアクセスがない場合はその出力にDSPを適用不可とする。

### DD-DSP-03 ReplayGain／R128

タグにTrack/Album値があればモードで切替え、欠損なら無適用か明示した解析ルールを用いる。R128解析はlibebur128、既定-23 LUFS/True Peak目標-1 dBTP、設定範囲と誤差は09の②③に従う。Albumは全曲の解析結果を同じalbum revisionで集計し、欠損分を架空に補完しない。再生中解析は負荷に応じてバックグラウンド処理し、利用者の明示設定を優先。

### DD-DSP-04 利用場面プリセット（FR-AUD-10）

据置向け／ポータブルの切替は、DSP・リサンプラー・Visualizer品質設定をまとめたAudioPresetとして扱う。適用前に差分・推定負荷・非対応項目を表示し、出力モード変更はDD-AUD-02を通す。音量の引上げ、Bit-perfect検証状態の流用、能力・許諾制限の解除はプリセットに含めない。具体的な処理品質・電力予算は09の②③を適用する。実測前にプリセットの効果を保証しない。

## DD-DEV：接続機器・プロファイル

### DD-DEV-01 デバイス認識

`AudioManager`で出力先一覧とルート変更イベントを取得。`UsbManager`では許可範囲に限りUSB識別情報を取得し、標準USB Audio以外のクラスをオーディオDACと誤識別しない。

- `AudioRoute`（実際の再生経路）と `UsbDeviceRecord`（接続中のUSBデバイス）は別エンティティ。
- 機器名、VID/PID、シリアルの可否・メーカー情報・対応レートを取得ソースごとに保存。
- 識別不足時は`deviceId`に依存した恒久的な機器マッピングを行わず、ユーザー選択で紐付けを補強。
- プロファイル一致は `exact verified identity > user-confirmed mapping > manufacturer/product candidates`。候補を無断で確定しない。

### DD-DEV-02 安全なプロファイル適用

```text
onRouteChanged(newRoute):
  pauseIfRequiredBySafetyPolicy()
  cancelPendingHighGainUpdates()
  candidates = matchProfiles(newRoute)
  if exact and safeToAutoApply:
       applyOutputBackendKeepingSafeVolume()
       restoreNonVolumeParameters()
       offerVolumeRestoreIfPotentiallyLouder()
  else:
       showSelectionWithRiskNotice()
  logRouteChangeWithoutSensitiveDeviceSerial()
```

- 物理アンプゲインを制御したというログは記録しない。機器の手動メモとして示す。
- 初期音量・安全音量の単位は`digitalGainDb`/Android音量ステップ/ハードウェア音量を分離し、相互換算を保証しない。

### DD-DEV-03 機器DB・自動選択・共有

端末内Registryは取得情報・利用者の手動メモ・対応形式・動作確認結果を、出典と確認条件付きで保存する。利用者が設定した出力優先順で接続候補を評価し、前回の機器・組合せ・設定を復元する。接続IDだけを恒久IDにせず、機器識別が曖昧なら候補選択を求める。プロファイルにはPCM/DSD、リサンプル・フィルター、全DSP、バッファ、各単位の音量を保持し、音量復元はDD-DEV-02を通す。

共有操作では本人所有の共有用コピーをサーバーへ送り、メーカー公称・利用者申告・実測を区別する。機器名・VID/PID等の公開項目を事前表示し、USBシリアル・認証情報・個人のURIは送らない。実測にはアプリ・OS・機器・設定・試験条件を付け、他人の報告だけで端末のBit-perfectをVERIFIEDにしない。共有の編集・撤回・通報・ブロックとサーバー側の権限検査を必須とする。私用版の相手指定共有・撤回・通報は09の⑤に従い、不特定多数への公開/審査運用はPUBLICへ延期する。

## DD-PROV：ストリーミングProvider統合

### DD-PROV-01 Adapter契約

```kotlin
interface MusicProviderAdapter {
    val key: String
    suspend fun capabilities(accountId: String?): ProviderCapabilities
    suspend fun authorize(): AuthorizationResult      // SDKが提供する正規の認可方法
    suspend fun search(query: String, page: Page): ProviderSearchResult
    suspend fun resolve(itemId: String): ProviderItem
    suspend fun requestPlayback(itemId: String, mode: ExecutionMode, transitionId: String): ProviderPlayResult
    suspend fun requestPauseOrStop(transitionId: String): OperationResult
    suspend fun cancelPendingPlayback(transitionId: String): OperationResult
    suspend fun observeRemoteState(): Flow<RemotePlaybackSnapshot?>
    suspend fun disconnect(): OperationResult
}

data class ProviderCapabilities(
    val canSearch: Boolean,
    val directPlaybackLicensed: Boolean,
    val remoteControlAllowed: Boolean,
    val handoffSupported: Boolean,
    val canObservePosition: Boolean,
    val canChainQueue: Boolean,
    val canAccessPcm: Boolean,
    val permissions: Map<ProviderUse, UsePermission>
)

enum class ProviderUse { SEARCH, MIXED_DISPLAY, MIXED_QUEUE, PLAYBACK_CONTROL,
    DIRECT_PLAYBACK, HISTORY_STORAGE, STATISTICS, RECOMMENDATION, SCROBBLE,
    SYNC_EXPORT, PCM_PROCESSING, PCM_ANALYSIS }
enum class PermissionState { ALLOWED, DENIED, UNREVIEWED }
data class UsePermission(
    val state: PermissionState,
    val evidenceRef: String?,
    val reviewedAt: String?,
    val conditionsRef: String?
)
```

契約で`directPlaybackLicensed`は**アプリとサービスの契約事実**を表し、APIにURLがあるだけで`true`にしない。

能力は技術上の可否と許諾の両方を満たした場合だけ有効にする。permissionsの欠落・UNREVIEWEDは許可として扱わない。ALLOWEDには根拠条項または個別許諾、確認日、対象アプリ・地域・契約・保存期限等の条件を必須とする。開始・検索・保存・集計・外部送信の各入口で該当ProviderUseを再検査し、UIの非表示だけで制限しない。許諾失効時は新規処理を止め、既存データは契約上の削除・保持条件に従う。

Spotifyの[Developer Policy](https://developer.spotify.com/policy) III.5（他サービスとの統合）・III.13（聴取指標・利用統計等）を2026-10-27に確認した。App Remoteの接続成功は混在表示・混在キュー・履歴・分析等の許可を意味しない。各用途の適用範囲と個別許諾を確認するまでUNREVIEWEDとし、当該用途を含む公開判定はBLOCKEDとする。Scrobbleや同期も第三者への送信先を含めて審査する。

requestPauseOrStop/cancelPendingPlaybackがSDKで実行できなければUNSUPPORTEDを返す。disconnectを再生停止の代用にしない。リモート操作の完了確認・取消後の遅延応答はDD-QUEUE-04に従う。

### DD-PROV-02 プロバイダー別の状態・例外

`DISCONNECTED -> AUTHORIZING -> CONNECTED -> EXPIRED / RATE_LIMITED / UNAVAILABLE / POLICY_BLOCKED`

- OAuth等で必要な場合、ブラウザー／公式SDKで認可し、PKCE等の採用フローを許可リストで検証する。redirect URIを厳密照合。client secretをAndroid APK内の機密として扱わない。
- ストリームURLの有効期限・地域制限・サブスクリプション状態を正規SDKの回答から判断。期限切れ前に勝手に第三者のURLを更新しない。
- `429`等は提供元の`Retry-After`や規約に従う。一定回数後はユーザーに表示。
- Providerごとに`authState`, `playbackOwner`, `featuresAllowed`, `licenseEvidence`, `lastPolicyReview`を管理。
- Spotify App Remote型では元のSpotifyアプリが再生主体。`requestPlayback`の成功応答のみでAudipp側のPCM使用許可を付与しない。
- Spotify App Remoteは参考分類・補助操作に限り、Q-01の直接再生要求の実装には採用しない。直接再生の正式な実装手段と個別許諾はR-06で調査する。
- YouTube系の要求はyt-dlpによる音声のみの直接再生。動画UI・公式動画プレーヤーへの置換は行わない。技術・利用条件・権利・配布条件の判定はDD-PROV-04/05で別々に記録する。

### DD-PROV-03 能力に応じたUI制御

| 状態 | 表示例 | 次の操作 |
|---|---|---|
| `CONNECTED+DIRECT` | Audipp内で再生可 | 再生・対応DSP（許諾範囲） |
| `CONNECTED+REMOTE` | 公式アプリで再生を制御 | SDKで許された再生操作のみ |
| `CONNECTED+HANDOFF` | 公式アプリで開く | 明示的な遷移 |
| `AUTH_REQUIRED` | 再ログインが必要 | 正規認可 |
| `POLICY_BLOCKED` | 利用条件により非対応 | 説明・代替公式URL |
| `RATE_LIMITED` | 一時的に利用不可 | 待機時間の表示／後で再試行 |
| `UNAVAILABLE` | この音源は再生不可 | キューに状態を保持、別版候補 |

### DD-PROV-04 サービス別成立台帳・YouTube検証

現行3サービス群（Qobuz/Spotify/YouTube系。YouTubeとYouTube Musicは両方を検証）について、サービス名、直接再生の実装手段、カタログ検索可否、認証・再生・キュー・履歴等の用途別許諾、採用API/SDK/ツール版、個別許諾証跡、期限、試験ケースと結果を台帳へ記録する。カタログ検索は可能なら実装し、不可能なら理由を残す。YouTubeとYouTube Musicは同一方式が使えると仮定せず、両者の成立条件を別行で記録する。台帳に必要な直接再生機能のBLOCKEDが残れば公開しない。

FR-SRC-11の技術検証は製品コードへの採用と分け、音源・検証アカウント・利用条件・取得方式・事前保存量・URL有効期限を記録する。逐次読込、失効、シーク、認証失敗、通信切断を再現し、成功・失敗と回復動作を残す。利用条件上実行できない試験はBLOCKEDとして理由を残し、許諾を得るまで実施しない。技術的成功、利用許諾、配布許可は別の判定欄を持つ。

### DD-PROV-05 yt-dlp音声ソース解決

最新の利用者決定により、yt-dlpの実行は自前サーバーへ置く。音声専用形式（bestaudioかつvcodec=none相当）を解決/取得し、同じ接続側から認証済みHTTPSでAudippへ中継する。映像付き形式へ切り替えない。原則再圧縮しない。端末のProvider Adapterは本人用session、既知の形式/曲長/seek能力をPlaybackCoordinatorへ渡し、音声のdecode/DSP/出力は端末で行う。上流Cookie/URLを端末へ渡す方式を初期案にしない。

Playerは全体保存を待たず逐次再生する。seekは上流Range/segmentと取得済み領域を検査し、位置を確認してから再開する。失効は有界回数の再解決と媒体同一性検査で回復し、取消後の旧結果は遷移IDで破棄する。サーバーが止まってもローカル/保存済み音源の再生は維持する。転送の受付・取得完了と再生開始通知を混同しない。

私用API、実行版の固定/更新、資源上限、認証情報、初期の認証不要試料と本人認証必要ケースの分離は[中継契約](11_ytdlp_delivery.md)に従う。端末内蔵/Python/JSのAndroid対応とAPI24ラッパー問題は後段候補へ移し、現在の完成条件にしない。提供元の利用条件・権利と技術成功は別判定のまま保持する。

### DD-PROV-06 Apple Music（廃止）

2026-10-29の利用者指示によりApple Musicは実装対象外。公式MusicKit for Android/Apple Music API、developer token署名API、本人認証、検索・再生AdapterとCider参考の独自PCM方式Cの研究・試験を実施しない。資格未取得・未実装・未検証を完成阻害に数えない。旧設計はGit履歴を参照し、このIDを別機能へ再利用しない。

### DD-PROV-07 添付からの追加候補・対象外サービス

Apple Music/Amazon Music/SoundCloudは実装しない。Adapter/ログイン/検索・再生UI/申請・UATの対象を現行3群・4サービスへ絞る。除外した3サービスの停止・未許諾をアプリの未完成理由にしない。以前の調査結果は履歴として保持する。

添付のQobuz非公式接続、Spotify librespot PCM供給の主張・一次ソース・未検証箇所は[候補照合](10_provider_proposal_review.md)に記録した。これらを技術調査対象へ追加し、「正式SDK以外は候補がない」とは扱わない。一方、資料に書かれた採用/申請不要/機能追加を、そのままユーザー決定や動作保証へ昇格しない。

Qobuzはcommit固定qobineを参考にブラウザー認証、FileBased/SessionBased、報告と回復を分離して検証する。Spotifyはlibrespot v0.8.0のSamples(Vec<f64>)だけをPCM候補とし、Rawと区別して共通bufferへ渡す。callback生存期間・Stop/seek世代・cancel可能なbackpressure・正規化の二重適用を検査する。正規のメディア/DRM条件とPCMアクセスを確認できなければ共通DSPへ接続しない。

TEST-PROV-01/RESEARCH-01は現行対象だけで、版/ABI・認証/契約・取得失敗・報告・callback・秘密漏洩・用途別拒否を確認する。TEST-QUEUE-01/TAP-01/SEC-01も候補導入時に適用する。生の添付コード・Rust/Gradleモジュール・新たな依存を先に取り込まない。未実装のcrossfade/AutomixやConnectの採否は今回の製品変更に追加しない。

### DD-PROV-08 利用者選択による端末保存

FR-SRC-12は[中継・端末保存契約](11_ytdlp_delivery.md)に従う。既定saveRequested=falseはstream、一時cacheは期限/容量で破棄する。trueは保存先/保存用途を検査し、Audippが受信してSAF URIへ保存する。URIをserverやsyncへ送らない。保存先取消・権限拒否・保存不可時は理由と再選択/保存しない再生を提示し、黙って保存する/成功表示することはない。

再生状態と保存job状態、Stopと保存取消を分離する。媒体同一性・offsetを検査して中断再開し、確認できなければ未完了分を作り直す。未完了名/journalで書込み、demux/長さ等の検査後だけCOMPLETEDとVariant登録へ進む。既存ファイルの無断上書き、未完了ファイルのlibrary登録、cache削除による保存曲削除は禁止する。詳細な状態・API・失敗復旧は11を正本とする。

## DD-NET：LAN音源・遠隔レンダラー

### DD-NET-01 Protocol Adapter

- `NetworkLibraryAdapter`：`listDirectory`, `listMedia`, `resolveStream`。SMB/WebDAV/NAS。資格情報はKeyStore保護方針で保存し、資格情報をURIクエリに載せない。
- `RendererAdapter`：`discover`, `connect`, `getCapabilities`, `sendCommand`, `observeState`, `disconnect`。MPD/UPnP/OpenHome。
- UPnP discovery／LAN利用制約はAndroid OSのネットワーク・マルチキャスト制限とネットワーク権限・LANポリシーに従う。
- 外部レンダラーは独立したキュー・音量を持ち得る。Audippの内部キューと自動的に一致すると仮定しない。
- `remoteVolume`は機器に対する要求値。物理音圧・アンプ利得の安全保証ではない。

### DD-NET-02 失敗条件

ネットワーク断→`OFFLINE`、再探索、ユーザー操作で復帰。サービス失効→`AUTH_REQUIRED`。MPDが対応しない操作→機能無効。再試行はタイムアウト＋有界指数バックオフ。意図せず別のレンダラーで再生が始まらないよう固定IDを確認。

### DD-NET-03 ソース・レンダラー・キューの整合性

SMB/WebDAVは音源を読むLibrary Adapter、MPD/OpenHome/UPnPは出力先を操作するRenderer Adapterとして分離する。SourceRefの種別で出力モードを固定せず、選択出力先ごとにExecutionPlanを生成する。レンダラーが到達できないSAF URIや端末内資格情報をそのまま送らない。外部機器へローカルファイルを配信するHTTPサーバーを暗黙に起動しない。

MPDは接続時のプロトコル応答と使用可能コマンドを確認し、状態監視と操作を別接続で扱う。曲ID・現在位置・キューバージョンを照合し、外部からキューが変更されたらAudippの順序を無断で押し戻さず再同期を提示する。操作ACKと実際の再生状態を区別する。曲名・URI・パスを文字列連結でコマンドへ埋めず、引用・改行をエンコード/拒否して複数コマンド注入を防ぐ。[MPD Protocol](https://mpd.readthedocs.io/en/stable/protocol.html)。

UPnP/OpenHomeは、発見した機器ID、サービス種別、提供された操作と状態情報から可否を判断する。独立キューを持つ機器と、現在URIだけを受ける機器を同じ能力として扱わない。イベント欠落後は状態を取得し直し、期限切れの機器情報だけで自動再生しない。応答XMLは外部エンティティを解決せず、サイズ・件数・期限を制限する。原典のBubbleUPnP/upmpdcli/MPDを相互運用試験の対象に残す。

SMBJ 0.13.0、WebDAV/MPD/UPnP/OpenHomeの実装方式・対象service・timeout/入力制限は09の④に従う。Android推移依存・権限と相互運用は導入検証待ち。

## DD-HIST：履歴・ランキング・推薦・ABX

### DD-HIST-01 再生計測

再生試行ID、連続聴取区間、単調時計と再生ポジションの役割、日付境界・再生回数は[技術設計決定記録](08_design_decisions.md)に従う。

- 再生時間はWall clock経過ではなく、実際に進んだ音声再生ポジションと状態を基礎に推定する。シークでスキップした長さは聴取時間に加算しない。
- PlayEvent IDを生成し、セッション・エントリーIDとともに保存。DBの重複排除で二重集計を防ぐ。
- Providerがイベントを返さない場合は「外部状態未取得」とし、根拠不十分な再生時間を確定値に加算しない。
- 外部ProviderのデータはHISTORY_STORAGEがALLOWEDのときだけ保存し、統計・推薦・Scrobble・同期への使用はそれぞれの許諾を別途検査する。再生状態の取得能力だけで履歴保存を許可しない。
- `sourceType/codec/routeId/profileId`の取得済み時点スナップショットを保存。未取得値はNULL。

### DD-HIST-02 統計計算

- 日・週・月・年の集計はタイムゾーンと日付境界を明記。ユーザーが変更した端末時計を調査可能にする。
- 曲、アーティスト、アルバム、ジャンル、再生形式、曜日、時間帯、デバイス別の集計ビューを作成。
- 使用可能な履歴を削除した場合、統計・スマートプレイリストを再計算。
- 推薦はまずメタデータとローカル履歴に基づく説明可能なスコアの実装を検討し、クラウドMLとユーザーデータ送信を必須としない。

### DD-HIST-03 ABX試験

`prepare stimulus A/B -> loudness match(測定済み条件) -> assign random X -> trial -> record answer -> finalize -> summarize`。試行数・条件固定、等確率割付、途中終了の区別、結果の片側二項確率は08_design_decisions.mdに従う。ランダム割付を試行中に表示しない。音源間の音量・遅延・DSP状態を統制し、比較条件が統制できない場合は試験不可として明示する。単独試験結果を「聴覚能力の証明」と断言しない。

### DD-HIST-04 Last.fm

FR-USE-05は個人間利用版必須。[技術設計決定記録](08_design_decisions.md)のLast.fm規則を採用する。曲長>30秒、実再生時間>=min(曲長/2,240秒)の両方を満たすときだけ送信し、アプリ内の再生回数とは別に判定する。曲長不明はScrobbleしない。ProviderごとのSCROBBLE許諾と利用者同意の両方を必要とする。

ブラウザー承認を経由し、署名用secretは自前サーバーGatewayへ保持する。Now PlayingとScrobbleは別要求、Now Playingは失敗しても再送しない。Scrobbleはdestination+play_event_idで端末とサーバーの重複登録を防ぐ。HTTP結果だけでなく本文statusと項目別accepted/ignoredを検査し、外部サーバーでの冪等性は仮定しない。到達不明はUNCERTAINで自動再送を保留する。無関係なProviderトークンをGatewayへ送らない。

送信状態はPENDING/SENDING/ACKNOWLEDGED/IGNORED/RETRYABLE/UNCERTAIN/CANCELLED/PERMANENT_FAILUREとする。失効・同意撤回・連携解除・履歴削除で未送信を止め、再試行時にも再検査する。API資格取得と実サービス契約試験は未実施だが、認証・判定・再送の方式を再び未決定とは扱わない。

### DD-HIST-05 ランキング・推薦・スマートプレイリスト

History集計処理は許諾されたPlayEventから日・週・月・年・累計の再生回数と時間を算出し、曲・アーティスト・アルバム・ジャンル・全体の対象を切り替えて画面へ返す。期間境界は保存したタイムゾーン、再生時間帯・曜日は実再生区間に基づく。期間をまたぐ聴取時間は区間を分割する。複数アーティストは各人へ帰属、全体はevent_idで一度だけ計数する。週は月曜開始、同順位は1,1,3とし、再生回数は08_design_decisions.mdの規則で固定する。形式・レート・端末・機器・プロファイルは再生時点の記録から分類し、不明は独立区分にする。全体ランキングはQ-11の回答どおり本人の全曲・全期間を対象とする。

RecommendationEngineは、本人の同意した履歴・好み・時間帯・ジャンルとメタデータ一致度から候補を計算し、曲ごとの推薦理由を返す。メタデータ充実度は順位付け因子として別に記録し、履歴・メタデータ不足で架空の推薦を作らない。計算式・重み・上限50件は08_design_decisions.mdの推薦v1へ固定し、同一入力で結果を再現できる版番号を付ける。外部ProviderのRECOMMENDATION許諾がないデータは入力から除外する。

スマートプレイリストは利用者が作る検索条件と推薦処理が作る条件を区別して保存する。ライブラリ・履歴・条件式のrevisionが変わったときに再評価し、候補曲と選定理由を画面へ返す。履歴削除、ソース失効、関連付け解除時には派生値を無効化して再計算する。固定プレイリストとして保存したものは勝手に書き換えない。

## DD-OBS：Signal Path／Visualizer／診断

### DD-OBS-01 Signal Path Inspector

```text
Source: [FLAC 96 kHz / 24-bit] (SOURCE, metadata)
Decoder: [PCM 96 kHz / 32-bit float?] (APP_OBSERVED only if instrumented)
DSP: [EQ enabled, Preamp -3 dB] (APP_OBSERVED/config applied)
Output requested: [96 kHz stereo] (REQUESTED)
OS reported: [48 kHz USB] (OS_REPORTED)
DAC verified: [not measured] (UNKNOWN)
Bit-perfect: [NOT_VERIFIED]
```

**ここに記載の値は画面設計の例であり、実機で確認済みの事実ではない。** 各フィールドは`EvidenceLevel`バッジと時刻・出典を表示し、値が取得できないときはデータ構造自体を未知にする。

### DD-OBS-02 Visualizerグラフ

```text
Approved PCM source
  -> AudioTap (read-only, does not mutate playback)
  -> bounded RingBuffer (sampling / channel format)
  -> Analysis Worker: RMS/Peak/FFT/phase correlation
  -> frame throttler (default 30fps, selectable 15/30/60fps)
  -> Compose Canvas / graphics rendering
```

- 音声再生スレッドで重いFFTや描画処理を行わない。固定長のバッファから非同期に読み、上限超過時は古い可視化データを捨てる。
- 可視化の負荷増大で再生バッファを枯渇させない。バックグラウンドでは描画停止、画面表示時のみ再開。
- 相関やベクトルスコープはステレオの時間整列したPCMが必要。1ch音源や外部音声非公開時は非対応として表示。
- FFT窓長、窓関数、更新レート、dBFS基準、True Peakのオーバーサンプリング方式は計測モジュールADRで固定する。
- FR-OBS-07に従い、AudioTapごとに`tapPoint=SOURCE_ANALYSIS/DECODER_OUTPUT/POST_DSP`、取得位置のsampleRate・チャンネル・測定方式・時刻を持つ。解析Workerから画面へ値と取得位置を一緒に渡す。DSP後のTapがない経路ではDSP後の値を不明とし、音源の解析値を代用しない。

### DD-OBS-03 診断ログ

```text
AudioEvent {
  event_id, session_id, monotonic_time_ms, wall_time_utc,
  category, phase, route_class, source_kind,
  evidence_level, error_code?, app_build, android_api
}
```

- ファイル名、フルURI、USBシリアル、メールアドレス、認証トークンを既定収集しない。
- 診断情報エクスポート前にプレーンテキスト等で内容を確認させる。`REDACTED`化した例外ログと連携状態のみ同梱。
- 再生処理中の高頻度ログはバッファ制御・レート制限し、ユーザーの診断モード時のみ増量する。

## DD-CLOUD：アカウント・同期・共有（個人間利用版必須）

### DD-CLOUD-01 API境界（エンドポイント案、**未実装**）

| Method/Path（仮） | 目的 | 認証・制御 |
|---|---|---|
| `POST /v1/auth/session` | 正規IDプロバイダーを通したセッション確立 | Token検証、レート制限、端末特定 |
| `GET /v1/sync?cursor=...` | 利用者の差分同期取得 | 所有者スコープ、ページング、cursor失効 |
| `POST /v1/sync/batches` | 差分イベントの冪等送信 | `eventId`重複除去・replay耐性 |
| `DELETE /v1/me` | アカウント削除要求 | 再認証・削除結果の確認 |
| `GET /v1/friends` | 許可済み関係取得 | 既定非公開、ブロック・公開範囲検証 |
| `POST /v1/friends/invitations` | 許可制フレンド招待 | スパム・レート制限・通報対応 |
| `GET /v1/device-profiles/shared` | 機器情報共有（搭載必須、利用は任意） | 公称／申告／実測の根拠付き表示 |

これは個人間利用版に必須のバックエンドの設計契約案。自前サーバーを使用可能とする方針はQ-06で確定した。認証はKeycloak、業務HTTPS APIはKotlin/Ktor、DBはPostgreSQLとする。構成・同期バッチ・revision・cursor・所有権規則は08_design_decisions.mdに従い、具体URL・配備版・バックアップ先等はP-02/03で確定する。Q-07〜12の製品方針は回答済みであり、設計の具体化と運用条件の確認を残す。

### DD-CLOUD-02 同期衝突・削除

- 設定はフィールド単位のrevision/version、履歴はイベントベースの冪等マージを原則とする。プレイリスト・キューの並べ替えは基準revisionが違えば409とし、両コピーを保持して利用者に選択させる。同期バッチとcursorは08_design_decisions.mdの原子性・ページング規則に従う。
- オフライン更新は`sync_outbox`から再送し、応答確認後に確定。送信失敗でローカルデータを消さない。
- アカウント削除ではサーバー側の削除・保持義務の例外・完了通知などを事前に規定。端末上のローカルファイル自体は勝手に削除しない。
- フレンド情報・機器プロファイル公開は**初期非公開**。ランキング共有は自動ONにしない。

履歴同期は以下と08_design_decisions.mdの削除世代・tombstone規則を採用する。実装で契約を満たすことを確認するまで同期・復元を有効化しない。

- 履歴削除は、対象event_idのtombstone、またはアカウント全履歴の削除世代としてサーバーへ記録する。壁時計の新旧だけで競合を決めず、サーバー採番のrevisionを使う。削除済みevent_idの再送は作成として受理しない。
- 全履歴削除前の世代に属するオフライン履歴は、削除後に届いても復活させない。旧端末は削除世代を適用してから新規履歴を送信する。tombstoneを破棄する前に、古いcursor・端末を失効して完全再同期を要求できることを検証する。
- 端末内の履歴削除と同じトランザクションで未送信のScrobble・同期イベントを取消対象にし、統計・推薦用の派生データを無効化する。Workerは送信直前にも削除状態と同意を検査する。送信済み・通信中の要求は取消を保証せず、送信先で削除できる範囲を利用者へ示す。
- オフライン削除は端末内では即時反映し、サーバー反映待ちを表示する。アカウント削除後の送信は認証側で拒否する。サーバーのバックアップ復元時も削除記録を適用してから提供を再開する。私用版の保持期間と削除期限は09の⑤を適用し、復旧試験前に完成扱いにしない。

### DD-CLOUD-03 フレンド・本人データ・共有の契約

サーバーはアカウント、端末、友達申請・承認、ブロック、公開範囲、機器共有の所有者を管理する。クライアントが非表示にするだけでなく、すべての取得・更新で本人または公開範囲の権限を検査する。フレンド関係は相互承認で成立し、解除・ブロック後は共有ランキング・共通嗜好・プロフィールへの参照を拒否する。集計の共有可否とプロフィールの公開範囲は別に保存する。

本人のランキング比較・共通嗜好は、公開を許可された曲・アーティスト・集計だけをサーバーが照合して返す。ProviderごとのSTATISTICS/SYNC_EXPORT等の許諾がないデータは送信・派生処理へ渡さない。公開範囲縮小や削除後には共有キャッシュを無効化する。Q-11に従い、全体ランキングは本人の全曲・全期間を対象とし、全利用者を合算した世界ランキングは作らない。

DD-CLOUD-01のAPI案には、本人データのエクスポート・削除結果照会、フレンド承認・解除・ブロック、比較・共通嗜好の取得、プロフィール公開設定、機器共有の作成・更新・撤回・通報を追加する。サーバーの論理キー、原子性、ページングと応答コードは08_design_decisions.mdで定義する。物理migration・URL・配備レート制限は導入時に固定し、私用削除期限は09の⑤を採用する。未確定のまま実装完了とはしない。

同期対象・保持・バックアップは回答済みQ-08〜10とDD-CLOUD-04に従う。並べ替えの同時更新は自動で片方を捨てず、基準revisionの不一致を競合として返し、両方のコピーを保持して利用者に選択させる。原曲・認証秘密・端末固有権限は同期しない。

### DD-CLOUD-04 回答済み認証・データ方針

メールアドレスでのログインとGoogle等の外部IDログインを用意し、まずGoogleを外部IDの設計対象とする。本人の明示操作と再認証なしに、メール文字列の一致だけでアカウントを結合しない。ログインしていない端末のローカル履歴を送信する場合も、同期開始の同意と重複検査を通す。私用版は管理者発行のメール識別子＋パスワードと本人確認後の管理者復旧を採用し、確認/再設定メールはPUBLICとする。Googleは設定済みclientでKeycloakのIdentity Brokerを用いる。AndroidはシステムブラウザーとAuthorization Code＋PKCEを使う。具体構成は08_design_decisions.mdに従う。

| データ | 同期・保持・出力の確定方針 |
|---|---|
| 履歴・集計 | 利用者が有効化した場合に同期し、本人が削除するまで保持。削除は派生統計と端末へ伝播する |
| プレイリスト・一般設定・音声プリセット・機器プロファイル | 同期対象。端末固有の能力・実測条件を他端末へ無条件適用せず、復元時に再評価する |
| キュー | 復元用コピーとして同期。他端末の再生を自動開始しない |
| 元音源・トークン・SAF URI・LAN資格情報 | 同期対象外。別端末の音源は識別情報から候補を提示し、曖昧な対応を自動選択しない |
| 本人エクスポート | 設定・プレイリスト・履歴・プロファイルを読める形式で出力。秘密と再配布権未確認の画像は除外 |
| インポート・バックアップ | 設定・プレイリスト・プロファイルを復元。履歴取込は重複と削除世代を検査。削除済み履歴を復活させない |
| 公開データ | 既定非公開、フレンドは相互承認、機器共有は明示公開・撤回可能。実測条件と根拠ラベルを保持 |

私用の保持/削除期限・招待/復旧は09の⑤に従う。配備先の実環境はP-02で確認する。一般向けの日本公開・窓口・体制はPUBLICへ延期する。料金無料・広告なし・API/有償ライブラリの上限予算なしという方針は、外部契約や支払いが実行済みであることを意味しない。

## DD-UI：画面・表示状態とバリデーション

| 画面ID | 主な表示 | 操作 | 空・エラー状態 |
|---|---|---|---|
| UI-01 Home | 履歴、現在再生、接続ソース | 再開、ライブラリ／検索への遷移 | 空ライブラリ→登録案内 |
| UI-02 Library | 曲／アルバム／アーティスト／フォルダ／形式 | フィルター、ソート、追加、更新 | URI喪失→再選択 |
| UI-03 Search | ローカル＋許諾済みサービスの結果 | サービス切替、再生方式の確認 | API拒否→理由別表示 |
| UI-04 NowPlaying | 曲名、アート、形式、出力、位置 | 再生、前後、シーク、出力選択 | シーク不可・再生不能を明示 |
| UI-05 Queue | 並び、ソース、予定、手動切替 | ドラッグ、削除、繰返し、保存 | 長さ不明→予定非表示 |
| UI-06 Audio | PCM/DSD、DSP、Bit-perfect情報 | 対応済みモードとフィルター選択 | 非対応値・信頼度を表示 |
| UI-07 Device | 接続DAC、取得データ、組合せ | プロファイル適用、音量確認 | 未取得→「不明」 |
| UI-08 Visualizer | 波形、FFT、VU、相関等 | モード、FPS、テーマ、全画面 | PCM権限無し→解析不可 |
| UI-09 Providers | 認証、許可、外部プレーヤー接続 | ログイン、解除、アプリで開く | 契約制限と対処 |
| UI-10 History | 日／週／月／年等の統計 | フィルター、削除、エクスポート | 記録不足→説明 |
| UI-11 Settings | 一般／高度／プライバシー | 変更、初期化、診断ログ | 危険設定→確認ダイアログ |
| UI-12 Account | 同期、フレンド、公開範囲 | 登録、停止、退会 | 未設定・認証失効・サーバー障害を区別し復旧操作を表示 |

### DD-UI-02 UIの表示契約

- データ読み込み：`Loading`→`Content`または`Empty/Error`。ロード中に未取得値を以前の別曲の値で表示しない。
- 操作有効条件は`PlaybackCapabilities`と`Availability`に従う。UIにグレー表示を置くだけでなくバックエンド側でも拒否。
- TalkBack読み上げ：「一時停止、現在再生中」「USB DAC、経路はOS申告、Bit-perfect未検証」など根拠を含める。
- 設定変更は適用前・実際に適用した・適用失敗を別ステータスで返す。リスナーの安全に関わる項目は確認画面を出す。
- テキスト、色、余白は`docs/design.md`に従う。FR-UI-03のSystem/Dark/Light、System既定、永続化、即時切替、OS追従を実装し、切替で再生セッションを再生成しない。ミニプレイヤーは共通のPlaybackSnapshotを参照する。文字拡大・モーション削減・端末幅差異を両配色で検証する。

### DD-UI-03 カスタムテーマ・ウィジェット・高度設定

Q-13の承認に従い、カスタムテーマはDark/Light別のアクセント・背景・文字色トークンを保存し、選択・編集・削除・既定へ戻す操作を設ける。文字・操作のコントラストを検査し、読めない設定には警告と復帰操作を表示する。利用者が明示指定した色をジャケット由来の色より優先し、未指定のアクセント領域だけに画像色を適用する。実行コード・外部テーマスクリプトは扱わず、再生Serviceをテーマ変更のために作り直さない。

Androidホーム画面ウィジェットは現在曲・アーティスト・アート、再生／一時停止・前後曲・アプリへの移動を表示する。ServiceのPlaybackSnapshotから表示を更新し、操作は同じCoordinatorへ送る。権限失効・再生未登録・プロセス未起動・外部制御不可の場合は状態と可能な操作を表示する。外部アプリの状態を推測で補完せず、ウィジェットへトークン・URIを埋め込まない。AppWidgetProvider＋RemoteViewsとし、09の④の更新/画像/操作契約とOS制限を検査する。

通常設定と高度設定を分け、高度設定には全PCM/DSD・DSP・バッファ・プロファイル項目を能力と適用結果付きで配置する。再生画面から出力選択、キューから開始予測・形式・提供元、プロファイル一覧から組合せ選択、機器詳細から取得情報へ移動できる。Visualizerは再生画面・専用画面・全画面で同じ解析状態を参照し、非表示の描画を止める。

日本語と英語を個人間利用版の必須UI言語として用意する。ラベル、状態・エラー、ヘルプ、アクセシビリティ文言、テーマ・ウィジェットを両言語で検査し、元の曲名・人名を翻訳で置き換えない。文字列リソースと日付・数値のロケール処理を共通化する。アプリ内の動画再生UIは設けない。

## DD-ERROR：エラー分類・例外処理

```kotlin
enum class ErrorDomain {
    SOURCE_IO, FORMAT_UNSUPPORTED, PERMISSION_DENIED, PLAYBACK_ENGINE,
    AUDIO_ROUTE, DSP, PROVIDER_AUTH, PROVIDER_POLICY, PROVIDER_RATE_LIMIT,
    NETWORK, DATABASE, SYNC, UNKNOWN
}

data class PlaybackError(
    val domain: ErrorDomain,
    val code: String,
    val retryable: Boolean,
    val userAction: UserAction?,
    val diagnosticId: String
)
```

| エラー | ハンドリング | 観測値・データ保護 |
|---|---|---|
| `SOURCE_IO` | ソース再選択、次曲へスキップ（設定次第） | 元ファイル書換え禁止 |
| `FORMAT_UNSUPPORTED` | 対応形式の案内 | 端末が再生できると断定しない |
| `PERMISSION_DENIED` | OS権限画面を再提示 | 許諾外のディレクトリへアクセスしない |
| `AUDIO_ROUTE` | 安全Pause、再評価 | 音量維持を慎重に扱う |
| `PROVIDER_AUTH` | 認可画面へ誘導 | Tokenログ禁止 |
| `PROVIDER_POLICY` | 機能無効／公式手段案内 | 制限回避を試みない |
| `NETWORK` | 有界リトライ／ユーザー確認 | ローカル再生を止めない |
| `DATABASE` | ロールバック、回復/エクスポート案内 | 破壊的再生成を勝手に行わない |

## DD-TEST：要求トレーサビリティ・自動試験・実機試験

### DD-TEST-01 テスト層

| 層 | テスト対象 | 推奨方式 |
|---|---|---|
| Unit | Queue reducer、URI正規化、推定時刻、DSP設定検証、Evidenceの整合性 | JUnit、プロパティテスト |
| DB Integration | Room migration、FK、Queue変更の原子性、重複イベント排除 | Android instrumented DBテスト |
| Session Integration | MediaController↔PlaybackService、通知・再生状態一致 | emulator + instrumented tests |
| Adapter Contract | 各Providerで正規認可、機能フラグ、RateLimit、Remote ack | Mock/Fake、契約テスト。外部本番アクセス別途 |
| Audio Signal | 正規PCMリサンプル／EQ応答／チャンネル／クリッピング | 既知信号のオフライン数値検証 |
| Hardware | 内蔵・USB DAC・DAP・Bluetooth・ハイレゾ/DSD | 実機で端末マトリクスと音声測定 |
| UI/A11y | 各画面・未対応状態・TalkBack・回転・大文字 | Compose UIテスト＋実機 |
| Security/Privacy | 権限、暗号保管、OAuth、削除、ログ・依存ライセンス | 静的・動的監査 |
| Completion / PUBLIC | 個人間版は署名APK更新・ライセンス・復旧。AAB/Play/窓口は完成後 | 段階別チェックリスト |

### DD-TEST-02 試験ファミリーと合格条件

Android向け全機能をRB必須とし、各試験ファミリーの私用ケースを個人間利用版で適用する。TEST-STORE/GOVのストア・窓口等はPUBLICで実施し、現在の未達に数えない。原典の適用条件から対象外になる個別ケースは理由を記録するが、機能全体を適用除外にしない。試験ファミリーは実装時にケースへ分割し、入力・操作・期待値・測定条件を追記する。以下の試験定義は実装済み・実行済みを意味しない。設計未完了の要求はDD-TRACEに明記し、該当する設計と試験を具体化するまで出荷対象にできない。

| TEST-ID | 仕様ID | 実施内容 | 合格条件 |
|---|---|---|---|
| TEST-LIB-01 | FR-SRC-02 | URI許可、取消、SD切断、走査取消・上限・部分失敗・正常完了後の削除 | 不完全な走査で未訪問曲をMISSINGにせず、正常完了時だけ消失確定 |
| TEST-LIB-02 | FR-SRC-03〜05 | 欠損タグ、巨大アート、同名アルバム、複数ディスク | 関連情報の正しさとメモリ制限 |
| TEST-PLY-01 | FR-PLY-01〜04 | 隣接交換・逆順・挿入削除・ロールバック、全曲不可＋リピート、自然終了・明示SkipNext | 一意制約違反なし、現在entry_id維持、全曲不可でも一巡以内に終了 |
| TEST-PLY-02 | FR-PLY-02〜03 | ロック、通話、音声フォーカス、BT/USB切断 | 安全な状態遷移 |
| TEST-AUD-01 | FR-AUD-01, FR-OBS-01 | ソース44.1k、OS48k等の差異を模擬 | 差異・証拠区分が正しく表示 |
| TEST-AUD-02 | FR-AUD-03〜07 | DAC別Direct/DSD/DSP検査 | 対応していない経路は無効、検証ログあり |
| TEST-AUD-03 | FR-AUD-08, FR-DEV-05 | 高感度イヤホン、音量急増シナリオ | 想定外の急増を生じない |
| TEST-PROV-01 | FR-SRC-08〜10 | 対象外3サービスを除く現行対象の直接再生・認証・失効・キュー、カタログ検索可否、補助操作。再生のみ許可・他用途不許可を模擬 | REMOTE/HANDOFFを直接再生と判定せず、検索可能時は実装・不可時は根拠を記録。不許可データ処理を入口で拒否 |
| TEST-QUEUE-01 | FR-PLY-07〜09, FR-NET-03 | LOCAL↔REMOTE/NETWORK_REMOTE/HANDOFF、開始待ち中のStop・別曲・削除・遅延成功 | 旧主体の停止未確認で次を自動開始しない。取消後の開始通知は状態を戻さず停止要求 |
| TEST-NET-01 | FR-NET-01〜03 | LAN切断・再接続・MPD再生状態不整合 | 状態が一致しなければ明示 |
| TEST-VIS-01 | FR-OBS-02〜04 | FFT入力比較、ステレオ相関、CPU高負荷 | 正しい計測結果、再生影響を制御 |
| TEST-HIST-01 | FR-USE-01 | 途中停止、シーク、二重イベント、削除、保存不許可の外部イベント | シーク長・重複を加算しない。不許可データを保存せず、削除が派生値へ反映 |
| TEST-ACC-01 | FR-USE-03〜04, NFR-PRIV-02 | メール/Googleログイン・復旧・明示連携、同意・退会・公開範囲、2端末の削除・旧世代再送・バックアップ | メール一致だけで誤統合せず、第三者アクセス不可。削除履歴が復活せず、未送信分が取消される。認証・削除プロトコル実装後に実施 |
| TEST-REL-01 | NFR-REL-02 | 8時間再生と画面OFF、試験機別測定 | 承認した条件で重大不具合なし |
| TEST-STORE-01 | NFR-STORE-01 | 個人間版：署名APK更新とデータ維持。PUBLIC：Play Console/AAB/掲載情報/窓口 | 個人間版の更新試験に合格。PUBLICケースは延期理由を明記し現在のFAILにしない |
| TEST-UI-01 | FR-UI-01〜06, NFR-A11Y-01, NFR-UX-01 | 日本語/英語の全画面・ヘルプ・読み上げ、テーマ初期値・保存・即時切替・OS追従・固定選択、モーション削減・文字拡大・回転・ミニプレイヤー | 翻訳漏れ・重なりなく、再生・位置・キュー・画面状態を維持。両言語・両配色で読め、ミニ／フル／通知の曲・状態が一致 |
| TEST-PERF-01 | NFR-PERF-01〜03 | 端末種別の起動／再生／シーク応答P50/P95、CPU/電池 | 公開前に承認した閾値内 |
| TEST-SEC-01 | NFR-SEC-01〜03, NFR-PRIV-01〜03 | Manifest権限、OAuth/token、ログ、外部送信同意 | 不要なアクセス・秘密漏洩なし |
| TEST-PLAYLIST-01 | FR-PLY-05 | 作成・改名・項目追加・並べ替え・削除・キューへ複製 | プレイリスト削除が現在キューを消さず、ソース消失項目を保持。番号の一意性と原子性を維持 |
| TEST-SCHEDULE-01 | FR-PLY-06/09 | 同じTrackの5分版・7分版、停止・再開・シーク・シャッフル・リピート・長さ不明 | 選択版と実再生順で算出。停止中の時計予定と繰返し中の全体終了予定を表示しない |
| TEST-RESTORE-01 | FR-PLY-04, NFR-REL-03 | 定期保存・シーク後に終了コールバックなしのプロセス終了、キュー変更との競合・DB保存失敗・版消失 | 最後のコミット済み項目・位置を整合して復元。保存間隔と遅延を記録し、復元だけで再生しない |
| TEST-ABX-01 | FR-OBS-05 | A/Bの音量・遅延統制、ランダムX、試行中表示、回答保存・結果再読込 | 正答が試行中に漏れず、回答・条件が再現できる。統制不可経路では試験を拒否 |
| TEST-LASTFM-01 | FR-USE-05 | 認証・送信判定・失効・再送・二重イベント・同意撤回・履歴削除 | 30秒/50%/240秒の境界、本文ignored、Now Playing再送なし、UNCERTAIN保留を確認。不許可・取消対象は送信しない。外部冪等性を保証しない |
| TEST-TAP-01 | FR-OBS-07 | 既知信号とEQ有効信号を音源・DSP後で解析、DSP後Tapなしを模擬 | 位置と方式を併記し、対応する信号の期待値に一致。欠損位置を別の値で埋めない |
| TEST-PRESET-01 | FR-AUD-10 | 据置／ポータブルの差分表示・適用・非対応設定 | 差分どおりに適用し、安全音量・能力・許諾・検証状態を上書きしない。品質値確定後に実施 |
| TEST-DIAG-01 | FR-OBS-06, NFR-OBS-01 | 問題再現→診断出力→プレビュー、個人情報を含む模擬例外 | 再現環境とエラーを確認でき、機微情報を除去し明示操作でのみ出力 |
| TEST-META-01 | FR-SRC-06/07 | MusicBrainz/AcoustID照合・取消、タグ修正・原曲書込み失敗・画像差替・高解像度、重複と異版の解除 | 根拠と同意を表示し、誤同定を解除可能。音声データ・選択Variant・履歴を破壊しない |
| TEST-FORMAT-01 | FR-SRC-01/04/05 | 原典のFLAC/WAV/MP3/AAC/DSD、欠損タグ・破損、実機コーデック差異 | 対応表どおり再生・表示し、未対応を理由付きで示す。別版の属性を代用しない |
| TEST-FORMAT-02 | FR-AUD-04/05, NFR-AUD-01/04 | 384 kHz境界・705.6/768 kHz、32-bit超PCM、機器上限以下/超、DSD256境界、2ch/5.1chとステレオ復帰 | デコード可能な上限超PCMは対応形式へ実変換して再生。精度・アンチエイリアス・配置・クリップ防止を検査し、変換経路をBit-perfectと表示しない |
| TEST-GAPLESS-01 | FR-PLY-08 | 分割した連続信号、パディング付き形式、形式/DSP/レート変更、外部境界 | 成立経路で欠落・重複・無音挿入が承認値内。未成立境界をギャップレスと表示しない |
| TEST-DEVICE-01 | FR-DEV-01〜05 | 全出力種別、USB属性の取得・不明、設定拒否、プロファイルの作成・保存・読込、曖昧識別と優先順 | 情報の出典が正しく、組合せ・全音声設定を保存。危険な音量復元を行わない |
| TEST-SHARE-01 | FR-DEV-06 | 機器DBの登録・修正、公開前確認、共有・撤回・通報・ブロック、公称/申告/実測 | 所有者権限と公開範囲を守り、シリアル等を送らず、他人の実測で自機をVERIFIEDにしない |
| TEST-STATS-01 | FR-USE-02 | 日/週/月/年/累計、曲/アルバム/アーティスト/ジャンル/全体、形式・曜日・時刻・機器、複数アーティスト、月曜・日付境界・時計/速度変更・シーク、1,1,3の順位 | 保存区間に基づく時間と試行数を再現し、全体を二重加算しない。不明区分・削除が反映 |
| TEST-REC-01 | FR-USE-06 | 履歴・好み・時間帯・ジャンル・メタデータ充実度、条件式編集・再生成・固定保存、許諾拒否・削除 | 同一入力と版で結果を再現し、理由を表示。不許可・削除済みのデータを使わない |
| TEST-YTDLP-01 | FR-SRC-11 | 音声専用server解決/中継、所有者権限、Range/segment seek、URL失効、Stop/断線、資源上限/server停止 | 全体保存を待たず再生でき、位置/主体が正しい。秘密が漏れず、server停止でlocalを止めない。未対応seekを成功と表示しない |
| TEST-SAVE-01 | FR-SRC-12 | 保存希望なし/あり、保存先取消/権限失効/容量不足/SD切断、Stopと保存取消、プロセス停止・中断再開・確定後offline再生 | 希望なしで共有フォルダに保存せず、未完了を保存済み扱いしない。既存音源と確定済み音源を破壊せずcacheと区別。媒体違いを結合しない |
| TEST-RESEARCH-01 | FR-SRC-11, 原典§14 | 全サービスの直接再生手段・規約、yt-dlp音声専用・逐次読込・URL失効・シーク・認証・通信断、DAC/可視化制約 | 映像取得・動画UIで代替せず、技術結果・許諾・配布可否を分離。未実施・未達は完成判定保留 |
| TEST-THEME-01 | FR-UI-04 | テーマ作成・編集・保存・切替・削除・既定復帰、文字/背景のコントラスト、両配色 | 保存した指定を再現し、危険な設定から復帰可能。テーマで音声が途切れない |
| TEST-WIDGET-01 | FR-UI-04 | ウィジェット追加・削除・再配置、再生操作、プロセス再起動、外部音声・権限喪失 | Service/通知と曲・状態が一致し、操作可否を守り、秘密を公開しない |
| TEST-COMP-01 | FR-PLT-01, NFR-COMP-01/02 | 承認したOS/ABI・スマートフォン/DAP/DACと全方式の成立/非対応ケース | 原典の条件に基づく実証済み対応表があり、機能ごとに成立例を持つ |
| TEST-DATA-01 | NFR-PRIV-02 | 本人データ出力・移行・復元、破損入力・版差・履歴重複・削除世代 | 出力内容が説明と一致し、秘密を含まず、破壊的復元と削除履歴の復活を防ぐ |
| TEST-GOV-01 | NFR-MAINT/TEST/LEGAL/OPS | 要求→設計→試験→結果→コミット、CIのDebug/Release・Unit/Lint、ライセンス台帳、私用障害・復旧試験（窓口/常設体制はPUBLIC） | 実施記録と私用配備情報があり、全必須要求の未達を隠さず完成判定保留にできる |
| TEST-SYNC-01 | FR-USE-03, NFR-PRIV-02 | 同一event_id再送・異内容再利用、100件/1 MiB境界、途中不正・409・cursorページ中の更新・410・旧削除世代 | 異内容ID再利用を拒否し、部分適用/重複を起こさず、ページ途中失敗で正本を変えない。完全同期で未送信データと削除世代を保持。403/409を認証更新だけで繰返さない |

音声/性能の数値基準は[順次レビュー③](09_private_completion_review.md)のQLT-01〜14を使う。関連TESTごとに測定条件と結果を残す。参照だけで実測済みにはしない。

### DD-TEST-03 端末マトリクスの必須項目

`device_vendor, model, Android_version, build_fingerprint_hash, API_level, ABI, audio_output_type, DAC_vendor/product, USB_VID/PID(if available), codec, sampleRate, bitDepth, playback_mode, DSP_state, session_test_id, evidence_type, outcome, known_limitations`。

- スマートフォンとDAPそれぞれ実機を含める。特定の端末で成功しても他社DAP対応を保証しない。
- 既知音源をテストフィクスチャとして保管し、SHA-256／コーデック／ビット深度／サンプルレート／曲長／利用権限を記録。
- 高度なBit-perfect検査はアプリ内部表示と独立した測定方法によって実施。

### DD-TEST-04 指定実機と対象の広がり

Galaxy S25（Android 16）とFiiO M21（OS版未確認）を必須対応・主要検証機に指定し、ローカル/ネットワーク/全サービス直接再生、通知・画面OFF・同期・日本語/英語UIの必須ケースを両端末で確認する。DAC候補はiFi audio ZEN DAC V2、水月雨ECHO-B/ECHO-A。実機で使える想定であり、接続済み・測定済みとはしない。

Galaxy S20も追加検証端末として利用可能（2026-10-28利用者連絡）。OS版・モデル番号・firmware・音声出力能力は未確認とし、実機準備時に記録する。標準再生・通知/画面OFF・権限・USB接続・性能の互換性検査へ追加し、主要2機種の必須試験を代替しない。型名だけでAPI 23/24の境界試験や特定SoCでの動作を確認済みにしない。

各端末・DACの世代、OS/ファームウェア、USB接続方法、申告能力、観測経路を記録する。全DACが384 kHz/32-bit・DSD256・Native/DoP・5.1chを持つとは仮定せず、成立する組合せと能力不足時の変換を個別に試験する。5.1ch出力とBit-perfect実測には別の対応機器・測定環境が必要ならP-04へ記録する。

幅広い対応のため、指定端末以外のAndroid API境界・ABI・画面サイズ・標準経路も自動/エミュレーター試験へ含める。実機結果を未検証機種へ一般化せず、公開互換性表を追加検証で拡張する。実機操作は利用者が明示したADBシリアルだけを対象とする。

## DD-RELEASE：個人間利用版の完成・後続公開

### DD-RELEASE-01 Completion / PUBLIC Gate

1. **要件ベースライン**：原典全項目の対応表と全RB要求に`IMPLEMENTED + TESTED + DOCUMENTED`を必須とする。調査項目は技術結果・許諾判定・反映先を揃える。EXCLUDEDは原典の初期対象外OSと、2026-10-28利用者決定によるAmazon Music/SoundCloudに適用する。除外サービスを完成条件へ戻さない。未設計・未実装・未許諾・未検証・非対応理由の掲載を合格や機能除外に変換しない。範囲変更は利用者の明示指示を要する。
2. **ビルドと更新**：個人間版は署名APK/対応ABI/インストール・同一鍵での更新・データ維持を確認。依存ライセンス・SBOM・必要な再リンク/ソース等の配布物を残す。
3. **音声品質**：09のQLT-01〜14と指定実機の試験を実施。全音声方式を成立環境で検証し、実験的5.1chは対応/非対応を検査。実機未検証を資料レビューで合格にしない。
4. **統合サービス**：全サービス群の直接再生、可能な検索、用途別許可と認証・再生を確認。YouTube系はyt-dlp/音声のみ。実装・資格不足は該当機能を未完了とし、REMOTE/HANDOFFで代替合格にしない。
5. **個人間データ**：招待・本人権限・共有/撤回・退会/削除・秘密保護・同期競合・バックアップ復旧を試験。メール自動送信なしで本人のログイン/復旧を完結させる。
6. **品質**：両指定端末で長時間・フォーカス・切断・プロセス終了・日英UI・アクセシビリティとQLTを検証する。Play vitalsを私用版の証拠に要求しない。
7. **PUBLIC（完成後）**：一般向け自己登録/メール送信、公開名義・配布経路・AAB/ストア審査・年齢区分・公開文書・常設窓口/体制・段階的公開。現在の完成を止める条件へ混ぜない。

### DD-RELEASE-02 GitHub PRテンプレート推奨項目

```markdown
## 対応要件
FR-... / NFR-... / DD-... / TEST-...
## 変更範囲
UI / domain / playback / adapter / storage / audio / network / backend
## 成果
実装した動作・出力・ユーザー可視の差分
## 証跡
CI結果 / Unit / Instrumented / 実機（機種、OS、DAC、サンプル）
## 安全・権利・プライバシー
音量 / Bit-perfectの根拠 / SDK規約 / 権限 / ログ
## 未確認
端末差異・対応不能のAPI・既知の制限
```

## DD-DECISIONS：領域ごとの実装・導入条件

| ID | 必要な判断 | 現在の設計案 | 開発中の扱い（該当機能の完成判定） |
|---|---|---|---|
| IMP-01 | Media3版とService種別 | Media3 1.11.1 / MediaLibraryServiceを設計採用 | 導入時の推移依存・実ビルド確認を必須とする |
| IMP-02 | DB版とURI権限 | 初期schema v1、Room 2.8.5、SAF、DataStore 1.2.1を設計採用 | 物理migration/FKとAPI23を導入PRで確認 |
| IMP-03 | Audio DSP/native実装ライブラリ | 09の②で採用先・処理順を選定。導入/実測待ち | STANDARDで開発継続できるが、全方式を満たすまで完成判定保留 |
| IMP-04 | 現行3サービス群（Qobuz/Spotify/YouTube系。YouTubeとYouTube Musicは両方を検証）の許諾と再生方式 | 直接再生が確定。実装手段と用途別許諾はR-06で審査 | 他領域は開発継続。未達サービスがあれば完成判定保留 |
| IMP-05 | Sync backend運用とデータ管理 | 自前サーバー・認証/同期方針は確定。構成・運用詳細はR-04/P-02/03 | ローカル開発は継続。同期・共有等が未達なら完成判定保留 |
| IMP-06 | 目標値と検証機種 | 09の③のQLT-01〜14を採用し実機で検査 | 未測定を実測合格にしない |
| IMP-07 | 原典の全機能の公開成立 | D-01/03/04/09確定：全機能必須。方式等はQ/Rで具体化 | 1件基準・1方式基準・G/O除外では公開しない |

## DD-TRACE：全要件の設計・試験への追跡表

この表は**設計上のトレーサビリティ**であり、テストが実行済み・合格済みである意味ではない。試験IDの集合が要求を十分網羅するかは実装PRごとに具体的な個別ケースで補完する。「試験未定義」「追加定義」は未完了の追跡項目であり、既存の試験リンクを完了証拠として扱わない。個人間版の要求について個別ケースと結果が揃うまで完成判定を出さない。PUBLICケースは後段へ分ける。

| 要求ID | 詳細設計箇所 | 試験ファミリー |
|---|---|---|
| `FR-SRC-01` | DD-MODEL / DD-SRC / DD-UI | TEST-FORMAT-01 / TEST-PROV-01 / TEST-UI-01 |
| `FR-SRC-02` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-03` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-04` | DD-SRC / DD-AUD-04 | TEST-FORMAT-01 |
| `FR-SRC-05` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-06` | DD-SRC-04 | TEST-META-01 |
| `FR-SRC-07` | DD-SRC-03/04 / DD-DB-01 | TEST-META-01 / TEST-SCHEDULE-01 |
| `FR-SRC-08` | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| `FR-SRC-09` | DD-PROV-01/02/04/06/07 | TEST-PROV-01 / TEST-RESEARCH-01 |
| `FR-SRC-10` | DD-PROV-01/04（検索は技術・許諾上可能なら実装） | TEST-PROV-01 |
| `FR-SRC-11` | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 / TEST-YTDLP-01 |
| `FR-SRC-12` | DD-PROV-08 | TEST-SAVE-01 |
| `FR-PLY-01` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-02` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-03` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-04` | DD-DB-02 / DD-PLY-03 / DD-QUEUE | TEST-PLY-01 / TEST-RESTORE-01 |
| `FR-PLY-05` | DD-DB-01/02 | TEST-PLAYLIST-01 |
| `FR-PLY-06` | DD-QUEUE-03 / DD-DB-01 | TEST-SCHEDULE-01 |
| `FR-PLY-07` | DD-PROV / DD-QUEUE-04 | TEST-QUEUE-01 / TEST-PROV-01 |
| `FR-PLY-08` | DD-QUEUE-05 | TEST-GAPLESS-01 |
| `FR-PLY-09` | DD-QUEUE-03 / DD-UI | TEST-SCHEDULE-01 / TEST-QUEUE-01 |
| `FR-AUD-01` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-02` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-03` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-04` | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| `FR-AUD-05` | DD-AUD-04/05 | TEST-AUD-02 / TEST-FORMAT-02 |
| `FR-AUD-06` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-07` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-08` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-09` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-10` | DD-DSP-04 | TEST-PRESET-01 |
| `FR-DEV-01` | DD-DEV-01/02 | TEST-DEVICE-01 / TEST-AUD-03 |
| `FR-DEV-02` | DD-DEV-01/03 | TEST-DEVICE-01 |
| `FR-DEV-03` | DD-AUD-04 / DD-DEV-03 | TEST-DEVICE-01 / TEST-AUD-02 |
| `FR-DEV-04` | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| `FR-DEV-05` | DD-DEV-02/03 | TEST-DEVICE-01 / TEST-AUD-03 |
| `FR-DEV-06` | DD-DEV-03 / DD-CLOUD-03 | TEST-SHARE-01 |
| `FR-NET-01` | DD-NET | TEST-NET-01 |
| `FR-NET-02` | DD-NET | TEST-NET-01 |
| `FR-NET-03` | DD-NET | TEST-NET-01 |
| `FR-NET-04` | DD-NET | TEST-NET-01 |
| `FR-USE-01` | DD-HIST-01/02 | TEST-HIST-01 |
| `FR-USE-02` | DD-HIST-05 | TEST-STATS-01 |
| `FR-USE-03` | DD-CLOUD-01/02/04 | TEST-ACC-01 / TEST-DATA-01 / TEST-SYNC-01 |
| `FR-USE-04` | DD-CLOUD-03/04 | TEST-ACC-01 / TEST-STATS-01 |
| `FR-USE-05` | DD-HIST-04 / DD-PROV-01 | TEST-LASTFM-01（API資格・実サービス試験は未完了） |
| `FR-USE-06` | DD-HIST-05 | TEST-REC-01 |
| `FR-OBS-01` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-02` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-03` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-04` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-05` | DD-HIST-03 | TEST-ABX-01 |
| `FR-OBS-06` | DD-OBS-03 | TEST-DIAG-01 / TEST-SEC-01 |
| `FR-OBS-07` | DD-OBS-02 | TEST-TAP-01 |
| `FR-UI-01` | DD-UI | TEST-UI-01 |
| `FR-UI-02` | DD-UI | TEST-UI-01 |
| `FR-UI-03` | DD-UI | TEST-UI-01 |
| `FR-UI-04` | DD-UI-03 | TEST-THEME-01 / TEST-WIDGET-01 / TEST-UI-01 |
| `FR-UI-05` | DD-UI | TEST-UI-01 |
| `FR-UI-06` | DD-UI-02 | TEST-UI-01 |
| `FR-PLT-01` | DD-TEST-03/04 | TEST-COMP-01 / TEST-FORMAT-02 |
| `FR-PLT-02` | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| `NFR-REL-01` | DD-ERROR / DD-SRC / DD-QUEUE | TEST-LIB-01 / TEST-PLY-02 / TEST-NET-01 / TEST-QUEUE-01 |
| `NFR-REL-02` | DD-PLY / DD-ERROR / DD-TEST | TEST-REL-01 |
| `NFR-REL-03` | DD-PLY-03 / DD-DB-02 | TEST-RESTORE-01 |
| `NFR-PERF-01` | DD-OBS / DD-TEST | TEST-PERF-01 |
| `NFR-PERF-02` | DD-OBS / DD-TEST | TEST-PERF-01 |
| `NFR-PERF-03` | DD-OBS / DD-TEST | TEST-PERF-01 |
| `NFR-AUD-01` | DD-AUD-05 / DD-OBS | TEST-AUD-01 / TEST-FORMAT-02 |
| `NFR-AUD-02` | DD-AUD / DD-DSP / DD-DEV | TEST-AUD-02/03 |
| `NFR-AUD-03` | DD-AUD / DD-DSP / DD-DEV | TEST-AUD-02/03 |
| `NFR-AUD-04` | DD-DSP / DD-AUD-05 | TEST-AUD-02 / TEST-FORMAT-02 |
| `NFR-COMP-01` | DD-TEST-03 | TEST-COMP-01 |
| `NFR-COMP-02` | DD-PLY-03 / DD-TEST-03 | TEST-COMP-01 / TEST-STORE-01 / TEST-SEC-01 |
| `NFR-SEC-01` | DD-PROV / DD-CLOUD / DD-RELEASE | TEST-SEC-01 |
| `NFR-SEC-02` | DD-PROV / DD-CLOUD / DD-RELEASE | TEST-SEC-01 |
| `NFR-SEC-03` | DD-PROV / DD-CLOUD / DD-RELEASE | TEST-SEC-01 |
| `NFR-PRIV-01` | DD-CLOUD / DD-OBS / DD-RELEASE | TEST-ACC-01 / TEST-SEC-01 |
| `NFR-PRIV-02` | DD-CLOUD-02/03 / DD-HIST | TEST-ACC-01 / TEST-HIST-01 / TEST-DATA-01 / TEST-SYNC-01 |
| `NFR-PRIV-03` | DD-CLOUD / DD-OBS / DD-RELEASE | TEST-ACC-01 / TEST-SEC-01 |
| `NFR-LEGAL-01` | DD-PROV / DD-RELEASE | TEST-STORE-01 |
| `NFR-LEGAL-02` | DD-PROV / DD-RELEASE | TEST-STORE-01 |
| `NFR-A11Y-01` | DD-UI-02 | TEST-UI-01 |
| `NFR-UX-01` | DD-UI-03 | TEST-UI-01 / TEST-WIDGET-01 |
| `NFR-STORE-01` | DD-RELEASE | TEST-STORE-01 |
| `NFR-OBS-01` | DD-OBS-03 | TEST-DIAG-01 |
| `NFR-MAINT-01` | DD-PKG / DD-TEST | TEST-GOV-01 |
| `NFR-TEST-01` | DD-TEST / DD-TRACE | TEST-GOV-01 |
| `NFR-TEST-02` | DD-TEST / DD-RELEASE | TEST-GOV-01 / TEST-COMP-01 |
| `NFR-OPS-01` | DD-CLOUD-03 / DD-RELEASE | TEST-GOV-01 |

## 参照

- [要件定義書](01_requirements.md)、[基本設計書](02_basic_design.md)。
- Android Developers: [MediaSessionService](https://developer.android.com/media/media3/session/background-playback)、[ExoPlayer](https://developer.android.com/media/media3/exoplayer/supported-formats)、[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)、[USB Host](https://developer.android.com/develop/connectivity/usb/host)。
- AOSP: [Preferred Mixer / Bit-perfect](https://source.android.com/docs/core/audio/preferred-mixer-attr)。
- サービス規約: [Spotify Android SDK](https://developer.spotify.com/documentation/android)、[YouTube Developer Policies](https://developers.google.com/youtube/terms/developer-policies)、[SoundCloud API Guide](https://developers.soundcloud.com/docs/api/guide)。
