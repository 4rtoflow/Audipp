# Audipp 完成・一般公開版 詳細設計書（LLD / Implementation Specification）

| 項目 | 内容 |
|---|---|
| 文書ID | AUD-LLD-001 |
| 文書版・作成日 | 1.0.0-draft / 2026-10-26 |
| 対応文書 | [要件定義書](01_requirements.md)・[基本設計書](02_basic_design.md) |
| 実装状態 | **設計案であり、コードへの実装・コンパイル・端末試験は未実施** |
| 適用範囲 | 完成版のローカル／高音質／統合ソース／ネットワーク／統計／公開機能 |

## 0. 実装の優先ルール

1. 本書のクラス名・型・関数名は**設計契約の提案**である。既存の`MainActivity.kt`などを変更した実績を意味しない。
2. ユーザーが観測できる要件は `01_requirements.md` を優先。本書の詳細が権利、Android API、端末能力と矛盾する場合は設計変更ADRを作成する。
3. すべての端末でNative DSD、Bit-perfect、PCM取り出しができるという仮定を禁止する。
4. 未決定のAPI鍵・認証、サーバーDB、暗号化ライブラリ、DSP処理ライブラリ、精度閾値は**実装が必要になる前に採用ADR**を確定する。
5. `FR-` と `NFR-` への対応は「設計対象」であり、実装済みの状態を表さない。

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
│   ├── PlaybackService.kt      # Media3 Session所有者
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

以下のKotlinは*概念を示す設計擬似コード*。Android/Media3の正確なSDK型や依存関係は実装時の採用版で確定する。

```kotlin
@JvmInline value class TrackId(val value: String)       // UUID（内部ID）
@JvmInline value class SourceId(val value: String)      // "local:..." / "provider:..."
@JvmInline value class QueueEntryId(val value: String)  // 重複追加ごとに固有

enum class ExecutionMode { LOCAL_DIRECT, PARTNER_DIRECT, PARTNER_REMOTE,
                           EXTERNAL_HANDOFF, NETWORK_REMOTE }
enum class Availability { PLAYABLE, AUTH_REQUIRED, EXPIRED, MISSING,
                          UNSUPPORTED, POLICY_BLOCKED, UNKNOWN }

data class SourceRef(
    val sourceId: SourceId,
    val mode: ExecutionMode,
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
```

**制約**：`providerKey + providerItemId`はソース側での一意性を表すが、同一楽曲・同一マスターの証拠ではない。`SourceRef`をURLの生文字列だけで識別しない。`canAccessPcm=false`の場合、Audipp内部DSP/Visualizerを提供しない。

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

### DD-DB-01 Roomテーブル（提案）

| テーブル | PK | 主なカラム | 主要制約・索引 |
|---|---|---|---|
| `sources` | `source_id TEXT` | `kind,provider_key,provider_item_id,local_uri,availability,grant_mode,updated_at` | `(provider_key,provider_item_id)`は部分ユニーク（NULL除く）、`local_uri`非公開 |
| `tracks` | `track_id TEXT` | `title,album_id,artist_display,duration_ms,disc_no,track_no,year,genre,artwork_ref` | `title`, `album_id`, `artist_display`索引。NULLや別表を必要に応じて使用 |
| `track_variants` | `variant_id TEXT` | `track_id,source_id,codec,sample_rate,bit_depth,channels,mastering_label` | `(track_id,source_id)`索引。同一曲かは検証可能な時のみ紐付け |
| `albums` | `album_id TEXT` | `title,album_artist,year,artwork_ref` | アルバム名だけを一意キーにしない |
| `artists` | `artist_id TEXT` | `name,external_id` | 重名・同名アーティストを許容 |
| `track_artists` | 複合 | `track_id,artist_id,role,ordinal` | 複合一意、FK、順序付け |
| `queues` | `queue_id TEXT` | `name,created_at,updated_at,shuffle_seed,repeat_mode` | 明示的な現在キューIDを設定で保持 |
| `queue_items` | `entry_id TEXT` | `queue_id,ordinal,track_id,source_id,added_at` | `(queue_id,ordinal)`ユニーク、positionの整合性をトランザクションで保証 |
| `playlists` | `playlist_id TEXT` | `name,created_at,updated_at` | ユーザー所有で他の端末との競合に備える |
| `playlist_items` | `entry_id TEXT` | `playlist_id,ordinal,track_id,source_id` | 削除されたソースの表示も管理可能 |
| `play_events` | `event_id TEXT` | `session_id,entry_id,track_id,source_id,started_at,ended_at,played_ms,qualified` | `session_id, event_id`で重複防止。再生閾値は設定可能 |
| `device_components` | `component_id TEXT` | `kind,vendor,product,vid,pid,usb_serial_hash,notes` | 識別できない/非公開値はNULL |
| `device_profiles` | `profile_id TEXT` | `display_name,output_mode,default_gain_db,safe_volume,created_at` | 設定と実機能力を別管理 |
| `profile_components` | 複合 | `profile_id,component_id,role` | DAC・アンプ・イヤホン組合せ |
| `audio_presets` | `preset_id TEXT` | `profile_id,mode,dsp_config_json,resampler_config_json,revision` | JSON妥当性・内部schemaVersion、変更前確認 |
| `provider_accounts` | `account_id TEXT` | `provider_key,user_alias,auth_state,scope_set,expiry` | **アクセストークン本体はここに保存しない** |
| `track_mappings` | `mapping_id TEXT` | `track_id,provider_key,provider_item_id,match_confidence,source_of_match` | マッピングの取消し可能 |
| `sync_outbox` | `event_id TEXT` | `entity_type,entity_id,operation,revision,created_at,sync_state` | 同期未採用なら未作成／未使用 |
| `abx_sessions` | `session_id TEXT` | `stimulus_a,stimulus_b,normalization,test_settings,created_at` | 利用同意・保存期間と測定条件を保持 |
| `abx_trials` | `trial_id TEXT` | `session_id,trial_index,selection,answer,elapsed_ms` | 正答は試行終了までUIに出さない |

**Roomの正式な型・FK・マイグレーションSQLはDB実装PRで確定**。上記はテーブル設計の論理的契約であり、そのまま実行するDDLではない。

### DD-DB-02 トランザクションと競合

- Queue順序変更は`BEGIN TRANSACTION`内で全件の`ordinal`を再採番する。途中失敗時はロールバック。再生中の現在アイテムを`entry_id`で追跡する。
- PlayEventの適格判定は「一定以上の実再生時間または楽曲長の所定割合」等とし、**閾値はD-06で決定**。同じイベントを再試行しても重複カウントしない。
- ソースを失った曲はユーザーのプレイリストから無断削除しない。`MISSING`状態で再登録を促す。
- スマートプレイリストは原データの検索式から再生成可能な派生データとして扱う。
- `Room`マイグレーションは本番DBの破壊的fallbackを無効とし、旧バージョンテストを必須にする。

### DD-DB-03 DataStore／機密情報

- DataStore：テーマ、現在キューID、リピート、音声初期モード、Visualizer設定、プライバシー同意バージョンなど小規模キー。
- 秘密情報：Android Keystore等により暗号化の鍵を保護した保存方式を**採用ADRで決定**。Keystoreは暗号化保存領域そのものではないことに留意。生のrefresh tokenをバックアップ・ログ・共有しない。
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

### DD-SRC-02 source選択の優先度

1. ユーザーが明示した`variant`と`sourceId`を尊重。
2. 指定ソースが`PLAYABLE`でなければ、ソース切替を勝手に行わず、本人設定に従って代替候補を提示。
3. 複数版がありユーザーが`優先形式`を設定した場合のみ`FLAC/WAV/MP3`等を順位付け。レート・ビット深度だけで音質の優劣を断定しない。
4. `PARTNER_DIRECT`はAPI利用許可を再評価。オフラインキャッシュは権利上許可された場合だけ使用。
5. 同一楽曲とみなしてもストリーミング側の再生権限・地域制限を引き継がない。

### DD-SRC-03 アルバムと重複

`album_artist + album_title + release_id + disc_no`等を候補にし、MusicBrainz等の外部IDは照合証拠として保存。曖昧な一致を自動結合しない。マスタリング違いは別Variantで維持し、利用者が関連付けを取り消せる。

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
    data class PlayTrack(val trackId: TrackId, val preferredSourceId: SourceId?) : PlaybackCommand
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
    val error: PlaybackError?
)
```

入力検証：シーク位置は`0..duration`の範囲（長さ未知時は機能無効）。連続タップでは古いコマンドの遅延応答により新状態を上書きしない。コマンドとPlayerイベントは単一順序でReducerへ入れる。

### DD-PLY-03 Session/ForegroundServiceのライフサイクル

- `PlaybackService`でPlayer・MediaSessionを生成し、Service終了時にSessionとPlayerを開放。UIは`MediaController`から接続。
- 再生をバックグラウンドで継続する場合に`mediaPlayback` Foreground ServiceのManifestと必要権限を宣言する。具体的なAndroid API上の制約はtargetSdk更新時に見直す。
- ロック画面・Bluetooth MediaButton・外部コントローラーはSession権限の範囲内で処理。
- OSがプロセスを停止した場合、`queue_id,entry_id,last_known_position_ms`を保存する。**ユーザーの明示操作なしに自動で大音量再生を開始しない**。
- 音声フォーカス・`becoming noisy`などの処理を再生ポリシーに集中させ、UIが勝手に音量を変更しない。

## DD-QUEUE：統合キューの実行アルゴリズム

### DD-QUEUE-01 キューの不変条件

- 1キュー内の`entry_id`はユニーク。**同じTrackの複数追加は許容**。
- `ordinal`は`0..n-1`の重複のない連続整数。更新は原子的に行う。
- 再生位置は「現在のエントリーID」で持ち、順序変更でも曲が突然変わらない。
- `durationMs=null`が一つでも含まれる区間は、合計終了予定を厳密値と表示しない。
- External handoffでは、ユーザーが公式アプリから戻るまでは外部アプリの再生状態を推測しない。

### DD-QUEUE-02 `advance()`擬似コード

```text
onEnded(currentEntry):
  updateHistoryOnce(currentEntry.session)
  candidate = queue.nextOf(currentEntry, repeatMode, shuffleSeed)
  while candidate exists:
    result = resolver.resolve(candidate.sourceRef)
    if result == PLAYABLE_DIRECT:
      player.prepareAndPlay(candidate); return STARTED
    if result == REMOTE:
      ack = provider.requestPlay(candidate, idempotencyKey)
      if ack.confirmedActive: return DELEGATED
      return WAIT_REMOTE_CONFIRMATION
    if result == HANDOFF:
      return REQUIRES_USER_ACTION
    markUnavailable(candidate, result.reason)
    if skipUnplayablePolicy == ASK: return REQUIRES_USER_ACTION
    candidate = queue.nextOf(candidate, ...)
  return QUEUE_ENDED
```

- `timeout`と`correlationId`を持ち、REMOTEコマンド成功を音声出力の成功と同一視しない。
- プロバイダーごとの同時接続制約・レート制限を順守し、エラーを静かに無限リトライしない。
- 1プレイリストへのソース混在は可能でも、ソース切替を自動化できない場合はUIに「次の曲は手動切替」と表示。

### DD-QUEUE-03 予定時刻計算

```text
remaining[current] = max(0, duration - observedPosition)   # duration確定時のみ
remaining[next..] = Σ knownDuration[i]
if unknownDuration or remoteDurationUnreliable or handoffPending:
    result = ESTIMATE_PARTIAL or UNAVAILABLE
else:
    expectedStartEpochMs[i] = now + remaining[current] + Σ previousDuration
    expectedEndEpochMs = now + remainingAll
```

再生速度、Crossfade、DSPによるレイテンシ、バッファ不足、広告、外部サービス側のキュー差を推定の不確かさとして扱う。再計算イベント：seek、pause/play、remove/reorder、duration更新、再生速度変更、remote ack、経路切替。

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

## DD-DSP：処理グラフとレベル管理

### DD-DSP-01 グラフモデル

`decoder PCM -> channel mapping -> ReplayGain/R128 -> preamp/headroom -> EQ -> Convolution/FIR -> Crossfeed -> resampler/filter -> limiter / clip guard -> PCM output`

- **実際の処理順**はADRと特性測定で確定し、選択されたDSPの種類と出力モードで依存・可換性を判断する。
- トラック切替時に音量が飛ばないよう、gain変更とフィルター再初期化に平滑化／短いフェードを検討。
- DSP処理は共通サンプル形式（たとえばFloat PCM）を内部で持つ設計案。変換精度・dither・チャンネル処理はライブラリ選定時に定義。
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

タグにTrack/Album値があればモードで切替え、欠損なら無適用か明示した解析ルールを用いる。R128解析のLUFS・True Peakの測定方式、アルバム補正、ターゲットLUFS、クリッピング防止をADRで確定。再生中解析は負荷に応じてバックグラウンド処理し、利用者の明示設定を優先。

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

## DD-PROV：ストリーミングProvider統合

### DD-PROV-01 Adapter契約

```kotlin
interface MusicProviderAdapter {
    val key: String
    suspend fun capabilities(accountId: String?): ProviderCapabilities
    suspend fun authorize(): AuthorizationResult      // SDKが提供する正規の認可方法
    suspend fun search(query: String, page: Page): ProviderSearchResult
    suspend fun resolve(itemId: String): ProviderItem
    suspend fun requestPlayback(itemId: String, mode: ExecutionMode): ProviderPlayResult
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
    val canAccessPcm: Boolean
)
```

契約で`directPlaybackLicensed`は**アプリとサービスの契約事実**を表し、APIにURLがあるだけで`true`にしない。

### DD-PROV-02 プロバイダー別の状態・例外

`DISCONNECTED -> AUTHORIZING -> CONNECTED -> EXPIRED / RATE_LIMITED / UNAVAILABLE / POLICY_BLOCKED`

- OAuth等で必要な場合、ブラウザー／公式SDKで認可し、PKCE等の採用フローを許可リストで検証する。redirect URIを厳密照合。client secretをAndroid APK内の機密として扱わない。
- ストリームURLの有効期限・地域制限・サブスクリプション状態を正規SDKの回答から判断。期限切れ前に勝手に第三者のURLを更新しない。
- `429`等は提供元の`Retry-After`や規約に従う。一定回数後はユーザーに表示。
- Providerごとに`authState`, `playbackOwner`, `featuresAllowed`, `licenseEvidence`, `lastPolicyReview`を管理。
- Spotify App Remote型では元のSpotifyアプリが再生主体。`requestPlayback`の成功応答のみでAudipp側のPCM使用許可を付与しない。
- YouTube系は公式ポリシーに違反する音声抽出／バックグラウンド専用プレーヤーを実装しない。正規UIへの遷移等に限定し得る。

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

## DD-NET：LAN音源・遠隔レンダラー

### DD-NET-01 Protocol Adapter

- `NetworkLibraryAdapter`：`listDirectory`, `listMedia`, `resolveStream`。SMB/WebDAV/NAS。資格情報はKeyStore保護方針で保存し、資格情報をURIクエリに載せない。
- `RendererAdapter`：`discover`, `connect`, `getCapabilities`, `sendCommand`, `observeState`, `disconnect`。MPD/UPnP/OpenHome。
- UPnP discovery／LAN利用制約はAndroid OSのネットワーク・マルチキャスト制限とネットワーク権限・LANポリシーに従う。
- 外部レンダラーは独立したキュー・音量を持ち得る。Audippの内部キューと自動的に一致すると仮定しない。
- `remoteVolume`は機器に対する要求値。物理音圧・アンプ利得の安全保証ではない。

### DD-NET-02 失敗条件

ネットワーク断→`OFFLINE`、再探索、ユーザー操作で復帰。サービス失効→`AUTH_REQUIRED`。MPDが対応しない操作→機能無効。再試行はタイムアウト＋有界指数バックオフ。意図せず別のレンダラーで再生が始まらないよう固定IDを確認。

## DD-HIST：履歴・ランキング・推薦・ABX

### DD-HIST-01 再生計測

- 再生時間はWall clock経過ではなく、実際に進んだ音声再生ポジションと状態を基礎に推定する。シークでスキップした長さは聴取時間に加算しない。
- PlayEvent IDを生成し、セッション・エントリーIDとともに保存。DBの重複排除で二重集計を防ぐ。
- Providerがイベントを返さない場合は「外部状態未取得」とし、根拠不十分な再生時間を確定値に加算しない。
- `sourceType/codec/routeId/profileId`の取得済み時点スナップショットを保存。未取得値はNULL。

### DD-HIST-02 統計計算

- 日・週・月・年の集計はタイムゾーンと日付境界を明記。ユーザーが変更した端末時計を調査可能にする。
- 曲、アーティスト、アルバム、ジャンル、再生形式、曜日、時間帯、デバイス別の集計ビューを作成。
- 使用可能な履歴を削除した場合、統計・スマートプレイリストを再計算。
- 推薦はまずメタデータとローカル履歴に基づく説明可能なスコアの実装を検討し、クラウドMLとユーザーデータ送信を必須としない。

### DD-HIST-03 ABX試験

`prepare stimulus A/B -> loudness match(測定済み条件) -> assign random X -> trial -> record answer -> finalize -> summarize`。ランダム割付を試行中に表示しない。音源間の音量・遅延・DSP状態を統制し、比較条件が統制できない場合は試験不可として明示する。単独試験結果を「聴覚能力の証明」と断言しない。

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
  -> frame throttler (visible 15/30/60fps as configured)
  -> Compose Canvas / graphics rendering
```

- 音声再生スレッドで重いFFTや描画処理を行わない。固定長のバッファから非同期に読み、上限超過時は古い可視化データを捨てる。
- 可視化の負荷増大で再生バッファを枯渇させない。バックグラウンドでは描画停止、画面表示時のみ再開。
- 相関やベクトルスコープはステレオの時間整列したPCMが必要。1ch音源や外部音声非公開時は非対応として表示。
- FFT窓長、窓関数、更新レート、dBFS基準、True Peakのオーバーサンプリング方式は計測モジュールADRで固定する。

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

## DD-CLOUD：アカウント・同期・共有（導入時の契約）

### DD-CLOUD-01 API境界（エンドポイント案、**未実装**）

| Method/Path（仮） | 目的 | 認証・制御 |
|---|---|---|
| `POST /v1/auth/session` | 正規IDプロバイダーを通したセッション確立 | Token検証、レート制限、端末特定 |
| `GET /v1/sync?cursor=...` | 利用者の差分同期取得 | 所有者スコープ、ページング、cursor失効 |
| `POST /v1/sync/batches` | 差分イベントの冪等送信 | `eventId`重複除去・replay耐性 |
| `DELETE /v1/me` | アカウント削除要求 | 再認証・削除結果の確認 |
| `GET /v1/friends` | 許可済み関係取得 | 既定非公開、ブロック・公開範囲検証 |
| `POST /v1/friends/invitations` | 許可制フレンド招待 | スパム・レート制限・通報対応 |
| `GET /v1/device-profiles/shared` | 機器情報共有（任意） | 公称／申告／実測の根拠付き表示 |

これはバックエンド採用後の設計議論用契約。**URL、ホスト、認証プロバイダー、保存先、実際の通信プロトコルは未選定**。

### DD-CLOUD-02 同期衝突・削除

- 設定はフィールド単位のrevision/version、履歴はイベントベースの冪等マージを原則とする。プレイリスト・キューの並べ替えは競合解決規則を別ADRで決める。
- オフライン更新は`sync_outbox`から再送し、応答確認後に確定。送信失敗でローカルデータを消さない。
- アカウント削除ではサーバー側の削除・保持義務の例外・完了通知などを事前に規定。端末上のローカルファイル自体は勝手に削除しない。
- フレンド情報・機器プロファイル公開は**初期非公開**。ランキング共有は自動ONにしない。

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
| UI-12 Account | 同期、フレンド、公開範囲 | 登録、停止、退会 | 未導入時は偽のログインを置かない |

### DD-UI-02 UIの表示契約

- データ読み込み：`Loading`→`Content`または`Empty/Error`。ロード中に未取得値を以前の別曲の値で表示しない。
- 操作有効条件は`PlaybackCapabilities`と`Availability`に従う。UIにグレー表示を置くだけでなくバックエンド側でも拒否。
- TalkBack読み上げ：「一時停止、現在再生中」「USB DAC、経路はOS申告、Bit-perfect未検証」など根拠を含める。
- 設定変更は適用前・実際に適用した・適用失敗を別ステータスで返す。リスナーの安全に関わる項目は確認画面を出す。
- テキスト、色、余白などは既存`docs/design.md`を起点とするが、製品UIとしてダークテーマとDynamic Typography、端末幅差異を再評価する。

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
| Release | AAB、target API、Playポリシー、第三者ライセンス、サポート | リリースチェックリスト |

### DD-TEST-02 最小テストケース一覧（初回公開に必須となる分類）

| TEST-ID | 仕様ID | 実施内容 | 合格条件 |
|---|---|---|---|
| TEST-LIB-01 | FR-SRC-02 | URI許可、取消、SD切断、再スキャン | 全状態で破綻せず復旧 |
| TEST-LIB-02 | FR-SRC-03〜05 | 欠損タグ、巨大アート、同名アルバム、複数ディスク | 関連情報の正しさとメモリ制限 |
| TEST-PLY-01 | FR-PLY-01〜04 | キュー編集、重複曲、シャッフル、自然終了、通知再生 | 原子的更新・重複再生なし |
| TEST-PLY-02 | FR-PLY-02〜03 | ロック、通話、音声フォーカス、BT/USB切断 | 安全な状態遷移 |
| TEST-AUD-01 | FR-AUD-01, FR-OBS-01 | ソース44.1k、OS48k等の差異を模擬 | 差異・証拠区分が正しく表示 |
| TEST-AUD-02 | FR-AUD-03〜07 | DAC別Direct/DSD/DSP検査 | 対応していない経路は無効、検証ログあり |
| TEST-AUD-03 | FR-AUD-08, FR-DEV-05 | 高感度イヤホン、音量急増シナリオ | 想定外の急増を生じない |
| TEST-PROV-01 | FR-SRC-08〜10 | Provider別認証・失効・外部制御・停止 | 権利範囲・エラー回復・表示整合 |
| TEST-QUEUE-01 | FR-PLY-07〜09 | DIRECT→REMOTE→HANDOFF遷移 | 無断再生せず承認・状態を反映 |
| TEST-NET-01 | FR-NET-01〜03 | LAN切断・再接続・MPD再生状態不整合 | 状態が一致しなければ明示 |
| TEST-VIS-01 | FR-OBS-02〜04 | FFT入力比較、ステレオ相関、CPU高負荷 | 正しい計測結果、再生影響を制御 |
| TEST-HIST-01 | FR-USE-01〜05 | 途中停止、シーク、二重イベント、削除 | 重複なし、削除が反映 |
| TEST-ACC-01 | FR-USE-03〜04 | 同意→同期→失効→退会／公開範囲変更 | 第三者アクセス不可、削除確認 |
| TEST-REL-01 | NFR-REL-02 | 8時間再生と画面OFF、試験機別測定 | 承認した条件で重大不具合なし |
| TEST-STORE-01 | NFR-STORE-01 | Play Console/AAB/ライセンス/プライバシー | 期限時点の審査条件クリア |
| TEST-UI-01 | FR-UI-01〜05, NFR-A11Y-01 | TalkBack、画面回転、ダークモード、大文字、未対応表示 | アクセシブルかつ誤誘導なし |
| TEST-PERF-01 | NFR-PERF-01〜03 | 端末種別の起動／再生／シーク応答P50/P95、CPU/電池 | 公開前に承認した閾値内 |
| TEST-SEC-01 | NFR-SEC-01〜03, NFR-PRIV-01〜03 | Manifest権限、OAuth/token、ログ、外部送信同意 | 不要なアクセス・秘密漏洩なし |

### DD-TEST-03 端末マトリクスの必須項目

`device_vendor, model, Android_version, build_fingerprint_hash, API_level, ABI, audio_output_type, DAC_vendor/product, USB_VID/PID(if available), codec, sampleRate, bitDepth, playback_mode, DSP_state, session_test_id, evidence_type, outcome, known_limitations`。

- スマートフォンとDAPそれぞれ実機を含める。特定の端末で成功しても他社DAP対応を保証しない。
- 既知音源をテストフィクスチャとして保管し、SHA-256／コーデック／ビット深度／サンプルレート／曲長／利用権限を記録。
- 高度なBit-perfect検査はアプリ内部表示と独立した測定方法によって実施。

## DD-RELEASE：一般公開・運用手順

### DD-RELEASE-01 Release Gate

1. **要件ベースライン**：要求IDごとに`IMPLEMENTED + TESTED + DOCUMENTED`、条件付き項目は`NOT_AVAILABLE + REASON + PRODUCT_REVIEW_APPROVED`を記録。
2. **ビルド**：署名済みAAB／対応ABI／TargetAPIをストア要件と照合。依存・第三者ライセンス／SBOMを残す。
3. **音声品質**：実機ルート別8時間テスト、切断・フォーカス・電力・バッファ・安全音量試験の証跡。RBでロスレス音源再生と少なくとも1種類の高度制御（ReplayGain、PEQ、検証済みDirect経路から選定）を検証。
4. **統合サービス**：公開するサービスごとに正規API使用権、SDK規約、認証、リモート制御範囲、商用／広告の適否を承認。未許諾は機能を公開しない。
5. **プライバシー**：収集データ・第三者提供先・同意撤回・アカウント削除・保持期間・サポート対応を文書化。
6. **品質**：Android vitalsのCrash/ANR指標を監視し、公開目標値を満たす。初期段階は段階的ロールアウト。
7. **運用**：問い合わせ／障害報告／脆弱性連絡先、外部連携停止時の機能制限方法、アップデート／ロールバックの計画。

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

## DD-DECISIONS：実装開始前に凍結する判断

| ID | 必要な判断 | 現在の設計案 | 決定がない場合の扱い |
|---|---|---|---|
| IMP-01 | Media3版とService種別 | Media3 Session or LibraryService | 安全なLocal基本再生のみ進める |
| IMP-02 | DB schema versionとURI権限の扱い | Room＋SAF＋DataStore | 再生のみ先行、インデックス永続化保留 |
| IMP-03 | Audio DSP/native実装ライブラリ | 計測・ライセンス選定後 | STANDARD経路のみ |
| IMP-04 | 公開許諾を得られるProvider | 公式SDK/API別に審査 | 外部サービスは無効化して説明 |
| IMP-05 | Sync backend運用とデータ管理 | 未選定 | ローカル履歴のみ |
| IMP-06 | 目標値と検証機種 | 要件定義書の仮基準をレビュー | 公開承認を出さない |
| IMP-07 | 統合サービスの最小公開件数 | 少なくとも1件、複数訴求なら2件以上 | 宣伝上の表現を制限 |

## DD-TRACE：全要件の設計・試験への追跡表

この表は**設計上のトレーサビリティ**であり、テストが実行済み・合格済みである意味ではない。試験IDの集合が要求を十分網羅するかは実装PRごとに具体的な個別ケースで補完する。

| 要求ID | 詳細設計箇所 | 試験ファミリー |
|---|---|---|
| `FR-SRC-01` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-02` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-03` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-04` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-05` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-06` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-07` | DD-SRC / DD-DB | TEST-LIB-01/02 |
| `FR-SRC-08` | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| `FR-SRC-09` | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| `FR-SRC-10` | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| `FR-PLY-01` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-02` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-03` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-04` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-05` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-06` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-07` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-08` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-PLY-09` | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| `FR-AUD-01` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-02` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-03` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-04` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-05` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-06` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-07` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-08` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-AUD-09` | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| `FR-DEV-01` | DD-DEV / DD-AUD | TEST-AUD-03 |
| `FR-DEV-02` | DD-DEV / DD-AUD | TEST-AUD-03 |
| `FR-DEV-03` | DD-DEV / DD-AUD | TEST-AUD-03 |
| `FR-DEV-04` | DD-DEV / DD-AUD | TEST-AUD-03 |
| `FR-DEV-05` | DD-DEV / DD-AUD | TEST-AUD-03 |
| `FR-DEV-06` | DD-DEV / DD-AUD | TEST-AUD-03 |
| `FR-NET-01` | DD-NET | TEST-NET-01 |
| `FR-NET-02` | DD-NET | TEST-NET-01 |
| `FR-NET-03` | DD-NET | TEST-NET-01 |
| `FR-NET-04` | DD-NET | TEST-NET-01 |
| `FR-USE-01` | DD-HIST / DD-CLOUD | TEST-HIST-01 / TEST-ACC-01 |
| `FR-USE-02` | DD-HIST / DD-CLOUD | TEST-HIST-01 / TEST-ACC-01 |
| `FR-USE-03` | DD-HIST / DD-CLOUD | TEST-HIST-01 / TEST-ACC-01 |
| `FR-USE-04` | DD-HIST / DD-CLOUD | TEST-HIST-01 / TEST-ACC-01 |
| `FR-USE-05` | DD-HIST / DD-CLOUD | TEST-HIST-01 / TEST-ACC-01 |
| `FR-USE-06` | DD-HIST / DD-CLOUD | TEST-HIST-01 / TEST-ACC-01 |
| `FR-OBS-01` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-02` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-03` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-04` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-05` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-OBS-06` | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| `FR-UI-01` | DD-UI | TEST-UI-01 |
| `FR-UI-02` | DD-UI | TEST-UI-01 |
| `FR-UI-03` | DD-UI | TEST-UI-01 |
| `FR-UI-04` | DD-UI | TEST-UI-01 |
| `FR-UI-05` | DD-UI | TEST-UI-01 |
| `FR-PLT-01` | DD-TEST / DD-RELEASE | TEST-STORE-01 |
| `FR-PLT-02` | DD-TEST / DD-RELEASE | TEST-STORE-01 |
| `NFR-REL-01` | DD-PLY / DD-ERROR / DD-TEST | TEST-REL-01 |
| `NFR-REL-02` | DD-PLY / DD-ERROR / DD-TEST | TEST-REL-01 |
| `NFR-REL-03` | DD-PLY / DD-ERROR / DD-TEST | TEST-REL-01 |
| `NFR-PERF-01` | DD-OBS / DD-TEST | TEST-PERF-01 |
| `NFR-PERF-02` | DD-OBS / DD-TEST | TEST-PERF-01 |
| `NFR-PERF-03` | DD-OBS / DD-TEST | TEST-PERF-01 |
| `NFR-AUD-01` | DD-AUD / DD-DSP / DD-DEV | TEST-AUD-02/03 |
| `NFR-AUD-02` | DD-AUD / DD-DSP / DD-DEV | TEST-AUD-02/03 |
| `NFR-AUD-03` | DD-AUD / DD-DSP / DD-DEV | TEST-AUD-02/03 |
| `NFR-AUD-04` | DD-AUD / DD-DSP / DD-DEV | TEST-AUD-02/03 |
| `NFR-COMP-01` | DD-AUD / DD-TEST | TEST-STORE-01 |
| `NFR-COMP-02` | DD-AUD / DD-TEST | TEST-STORE-01 |
| `NFR-SEC-01` | DD-PROV / DD-CLOUD / DD-RELEASE | TEST-SEC-01 |
| `NFR-SEC-02` | DD-PROV / DD-CLOUD / DD-RELEASE | TEST-SEC-01 |
| `NFR-SEC-03` | DD-PROV / DD-CLOUD / DD-RELEASE | TEST-SEC-01 |
| `NFR-PRIV-01` | DD-CLOUD / DD-OBS / DD-RELEASE | TEST-ACC-01 / TEST-SEC-01 |
| `NFR-PRIV-02` | DD-CLOUD / DD-OBS / DD-RELEASE | TEST-ACC-01 / TEST-SEC-01 |
| `NFR-PRIV-03` | DD-CLOUD / DD-OBS / DD-RELEASE | TEST-ACC-01 / TEST-SEC-01 |
| `NFR-LEGAL-01` | DD-PROV / DD-RELEASE | TEST-STORE-01 |
| `NFR-LEGAL-02` | DD-PROV / DD-RELEASE | TEST-STORE-01 |
| `NFR-UX-01` | DD-UI | TEST-UI-01 |
| `NFR-STORE-01` | DD-RELEASE | TEST-STORE-01 |
| `NFR-OBS-01` | DD-OBS / DD-TEST | TEST-VIS-01 |
| `NFR-MAINT-01` | DD-PKG / DD-TEST | TEST-STORE-01 |
| `NFR-TEST-01` | DD-TEST | TEST-STORE-01 |
| `NFR-TEST-02` | DD-TEST | TEST-STORE-01 |
| `NFR-OPS-01` | DD-RELEASE | TEST-STORE-01 |

## 参照

- [要件定義書](01_requirements.md)、[基本設計書](02_basic_design.md)。
- Android Developers: [MediaSessionService](https://developer.android.com/media/media3/session/background-playback)、[ExoPlayer](https://developer.android.com/media/media3/exoplayer/supported-formats)、[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)、[USB Host](https://developer.android.com/develop/connectivity/usb/host)。
- AOSP: [Preferred Mixer / Bit-perfect](https://source.android.com/docs/core/audio/preferred-mixer-attr)。
- サービス規約: [Spotify Android SDK](https://developer.spotify.com/documentation/android)、[YouTube Developer Policies](https://developers.google.com/youtube/terms/developer-policies)、[SoundCloud API Guide](https://developers.soundcloud.com/docs/api/guide)。
