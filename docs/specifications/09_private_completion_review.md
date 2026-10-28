# 個人間利用版の完成に向けた順次レビュー

| 項目 | 内容 |
|---|---|
| 版・日付 | 1.0.0-draft.3 / 2026-10-28 |
| 根拠 | 利用者の「①〜⑥の順に行う」「まず個人間でしっかり使えるアプリを完成させる」という指示 |
| 適用 | 要件・基本・詳細設計draft.8。旧版と相違する段階・方式は本書と最新要件を優先 |
| 状態の区別 | 設計採用＝実装で使う方式の決定。導入検証待ち＝未ビルド/未測定。外部条件未解決＝利用資格・サービス条件等の証拠なし。いずれも実装完了を意味しない |

## ① ストリーミング連携

開発者登録・API許可は利用者回答で「未取得または不明」。取得済みとは扱わない。

個人間版でも、Qobuz・Spotify・Apple Music・YouTube/YouTube Musicの直接再生を必須とし、検索は可能なら実装する。公開名義・ストア手続は完成後へ移すが、API資格・ソースの利用権・用途別条件を満たす必要は残る。

| サービス | 今回の判断 | 残る証拠・処理 |
|---|---|---|
| Qobuz | qobine参考の非公式候補を追加。認証/解決・報告・回復を調査 | Android callbackとapp identity、認可された取得方式・用途条件は10で確認 |
| Spotify | 公開Android SDKのApp Remoteは直接再生の採用案から除外。混在サービスと派生統計についても公開ポリシーと要求が衝突する | librespot PCM Sink候補とAndroid導入を10で評価。統合・統計等の用途条件は未解決 |
| Apple Music | 2026-10-28追加。公式MusicKit for Androidによるアプリ内音声再生とApple Music APIによる検索を採用経路とする | Developer資格・MusicKit識別子/鍵、SDK版/OS/ABI、本人認証/契約、用途別条件と実機動作。詳細はDD-PROV-06 |
| YouTube / YouTube Music | yt-dlpで音声専用形式を解決し、音声Playerへ渡す契約を維持。動画へ代替しない | サーバーの実行版/更新、JS challenge依存、URL/ヘッダー・IP条件、音声専用で扱える対象、サービス条件。API経路の音声分離/背景再生の制限と、yt-dlp経路の技術条件を混同しない |

最新の利用者決定で、YouTubeの実行環境はサーバー側のyt-dlp/Python/JS runtime/ejsへ移した。音声もサーバーから中継し、URLだけを無検証で端末へ渡さない。端末内蔵は後段候補で、API24 wrapperとAPI23基盤の衝突は現在の完成条件から外す。保存希望なしはstream、希望ありは端末の選択フォルダへ保存する。詳細・試験は[中継・保存契約](11_ytdlp_delivery.md)に従う。

各Adapterは期限付き再生記述子をメモリで保持し、期限切れ時に再解決する。401/失効は認証更新を一度だけ試み、403/用途不許可を繰返さない。URL・Cookie・認証ヘッダーは履歴・共有・同期・診断へ載せない。再生を許可された音源でもDSP/解析/履歴/推薦/Last.fmを包括的に許可された扱いにしない。

**①の結論：** 調査と設計上の選別は実施したが、全サービス直接再生の成立は未確認。サービス要件は削らず、外部条件が不足するAdapterを未完了として独立させ、②以降のレビューと他領域の実装を進める。

一次資料：[Spotify Android](https://developer.spotify.com/documentation/android)、[Spotify Policy](https://developer.spotify.com/policy)、[Amazon Music](https://developer.amazon.com/docs/music/landing_home.html)、[SoundCloud Guide](https://developers.soundcloud.com/docs/api/guide)、[YouTube API Policy](https://developers.google.com/youtube/terms/developer-policies)、[YouTube利用規約](https://www.youtube.com/static?template=terms)、[yt-dlp](https://github.com/yt-dlp/yt-dlp)、[Android wrapper](https://github.com/yausername/youtubedl-android)、[wrapperのminSdk](https://github.com/yausername/youtubedl-android/blob/master/library/build.gradle.kts)。Qobuz旧文書とサービス別確認案は[初期調査](07_service_feasibility.md)を参照。

## ② 音声処理・USBの方式選定

依存は必要な機能を実装する時点で追加し、現在の単一appモジュールへ空のNative/DSP層を先に作らない。以下は設計上の採用先であり、全ABIビルド・脆弱性確認・依存ライセンスの台帳・再リンク等の配布物を導入時に検査する。古い安定版を選んだことを安全性の証拠にしない。

| 領域 | 選定・処理 | 導入時に確認すること |
|---|---|---|
| リサンプラー1 | libsamplerate 0.2.2、SINC_BEST/MEDIUM。既定はBEST | BSD-2-Clause、NDK/ABI、44.1↔48系と上限超入力の実時間処理 |
| リサンプラー2 | libsoxr 0.1.3、HQ/VHQ。phase_responseでLinear/Minimumを区別 | LGPL-2.1-or-later、遅延・flush・品質。Minimumをlibsamplerateにもあると表示しない |
| DSD→PCM | FFmpeg n8.0.1のDSF/DSDIFF demuxとDSD decoder、必要機能だけのLGPLビルド | MSB/LSB・planar/interleaved、デコード後のレートと必要な追加SRC。映像decoder・GPL/nonfree機能を不要に組み込まない |
| PCM→DSD | mansr/soxのsigma-delta実装を採用先とする。commit `0be259eaa9ce3f3fa587a3ef0cf2c0b9c73167a2`、`sdm-5`、trellisなしを初期設定 | src/sdm.c/hはLGPL-2.1-or-later。内部APIでsox_i.h/sox_sample_t等に依存するためlibsoxとの接続・整数量子化・NDK/CPUを検査。安定public APIとは扱わない |
| FFT/畳込み | KissFFT 131.1.0。自前のpartitioned overlap-saveでIRを処理 | BSD-3-Clause。ブロック境界・tail・flush・無効化時の遅延補償 |
| LUFS/True Peak | libebur128 1.2.6。チャンネル位置を設定して解析 | MIT。対応率・ch、独立フィクスチャとの一致。採用だけで規格適合済みとはしない |
| USB転送 | libusb 1.0.29＋AudippのUAC制御/転送。Java許可後のFDをJNIで渡す | LGPL-2.1-or-later。AndroidでwrapしたFDの所有権・非同期isochronous転送・切断・OSとの排他。root不要で成立する組合せを実証 |

PCM→DSDはPCM側のEQ/音量等の処理を完了した後、44.1 kHz×64/128/256の変調入力へ補間し、SRCの単段比率上限を超える入力は複数段へ分けて全体応答を検査する。倍率に合うsdm-5係数を使う。DSD内部ビットクロックはPCM音声出力384 kHzの制限と別であり、11.2896 MHzの変調処理をPCM出力要求へ設定しない。変調前は最大-6 dBFSへ抑え、非有限値・過負荷・変調器異常で停止する。実時間処理が間に合わない場合に倍率やPCMへ黙って変更しない。初期方式は選んだが、端末負荷と帯域内/帯域外ノイズの合格は③の実測待ち。

Apodizingは独立した低域通過FIRとして、入力Nyquistの90%を通過域端、99%を阻止域端、阻止域80 dB以上、Kaiser窓から係数を生成する。タップ数は応答試験で必要数を算出し32769以下、超過なら設定を拒否して理由を示す。Linearは対称FIR、Minimumはlibsoxr側の対応設定と実測した群遅延を使い、同じ係数へ名称だけ付け替えない。Apodizingは過去の録音のリンギング除去を保証せず、実際の振幅/位相応答と遅延を表示する。プリフィルターとリサンプラーの組合せ全体を測る。

DSP順序は`decode -> channel map/downmix -> ReplayGain/R128 -> preamp -> PEQ -> IR -> Crossfeed -> Apodizing(選択時) -> SRC/phase -> clip guard -> 量子化PCM または DSD変調`に固定する。Float32を標準バッファ、係数・蓄積は必要に応じFloat64とし、32-bit整数Bit-perfect経路はこのグラフを迂回する。整数出力で精度を落とすときだけTPDF ditherを適用し、適用位置/精度をSignal Pathへ記録する。DSD変調前にPCM用ditherを重ねない。DSP設定はバックグラウンドで検査・係数生成し、音声スレッドは事前確保したバッファで処理する。

PCMの処理bufferは40/80/160 ms（既定80）、再生前の追加prebufferは0/250/500/1000 ms（既定250）から選ぶ。USBの転送bufferは別管理で既定32 msを要求し、endpoint/転送粒度から計算した実際値を別表示する。変更は停止・再構成を経由し、未知値を適用済みと表示しない。デジタル音量は-90〜0 dB/0.5 dB刻み、mute別とし、DAC音量は取得できた機器単位を別に扱う。DSP clip guardは5 ms lookaheadの初期方式とし、True Peak目標は-1 dBTP。Bit-perfect/Native/DoPには差し込まない。

PEQは最大20バンド、20 Hz〜min(20 kHz, 0.45 Fs)、gain ±12 dB、Q 0.1〜20、preamp -30〜0 dBとする。適用可能な最大正利得を合算し、IRの係数絶対和も含めて必要ヘッドルームを見積もる。安全な減衰を表示してから適用し、gain変更は10 msで平滑化する。フィルター係数の更新は旧/新の安定した処理枝をcrossfadeし、不安定になり得る係数の単純補間へ依存しない。R128は既定-23 LUFS、設定範囲-30〜-10 LUFS、True Peak目標-1 dBTPとし、解析不能時は補正しない。ReplayGainのタグ値とR128値を二重適用しない。Crossfeedはステレオのみ、他chでは無効理由を表示する。

5.1→2chは`L=FL+FC/√2+SL/√2`、`R=FR+FC/√2+SR/√2`を各々`1+√2`で割る初期方式とする。LFEは既定で合成しないと表示し、サラウンド位置不明なら拒否する。元の5.1chと変換後2chを併記し、実験機能であることと係数を保存する。

UAC制御はdescriptorからrate/ch/formatとfeedback方式を読み、対応するalternate settingとclockを設定してから転送を開始する。パケット長は端数を繰越し、feedbackで速度を補正する。OSの出力停止とinterface取得を確認できなければDirect開始を拒否する。停止時は転送取消→callback回収→interface解放→native handle終了→Java接続closeの順とし、FDを二重closeしない。Native DSDのvendor固有制御はVID/PID・firmware等の検証済み対応表だけに適用し、未知機器へ推測コマンドを送らない。

DoPはDSD16bitをPCMフレームへ詰め、marker 0x05/0xFAを交互にする。DSD64/128/256の搬送は176.4/352.8/705.6 kHzで、PCM音源の384 kHz上限とは別。DACが705.6 kHz搬送に対応しなければDoPのDSD256は成立しない。Native対応または明示したDSD→PCM変換を提示する。DoPへPCM音量・DSPをかけず、開始/終了・切断時のミュートとDAC検知を確認する。

**②の結論：** 実装方式・対象ソース・処理順を選定。NDK導入、sigma-deltaのlibsox接続、USB機器固有制御は導入試験待ち。特定DACへのNative/DoP対応済みという保証はしない。

根拠：[libsamplerate 0.2.2](https://github.com/libsndfile/libsamplerate/tree/0.2.2)、[libsoxr API](https://github.com/chirlu/soxr/blob/0.1.3/src/soxr.h)、[FFmpeg DSD decoder](https://github.com/FFmpeg/FFmpeg/blob/n8.0.1/libavcodec/dsddec.c)、[SoX sigma-delta](https://github.com/mansr/sox/blob/0be259eaa9ce3f3fa587a3ef0cf2c0b9c73167a2/src/sdm.c)、[KissFFT](https://github.com/mborgerding/kissfft/tree/131.1.0)、[libebur128](https://github.com/jiixyj/libebur128/tree/v1.2.6)、[libusb Android](https://github.com/libusb/libusb/blob/v1.0.29/android/README)。USBクラス制御とDoPは次の機器契約試験で実際のbyte列を照合する。

## ③ 音声品質・性能の測定契約

以下は委任された技術判断として設定する初期合格基準であり、達成実績ではない。規格の要求値とAudipp独自の目標を分ける。両指定端末で同じ基準を試し、端末で不足したら機能を黙って省かず、計測結果と改善案を記録する。

測定記録は試験ID、アプリrevision、端末/OS/build、DAC/firmware、接続・音量、入力SHA-256/形式/ch、処理設定、取得位置、測定器/基準実装の版、結果・誤差・PASS/FAILを持つ。音声の数値検査は生成信号と独立したFloat64基準を使い、Android画面に表示した値を同じ計算の正解値として照合しない。DAC表示やアナログ出力だけでビット列一致を合格にしない。

| ID / 対応TEST | 条件・信号 | 初期合格基準 |
|---|---|---|
| QLT-01 / TEST-AUD-01, TEST-TAP-01 | 元音源/要求/OS申告/観測/外部実測が異なる模擬経路 | 値と出典を混同しない。不明を推測で補わない。内部Tap取得だけで外部実測にしない |
| QLT-02 / TEST-AUD-02 | DSPなし整数16/24/32-bit、既知PRNGと±境界、各検証対象レート/ch | 外部で取得したデジタル列が整列後に全サンプル一致。不一致0。外部取得できなければVERIFIED表示不可 |
| QLT-03 / TEST-FORMAT-02, TEST-AUD-02 | SRC両実装、44.1/48/96/192/384 kHzおよび705.6/768 kHz入力、sweep/impulse | BEST/HQ/VHQは通過域20 Hz〜0.90×低い側Nyquistで偏差±0.05 dB、0.99×低い側Nyquist以降の阻止域80 dB以上。MEDIUMは±0.1 dB/60 dB以上。遅延補正後の長さ誤差1出力sample以下。最終flushも含む |
| QLT-04 / TEST-VIS-01, TEST-TAP-01 | RMS/Peak、FFT正弦波・無音・同相/逆相/無相関 | 振幅誤差0.1 dB以内、相関±0.01以内。FFTはHann窓、既定4096点、2048/4096/8192/16384点を選択。bin幅Fs/Nと窓補正を保存。無音/片ch無音では相関不定を表示 |
| QLT-05 / TEST-VIS-01, TEST-ABX-01 | libebur128とEBU test set、48 kHz/5.1chを含む | 公開テストの指定許容値を満たす。追加生成信号のLUFS誤差0.1 LU以内、True Peak誤差0.2 dB以内。測定窓未成立は計測中表示。別レートは独立基準との差も記録 |
| QLT-06 / TEST-ABX-01 | 同一PCMから比較条件だけを変更。20試行を開始前固定 | A/B音量差0.1 dB以内、整列誤差1sample以下。条件統制不可なら拒否。正答漏洩なし、回答変更/再抽選なし。途中終了はp値による判定なし |
| QLT-07 / TEST-GAPLESS-01 | 同一連続PCMを分割したWAV/FLAC、パディング付きMP3/AACを別々に試す | Decoder/DSP遅延補償後にサンプル欠落・重複・追加無音0。形式やrate変更など保証外境界は別結果にし、通常境界の成功に混ぜない |
| QLT-08 / TEST-AUD-02, TEST-FORMAT-02 | DSD64/128/256のdecode/encode、1 kHz -6 dBFS、無音、DC、最大入力、30分連続 | 20 Hz〜20 kHzの振幅偏差±0.1 dB以内、帯域内THD+N -80 dB以下（測定帯域・基準レベルを記録）。非有限値・発散・buffer不足0。帯域外20〜100 kHz/100 kHz超の電力も別記録し、可聴帯域だけで安全性合格にしない |
| QLT-09 / TEST-AUD-02, TEST-COMP-01 | USB PCM/Native/DoP、各倍率、切断・再接続・Stop・経路切替 | byte順/ch配置/markerと原DSD列を照合。取消後の転送/解放後callback使用0、USB断で内蔵スピーカーへ自動出音しない。DACロックとアナログ過渡は別測定 |
| QLT-10 / TEST-PERF-01 | 各端末、release相当build、1000曲、各30回。初回スキャンは別 | P95：cold start 2秒以内、入力→描画開始200 ms以内、local選曲→音声1秒以内、local seek→音声500 ms以内。ネット経路はLAN/回線条件を別記録しlocalと合算しない |
| QLT-11 / TEST-REL-01, TEST-PERF-01 | 8時間、画面OFF、指定経路と必要なDSP別 | Crash/ANR/制御不能/アプリ起因underrun 0。DSPブロック処理時間P99はブロック再生時間の80%以下。Visualizer有効でも同基準を維持 |
| QLT-12 / TEST-PERF-01 | 30分warmup後2時間、温度・輝度・回線・音量を固定 | 音声/データcacheを除くPSS 350 MiB以下、最終1時間の増加10 MiB以下。標準local画面OFFの電池減少5ポイント/時以下、全DSP/USBは10ポイント/時以下を初期目標。USB給電を区別。計測不能ならNOT_RUNで、推定値を合格に使わない |
| QLT-13 / TEST-AUD-03, TEST-DEVICE-01 | DAC/BT切断、再接続、プロファイル切替、IR/EQ極端値 | 既存の出力音量を自動で上げない。新規経路はPause＋音量確認。DSP後True Peakが目標を超えない。DACのハード音量を読めない場合は確認が済むまで再開しない |
| QLT-14 / TEST-AUD-02, TEST-PRESET-01 | IR単位impulse/既知FIR、各ch、rate差、差替・解除 | 32768 taps/chかつ2秒以内、最大6chの積を対象上限とする。正規化した基準との絶対誤差1e-5以下、末尾消失なし。rate差は表示して事前変換。上限超を切捨てず拒否 |

QLT-08の帯域外・過渡出力は、DAC側で除去されると仮定しない。変調器単体とDAC出力を分け、測定設備の帯域・電圧/負荷条件を記録する。機器の許容範囲や測定系が不明な間は、Native/PCM→DSD経路のアナログ安全性は未検証とする。自動的な高音量再生や、耳で聴くことだけでの判定をしない。

IRは係数を読み込んで長さ・ch・rate・有限性・利得上限を検査し、partition size 1024 framesを初期値とする。未処理経路と遅延を合わせ、50 ms crossfadeで差し替える。リアルタイム処理の予算を満たさない場合は変更を拒否し、旧IRを維持する。30fps描画を維持できない場合の低下手順は08の規則に従い、描画待ちで音声スレッドを止めない。

**③の結論：** 一律「閾値TBD」とする状態を解消し、測定対象・初期数値・失敗時の扱いを決定。実機で目標に届くこと、外部デジタル取得とDSD帯域外の安全性は未検証。測定器/5.1ch出力環境は機器候補と接続方法を具体化してから利用者へ確認する。

規格側の根拠：[EBU Tech 3341](https://tech.ebu.ch/publications/tech3341)、[EBU test set](https://tech.ebu.ch/cms/live/live/en/sites/tech/contents/publications/testmaterial/publication_6035.html)、[DoP 1.1](https://dsd-guide.com/sites/default/files/white-papers/DoP_openStandard_1v1.pdf)、[USB Audio 2.0資料一覧](https://www.usb.org/documents?search=Audio+2.0)。QLTの数値全体がこれらの規格に規定されているという意味ではない。外部試験音源は利用条件を確認し、無断でリポジトリへ同梱しない。

## ④ LAN連携・メタデータ編集

| 領域 | 採用する実装 | 入力・異常・復帰の契約 |
|---|---|---|
| SMB | SMBJ 0.13.0、SMB2/3。ファイルAPIを使い、未完成のNIO providerへ依存しない | 明示したhost/share/pathだけ。資格情報は端末内暗号化。SMB1への自動降格なし。接続10秒/操作30秒、途中読込失敗は同じoffsetから再取得し、変更済みファイルを旧bufferと結合しない |
| WebDAV | AndroidのHTTPクライアント＋XML parser。RFC4918のPROPFIND Depth:1、GET/Rangeで読込 | 無限再帰のDepth:infinityは使わない。ETag/Last-Modifiedと長さを保存し、変更時は再読込。Range無視の200を206と扱わず、seek不可を表示。401は本人の再入力、他hostへのredirectへ秘密を渡さない |
| MPD | Audippの小規模なTCP protocol client。操作/idle監視を別接続 | 接続応答・commandsで機能判定、status/songid/playlist versionを照合。失われた操作ACKを成功と推測しない。外部queue変更は再取得し、停止・再試行後も同じ所有者規則を通す |
| UPnP AV | 汎用フレームワークは追加せず、SSDP＋device/SCPD取得＋SOAPを実装。ContentDirectory:1/AVTransport:1/RenderingControl:1を基準 | Browse、SetAVTransportURI/Play/Pause/Stop/Seek/GetTransportInfo/GetPositionInfo、取得できる音量操作だけ。advertiseされたservice/actionを検査。SetNextAVTransportURIの有無とgapless保証を同一視しない |
| OpenHome | Playlist:1/Volume:1等の公開service定義へ対応するSOAP client | Insert/Delete/ReadList/IdArray/IdArrayChanged/TransportState等を定義と照合。返却IDとtokenを記録し、外部変更で再取得。MPD/UPnP AVとqueueの所有権・IDを混同しない |
| タグ/画像 | TagLib 2.1.1のC++ APIをJNIで使う。AndroidのURIは一時ファイルまたはseek可能なIOStreamへ明示変換 | アプリ内overrideを正本に保持し、原曲書込みは本人の明示操作。未知フィールドを無断削除しない。書換え後に再読込・音声packet/形式/長さを検証 |

SMBJは公式buildのJava release=8を確認したが、Androidでの暗号provider・推移依存・API23動作は未検証。導入時に実機SMB2/3の認証・署名・seekを試す。UPnP/OpenHomeを自前実装する判断は「相互運用確認不要」という意味ではなく、BubbleUPnP/upmpdcli/MPDとの契約試験を必須にする。

SSDP探索は利用者が選んだネットワークで10秒に制限し、常時multicastしない。LOCATION/制御URLを検査し、外部ホストや他ネットワークへの移動を自動で追わない。XMLは外部entity/DTDを無効、単一応答2 MiB/深さ32/一覧1000件を上限とし、続きがある場合はページを取得する。初期状態監視は接続中だけ2秒間隔のpollとし、操作直後は応答と状態を照合する。GENA callbackや端末音源配信用のHTTP listenerを暗黙に追加しない。発見できない場合は手動host指定を用意する。Android版ごとのLAN/multicast権限拒否は理由表示と再要求にする。

タグ書込みの対応表は次を初期対象とし、形式/タグ/chunkごとのfixtureが合格するまで「書込対応」にしない。

| 音源 | タグ・画像方式 | 保存時の条件 |
|---|---|---|
| MP3 | ID3v2（既存版を尊重）、APIC | ID3v1だけの情報と未知frameを保持。音声frameを変更しない |
| FLAC | Vorbis comment、PICTURE | 複数artist・disc/track・画像の役割を保持。圧縮音声blockを変更しない |
| WAV | RIFF INFO＋既存ID3 chunk | INFOで表せない値はID3/アプリoverrideを使い分け、未知chunkを保持 |
| AAC in MP4/M4A | MP4 item/cover art | AAC packetとtimingを変えずmetadataだけ保存 |
| DSF | ID3v2/画像 | DSD block、padding、channel順、metadata offsetを検査 |
| DFF / raw ADTS AAC | 既存タグの読取＋アプリoverride、書込APIが成立する形式だけ原曲更新 | 拡張子だけで書込APIありとみなさない。非対応時は編集を端末DBへ保存し、対応コンテナーへの別ファイル出力を提示。原曲へ破壊的に独自タグを挿入しない |

保存は、元の識別/長さ/変更情報を取得→一時コピーへタグ変更→再読込/音声不変検査→元ファイルの競合再検査→置換→DB更新の順とする。原子置換と復旧が保証できないSAF providerでは元ファイルをその場で切り詰めず、新規ファイルとして保存する。作業journalに元URI/一時URI/段階を保持し、失敗・容量不足・権限失効・プロセス停止から旧音源を参照して復帰する。書込み中の音源は再生中のVariantと切り離さず、完了後も履歴IDを変えない。

画像は埋込み/本人指定/許可された提供元の画像を区別する。取込みは最大20 MiB・40 MP、展開後は長辺2048px以下、表示用cacheは最大64 MiBとし、超過はdownsampleまたは理由付き拒否。外部画像の再配布を端末表示と一括許可にしない。

ウィジェットは追加のUI依存を先行導入せず、AppWidgetProvider＋RemoteViewsを採用する。更新は曲/状態/操作可否の変更時と、表示中の最大1秒間隔へまとめる。位置を毎sample更新しない。アートは256px以下、PendingIntentはinstance/actionごとに分けてimmutableとし、token/URIをextrasへ載せない。操作はMediaController経由でServiceへ集め、プロセス再生成・権限喪失・OSの背景起動拒否は理由表示にする。タップだけで別の再生主体を勝手に作らない。[Android widgets](https://developer.android.com/develop/ui/views/appwidgets/overview)。

**④の結論：** LANの具体方式、対象service、書込対象と復旧手順を決定。各依存のAndroid導入と相互運用・タグ不変性は実装試験待ち。MusicBrainz/AcoustIDは08と詳細設計の同意・API制限を維持する。

根拠：[SMBJ 0.13.0 build](https://github.com/hierynomus/smbj/blob/v0.13.0/build.gradle)、[SMBJ license](https://github.com/hierynomus/smbj/blob/v0.13.0/LICENSE_HEADER)、[WebDAV RFC4918](https://www.rfc-editor.org/rfc/rfc4918)、[MPD protocol](https://mpd.readthedocs.io/en/stable/protocol.html)、[UPnP AVTransport:1](https://upnp.org/specs/av/UPnP-av-AVTransport-v1-Service.pdf)、[OpenHome Playlist:1定義](https://github.com/openhome/ohNet/blob/b86dab7ff82cd972ef83cf27aab667e821242440/OpenHome/Net/Service/Upnp/OpenHome/Playlist1.xml)、[TagLib 2.1.1](https://github.com/taglib/taglib/tree/v2.1.1)。

## ⑤ 個人間サーバー運用と完成後の公開準備

現在のサーバーは招待した個人の認証・同期・相互承認フレンド・機器プロファイル共有を扱う。不特定多数の登録・機器DBの公開閲覧・常設問い合わせは有効化しない。端末での機器DB登録/訂正、指定した相手への共有・撤回・ブロックと本人データ削除は現在も搭載する。公開サービス化を必要としない機能まで削らない。

Keycloak/Ktor/PostgreSQLの分離は維持する。私用構成はKeycloakの自己登録を閉じ、管理者が本人と確認した招待先にアカウントを発行する。メール文字列はログイン識別子に使えるが、メールの自動確認は行わずemail_verifiedを偽ってtrueにしない。本人確認済みの別連絡で初期一時パスワードを渡し、初回変更を要求する。復旧は本人を別経路で確認した管理者による一時パスワード再発行とsession失効を使う。文字列一致だけでGoogle IDを関連付けず、既存本人の再認証と明示操作を必要とする。Google連携は設定済みのclient/redirect/testerで試験し、利用資格や鍵がない状態を合格にしない。端末はブラウザー＋PKCEを維持する。

メール送信のSMTP・verify/reset email・通知配信・一般向け自己登録はPUBLICへ移す。私用版にメールパスワード復旧済みという表示を残さない。アプリはローカル利用にアカウントを要求せず、サーバー障害時も端末内再生を続ける。

招待相手との共有は相互承認を必須とし、検索で全アカウントを列挙しない。招待コードは256-bit乱数・24時間有効・1回限りで、サーバーにhashを保存する。受諾時に対象・送信者・公開範囲を表示する。拒否/解除/ブロックではAPIの読取権限と共有cacheを取り消す。デバイスserial・音源本体・サービスtokenを相手へ送らない。通報操作は私用管理者が確認できる記録へ保存し、一般向け審査窓口の開設と分ける。

Debian/x86_64、Docker、Ryzen 5 2600X、32 GBを前提にAudipp専用Composeを設計する。ホストのDebian/Docker版・空き資源・既存service・HTTPS終端・保存先は配備前に確認する。イメージは配備PRで互換性確認済みの版とdigestを固定し、latestを使わない。DB/内部API/管理consoleを無条件に外へ公開せず、私用アクセス経路もHTTPSと認証を使う。既存設定の変更・sudo・配備は本レビューで実行しない。

私用運用の初期値は、暗号化した日次DBバックアップ7世代、通常ログ7日・本文/曲名/秘密なし、削除済みデータは稼働DBと派生値から24時間以内・バックアップから7日以内に失効とする。定期処理の実装・失敗通知・復旧試験を合格条件にする。Q-09の「削除まで履歴保持」は維持し、通常履歴を7日で消す意味ではない。復旧後は別保護した削除記録を再適用してから同期/共有APIを開く。本人削除・失効を旧バックアップで復活させない。バックアップ先は別障害領域を推奨し、場所と担当が未指定なら配備準備未完了と記録する。

| 段階 | 必要な成果物 | 現段階の扱い |
|---|---|---|
| 個人間版 | 署名APKのインストール・同一鍵での更新・データ維持、招待/復旧/削除、HTTPS、バックアップ復旧、障害表示 | 完成条件に含める。署名鍵をGitへ置かない |
| PUBLIC | メール自動送信・自己登録、公開名義、Play/APKの一般向け経路、ストア提出/審査・年齢区分、公開文書・窓口・応答体制 | 完成後の発展。現在の判断待ちや完成阻害に数えない |
| サービス/依存の利用条件 | API資格、ソース利用、秘密保護、ソフトウェアのライセンスと必要な配布物 | 個人間で他人へ渡す版でも確認。PUBLICだけの項目に移さない |

**⑤の結論：** 完成目標を私用に合わせ、メール/公開名義/配布経路/問い合わせ担当の決定待ちを現在の作業から外した。残る実環境の入力は「Debian/Docker版、私用接続URL/HTTPS終端、既存構成、バックアップ先」。実機は指定ADB serialが必要。実環境に接続する前に具体の配備・操作内容を示す。


## ⑥ 原典・回答・設計・試験の横断レビュー

次を突合し、今回の変更範囲について一巡した。一般公開専用の項目は`PUBLIC`/`DEFERRED_PUBLIC`、機器依存の非適用は`NOT_APPLICABLE`、未実装/未実測は`NOT_RUN`/未完了として区別する。PUBLIC延期を機能の欠落に、未実装を対応済みに読み替えない。

| 点検 | 修正・結果 |
|---|---|
| 最新指示の優先 | 一般公開準備を現在の完成と一体にしていた要件・基本・詳細設計・回答シート・デザイン・原典注記・対応表を更新 |
| 機能の保持 | 当初の233項目から利用者が除外した2サービスの4行を廃止し、229項目へ変更後、中継/保存2行を追加し現行231項目を対応。個人間利用の再生/全音声方式/同期/フレンド/共有/日英UI等を維持。問い合わせ導線や不特定多数向け公開だけ段階を分けた |
| 認証と運用 | 旧「確認済みメール＋送信必須」「公開名義等の回答待ち」「30日保持案」を私用の招待/管理者復旧・7日保持へ更新。未確認emailを確認済みに偽らない |
| 設計選定の状態 | DSP/USB/LAN/タグの選定先とQLT基準を記述し、「方式/閾値未定」と「導入/実測待ち」を分離 |
| 参照 | 90要求と43試験ファミリー、原典231項目の参照、ローカルリンク/見出し/行番号・フェンス・空白を検査 |
| 数式の独立検査 | 5.1→2chの全64符号端点で振幅上限1、DoP搬送レートと1024 stereo frameのmarker/復元、20試行の二項確率境界を確認。アプリ/USB実装の試験とは別 |

残る課題を次の種類へ絞る。①〜⑥の項目名を繰返すだけの未完了一覧にはしない。

| 残る課題 | 次の具体的な作業 | 他の実装を止めるか |
|---|---|---|
| 外部サービスの直接再生/資格/用途制限 | 個別の正式手段と必要な申請を整理。申請や送信が必要なら対象/本文を提示。取得済みの秘密があるとは仮定しない | 当該Adapterは未完了。他領域は進める |
| yt-dlp server runtime | 私用serverで版固定したyt-dlp/Python/JS/ejsと中継/seek/保存希望分岐を検証 | 端末内蔵/API24は後段候補。現在の最低OS変更を前提にしない |
| 選定依存の導入/実測 | NDK/ABI・推移依存・libsox内部API、USB固有機器、タグ復旧、NAS/renderer相互運用とQLT試験 | 実装領域ごとに試験して完了判定 |
| 私用環境の入力 | Debian/Docker版・私用URL/HTTPS終端・既存サービス・バックアップ先、実機serial。外部デジタル/5.1ch/DSD測定環境は具体案を作って確認 | 配備・実機試験前の入力。資料/ローカル開発は進める |
| DSDアナログ安全性 | 帯域外ノイズ/過渡とDAC・負荷の許容値、測定帯域を確定して検査。現状は未確認であることを明記 | DSD経路の完成証拠に必要。耳だけで代用しない |

今回の資料レビュー一巡と、全設計の正式確定・全機能の実装合格は別である。未解決の外部手段とruntimeを残したまま全仕様を正式版に昇格しない。ローカル再生からの実装には今回の設計判断を使い、実装時に生じた差分だけ改訂する。API資格待ちや完成後の公開準備を理由に、独立したアプリ開発を停止しない。

今回、アプリコード・依存・端末・サーバーは変更していない。同じアプリコードに対する前回成功済みのassembleDebug/testDebugUnitTest/lintDebugは再実行せず、資料検査と数式モデル検査だけを追加した。実サービスへの認証・音声取得・申請/送信・購入・配備・実機再生は実施していない。

2026-10-28 Apple Music追加：現在の個人間利用版の必須対象に含める。公式経路の根拠と資格・SDK/本人認証・用途別条件は[サービス調査](07_service_feasibility.md)とDD-PROV-06へ追加し、一般公開準備を完成後とする方針は維持する。

2026-10-28範囲改訂：Amazon Music/SoundCloudは現在の実装・申請・試験・完成条件から除外。qobine/librespot/Ciderは[添付候補照合](10_provider_proposal_review.md)で確認済みと仮説を分離する。Apple独自PCMは研究候補であり、公式MusicKit backendの設計を置換したとはしない。

2026-10-28 yt-dlp方式改訂：利用者がサーバー方式を採用し、希望時のみ端末保存/それ以外streamを決定。FR-SRC-12とTEST-YTDLP-01/SAVE-01を追加。現在の原典は2行増え231項目。旧Android内蔵優先案はこの決定で置換した。
