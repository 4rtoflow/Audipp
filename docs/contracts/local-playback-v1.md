# 標準ローカル再生 v1（Issue #30）

対象: [Issue #30](https://github.com/4rtoflow/Audipp/issues/30)。既存の[domain契約](domain-model-v1.md)と[SourceResolver](source-resolution-v1.md)へ接続する。単一`:app`、minSdk 23、compile/targetSdk 36を維持する。

## 所有権と入口

`PlaybackService`が唯一のExoPlayerとMediaLibrarySessionを生成し、Service終了時にSession・Player・解決ジョブを解放する。ActivityにPlayerを生成しない。UIの接続先は`PlaybackConnection.connect(context, listener)`で、Main looperから使う。接続futureは画面の破棄時に`MediaController.releaseFuture`へ渡す。Controllerの切断は再生停止の命令にしない。

`PlaybackConnection.playLocal(controller, entryId, variant)`へ、選択版と重複曲を区別するエントリーIDを渡す。`SourceResolver`にLOCAL/DEVICEの具体アダプターだけを登録する。指定された版を別版へ置き換えず、現在のOS権限で`content://`文書を開閉して検査する。許可の取得・永続grantの保存は選択UI/ライブラリ側の責務で、URIの文字列だけでは許可とみなさない。file/HTTP、userinfo/fragment付きURI、不明なソース種別、利用不可の版は再生しない。実再生もContentDataSourceに限定し、ネットワークfallbackを設けない。

音声フォーカスをExoPlayerに委ね、`becoming noisy`を有効にする。video/textトラックを無効にし、Android標準の音声経路を使う。音量変更、DSP、Direct/USB出力、Bit-perfect検証は行わない。未知のduration/positionはnull、seek可否はPlayerの観測値で更新する。PCM取得能力は申告しない。

## 結果・取消・失敗

コマンドの`RESULT_SUCCESS`は**準備処理を受け付けたこと**を表し、音声出力の成功ではない。アプリ用session extrasを`PlaybackConnection.snapshot`で共通PlaybackSnapshotへ復号し、Listenerの`onExtrasChanged`で更新する。Service内ではStateFlowを保持する。解決中、buffering、実エンジンのplaying/paused、終了、errorを区別する。初回接続時も現在のsnapshotを返す。

コマンド失敗は`PlaybackConnection.error(result)`からPlaybackErrorを取得する。解決時の権限拒否、消失、IO、timeout、非対応入力を分類し、開始後のcodec/IO/権限失効も共通エラーに変換する。エンジン例外のmessage/cause/URIは共通エラーへコピーしない。エラー時はPlayerをstopし、media itemを除去してdecoder・音源資源を解放する。接続中のSession自体は再選択・再試行のため維持し、Service終了時に解放する。失敗した曲の自動再試行・自動スキップは行わない。

別曲、Stop、解決中のPause、Service終了で未完了の解決を取り消す。遷移IDも更新し、取消を無視するproviderや古い成功が新しい再生を開始・停止できないようにする。権限/IOのprobeはIO workerへ送り、CancellationSignalをContentResolverへ渡し、取得したFDは成功・失敗・遅延応答でもcloseする。既定の解決timeoutはSourceResolverの10秒。providerが取消を無視するblocking処理を強制終了する保証はない。

## 接続権限・公開情報

ManifestにmediaPlayback FGS、FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK、画面OFF時のCPU維持用WAKE_LOCKを宣言する。Media3の推移ManifestはACCESS_NETWORK_STATEも宣言する。ストレージ全体やINTERNETの権限は追加しない。media session通知はMedia3の標準実装を使う。通知/ロック画面/メディアボタンは同じSessionのPlayerへ送る。[公式のServiceライフサイクル](https://developer.android.com/media/media3/session/background-playback)に従う。

接続時に同一UIDまたはMedia3のtrusted判定を必須とする。ローカル音源を指定できるcustom commandは同一UIDだけに許可し、Callbackでも再検査する。信頼済み外部Controllerには再生/一時停止・停止・現在曲のseek/metadataだけを許可し、playlist変更・任意URIの投入・timeline取得を許可しない。Playerの実能力との共通部分だけが有効になる。

ブラウズはアプリ名の空rootだけを返し、曲、URI、ジャケットURL、検索結果は公開しない。snapshot extrasも同一UIDに限定し、さらにsource/planから文書URIを除く。Media3のPlayerエラーもcauseのない固定メッセージに置き換える。元例外がURIを含むため、このアプリのMedia3 utilityログはプロセス内でOFFにする。診断には共通error code/diagnosticIdを使い、生の例外や文書URIをアプリのログへ出さない。OS/provider自身のログの実機検査は未実施。

## 実装案と後続接続

仕様にない以下の詳細は本PRの実装案であり、承認済み製品条件の変更ではない。

- `PlaybackConnection`のcustom commandと版付きdomain JSON、entry ID最大256文字/要求JSON最大16,384文字。これらは製品の曲数・タグ長制限ではなく、このIPC入口の上限。
- `LocalPlayback`をService looperで直列に扱い、標準端末出力のtarget IDを`android-standard`、初期route revisionを0とする。これは実経路の観測済み申告ではない。経路の監視・証拠とrevision更新は #42、再生Reducer/詳細なフォーカス・切断方針は #37 で統合する。
- 再生中のsnapshot位置を1秒ごとに観測する。checkpointの保存周期や性能達成値ではない。
- 1要求につき1曲を準備する。キューDB/現在項目参照は #38、次/前曲・shuffle/repeatと有限探索は #60、復元を含む結合試験は #83 に接続する。未実装の操作をControllerで有効にしない。現行WelcomeScreenへの選曲UI追加は本Issueの対象外。

## 依存と検証追跡

Media3のexoplayer/sessionと推移artifactをすべて1.11.1へ統一し、Debug/Release・Unit/Lintの構成をlockする。導入に伴いconcurrent-futuresは1.1.0→1.2.0、listenablefuture単体1.0はGuava 33.3.1-androidと重複回避用empty artifactへ解決が変わる。新規runtime依存のPOMはApache-2.0（Media3、AndroidX、Guava）。テスト専用のRobolectric 4.16.1はMITで、Android framework上のService/Controller/ContentResolverをホストで検査するために導入する。テスト推移依存にはApache-2.0、MIT、BSD-3-Clause、Unicode-3.0、CDDL/GPLv2+Classpath Exception等があり、製品APKのruntime依存とは分ける。依存closureはlock差分とビルドで確認する。配布用NOTICEの集約はリリース担当範囲で、導入成功を配布完了とは扱わない。

| 要求 → 設計 → 試験 | 本Issueで検査する範囲 |
|---|---|
| FR-PLY-01/02 → DD-PLY-03 / DD-PKG-02 → TEST-PLY-01/02 | Controllerの再接続とService Player共有、準備受付と実状態の区別、Stopによる解決取消、古い応答の拒否、retry |
| FR-SRC-04 / FR-AUD-02/03 → DD-PLY-03 / DD-ERROR → TEST-FORMAT-01 / TEST-PLY-02 | content限定、provider権限拒否、実ContentResolver probeのFD close/取消、破損文書の実ExoPlayerエラーと資源解放、未知値/seek能力 |
| NFR-COMP-02 → DD-PLY-03 → TEST-COMP-01 / TEST-SEC-01 | minSdk 23/target 36、FGS宣言、接続UID/信頼判定、空browse、要求上限、URI/例外のIPC制限、依存lock |

2026-10-31: Debug/未署名Releaseビルド、全99件のUnit（失敗/skip 0）、Lint（エラー0、警告12）と依存lock生成が成功した。LocalPlaybackTest、PlaybackServiceTest、AndroidDocumentAccessTestと既存SourceResolverTestを含む。警告は依存更新案内10件、動的に接続を検査するexported service、toUriのKTX提案である。RobolectricはAPI23/35の模擬frameworkであり、端末のinstrumented testではない。API36のRobolectric実行にはJDK21が必要なため、既定JDK17のCIではAPI35を上限にする（[公式互換表](https://robolectric.org/compatibility_table/)）。コマンド・結果・対象commit・CI URLは対応PRの「証跡」に記録する。

**NOT_RUN:** Android API23/36上のinstrumented test、実Activity回転中の継続音声、画面OFF・ロック画面・通知/BTボタン、実通話/フォーカス/BT・USB切断、Galaxy S25/FiiO M21/S20、実形式別codec/音声出力、DAC実測。ADB serialの指定がないため端末を操作しない。Bit-perfect、DSP精度、長時間再生、性能目標、試験ファミリー全体の成功を申告しない。未実施項目と最終レビューは本PR/#83等で追跡する。
