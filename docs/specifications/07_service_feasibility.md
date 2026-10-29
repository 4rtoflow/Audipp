# 全サービス直接再生の初期調査（R-06）

| 項目 | 内容 |
|---|---|
| レビュー状態 | **Complete**（2026-10-28、利用者による全レビュー完了・正式版承認） |
| 版・調査日・更新日 | 1.0.1 / 2026-10-27 / 2026-10-29 |
| 製品要求 | 全サービスの直接再生。カタログ検索は可能なら実装。YouTube系はyt-dlp・音声のみ |
| 状態 | 公開資料の一次調査。実装手段・個別許諾・認証・実機再生は未確定 |

直接再生はAudippが音声再生を所有する方式とする。SDK経由の再生でも、提供元アプリが再生主体なら遠隔制御であり、この要求を満たさない。一方、直接再生が成立しても、復号PCMへのアクセス・DSP・履歴・推薦等まで使えるとは限らない。

2026-10-28追記：まず招待した個人間で使うアプリを完成させ、一般公開準備は完成後へ移す。利用者の開発者登録・API許可は「未取得または不明」。個人利用で適用される条件は残る。今回の再確認・技術選別は[順次レビュー①](09_private_completion_review.md)を参照。

2026-10-29範囲更新：Apple Music/Amazon Music/SoundCloudは利用者判断で実装対象外。現在の調査・問い合わせ対象から除外する。以下の一次資料欄に残る過去の参照は再実装の予定ではない。qobine/librespotの追加調査と対象外Apple Musicの過去調査は[候補照合](10_provider_proposal_review.md)を参照。

## サービス別の確認結果

| サービス | 一次資料で確認したこと | Audippで残る確認 | 次の作業 |
|---|---|---|---|
| Qobuz | 公式ドメインにAPI利用条件文書があり、Qobuz発行のApplication ID/secretを必要とする。ただし取得した文書は2011年発効の旧資料 | 現在有効な開発者契約、Android直接再生の方式・高音質範囲、検索・統合・DSP・履歴等の利用条件 | qobine参考の非公式候補をDD-PROV-07/10で照合。認証/取得と用途条件を分けて確認し、旧PDFだけで現在の利用許可を判断しない |
| Spotify | 公開Android SDKはSpotifyアプリへの再生指示・状態取得。Developer Policyには他サービスとの統合、指標・統計生成等の制限がある | AndroidでAudippが再生主体になれる正式な提供手段、混在キュー等の必要用途の許諾 | App Remoteで代替しない。librespot v0.8.0のPCM Sink候補を10で照合し、Android導入・認証/取得・用途条件を確認 |
| Apple Music | EXCLUDED（2026-10-29利用者決定） | MusicKit/Apple Music API・Cider参考の独自PCM研究は対象外 | 資格取得・問い合わせ・実装・試験・完成条件から除外 |
| YouTube / YouTube Music | yt-dlpは音声専用フォーマット選択を持つ。YouTube APIのポリシーはAPI経由コンテンツの音声分離・バックグラウンド再生等を制限し、一般利用規約にも取得・自動アクセスの制限がある | yt-dlp経路の技術的適合、サービス条件・音源の権利・配布可否。YouTube Music側も別に確認が必要 | 音声専用形式・映像なしの契約を維持し、利用可能な条件を確認して検証する。公式動画UI・遠隔操作へ無断変更しない |

いずれもAudippが利用許諾を取得した証拠はない。現時点の判定は全サービスで「製品要求確定／実現手段・公開条件の確認未完了」である。Spotifyの現在確認したAndroid SDKで要件を満たせないことと、あらゆる契約・方式で不可能であるという断定は区別する。

## 共通の設計への反映

- Provider Adapterは、音源IDと短期の再生記述子を分離する。URL・認証ヘッダー・ライセンス関連情報を履歴・同期・ログへ保存しない。
- 必須の直接再生、可能時のカタログ検索、PCMアクセス、DSP、解析、統計、外部送信を別の能力・許諾項目として扱う。検索不可を直接再生不可と同一視しない。
- DRM対応が必要なサービスは正式な復号経路を使用し、任意のPCM処理へ差し込める前提にしない。承認されていない形式・音質を取得できると表示しない。
- 音源の所有・サブスクリプション・API資格・アプリ許諾は別々に記録する。アプリが無料・広告なしであることやAPK配布であることだけで条件成立としない。
- 公開判断は要件定義とDD-RELEASEに従う。成立する直接再生経路がないサービスを遠隔制御や外部遷移へ置き換えて合格にしない。

## 一次資料

1. [Qobuz API Terms of Use](https://static.qobuz.com/apps/api/QobuzAPI-TermsofUse.pdf) — 2011年発効と記載された旧文書。現行契約の代用にはしない。
2. [Amazon Music Developer Portal](https://developer.amazon.com/docs/music/landing_home.html) — Web APIの提供状態。
3. [Amazon Music DRM Overview](https://developer.amazon.com/docs/music/playback_overview.html) — 再生・DRM・端末等の要求。
4. [Spotify Android SDK](https://developer.spotify.com/documentation/android)、[Developer Policy](https://developer.spotify.com/policy) — SDKの再生主体と用途制限。
5. [SoundCloud API Guide](https://developers.soundcloud.com/docs/api/guide) — 認証・検索・再生と楽曲別制限。
6. [yt-dlp Format Selection](https://github.com/yt-dlp/yt-dlp#format-selection) — 音声専用形式選択。
7. [YouTube API Developer Policies](https://developers.google.com/youtube/terms/developer-policies)、[YouTube Terms of Service](https://www.youtube.com/static?template=terms) — API経路とサービス全体の条件を分けて確認する。

外部への問い合わせ、契約、APIキー発行、ログイン、音声取得・再生、機器操作は本調査では行っていない。具体の契約・資格情報・実機結果が必要な段階では、対象と作業を示して確認する。


8. [MusicKit公式案内](https://developer.apple.com/musickit/)、[Developer token](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens)、[Music User Token](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit) — 2026-10-28追加。Android内再生・検索経路と開発者/本人認証の分離。2026-10-29に対象外へ変更したため過去調査の参照のみ。採用・追加調査・問い合わせの予定ではない。

## 提供元への確認案（未送信）

Apple Music/Amazon Music/SoundCloudへの問い合わせ案は撤回。残る問い合わせも必要性を候補検証と合わせて判断し、資料の提供だけで送信や公式申請を実行しない。

以下は問い合わせの準備資料である。受領済みの許可や回答ではない。送信先・申請主体・本文を確認し、利用者から送信の明示指示を受けた場合にのみ連絡する。APIの利用申請、契約締結、料金支払はそれぞれ別の行為として扱う。

**共通の説明案：**

> Audippは、まず日本の招待した個人間で使うことを目標とするAndroid向け無料・広告なしの音楽プレーヤーです。複数の音楽サービスとローカル音源を扱い、各サービスのアカウントと必要なサブスクリプションを利用します。アプリ内での直接音声再生を必要とし、他アプリへの再生指示はその代替にしません。現在は個人間の署名APKで検証し、一般公開の名義・配布経路は完成後に検討します。対応する正式なAndroid再生手段、参加資格、申請窓口と、下記用途の条件をご案内ください。

| 確認先 | 共通説明に加えて確認する内容 | 設計を進めるために必要な回答 |
|---|---|---|
| Qobuz | 現行API契約とAndroid直接再生、対応音質、App ID/secretを安全に扱う正式方式 | 現行文書・申請資格・SDK/プロトコル・資格情報の扱い。2011年資料だけで判断しない |
| Spotify | Spotifyアプリを再生主体としないAndroid向け直接再生手段、他サービス/ローカルとの混在キュー、履歴・個人統計の許否 | App Remote以外の正式手段が提供されるか、必要な個別契約、用途別の可否。公開Android SDKで代替した回答にしない |
| Apple Music | EXCLUDED（2026-10-29利用者決定） | 公式SDK/API・独自PCM研究とも実装対象外。資格取得・問い合わせ・試験を行わず、完成条件に数えない |
| YouTube / YouTube Music | yt-dlpで音声専用形式を扱い、映像表示なし・画面OFFでも再生する構想について、利用できる条件・許可手順の有無 | 両サービスそれぞれの用途・取得方法・配布条件。技術的に取得可能という回答だけで許可済みとしない |

各提供元には、直接再生とカタログ検索のほか、混在キュー、メタデータ表示/キャッシュ、PCMアクセス、DSP、波形/解析表示、履歴、個人統計、推薦、Last.fm送信、端末間同期を**用途ごと**に確認する。利用不可の用途があれば制限条項・例外の有無を記録する。すべての用途を包括的な「API利用可能」の一言で許可済みにしない。提供元に音源や秘密を不要に送らない。

回答記録は`service / 文書・回答日 / 回答主体 / 対象アプリ・地域・配布経路 / SDK・API版 / 用途 / ALLOWED・DENIED・UNKNOWN / 条件・期限 / 証拠 / 設計への影響`を持つ。非公開契約・秘密は公開リポジトリへ添付しない。許諾が得られない必須機能は公開保留として利用者へ戻し、無断でREMOTE/HANDOFFや動画UIへ置き換えない。

2026-10-28 yt-dlp方式決定：YouTube系は自前サーバーで解決/取得/中継し、Audippで再生。保存を希望する場合だけ端末の選択フォルダへ保存する。[中継・保存契約](11_ytdlp_delivery.md)を実装基準とし、端末内蔵の実行環境は後段候補へ移す。認証不要試料と本人認証が必要なケース、stream許可と保存許可は分けて検証する。
