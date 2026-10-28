# 個人間利用版の機能対応表

| 項目 | 内容 |
|---|---|
| 版・更新日 | 1.0.0-draft.6 / 2026-10-28 |
| 原典 | [audio_app_feature_list.md](../audio_app_feature_list.md)（同日改訂） |
| 完成範囲 | 原典機能を個人間利用の範囲で必須とし、一般公開準備はPUBLICへ延期。原典の適用条件・検証事項・対象外OS方針を保持 |
| 状態 | 設計契約への対応付け。実装・試験合格・許諾取得を意味しない |

親の箇条書きと字下げされた子の箇条書きをすべて別行にし、原文と要求IDを対応付ける。F-IDは原典の節番号・親番号・子番号で識別する。原典の追加・並べ替え時は既存IDの対応をレビューし、別の機能へ無断で再割当てしない。要求の詳細な設計・試験は各行のDD/TESTを参照する。

原典§13の初期対象外OSや「将来検討」は、その条件を個人間利用版の範囲規定として守る。iOS/PC版の実装を個人間利用版へ追加する意味ではない。§14等の調査は個人間利用版前の必須作業であり、調査報告のみで関連する製品機能の達成とはしない。原典が対応機器・権利・取得可能性を条件にする項目は、承認した成立環境と非対応時の表示を両方試験する。

全サービス直接再生、YouTube系yt-dlp/音声のみを維持し、検索は可能なら実装する。私用とPUBLICの適用は最新要件と[順次レビュー](09_private_completion_review.md)を優先する。一般公開専用ケースはDEFERRED_PUBLICで別管理し、私用版の未達に数えない。以下は原典231項目を保持した対応表であり、元の文言だけで一般公開の準備まで現在必須へ戻さない。

個人間利用に必要な機能が必須であるため、方式・費用・権利・試験条件が未決定の行は該当機能の完成判定上BLOCKEDである。質問と回答欄は[判断事項・回答シート](06_decisions.md)へ集約する。R調査未完了・数値閾値未確定の設計は、対応先があるだけで設計完了としない。


## 1. 音源対応・ストリーミング連携

判断・調査：Q-01/02/05/12、R-06（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-01-01](../audio_app_feature_list.md#L11) | 端末内のローカル音楽ファイルを再生する。 | FR-SRC-04 | DD-SRC / DD-AUD-04 | TEST-FORMAT-01 |
| [F-01-01.01](../audio_app_feature_list.md#L12) | FLAC、WAV、MP3、AAC、DSDなど、対応フォーマットを拡張する。 | FR-SRC-04 | DD-SRC / DD-AUD-04 | TEST-FORMAT-01 |
| [F-01-02](../audio_app_feature_list.md#L13) | 複数のストリーミングサービスを単一アプリから扱う。 | FR-SRC-09 | DD-PROV-01/02/04 | TEST-PROV-01 / TEST-RESEARCH-01 |
| [F-01-02.01](../audio_app_feature_list.md#L14) | Qobuz連携。 | FR-SRC-09 | DD-PROV-01/02/04 | TEST-PROV-01 / TEST-RESEARCH-01 |
| [F-01-02.03](../audio_app_feature_list.md#L15) | Spotify連携。 | FR-SRC-09 | DD-PROV-01/02/04 | TEST-PROV-01 / TEST-RESEARCH-01 |
| [F-01-02.05](../audio_app_feature_list.md#L16) | YouTube／YouTube Music連携。 | FR-SRC-09 | DD-PROV-01/02/04 | TEST-PROV-01 / TEST-RESEARCH-01 |
| [F-01-02.06](../audio_app_feature_list.md#L17) | Apple Music連携。 | FR-SRC-09 | DD-PROV-06 | TEST-PROV-01 / TEST-RESEARCH-01 |
| [F-01-03](../audio_app_feature_list.md#L18) | ストリーミングの接続方式をサービス別に切り替える。 | FR-SRC-08 | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| [F-01-03.01](../audio_app_feature_list.md#L19) | 許諾された音声ストリームの直接再生。 | FR-SRC-08 | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| [F-01-03.02](../audio_app_feature_list.md#L20) | 公式SDK等による外部プレーヤーのリモート制御。 | FR-SRC-08 | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| [F-01-03.03](../audio_app_feature_list.md#L21) | 公式アプリへの遷移・連携。 | FR-SRC-08 | DD-PROV / DD-QUEUE | TEST-PROV-01 |
| [F-01-04](../audio_app_feature_list.md#L22) | YouTube音源を再生時に逐次ストリーミングする方式を検証する。 | FR-SRC-11 | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 |
| [F-01-04.01](../audio_app_feature_list.md#L23) | `yt-dlp`を利用したストリーム情報取得を技術検証する。 | FR-SRC-11 | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 |
| [F-01-04.02](../audio_app_feature_list.md#L24) | 楽曲全体を事前保存せずに再生する方式を検討する。 | FR-SRC-11 | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 |
| [F-01-04.03](../audio_app_feature_list.md#L25) | ストリームURLの失効、シーク、認証、ネットワーク切断への対処を検討する。 | FR-SRC-11 | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 |
| [F-01-04.04](../audio_app_feature_list.md#L26) | 実装・配布はYouTubeの利用条件と権利上の制約を満たす範囲に限る。 | FR-SRC-11 | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 |
| [F-01-04.05](../audio_app_feature_list.md#L27) | 自前サーバーのyt-dlpで音声を取得・中継し、Audippでストリーミング再生する。 | FR-SRC-11 | DD-PROV-05 | TEST-YTDLP-01 / TEST-RESEARCH-01 |
| [F-01-04.06](../audio_app_feature_list.md#L28) | 利用者が端末保存を希望した場合だけ、Audippが受信音声を指定フォルダへ保存する。希望しない場合はストリーミングし、一時キャッシュと保存音源を分ける。 | FR-SRC-12 | DD-PROV-08 | TEST-SAVE-01 |
| [F-01-05](../audio_app_feature_list.md#L29) | ローカル音源とストリーミング音源を統合して表示・検索する。 | FR-SRC-10 | DD-PROV-01/04（検索は技術・許諾上可能なら実装） | TEST-PROV-01 |
| [F-01-06](../audio_app_feature_list.md#L30) | 音源の提供元、対応音質、再生可否、認証状態を表示する。 | FR-SRC-01, FR-SRC-08 | DD-MODEL / DD-SRC / DD-UI / DD-PROV / DD-QUEUE | TEST-FORMAT-01 / TEST-PROV-01 / TEST-UI-01 / TEST-PROV-01 |

## 2. 統合再生キュー・プレイリスト

判断・調査：R-01/05/06（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-02-01](../audio_app_feature_list.md#L34) | ローカル音源と複数サービスの楽曲を**1つの統合キュー**に登録する。 | FR-PLY-07 | DD-PROV / DD-QUEUE-04 | TEST-QUEUE-01 / TEST-PROV-01 |
| [F-02-02](../audio_app_feature_list.md#L35) | キューに登録した曲を順番に連続再生する。 | FR-PLY-01, FR-PLY-07 | DD-PLY / DD-QUEUE / DD-PROV / DD-QUEUE-04 | TEST-PLY-01/02 / TEST-QUEUE-01 / TEST-PROV-01 |
| [F-02-03](../audio_app_feature_list.md#L36) | 楽曲切り替え時に再生元を自動的に切り替える方式を検討する。 | FR-PLY-07 | DD-PROV / DD-QUEUE-04 | TEST-QUEUE-01 / TEST-PROV-01 |
| [F-02-04](../audio_app_feature_list.md#L37) | 対応可能な音源間でギャップレス／シームレスな切り替えを目指す。 | FR-PLY-08 | DD-QUEUE-05 | TEST-GAPLESS-01 |
| [F-02-05](../audio_app_feature_list.md#L38) | キュー内の各楽曲について、再生開始までの残り時間を表示する。 | FR-PLY-06 | DD-QUEUE-03 / DD-DB-01 | TEST-SCHEDULE-01 |
| [F-02-06](../audio_app_feature_list.md#L39) | 各楽曲の再生開始予定時刻を表示・随時更新する。 | FR-PLY-06 | DD-QUEUE-03 / DD-DB-01 | TEST-SCHEDULE-01 |
| [F-02-07](../audio_app_feature_list.md#L40) | キュー全体・アルバムの終了予定時刻や残り時間を表示する。 | FR-PLY-06 | DD-QUEUE-03 / DD-DB-01 | TEST-SCHEDULE-01 |
| [F-02-08](../audio_app_feature_list.md#L41) | キュー内に各曲の音源提供元、音声形式、再生時間を表示する。 | FR-PLY-09 | DD-QUEUE-03 / DD-UI | TEST-SCHEDULE-01 / TEST-QUEUE-01 |
| [F-02-09](../audio_app_feature_list.md#L42) | プレイリストを作成・管理する。 | FR-PLY-05 | DD-DB-01/02 | TEST-PLAYLIST-01 |
| [F-02-10](../audio_app_feature_list.md#L43) | キューのリピート（オフ／全曲／1曲）を設定する。 | FR-PLY-01 | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| [F-02-11](../audio_app_feature_list.md#L44) | ギャップレス再生に対応する。 | FR-PLY-08 | DD-QUEUE-05 | TEST-GAPLESS-01 |
| [F-02-12](../audio_app_feature_list.md#L45) | 外部再生方式では、切り替え・ギャップレスなどの制限を明示する。 | FR-PLY-07, FR-PLY-08 | DD-PROV / DD-QUEUE-04 / DD-QUEUE-05 | TEST-QUEUE-01 / TEST-PROV-01 / TEST-GAPLESS-01 |

## 3. ローカルライブラリ・メタデータ

判断・調査：R-01/07（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-03-01](../audio_app_feature_list.md#L49) | ユーザーが音楽フォルダを指定する。 | FR-SRC-02 | DD-SRC / DD-DB | TEST-LIB-01/02 |
| [F-03-02](../audio_app_feature_list.md#L50) | 指定フォルダ内の音楽ファイルを収集し、ライブラリ化する。 | FR-SRC-02 | DD-SRC / DD-DB | TEST-LIB-01/02 |
| [F-03-03](../audio_app_feature_list.md#L51) | ファイル・フォルダ構造、タグ、ディスク番号などからアルバムを自動で構築する。 | FR-SRC-05 | DD-SRC / DD-DB | TEST-LIB-01/02 |
| [F-03-04](../audio_app_feature_list.md#L52) | 曲名・アーティスト・アルバム・年・ジャンル・音声形式などで検索する。 | FR-SRC-03 | DD-SRC / DD-DB | TEST-LIB-01/02 |
| [F-03-05](../audio_app_feature_list.md#L53) | MusicBrainz／AcoustIDなどを利用したメタデータの照合・補完を行う。 | FR-SRC-06 | DD-SRC-04 | TEST-META-01 |
| [F-03-06](../audio_app_feature_list.md#L54) | 不適切なメタデータの修正・整理に対応する。 | FR-SRC-06 | DD-SRC-04 | TEST-META-01 |
| [F-03-07](../audio_app_feature_list.md#L55) | アルバムアートを取得・管理・差し替えする。 | FR-SRC-06 | DD-SRC-04 | TEST-META-01 |
| [F-03-08](../audio_app_feature_list.md#L56) | 高解像度ジャケット画像を表示する。 | FR-SRC-05 | DD-SRC / DD-DB | TEST-LIB-01/02 |
| [F-03-09](../audio_app_feature_list.md#L57) | 重複する音源ファイルを検出する。 | FR-SRC-07 | DD-SRC-03/04 / DD-DB-01 | TEST-META-01 / TEST-SCHEDULE-01 |
| [F-03-10](../audio_app_feature_list.md#L58) | 同一楽曲のMP3／FLAC／ハイレゾ／DSDなど複数バージョンを関連付ける。 | FR-SRC-07 | DD-SRC-03/04 / DD-DB-01 | TEST-META-01 / TEST-SCHEDULE-01 |
| [F-03-11](../audio_app_feature_list.md#L59) | 同一楽曲のマスタリング違い・収録時間の違いを識別・管理する。 | FR-SRC-07 | DD-SRC-03/04 / DD-DB-01 | TEST-META-01 / TEST-SCHEDULE-01 |
| [F-03-12](../audio_app_feature_list.md#L60) | 異なる音源提供元の同一楽曲を関連付けて比較する（サービス利用条件の範囲内）。 | FR-SRC-07, FR-SRC-10 | DD-SRC-03/04 / DD-DB-01 / DD-PROV-01/04（検索は技術・許諾上可能なら実装） | TEST-META-01 / TEST-SCHEDULE-01 / TEST-PROV-01 |
| [F-03-13](../audio_app_feature_list.md#L61) | 楽曲の音声形式・ビット深度・サンプルレートを確認できるようにする。 | FR-AUD-01 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |

## 4. PCM・DSD再生エンジン

判断・調査：Q-03/04/06、R-02/03/05（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-04-01](../audio_app_feature_list.md#L65) | Bit-perfect再生をサポートする。 | FR-AUD-03 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-04-02](../audio_app_feature_list.md#L66) | 実際の出力経路がBit-perfect条件を満たすか検証・表示する。 | FR-AUD-03, FR-OBS-01 | DD-AUD / DD-DSP / DD-OBS | TEST-AUD-01/02/03 / TEST-VIS-01 / TEST-AUD-01 |
| [F-04-03](../audio_app_feature_list.md#L67) | PCMのアップサンプリングを行う。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-03.01](../audio_app_feature_list.md#L68) | 2倍・4倍などの倍率指定。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-03.02](../audio_app_feature_list.md#L69) | 出力サンプルレートの指定。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-04](../audio_app_feature_list.md#L70) | ソフトウェア側のリサンプリングを省くNOS相当モードを用意する。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-04.01](../audio_app_feature_list.md#L71) | DAC内部のNOSモードとの違いを明示する。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-05](../audio_app_feature_list.md#L72) | リサンプラーを選択する（SoX系・libsamplerate系などを候補とする）。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-06](../audio_app_feature_list.md#L73) | リサンプリングの品質と処理負荷を調整する。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-07](../audio_app_feature_list.md#L74) | デジタルフィルターを選択する。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-07.01](../audio_app_feature_list.md#L75) | Linear Phase。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-07.02](../audio_app_feature_list.md#L76) | Minimum Phase。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-07.03](../audio_app_feature_list.md#L77) | Apodizingなど。 | FR-AUD-04 | DD-AUD-04/05 / DD-DSP | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-08](../audio_app_feature_list.md#L78) | PCMからDSDへのリアルタイム変換・アップサンプリングを行う。 | FR-AUD-05 | DD-AUD-04/05 | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-08.01](../audio_app_feature_list.md#L79) | 対応する場合にDSD64／DSD128／DSD256などを選択する。 | FR-AUD-05 | DD-AUD-04/05 | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-09](../audio_app_feature_list.md#L80) | DSDからPCMへの変換を行う。 | FR-AUD-05 | DD-AUD-04/05 | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-10](../audio_app_feature_list.md#L81) | Native DSDでのダイレクト再生に対応する（機器・ドライバーが対応する場合）。 | FR-AUD-05 | DD-AUD-04/05 | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-11](../audio_app_feature_list.md#L82) | DoP（DSD over PCM）再生に対応する。 | FR-AUD-05 | DD-AUD-04/05 | TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-12](../audio_app_feature_list.md#L83) | 音源形式と出力機器に応じて適切な再生方式を選択する。 | FR-AUD-02, FR-AUD-05 | DD-AUD / DD-DSP / DD-AUD-04/05 | TEST-AUD-01/02/03 / TEST-AUD-02 / TEST-FORMAT-02 |
| [F-04-13](../audio_app_feature_list.md#L84) | FLAC等のデコードからDAC出力までの信号処理を管理する。 | FR-AUD-01, FR-AUD-07 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |

## 5. DSP・音質調整

判断・調査：R-02/05（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-05-01](../audio_app_feature_list.md#L88) | ReplayGainに対応する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-01.01](../audio_app_feature_list.md#L89) | Track Gain／Album Gain切り替え。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-01.02](../audio_app_feature_list.md#L90) | Preamp設定。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-01.03](../audio_app_feature_list.md#L91) | クリッピング防止。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-02](../audio_app_feature_list.md#L92) | EBU R128／LUFSに基づくラウドネス正規化に対応する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-03](../audio_app_feature_list.md#L93) | Parametric EQを搭載する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-03.01](../audio_app_feature_list.md#L94) | 周波数・ゲイン・Q値の詳細指定。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-04](../audio_app_feature_list.md#L95) | Convolution／FIR補正に対応する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-04.01](../audio_app_feature_list.md#L96) | インパルス応答の適用。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-04.02](../audio_app_feature_list.md#L97) | イヤホン・ヘッドホン補正。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-05](../audio_app_feature_list.md#L98) | Crossfeedを搭載する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-06](../audio_app_feature_list.md#L99) | 左右チャンネルのゲインを個別調整する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-07](../audio_app_feature_list.md#L100) | 左右チャンネルの入れ替え、モノラル化などに対応する。 | FR-AUD-06 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-08](../audio_app_feature_list.md#L101) | DSP適用時のHeadroomを管理する。 | FR-AUD-07 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-09](../audio_app_feature_list.md#L102) | デジタルクリッピングを防止・検知する。 | FR-AUD-07 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-10](../audio_app_feature_list.md#L103) | DSPの有効／無効状態を分かりやすく表示する。 | FR-AUD-01, FR-AUD-07 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-05-11](../audio_app_feature_list.md#L104) | Bit-perfect再生と音声データを変更するDSP処理の排他関係を明示する。 | FR-AUD-07 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |

## 6. オーディオ出力先・低レベル出力設定

判断・調査：Q-03/04、R-03/05（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-06-01](../audio_app_feature_list.md#L108) | 出力先をアプリから選択・切り替えできるようにする。 | FR-DEV-03, FR-NET-03 | DD-AUD-04 / DD-DEV-03 / DD-NET | TEST-DEVICE-01 / TEST-AUD-02 / TEST-NET-01 |
| [F-06-01.01](../audio_app_feature_list.md#L109) | 端末内蔵スピーカー。 | FR-DEV-03, FR-NET-03 | DD-AUD-04 / DD-DEV-03 / DD-NET | TEST-DEVICE-01 / TEST-AUD-02 / TEST-NET-01 |
| [F-06-01.02](../audio_app_feature_list.md#L110) | 端末の3.5mmイヤホン端子／内蔵DAC。 | FR-DEV-03, FR-NET-03 | DD-AUD-04 / DD-DEV-03 / DD-NET | TEST-DEVICE-01 / TEST-AUD-02 / TEST-NET-01 |
| [F-06-01.03](../audio_app_feature_list.md#L111) | USB DAC。 | FR-DEV-03, FR-NET-03 | DD-AUD-04 / DD-DEV-03 / DD-NET | TEST-DEVICE-01 / TEST-AUD-02 / TEST-NET-01 |
| [F-06-01.04](../audio_app_feature_list.md#L112) | Bluetoothオーディオ機器。 | FR-DEV-03, FR-NET-03 | DD-AUD-04 / DD-DEV-03 / DD-NET | TEST-DEVICE-01 / TEST-AUD-02 / TEST-NET-01 |
| [F-06-01.05](../audio_app_feature_list.md#L113) | ネットワークレンダラー（MPD、UPnP／DLNA、OpenHomeなど）。 | FR-DEV-03, FR-NET-03 | DD-AUD-04 / DD-DEV-03 / DD-NET | TEST-DEVICE-01 / TEST-AUD-02 / TEST-NET-01 |
| [F-06-02](../audio_app_feature_list.md#L114) | 出力バックエンドを選択する。 | FR-AUD-02, FR-AUD-03 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-06-02.01](../audio_app_feature_list.md#L115) | Android標準オーディオ経路。 | FR-AUD-02, FR-AUD-03 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-06-02.02](../audio_app_feature_list.md#L116) | 対応端末のDirect／Bit-perfect経路。 | FR-AUD-02, FR-AUD-03 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-06-02.03](../audio_app_feature_list.md#L117) | 独自USBオーディオバックエンド（高度な機能として検証）。 | FR-AUD-02, FR-AUD-03 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-06-03](../audio_app_feature_list.md#L118) | PCM出力のサンプルレート・ビット深度・チャンネル数を設定する。 | FR-DEV-03 | DD-AUD-04 / DD-DEV-03 | TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-04](../audio_app_feature_list.md#L119) | DSD出力方式・レートを設定する。 | FR-AUD-05, FR-DEV-03 | DD-AUD-04/05 / DD-AUD-04 / DD-DEV-03 | TEST-AUD-02 / TEST-FORMAT-02 / TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-05](../audio_app_feature_list.md#L120) | USBオーディオ出力のバッファを設定する。 | FR-DEV-03 | DD-AUD-04 / DD-DEV-03 | TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-05.01](../audio_app_feature_list.md#L121) | ソフトウェアバッファ量。 | FR-DEV-03 | DD-AUD-04 / DD-DEV-03 | TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-05.02](../audio_app_feature_list.md#L122) | プリバッファ量。 | FR-DEV-03 | DD-AUD-04 / DD-DEV-03 | TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-05.03](../audio_app_feature_list.md#L123) | 利用できる場合のUSB転送関連パラメーター。 | FR-DEV-03 | DD-AUD-04 / DD-DEV-03 | TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-06](../audio_app_feature_list.md#L124) | 出力遅延・バッファ残量・アンダーランを監視する。 | FR-OBS-04 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-06-07](../audio_app_feature_list.md#L125) | システム音量・ソフトウェア音量・デジタルゲインを用途別に管理する。 | FR-AUD-08, FR-DEV-03 | DD-AUD / DD-DSP / DD-AUD-04 / DD-DEV-03 | TEST-AUD-01/02/03 / TEST-DEVICE-01 / TEST-AUD-02 |
| [F-06-08](../audio_app_feature_list.md#L126) | 出力先の接続・切断時に安全に再生状態を切り替える。 | FR-PLY-03, FR-DEV-05 | DD-PLY / DD-QUEUE / DD-DEV-02/03 | TEST-PLY-01/02 / TEST-DEVICE-01 / TEST-AUD-03 |
| [F-06-09](../audio_app_feature_list.md#L127) | 出力先の自動選択・優先順位・前回使用設定の復元に対応する。 | FR-DEV-05 | DD-DEV-02/03 | TEST-DEVICE-01 / TEST-AUD-03 |
| [F-06-10](../audio_app_feature_list.md#L128) | USB機器の製品名、VID／PID、対応フォーマット、接続状態を確認する。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-11](../audio_app_feature_list.md#L129) | デジタル接続されたオーディオ機器について、取得可能な機器情報を自動検出・表示する。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-11.01](../audio_app_feature_list.md#L130) | USBオーディオ機器のメーカー名・製品名・識別情報（取得できる場合）。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-11.02](../audio_app_feature_list.md#L131) | 接続インターフェース、対応サンプルレート・ビット深度・チャンネル数などの能力情報。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-11.03](../audio_app_feature_list.md#L132) | 実際の出力先・適用中の音声フォーマット・接続状態。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-11.04](../audio_app_feature_list.md#L133) | 機器が公開する場合に限り、ハードウェア音量・ゲイン・動作モード・その他の機器固有情報。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-11.05](../audio_app_feature_list.md#L134) | 自動取得できない項目は「不明」または手動登録とし、推定値と実測値を区別する。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |
| [F-06-12](../audio_app_feature_list.md#L135) | OSに要求した出力先と実際の出力先を区別して表示する。 | FR-AUD-01, FR-DEV-01 | DD-AUD / DD-DSP / DD-DEV-01/02 | TEST-AUD-01/02/03 / TEST-DEVICE-01 / TEST-AUD-03 |
| [F-06-13](../audio_app_feature_list.md#L136) | 出力経路の変更・不具合をログとして記録する。 | FR-OBS-06 | DD-OBS-03 | TEST-DIAG-01 / TEST-SEC-01 |

## 7. DAC・DAP・アンプ・イヤホン別プロファイル

判断・調査：Q-03/11、R-03/04（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-07-01](../audio_app_feature_list.md#L140) | 使用するDAC、DAP、アンプ、イヤホン、ヘッドホンを登録・一覧管理する。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-02](../audio_app_feature_list.md#L141) | デバイスごとに再生設定を保存する。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-03](../audio_app_feature_list.md#L142) | DAC＋アンプ＋イヤホン／ヘッドホンの**組み合わせ**を1つのプロファイルとして保存する。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-04](../audio_app_feature_list.md#L143) | デバイス接続時に対応プロファイルへ自動切り替えする。 | FR-DEV-05 | DD-DEV-02/03 | TEST-DEVICE-01 / TEST-AUD-03 |
| [F-07-05](../audio_app_feature_list.md#L144) | プロファイルごとに以下を保存する。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-05.01](../audio_app_feature_list.md#L145) | 出力先・出力方式。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-05.02](../audio_app_feature_list.md#L146) | PCM／DSDの出力設定。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-05.03](../audio_app_feature_list.md#L147) | リサンプリング・フィルター・NOS関連設定。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-05.04](../audio_app_feature_list.md#L148) | ReplayGain、DSP、EQ、Convolution、Crossfeed。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-05.05](../audio_app_feature_list.md#L149) | バッファ設定。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-05.06](../audio_app_feature_list.md#L150) | デジタルゲイン・初期音量・安全音量。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-06](../audio_app_feature_list.md#L151) | 高感度イヤホン向けに急激な音量上昇を防止する。 | FR-AUD-08 | DD-AUD / DD-DSP | TEST-AUD-01/02/03 |
| [F-07-07](../audio_app_feature_list.md#L152) | USB DAC接続時に必要に応じて再生を停止し、音量・設定を確認する。 | FR-DEV-05 | DD-DEV-02/03 | TEST-DEVICE-01 / TEST-AUD-03 |
| [F-07-08](../audio_app_feature_list.md#L153) | アンプの物理ゲインなど、アプリが制御できない項目をメモ・警告として管理する。 | FR-AUD-08, FR-DEV-04 | DD-AUD / DD-DSP / DD-DEV-03 / DD-DB | TEST-AUD-01/02/03 / TEST-DEVICE-01 |
| [F-07-09](../audio_app_feature_list.md#L154) | デバイス別の対応フォーマット・動作確認結果を記録する。 | FR-PLT-01 | DD-TEST-03/04 | TEST-COMP-01 / TEST-FORMAT-02 |
| [F-07-10](../audio_app_feature_list.md#L155) | 自動取得したデバイス情報を機器プロファイルの新規作成・更新に活用する。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-07-11](../audio_app_feature_list.md#L156) | 機器仕様・対応状況のデータベースを構築する。 | FR-DEV-06 | DD-DEV-03 / DD-CLOUD-03 | TEST-SHARE-01 |
| [F-07-12](../audio_app_feature_list.md#L157) | 機器プロファイルや動作確認結果をユーザー間で共有する。 | FR-DEV-06 | DD-DEV-03 / DD-CLOUD-03 | TEST-SHARE-01 |
| [F-07-13](../audio_app_feature_list.md#L158) | メーカー公称仕様、ユーザー申告情報、アプリの実測結果を区別する。 | FR-DEV-06 | DD-DEV-03 / DD-CLOUD-03 | TEST-SHARE-01 |

## 8. ネットワークオーディオ

判断・調査：Q-03、R-07（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-08-01](../audio_app_feature_list.md#L162) | NASなどに保存された楽曲を再生する。 | FR-NET-01 | DD-NET | TEST-NET-01 |
| [F-08-02](../audio_app_feature_list.md#L163) | SMB／WebDAV経由の音楽ライブラリに対応する。 | FR-NET-01 | DD-NET | TEST-NET-01 |
| [F-08-03](../audio_app_feature_list.md#L164) | UPnP／DLNA経由で音源を参照・再生する。 | FR-NET-01, FR-NET-02 | DD-NET | TEST-NET-01 |
| [F-08-04](../audio_app_feature_list.md#L165) | OpenHome対応レンダラーを操作する。 | FR-NET-02 | DD-NET | TEST-NET-01 |
| [F-08-05](../audio_app_feature_list.md#L166) | MPDサーバーを操作する。 | FR-NET-02 | DD-NET | TEST-NET-01 |
| [F-08-06](../audio_app_feature_list.md#L167) | Android端末をネットワークプレーヤーのリモコンとして利用する。 | FR-NET-02 | DD-NET | TEST-NET-01 |
| [F-08-07](../audio_app_feature_list.md#L168) | 再生先としてネットワークレンダラーを選択する。 | FR-NET-03 | DD-NET | TEST-NET-01 |
| [F-08-08](../audio_app_feature_list.md#L169) | BubbleUPnP、MPD、upmpdcliなど既存システムとの相互運用を検討する。 | FR-NET-04 | DD-NET | TEST-NET-01 |
| [F-08-09](../audio_app_feature_list.md#L170) | ローカル再生と遠隔レンダラーでの再生を明確に切り替える。 | FR-NET-03 | DD-NET | TEST-NET-01 |

## 9. 再生履歴・ランキング・アカウント

判断・調査：Q-06〜12、R-04/05/06（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-09-01](../audio_app_feature_list.md#L174) | ユーザーアカウントを作成・管理する。 | FR-USE-03 | DD-CLOUD-01/02/04 | TEST-ACC-01 / TEST-DATA-01 / TEST-SYNC-01 |
| [F-09-02](../audio_app_feature_list.md#L175) | 再生履歴・各種設定を端末間で同期する。 | FR-USE-03 | DD-CLOUD-01/02/04 | TEST-ACC-01 / TEST-DATA-01 / TEST-SYNC-01 |
| [F-09-03](../audio_app_feature_list.md#L176) | 再生回数・再生時間を集計する。 | FR-USE-01 | DD-HIST-01/02 | TEST-HIST-01 |
| [F-09-04](../audio_app_feature_list.md#L177) | ランキングを期間別に表示する。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-04.01](../audio_app_feature_list.md#L178) | 日別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-04.02](../audio_app_feature_list.md#L179) | 週別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-04.03](../audio_app_feature_list.md#L180) | 月別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-04.04](../audio_app_feature_list.md#L181) | 年別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-04.05](../audio_app_feature_list.md#L182) | 累計。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-05](../audio_app_feature_list.md#L183) | ランキングを対象別に表示する。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-05.01](../audio_app_feature_list.md#L184) | 楽曲別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-05.02](../audio_app_feature_list.md#L185) | アーティスト別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-05.03](../audio_app_feature_list.md#L186) | アルバム別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-05.04](../audio_app_feature_list.md#L187) | ジャンル別。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-05.05](../audio_app_feature_list.md#L188) | 全体ランキング。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-06](../audio_app_feature_list.md#L189) | 音声形式別（FLAC、DSD、ハイレゾなど）の再生傾向を分析する。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-07](../audio_app_feature_list.md#L190) | 再生時間帯・曜日・端末・音響機器別に聴取傾向を分析する。 | FR-USE-02 | DD-HIST-05 | TEST-STATS-01 |
| [F-09-08](../audio_app_feature_list.md#L191) | フレンド機能を搭載する。 | FR-USE-04 | DD-CLOUD-03/04 | TEST-ACC-01 / TEST-STATS-01 |
| [F-09-09](../audio_app_feature_list.md#L192) | フレンド間でランキングや再生傾向を比較する。 | FR-USE-04 | DD-CLOUD-03/04 | TEST-ACC-01 / TEST-STATS-01 |
| [F-09-10](../audio_app_feature_list.md#L193) | 共通して好きな楽曲・アーティストを確認する。 | FR-USE-04 | DD-CLOUD-03/04 | TEST-ACC-01 / TEST-STATS-01 |
| [F-09-11](../audio_app_feature_list.md#L194) | 再生履歴・プロフィールの公開範囲を設定する。 | FR-USE-04 | DD-CLOUD-03/04 | TEST-ACC-01 / TEST-STATS-01 |
| [F-09-12](../audio_app_feature_list.md#L195) | Last.fm Scrobbleに対応する。 | FR-USE-05 | DD-HIST-04 / DD-PROV-01 | TEST-LASTFM-01（API資格・実サービス試験は未完了） |
| [F-09-12.01](../audio_app_feature_list.md#L196) | 再生元サービスごとのScrobble ON／OFF。 | FR-USE-05 | DD-HIST-04 / DD-PROV-01 | TEST-LASTFM-01（API資格・実サービス試験は未完了） |
| [F-09-12.02](../audio_app_feature_list.md#L197) | 再生判定条件に基づく送信。 | FR-USE-05 | DD-HIST-04 / DD-PROV-01 | TEST-LASTFM-01（API資格・実サービス試験は未完了） |
| [F-09-12.03](../audio_app_feature_list.md#L198) | 二重Scrobbleの防止。 | FR-USE-05 | DD-HIST-04 / DD-PROV-01 | TEST-LASTFM-01（API資格・実サービス試験は未完了） |

## 10. レコメンド・自動プレイリスト

判断・調査：Q-08/11、R-05（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-10-01](../audio_app_feature_list.md#L202) | ユーザーの好みに合わせておすすめ楽曲を提案する。 | FR-USE-06 | DD-HIST-05 | TEST-REC-01 |
| [F-10-02](../audio_app_feature_list.md#L203) | 再生履歴・好み・時間帯・ジャンルに基づき推薦する。 | FR-USE-06 | DD-HIST-05 | TEST-REC-01 |
| [F-10-03](../audio_app_feature_list.md#L204) | メタデータを利用した類似楽曲の推薦を行う。 | FR-USE-06 | DD-HIST-05 | TEST-REC-01 |
| [F-10-04](../audio_app_feature_list.md#L205) | メタデータが充実している楽曲を優先して推薦する方式を検討する。 | FR-USE-06 | DD-HIST-05 | TEST-REC-01 |
| [F-10-05](../audio_app_feature_list.md#L206) | 自動プレイリスト（スマートプレイリスト）を作成する。 | FR-USE-06 | DD-HIST-05 | TEST-REC-01 |
| [F-10-06](../audio_app_feature_list.md#L207) | ユーザーの聴取傾向とライブラリ情報を組み合わせて推薦する。 | FR-USE-06 | DD-HIST-05 | TEST-REC-01 |

## 11. 音声分析・測定・診断

判断・調査：Q-03/04、R-02/03/05（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-11-01](../audio_app_feature_list.md#L211) | Signal Path Inspectorを搭載する。 | FR-OBS-01 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-01.01](../audio_app_feature_list.md#L212) | 入力ソース・コーデック・サンプルレート・ビット深度を表示する。 | FR-OBS-01 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-01.02](../audio_app_feature_list.md#L213) | デコード、DSP、リサンプリング、出力までの信号経路を表示する。 | FR-OBS-01 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-01.03](../audio_app_feature_list.md#L214) | Bit-perfect状態やDSPバイパス状態を表示する。 | FR-OBS-01 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-01.04](../audio_app_feature_list.md#L215) | 検証できた値と推定／申告値を区別する。 | FR-OBS-01 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-02](../audio_app_feature_list.md#L216) | FFTスペクトラムアナライザーを表示する。 | FR-OBS-02 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-03](../audio_app_feature_list.md#L217) | スペクトログラムを表示する。 | FR-OBS-02 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04](../audio_app_feature_list.md#L218) | **複数種類のVisualizer（音楽ビジュアライザー）**を搭載し、切り替えられるようにする。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.01](../audio_app_feature_list.md#L219) | 波形表示（Waveform）・オシロスコープ表示。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.02](../audio_app_feature_list.md#L220) | 周波数帯域別のバー型／曲線型スペクトラム表示。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.03](../audio_app_feature_list.md#L221) | VUメーター・ピークメーター。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.04](../audio_app_feature_list.md#L222) | ステレオのベクトルスコープ／位相・相関表示。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.05](../audio_app_feature_list.md#L223) | ジャケット画像・楽曲再生状態に連動するアニメーション型Visualizer。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.06](../audio_app_feature_list.md#L224) | フルスクリーン表示とレイアウト・色・感度・更新頻度などのカスタマイズ。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.07](../audio_app_feature_list.md#L225) | Visualizerの描画負荷（CPU／GPU）やバッテリー消費に応じた品質設定。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-04.08](../audio_app_feature_list.md#L226) | 音声データを取得できない外部アプリ再生では利用制限を明示する。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-05](../audio_app_feature_list.md#L227) | ピークレベル、RMS、LUFS、True Peakを表示する。 | FR-OBS-04 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-06](../audio_app_feature_list.md#L228) | 音源の解析値とDSP適用後の解析値を区別する。 | FR-OBS-07 | DD-OBS-02 | TEST-TAP-01 |
| [F-11-07](../audio_app_feature_list.md#L229) | ABXブラインドテスト機能を搭載する。 | FR-OBS-05 | DD-HIST-03 | TEST-ABX-01 |
| [F-11-07.01](../audio_app_feature_list.md#L230) | PCM／DSD、リサンプラー、デジタルフィルターなどを比較する。 | FR-OBS-05 | DD-HIST-03 | TEST-ABX-01 |
| [F-11-07.02](../audio_app_feature_list.md#L231) | 音量差を補正し、テスト条件を固定する。 | FR-OBS-05 | DD-HIST-03 | TEST-ABX-01 |
| [F-11-07.03](../audio_app_feature_list.md#L232) | 試行回数・正答率・結果を記録する。 | FR-OBS-05 | DD-HIST-03 | TEST-ABX-01 |
| [F-11-08](../audio_app_feature_list.md#L233) | CPU負荷・音声処理負荷を監視する。 | FR-OBS-04 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-09](../audio_app_feature_list.md#L234) | バッテリー消費やDSPによる電力負荷を分析する。 | FR-OBS-04 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-10](../audio_app_feature_list.md#L235) | 再生バッファ状態・アンダーラン回数を監視する。 | FR-OBS-04 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-11-11](../audio_app_feature_list.md#L236) | 再生の不具合を診断できるログを出力する。 | FR-OBS-06 | DD-OBS-03 | TEST-DIAG-01 / TEST-SEC-01 |
| [F-11-12](../audio_app_feature_list.md#L237) | 据置向けの高音質モードと、低負荷なポータブルモードを切り替える。 | FR-AUD-10 | DD-DSP-04 | TEST-PRESET-01 |

## 12. UI・操作性

判断・調査：Q-13、R-07（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-12-01](../audio_app_feature_list.md#L241) | ダークテーマを搭載する。 | FR-UI-03 | DD-UI | TEST-UI-01 |
| [F-12-02](../audio_app_feature_list.md#L242) | カスタムテーマを利用できるようにする。 | FR-UI-04 | DD-UI-03 | TEST-THEME-01 / TEST-WIDGET-01 / TEST-UI-01 |
| [F-12-03](../audio_app_feature_list.md#L243) | Androidホーム画面ウィジェットを搭載する。 | FR-UI-04 | DD-UI-03 | TEST-THEME-01 / TEST-WIDGET-01 / TEST-UI-01 |
| [F-12-04](../audio_app_feature_list.md#L244) | 通知領域・ロック画面などから再生操作できるようにする。 | FR-PLY-02 | DD-PLY / DD-QUEUE | TEST-PLY-01/02 |
| [F-12-05](../audio_app_feature_list.md#L245) | プレーヤー画面から出力先を素早く切り替えられるようにする。 | FR-UI-01, FR-DEV-03 | DD-UI / DD-AUD-04 / DD-DEV-03 | TEST-UI-01 / TEST-DEVICE-01 / TEST-AUD-02 |
| [F-12-06](../audio_app_feature_list.md#L246) | 通常設定画面と高度なオーディオ設定画面を分ける。 | FR-UI-04 | DD-UI-03 | TEST-THEME-01 / TEST-WIDGET-01 / TEST-UI-01 |
| [F-12-07](../audio_app_feature_list.md#L247) | 再生画面に音源提供元、音質、出力先、再生方式を表示する。 | FR-AUD-01, FR-UI-01 | DD-AUD / DD-DSP / DD-UI | TEST-AUD-01/02/03 / TEST-UI-01 |
| [F-12-08](../audio_app_feature_list.md#L248) | キュー画面に開始予測・楽曲形式・提供元を表示する。 | FR-PLY-06, FR-PLY-09 | DD-QUEUE-03 / DD-DB-01 / DD-QUEUE-03 / DD-UI | TEST-SCHEDULE-01 / TEST-SCHEDULE-01 / TEST-QUEUE-01 |
| [F-12-09](../audio_app_feature_list.md#L249) | プロファイル一覧からオーディオ環境を選択する。 | FR-DEV-04 | DD-DEV-03 / DD-DB | TEST-DEVICE-01 |
| [F-12-10](../audio_app_feature_list.md#L250) | 再生状態・接続状態・設定の適用可否を分かりやすく表示する。 | FR-UI-02 | DD-UI | TEST-UI-01 |
| [F-12-11](../audio_app_feature_list.md#L251) | Visualizerを再生画面・専用画面・フルスクリーンで表示する。 | FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 |
| [F-12-12](../audio_app_feature_list.md#L252) | 接続中のデジタルオーディオ機器の自動取得情報を詳細画面で表示する。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 |

## 13. 対応プラットフォーム・将来展開

判断・調査：Q-03、R-01（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-13-01](../audio_app_feature_list.md#L256) | **初期開発の対象はAndroidスマートフォンとAndroid搭載DAPに限定する。** | FR-PLT-01, NFR-COMP-01 | DD-TEST-03/04 / DD-TEST-03 | TEST-COMP-01 / TEST-FORMAT-02 / TEST-COMP-01 |
| [F-13-01.01](../audio_app_feature_list.md#L257) | 一般的なAndroid端末と、メーカー独自の音声機能を持つAndroid搭載DAPの両方で動作させることを目指す。 | FR-PLT-01, NFR-COMP-01 | DD-TEST-03/04 / DD-TEST-03 | TEST-COMP-01 / TEST-FORMAT-02 / TEST-COMP-01 |
| [F-13-01.02](../audio_app_feature_list.md#L258) | 対応Androidバージョン、CPUアーキテクチャ、DAP固有仕様は実機検証に基づいて決定する。 | FR-PLT-01, NFR-COMP-01 | DD-TEST-03/04 / DD-TEST-03 | TEST-COMP-01 / TEST-FORMAT-02 / TEST-COMP-01 |
| [F-13-01.03](../audio_app_feature_list.md#L259) | Android未搭載のDAPは初期対象に含めない（ネットワーク再生機器としての連携は別途検討する）。 | FR-PLT-01, NFR-COMP-01 | DD-TEST-03/04 / DD-TEST-03 | TEST-COMP-01 / TEST-FORMAT-02 / TEST-COMP-01 |
| [F-13-02](../audio_app_feature_list.md#L260) | **クロスプラットフォーム対応は初期開発の必須要件としない。** | FR-PLT-02 | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| [F-13-03](../audio_app_feature_list.md#L261) | Android版が十分に完成・安定した後、開発リソースに余裕がある場合に限り、**iOS対応を検討する。** | FR-PLT-02 | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| [F-13-03.01](../audio_app_feature_list.md#L262) | iOSで利用できるオーディオ出力API、USB DAC制御、Bit-perfect／DSD対応、バックグラウンド再生などを事前評価する。 | FR-PLT-02 | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| [F-13-03.02](../audio_app_feature_list.md#L263) | iOS対応を確約せず、機能差や実装負荷を確認して採否を決める。 | FR-PLT-02 | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| [F-13-04](../audio_app_feature_list.md#L264) | Windows・Linux・macOSへの対応は**現時点では計画しない**。将来の要望と開発余力に応じて再検討する。 | FR-PLT-02 | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| [F-13-05](../audio_app_feature_list.md#L265) | 将来の移植を不必要に難しくしない範囲で、音源管理・キュー・再生統計などのロジックと、Android固有のオーディオ制御を分離する。 | NFR-MAINT-01 | DD-PKG / DD-TEST | TEST-GOV-01 |
| [F-13-06](../audio_app_feature_list.md#L266) | アカウントを通じた再生履歴・設定・機器プロファイルの同期は、まずAndroid端末間を対象とする。 | FR-USE-03 | DD-CLOUD-01/02/04 | TEST-ACC-01 / TEST-DATA-01 / TEST-SYNC-01 |

## 14. 実装上の検討事項（未確定機能を含む）

判断・調査：R-01〜07（06_decisions.md参照）。

| F-ID・原典 | 原文 | 要求ID | 設計契約 | 試験ファミリー・適用 |
|---|---|---|---|---|
| [F-14-01](../audio_app_feature_list.md#L270) | Qobuzの直接音声再生について、正規API利用条件・認証・再生権限を確認する。 | FR-SRC-09 | DD-PROV-01/02/04 | TEST-PROV-01 / TEST-RESEARCH-01 / TEST-RESEARCH-01 |
| [F-14-03](../audio_app_feature_list.md#L271) | Spotifyの統合キュー・統計・外部制御について、公式SDKと開発者ポリシーへの適合性を確認する。 | FR-SRC-08, FR-SRC-09 | DD-PROV / DD-QUEUE / DD-PROV-01/02/04 | TEST-PROV-01 / TEST-PROV-01 / TEST-RESEARCH-01 / TEST-RESEARCH-01 |
| [F-14-05](../audio_app_feature_list.md#L272) | YouTube／YouTube Musicおよび`yt-dlp`連携について、技術的動作とサービス利用条件・配布可否を分けて確認する。 | FR-SRC-11 | DD-PROV-04/05 | TEST-RESEARCH-01 / TEST-PROV-01 / TEST-RESEARCH-01 |
| [F-14-06](../audio_app_feature_list.md#L273) | 外部サービス間の完全なギャップレス再生が不可能な場合の挙動を定義する。 | FR-PLY-08 | DD-QUEUE-05 | TEST-GAPLESS-01 / TEST-RESEARCH-01 |
| [F-14-07](../audio_app_feature_list.md#L274) | Android端末・DAPごとのUSBオーディオ、Bit-perfect、Native DSD対応範囲を検証する。 | FR-AUD-03, FR-AUD-05, FR-PLT-01 | DD-AUD / DD-DSP / DD-AUD-04/05 / DD-TEST-03/04 | TEST-AUD-01/02/03 / TEST-AUD-02 / TEST-FORMAT-02 / TEST-COMP-01 / TEST-FORMAT-02 / TEST-RESEARCH-01 |
| [F-14-08](../audio_app_feature_list.md#L275) | DAC固有のドライバーやファームウェアに依存する機能を識別する。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 / TEST-RESEARCH-01 |
| [F-14-09](../audio_app_feature_list.md#L276) | デジタル接続先から取得できる機器情報の範囲と、プラットフォーム・機器固有の制約を検証する。 | FR-DEV-02 | DD-DEV-01/03 | TEST-DEVICE-01 / TEST-RESEARCH-01 |
| [F-14-10](../audio_app_feature_list.md#L277) | Visualizerがアクセスできる音声データの範囲、描画負荷、外部サービス再生時の制限を確認する。 | FR-OBS-02, FR-OBS-03 | DD-OBS | TEST-VIS-01 / TEST-AUD-01 / TEST-RESEARCH-01 |
| [F-14-11](../audio_app_feature_list.md#L278) | 将来iOS対応を検討する場合は、共通コードとOS固有実装の責任分界・機能差・移植コストを評価する（初期開発の必須作業ではない）。 | FR-PLT-02 | 対象OS方針 | 適用除外：Android以外は本版の実装対象外 |
| [F-14-12](../audio_app_feature_list.md#L279) | 再生品質、安全性、長時間再生の安定性を優先して実装・検証する。 | NFR-REL-02, NFR-AUD-03 | DD-PLY / DD-ERROR / DD-TEST / DD-AUD / DD-DSP / DD-DEV | TEST-REL-01 / TEST-AUD-02/03 / TEST-RESEARCH-01 |
| [F-14-13](../audio_app_feature_list.md#L280) | Apple Musicの直接音声再生とカタログ検索について、MusicKit for Android・Apple Music API、開発者資格・利用者認証・用途別条件を確認する。 | FR-SRC-09 | DD-PROV-06 | TEST-PROV-01 / TEST-RESEARCH-01 |

## 行数の照合

原典の箇条書き（親・子を含む）は合計231行。本表は同じ231行を対応付けた。要件ID・試験IDの存在確認と内容の十分性の評価は別である。

| 原典節 | 対応行数 |
|---|---|
| §1 | 20 |
| §2 | 12 |
| §3 | 13 |
| §4 | 20 |
| §5 | 17 |
| §6 | 29 |
| §7 | 19 |
| §8 | 9 |
| §9 | 25 |
| §10 | 6 |
| §11 | 27 |
| §12 | 12 |
| §13 | 11 |
| §14 | 11 |

## 利用者判断による廃止ID（2026-10-28）

Amazon Music/SoundCloudは実装対象外。以下のIDは再利用せず、現行原典行数と完成試験の対象に含めない。

| 廃止ID | 以前の項目 | 理由 |
|---|---|---|
| `F-01-02.02` | Amazon Music連携。 | 利用者がAPI利用困難と判断し実装しないと決定 |
| `F-01-02.04` | SoundCloud連携。 | 利用者がAPI利用困難と判断し実装しないと決定 |
| `F-14-02` | Amazon Musicの直接音声再生について、承認制API／SDKの利用可否を確認する。 | 利用者がAPI利用困難と判断し実装しないと決定 |
| `F-14-04` | SoundCloud連携について、公式APIの利用条件・認証・再生可能な音源・統合キューでの扱いを確認する。 | 利用者がAPI利用困難と判断し実装しないと決定 |
