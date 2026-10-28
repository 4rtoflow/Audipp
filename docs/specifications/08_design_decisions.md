# 技術設計の決定記録・第2回レビュー

| 項目 | 内容 |
|---|---|
| レビュー状態 | **Complete**（2026-10-28、利用者による全レビュー完了・正式版承認） |
| 文書版・日付 | 1.0.0 / 2026-10-28 |
| 状態 | 利用者から委任された技術判断を反映。実装・実機検証・外部契約の完了とは別 |
| 対応 | [基本設計](02_basic_design.md)のADR-001〜008、[判断記録](06_decisions.md)のR-01〜07 |

2026-10-28の最新指示により個人間版とPUBLICを分ける。現在の採用先・数値・メールなし認証/保持条件は[順次レビュー](09_private_completion_review.md)で更新した。本書では、公開資料と設計上の整合性から決められる処理を確定する。根拠不足のライブラリやアルゴリズムを「採用済み」「検証済み」にしない。

## ADR-001 / ADR-007：再生サービス・Android基盤

**決定：** 完成版のPlaybackServiceはMedia3のMediaLibraryServiceを継承し、MediaLibrarySessionを所有する。PlayerとCoordinatorはServiceだけが生成し、Activity・ウィジェット・通知・外部コントローラーからの操作を同じ入口へ集める。ブラウズで返すのはアクセス可能なライブラリと許諾済みメタデータだけとする。SessionServiceから後で移行する二段階案は採用しない。[公式サービス資料](https://developer.android.com/media/media3/session/serve-content)。

現状のminSdk 23、compile/targetSdk 36を設計基準として維持する。Nativeコードの公開対象はarm64-v8aとarmeabi-v7a、x86_64はエミュレーター試験用とする。指定2機種だけの条件分岐を標準再生の前提にしない。特定機種の回避策はOS/build・適用理由・回帰試験を記録する。

| 導入予定の依存 | 設計で選ぶ版 | 2026-10-28に取得したGoogle Maven AARの確認結果 |
|---|---|---|
| Media3 | 1.11.1に関連artifactを統一 | media3-commonのmanifestはminSdk 23、minCompileSdk 36 |
| Room | runtime/compilerを2.8.5へ統一 | room-runtime-androidのmanifestはminSdk 23、minCompileSdk 34 |
| Preferences DataStore | 1.2.1 | datastore-preferences-androidのmanifestはminSdk 23 |

DataStore 1.3.0の同artifactはminSdk 24だったため、現状のAPI 23を維持する設計では採用しない。Media3の1.12 alphaにもminSdk引上げの記載があり、単に最新版へ更新しない。これは直接artifactのmanifest確認であり、全推移依存・KSP・既存Kotlin/AGPとのビルド互換性まで確認したものではない。導入PRで推移依存の下限とビルドを確認し、バージョンカタログとlockfileへ固定する。まだ依存追加はしていない。

根拠：[Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)、[Room releases](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore)、[Media3 AAR](https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.1/media3-common-1.11.1.aar)、[Room AAR](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.5/room-runtime-android-2.8.5.aar)、[DataStore 1.2.1 AAR](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-android/1.2.1/datastore-preferences-android-1.2.1.aar)、[DataStore 1.3.0 AAR](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-android/1.3.0/datastore-preferences-android-1.3.0.aar)。

## ADR-005：自前サーバー・認証・アプリAPI

**決定：** 自前サーバー上のKeycloakで認証を扱い、アプリ用HTTPS APIと業務DBを分離する。業務APIはKotlin/Ktor、業務DBはPostgreSQLを採用する。Keycloak用DBと業務用DBは少なくともDB・接続ユーザーを分け、アプリAPIがパスワードハッシュやGoogleの秘密へ直接アクセスしない。サービス数を最小にし、初期にマイクロサービスやメッセージブローカーを追加しない。

私用版のメール識別子＋パスワードは管理者が招待先へ発行し、復旧も本人確認した管理者が行う。メール自動確認・再設定の送信はPUBLICとし、未確認メールを確認済みとして記録しない。GoogleはKeycloakのIdentity Brokerを使う。Androidはシステムブラウザーを通したAuthorization Code＋PKCEで認証し、公開クライアントとしてsecretを埋め込まない。Googleと既存メールアカウントの関連付けは、既存アカウントの本人確認と明示操作を必須にする。外部IDのsubjectとアプリ内部user_idを分ける。

Keycloakはメール設定・復旧・Google仲介を備え、PostgreSQLをサポートする。利用者回答によりDebian Server、Ryzen 5 2600X（x86_64）、メモリ32 GB、Docker利用可、外部HTTPS公開可を前提にする。Debian/Dockerの具体版と既存稼働サービスは未確認。具体のサーバー製品版・DB版・私用URL/HTTPS終端・バックアップ先は、サーバー構成と対応表を照合して配備前に固定する。別基盤へ変更が必要なら本ADRを改訂し、ログイン方法やデータ方針は維持する。

根拠：[Keycloak管理ガイド](https://www.keycloak.org/docs/latest/server_admin/index.html)、[対応DB](https://www.keycloak.org/server/db)、[Ktorサーバー資料](https://ktor.io/docs/server-create-a-new-project.html)。

Audipp専用のDocker Composeプロジェクトを使い、認証・API・DBを別コンテナー/接続ユーザーとして配備する。DBと内部APIを直接インターネットへ公開せず、既存HTTPS終端との接続を配備前に決める。既存のコンテナー・DB・TLS設定を上書きしない。秘密・DBバックアップはGit/コンテナーイメージと分離する。ホストへの接続や配備は本レビューでは実行しない。

## ADR-006：同期APIと業務DBの不変条件

**決定：** user_idを認証subjectからサーバーが解決し、クライアント指定のuser_idを所有権の根拠にしない。APIはJSON/HTTPS、日時はUTC、revisionはサーバー採番の整数とする。端末時計によるlast-write-winsは使わない。

| データ | 最小キー・更新規則 |
|---|---|
| users / identities / devices | user_id、(issuer,subject)一意、本人のdevice_id。認証アカウントの無断統合なし |
| sync_events | (user_id,event_id)一意、device_id、entity_type/id、operation、base_revision、history_epoch、server_revision、payload。既処理event_idと同一内容の再送へ同一結果を返す。異なる内容でのID再利用は409 |
| play_events / play_segments | (user_id,event_id)、連続聴取区間のID。履歴は追記し、削除記録に反する旧世代イベントを拒否 |
| synced_settings / playlists / profiles | (user_id,entity_id)、revision、payload。基準revision不一致は409とし、自動上書きしない |
| tombstones / history_epochs | 削除対象IDまたは全履歴削除世代。復元・旧端末再送・cursor失効でも削除を優先 |
| friendship / block / visibility | 相互承認の関係、指向性のあるブロック、公開範囲。取得時にもサーバーが検査 |
| shared_profiles / reports | 所有者、公開revision、根拠区分、通報状態。撤回で共有キャッシュを失効 |

`POST /v1/sync/batches`は最大100イベント、本文上限1 MiBとする。DBトランザクション内でバッチ全体を検査し、失敗時に部分適用しない。200は結果確定、400は入力/schema不正、401は再認証、403は権限/用途不許可、409はrevision/削除世代の競合、410はcursor失効、413はサイズ超過、429は待機、503は一時障害として扱う。バッチ全体の検査では重複IDの内容も比較し、既処理IDの異内容再利用やバッチ内の競合を409として全新規変更をロールバックする。サーバーへ届いたか不明な再送でもevent_idを変えない。削除世代競合では削除済み履歴を新規event_idへ作り直さない。

`GET /v1/sync`はuser_idとsnapshot上限revisionに束縛した不透明cursorを返す。各ページは最大100イベントとし、ページはローカルの一時領域へ蓄積し、最後のページまで検査してから、受信変更とcursorを同一トランザクションで正本へ反映する。完全同期中も未送信outboxを別保持し、受信snapshotで上書きせず再検査する。途中失敗は正本と確定cursorを変えない。並行更新は次の同期へ回す。cursor失効時はtombstone/削除世代を含む完全同期を先に行い、未送信データは保持したまま再検査する。並べ替え競合は両コピーを提示して選択させる。

業務データのSQL型・索引・FK・migrationはこのキー・原子性・所有権規則を変えず実装時に記述する。秘密をpayloadに含めない。私用の削除期限とバックアップ保持は09の⑤の初期値を採用し、復旧試験で検査する。

端末側の初期Room schemaはv1とする。Source/Variantは失効・消失を状態として残し、参照中の行を連鎖削除しない。queue_itemsには(queue_id,entry_id)の一意性を持たせ、checkpointは同じqueue_idのentry_idだけを参照する。現在項目の削除は停止要求と同じCoordinator順序で処理し、DBトランザクションでcheckpointをNULLへ更新する。playlist_itemsの削除は再生キューへ連鎖させない。履歴のキュー参照は再生時点の値として保持し、キュー削除で履歴を消さない。

## 秘密情報の保存

Androidのトークン保存は、Android Keystore内のアプリ専用AES鍵とAES-GCMで暗号化したアプリ内部ファイルへ分ける。暗号化ごとにnonceを再生成し、同じ鍵で再利用しない。レコード種別・account_id・schemaVersionを認証データへ含め、他アカウントの暗号文の流用を拒否する。KeyStoreをデータ保存場所として扱わず、鍵と暗号文の両方をバックアップ・同期・診断出力から除外する。

バックグラウンドでの認証更新に利用できる鍵とし、生体認証を毎回要求する方式を必須にしない。鍵無効化・アプリ復元後の復号失敗では対象アカウントをAUTH_REQUIREDとし、平文保存へフォールバックせず、ローカルライブラリや履歴を破壊しない。サーバーのLast.fmセッション鍵・OAuth関連秘密も業務payloadやログから分離し、Google等の資格情報を端末間同期へ載せない。

## 再生履歴・統計の確定規則

1回の再生試行にplay_event_idを発行する。同じ曲を再度再生・リピートした場合は新ID、一時停止と再開は同IDとする。自然終了だけでなくStop・別曲・現在項目削除・エラー終了で試行を確定する。プロセス停止時には最後に保存した区間までを復元し、停止中の時間を補完しない。

連続して再生できた区間をplay_segmentsへ保存する。区間にはUTC開始時刻、単調時計で計った聴取時間、進んだ音源区間、再生速度、タイムゾーン、Variant・route・profileの取得済みスナップショットを持つ。Pause・buffering・シーク・経路/速度/タイムゾーン変更で区間を閉じる。UTC時計の変更を検出した場合も区間を閉じ、その区間の経過は開始UTCと単調時計に基づいて日付へ割り当てる。時間は音声が進んでいた単調時計区間で加算し、シーク距離やUTC時計変更を加算しない。壁時計と再生ポジションの単純差分だけで聴取時間を求めない。

日/週/月/年の回数は試行開始時点の保存済みタイムゾーンへ帰属させ、聴取時間は区間を日付境界で分割する。週は月曜開始、同順位は順位を共有して次順位を飛ばす（1,1,3）。複数アーティストの曲では各関連アーティストへ1試行を帰属させるが、全体合計はevent_idごとに一度だけ計数する。アーティスト別の合計を全体総数として足し上げない。曲長不明の再生時間も観測できた区間分は集計する。

アプリ内の「再生回数」は曲長既知なら`played_ms >= min(duration_ms / 2, 240000)`で1回とする。未知長は240秒の観測後に1回とし、0長や未観測のイベントは0回。短曲を除外するLast.fmの条件とは分離する。全条件の境界値はTEST-HIST-01/STATS-01で確認する。

## 推薦・ABX・描画の初期規則

推薦v1は履歴を入力とする決定的な順位付けとし、クラウドMLを必須にしない。候補ごとに、本人のアーティスト聴取比率、ジャンル聴取比率、選択時間帯の聴取比率、候補のメタデータ充実度を0〜1へ正規化し、順に0.40/0.35/0.20/0.05で加重する。充実度はtitle/artist/album/genre/yearの既知項目比率とする。欠損の嗜好因子は分母から除いて残る重みを正規化し、嗜好因子が一つもなければ推薦不足と表示する。情報量だけで架空の嗜好一致を作らない。既に再生不可の候補を除き、同点は内部Track IDで安定順にし、上位50件と寄与した理由を返す。計算式の変更はversionを増やして再計算する。

ABXは開始前に試行数と比較条件を固定し、試行ごとにXをA/Bへ等確率で割り付ける。答えは試験中のUI・読み上げ・通常診断へ出さない。試行ごとの再抽選を許さず、終了後に条件と回答を保存する。所定数完了時だけ、帰無仮説p=0.5に対する片側二項確率を正答率とともに計算する。途中終了は参考結果として完了試験と分ける。Native/DoP等で遅延・音量差を統制できない経路はABX不可と表示し、別経路を同一条件と偽らない。音量合わせ・解析精度の数値基準は09のQLTに従う。

Visualizerは初期30fpsとし、15/30/60fpsを選択できる。PCM解析と描画は別スレッド/バッファで扱い、表示待ちのフレームは常に最新1枚だけを保持する。非表示時は描画を停止する。連続したフレーム予算超過では60→30→15fpsの順に下げ、15fpsでも継続する場合は動的描画を停止して状態を表示する。音声Tapをブロックせず、表示品質の低下で音声処理順や出力形式を変えない。FFT窓長・RMS/LUFS/True Peak・IR長等の測定契約は09の②③に従い、見た目の設定値で測定精度を保証しない。

## Last.fmの確定規則

公式のScrobbling 2.0に従い、曲長が30秒を超え、実再生時間が曲長の半分または240秒の短い方以上になったときだけScrobbleする。曲長不明や提供元のSCROBBLE許諾なしでは送信しない。ファイル名からアーティスト/曲名を推定して送らない。試行ごとに送信キーを作り、開始時刻を固定する。再試行で現在時刻に置き換えない。

認証はLast.fmのブラウザー承認フローを使い、ユーザーのLast.fmパスワードをアプリで収集しない。アプリ秘密が必要な署名処理は自前サーバーの送信Gatewayに集約し、APKへ秘密を埋め込まない。Scrobble自体の利用者同意と、送信データ・送信先は別途明示する。オフライン再生も端末outboxへ保存し、オンライン復帰後に権限・削除状態を再検査して送る。

Now Playingの失敗は再送しない。Scrobbleは最大50件のバッチで送信でき、HTTP 200だけでなく本文statusと項目ごとのaccepted/ignoredを確認する。ignoredは送信成功カウントにせず、理由を保存して無限再送しない。通信前に失敗した要求と一時障害だけをバックオフで再送し、応答喪失など到達不明はUNCERTAINへ保持して自動再送を止める。重複排除が外部側で保証されているとは仮定しない。APIキー取得・実サービス契約試験は未実施。

根拠：[Scrobbling 2.0](https://www.last.fm/api/scrobbling)、[Web認証](https://www.last.fm/api/webauth)、[track.scrobble](https://www.last.fm/api/show/track.scrobble)。

## ADR-002 / ADR-003 / ADR-008：音声・USBの確定範囲

**決定する構造：** 標準のMedia3経路と、PCMを観測・処理できる音声経路、Native/DoP等のUSB転送経路を区別する。単一の独自PCMパイプラインをDRM音源にも強制しない。内部DSPはFloat PCMを基準にし、整数出力時の量子化とdither、左右/多ch配置、処理前後の信号取得を契約試験する。32-bit整数のビット列保存を要求する検証経路ではFloat変換を迂回する。

リサンプラーはlibsamplerate 0.2.2とlibsoxr 0.1.3、DSD decodeはFFmpeg n8.0.1、PCM→DSDはcommit固定のmansr/sox sigma-delta、FFTはKissFFT 131.1.0、解析はlibebur128 1.2.6を採用先とする。詳細なライセンス・処理順・上限・品質基準は09の②③に記録した。NDKビルドと実測は未実施。

Java UsbRequestだけでisochronous転送ができるとはしない。USBはlibusb 1.0.29とJava許可FDを使う方式を選び、UAC制御・feedback・停止/解放・Nativeの個別機器条件は09で定義する。root不要の実機成立は未確認。[libusb Android](https://github.com/libusb/libusb/blob/v1.0.29/android/README)。

Preferred Mixerの要求成功と、DACへ届くビット列の一致は別判定。Bit-perfectの証拠は端末・OS/build・DAC・形式・音量・DSP・転送方式・試験IDへ束縛し、条件変更で失効させる。利用者回答でDACはiFi ZEN DAC V2、Galaxy S25はAndroid 16と判明した。FiiO M21のOS版は未確認のままとする。機種情報だけでNative/DoP/384 kHzの実動作を保証しない。[AOSP](https://source.android.com/docs/core/audio/preferred-mixer-attr)。

**未完了として残すもの：** 選定した方式のNDK導入・libsox接続、個別DAC対応、数値精度・電力・遅延・音量の実測。この不足は初回機能の削減ではなく、該当詳細設計と公開判定の未完了である。

## レビューの完了条件と未解決分類

| 分類 | この段階で必要なもの | 実装・測定後に必要なもの |
|---|---|---|
| 文書内の整合性 | 全機能・回答・設計・試験が矛盾せず、主体/状態/入力/結果/異常処理を特定できる | 仕様変更時の更新 |
| 設計選定 | アーキテクチャ・アルゴリズム・データ・プロトコルの選定と根拠 | 依存導入・コンパイル・migration・契約試験 |
| 外部依存 | 必要な許諾・仕様・環境を明示。未取得を取得済み扱いしない | API資格・個別許諾・サービス試験 |
| 実機品質 | 対象・手順・信号・合格基準を定義 | 実測と結果の承認 |

R-01の基盤選定、R-04の論理構成・同期契約、R-05の履歴/Last.fm規則を今回前進させた。その後の①〜⑥の順次レビューで方式・初期品質基準・LAN/タグ・私用運用を09へ具体化した。外部サービス条件と導入/実測の未完了は09の⑥へ集約する。
