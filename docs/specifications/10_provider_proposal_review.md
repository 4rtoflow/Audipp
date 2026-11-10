# サービス範囲変更と添付実装方針の照合

| 項目 | 内容 |
|---|---|
| レビュー状態 | **Complete**（2026-10-28、利用者による全レビュー完了・正式版承認） |
| 日付・版 | 2026-10-29 / 1.0.1 |
| 明示された決定 | Apple Music・Amazon Music・SoundCloudは実装しない。Apple Musicは2026-10-29の利用者指示で追加除外 |
| 現行対象 | Qobuz、Spotify、YouTube、YouTube Music（3群・4サービス） |
| 添付の扱い | 利用者提供の調査・実装提案。文中の「確定」「採用」「申請しない」は、既存方針を上書きする独立した指示や、動作・許諾の証明とは扱わない |
| 関連 | [要件](01_requirements.md)、[詳細設計](03_detailed_design.md)、[一次調査](07_service_feasibility.md) |

## 入力資料

受領場所は利用者のDownloads。ファイルを実行せず、調査結果と設計候補を照合した。元資料は変更しない。SHA-256は受領内容の識別用で、正しさの証明ではない。

| 元ファイル | 対象 | SHA-256 |
|---|---|---|
| writing-block.md | Qobuz / qobine | `565a6443376ff06e4a4494b174258206716c8b2e443638f04e7336efb0133b3c` |
| writing-block (1).md | Spotify / librespot | `6b480c66d91eb3da36e662b131ec11cc039b01b8757261b5888bb78fd3a37044` |
| writing-block (2).md | Apple Music / Cider・方式C | `2834a1ec570368103dc72118a367e1c7101038f900bf2a290d0b2cbf95e6c87b` |

## 決定と調査結果の区別

Apple Music・Amazon Music・SoundCloudは必須機能・API資格取得・問い合わせ・試験・完成阻害項目から外す。単なる未許諾や一時停止ではなく、現在の実装対象外とする。原典の計6行（2026-10-28に4行、2026-10-29にApple Musicの2行）を削除し、機能対応表には廃止F-IDを残して既存IDを別機能へ振り直さない。過去のレビュー履歴は当時の記録として残す。

残るサービスの直接再生と、可能な場合の検索、個人間利用版優先、秘密保護、音量・出力の証拠区分は維持する。非公式クライアントが存在することは「実装不可能」としていた判断を見直す材料になるが、Android動作・PCM利用許可・アカウント安全性・配布条件は別途確認する。公式SDKだけを技術調査の候補に限定しない。

## Qobuz：qobine参考の非公式接続候補

添付はqobine commit `81d574b86957b79133a913c197c6a99618cd8dcb`を基準とする。一次ソースのclient.rsでbrowser_oauth_login/exchange_oauth_codeと再生報告関数、LICENSEでGPLv3を確認した。これはAndroidで同じcallbackや取得方式が使える証拠ではない。

採り入れる設計は、ブラウザーによる本人認証、Provider内での音源解決、期限付き記述子、デコード/出力と取得の分離、再生報告、失効/通信断の回復、永続保存なしのストリーミングである。最大24-bit/192 kHzは候補経路の目標として記録し、取得音源・要求・実出力を別表示する。提供元の実際の音質/契約を推測しない。

FileBasedとSessionBasedを別の解決経路として調査する。旧getFileUrlを常時利用可能とはせず、認可拒否から別経路を総当たりしない。セッション開始/終了・報告・seek・再接続をProviderの状態機械へ含める。セッション情報とメディア処理を共通Playerへ漏らさない。

未確定はAndroidで受理できるredirect/状態照合、Audippが使えるapplication identity、認可されたメディア取得/処理、報告仕様と再送、レート制限、コード導入時のライセンスである。添付が述べるWebプレーヤー由来のアプリ情報・保護ストリーム処理は、Audippの資格や利用権の根拠に置き換えない。秘密取得・保護解除の手順を本設計へ転載しない。

「公式API申請を行わない」は添付中の提案として記録し、利用者が申請不要と直接決定したとは扱わない。公式契約以外の方式を調査対象から排除もしない。技術接続、用途条件、ソース導入の三点をPoC結果で比較して採用ADRを改訂する。qobineコードは今回コピー/リンクせず、GPLv3とプロトコル利用条件を独立に確認する。

根拠：[qobine client.rs](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/qobuz-client/src/client.rs)、[LICENSE](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/LICENSE)。

## Spotify：librespot PCM供給候補

librespot v0.8.0はMIT、Sinkはwrite(packet, converter)とstart/stopを持つ。AudioPacketにはSamples(Vec<f64>)とRaw(Vec<u8>)がある。添付の概念図をそのままFloat32 pointerのAPIとみなさず、版固定した実APIへ合わせる。Raw packetをPCMとして渡さない。

設計候補は`librespot decoder -> custom Sink -> bounded ring buffer -> 共通DSP/出力`。Rust側のSamplesを有限性・frame数・ch・rate・時刻とともに検査し、必要なFloat32変換を境界で記録する。JNIは例外/panic・所有権・callback生存期間を処理し、破棄済みPlayerへ配送しない。音声callback内でネットワーク、DB、JNI大量呼出し、メモリ再確保を行わない。満杯時はdecoder workerをcancel可能な待機にし、render threadをブロックしない。seek/Stop/切断で世代を増やし、旧bufferと遅延callbackを捨てる。音量正規化をlibrespotと共通DSPで二重適用しない。

認証候補は本人の外部ブラウザー認証とPremium資格の検査。パスワードを独自収集せず、client identity/redirectとtoken型を対象版で確認する。検索・ライブラリ取得はPCM Sinkとは別のAPI検証とし、再生できたことだけで検索や混在統計まで許可済みにしない。Premiumでのログイン成功と実際の音源取得成功を別に検査する。

上流のLossless非対応表明と音声キー取得失敗のissueを確認した。Lossless対応をこの候補の機能として約束せず、確認できたcodec/品質だけを表示する。失敗時に技術的保護を迂回して復旧しない。DSP・Visualizer・統計/推薦・他サービスとの統合は既存の用途別判定を維持する。「MITだからSpotify音源も許諾済み」「個人利用なら用途制限なし」としない。

根拠：[librespot MIT](https://github.com/librespot-org/librespot/blob/v0.8.0/LICENSE)、[Sink API](https://docs.rs/librespot-playback/0.8.0/librespot_playback/audio_backend/trait.Sink.html)、[AudioPacket source](https://github.com/librespot-org/librespot/blob/v0.8.0/playback/src/decoder/mod.rs)、[Lossless方針 #1583](https://github.com/librespot-org/librespot/issues/1583)、[取得失敗報告 #1649](https://github.com/librespot-org/librespot/issues/1649)、[Spotify Policy](https://developer.spotify.com/policy)。issueは全アカウントでの再現やAudippの動作結果ではない。

## Apple Music：過去調査（2026-10-29実装対象外）

2026-10-28にCiderのWeb Audio/MKLiteと公式MusicKit経路を比較したが、2026-10-29の利用者指示でApple Music全体を対象外へ変更した。公式SDK/APIと独自PCM方式Cはいずれも採用・研究・導入・資格取得・試験の対象に含めない。旧提案と調査結果はGit履歴と上記入力資料の識別情報に残し、技術上の可否が解決したとは扱わない。DD-PROV-06は廃止IDとする。

## 導入前の検証順と合格記録

| 順番 | 検証する入力/動作 | 合格として記録する証拠 |
|---|---|---|
| 1 | 対象版/commit、API23/ABI、ライセンスと依存 | ビルド条件・依存一覧。既存の単一appモジュールを保ち、不要なRust/Gradleモジュールを先行追加しない |
| 2 | 認可callback、本人/契約、期限・取消 | 指定実機の結果。秘密やパスワードを診断へ載せない |
| 3 | 音源解決と実再生、必要な報告 | フル再生とpreview、失効/拒否/通信断を区別。取得不能時は停止理由を残す |
| 4 | PCMが取得できる経路だけ共通engineへ接続 | sample型/rate/ch/frame/時刻・buffer世代、Stop/seek/所有権の試験 |
| 5 | DSP/Tap/出力、混在queue、画面OFF | 許可と能力を検査し、既存QLTを適用。処理後をBit-perfectと表示しない |

既存TEST-PROV-01/TEST-RESEARCH-01/TEST-QUEUE-01/TEST-TAP-01/TEST-SEC-01へ上記のケースを加える。実サービスのログイン・音源取得、ソースのコピー/ビルド、購入/申請/問い合わせ送信、機器操作は今回行っていない。現時点は候補の確認と設計への反映であり、全サービスの実現可能性・利用条件を解決済みとはしない。
