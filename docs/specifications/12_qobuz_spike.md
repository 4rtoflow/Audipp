# Qobuz/qobine固定ソース調査・ローカル検証（E06-02 / #20）

調査日：2026-10-31 JST。対象：[Issue #20](https://github.com/4rtoflow/Audipp/issues/20)。Audipp基準commit：`ba0628b212a1ad736622f8fdb7be8d194c2eaf94`（取得時のorigin/main）。

**結論：Qobuz直接再生の製品実装はBLOCKEDを維持する。** 固定ソースの確認は再現できたが、Audipp用のapplication identity・本人の契約・用途別許諾・Android実行は未確認。qobineのcallback、ログ、ディスクキャッシュにはそのまま採用できない点がある。研究の検査成功を接続・再生・許諾の成功に読み替えない。必須の直接再生要求は削除しない。

## 環境と証拠の境界

| 項目 | 固定値 / 観測 |
|---|---|
| 候補 | `SofusA/qobine` commit `81d574b86957b79133a913c197c6a99618cd8dcb` |
| ホスト | Linux x86_64、Python標準ライブラリで静的検査 |
| Rust / NDK | `command -v cargo/rustc/rustup`はいずれも未検出。使用SDKに`ndk`ディレクトリなし。候補のビルドは未実施 |
| Android | AudippはminSdk 23 / compileSdk・targetSdk 36。候補のAPI23/API36実行は未実施 |
| 機器 / ADB | シリアルの指定なし。実機・エミュレーターの操作なし |
| 資格 | 本チャットにAudipp用identity、利用資格証跡、許諾、利用可能な音源試料の指定なし。利用者の秘密・ブラウザー・他アプリの保管領域を探索していない |
| 音源 | 取得・再生なし。音源fixture、ユーザー情報、署名URLなし |
| 固定fixture | [source.json](../evidence/issue-20/source.json)に原典9ファイルのSHA-256。候補コードはAudippへコピー・リンクしていない |
| 実行記録 | [local.txt](../evidence/issue-20/local.txt)。変更commitとCI URLは本調査PRの「証跡」で追跡 |

PASS＝記載した範囲の確認成功、FAIL＝確認した候補が採用条件を満たさない、BLOCKED＝必要条件不足で実行不可、NOT_RUN＝未実施。静的検査のPASSと、候補の採用判定FAILは両立する。

## 原典の照合

すべて2026-10-31に確認。サービスの現在の個別契約の存在・内容までは推定しない。

| 資料 | 確認内容 / 限界 |
|---|---|
| [公式API Terms of Use](https://static.qobuz.com/apps/api/QobuzAPI-TermsofUse.pdf) | 発効日は2011-09-01。p.2はQobuz発行のID/secretと第三者共有制限、p.3は許諾外利用・索引化・地域制限回避等の制約。古い公開文書だけで現在のAudipp用途を許可済みにしない。HTTP 200、102452 bytes、SHA-256 `af8db59e3fc742bccc1484f06806d9ab8a7bc76915068253305b122b2b41985c` |
| [公式一般条件・英語版](https://www.qobuz.com/gb-en/legal/terms) §4, §7, §17 / [日本語版](https://www.qobuz.com/jp-ja/legal/terms) §4, §7, §18 | 個人的・非商業的な加入者の権利と、保護措置や必要ソフトウェアの回避・変更等に関する制約を確認。第三者アプリの各用途への包括的許諾とは扱わない。地域別の契約への適用確認は残る |
| [公式Apps Guidelines V1.0](https://static.qobuz.com/apps/api/Qobuz-AppsGuidelines-V1.0.pdf) | 閲覧ツールで403。本文未確認。別経路での回避取得はしていない |
| [固定client.rs](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/qobuz-client/src/client.rs) / [player client](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/player-module/src/client.rs) | 認証・identity・別々の取得関数・報告・失効のソースを確認。秘密の値や取得/保護解除手順は証跡に転載しない。実行していない |
| [固定FileBased処理](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/qobuz-client/src/stream/file_based.rs) / [downloader](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/player-module/src/downloader.rs) / [report](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/player-module/src/report.rs) | キャッシュ再利用、開始/終了報告、報告再試行の上限を確認。完全な音源再生・シーク・報告受理の証拠ではない |
| [固定LICENSE](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/LICENSE) / [Cargo.toml](https://github.com/SofusA/qobine/blob/81d574b86957b79133a913c197c6a99618cd8dcb/Cargo.toml) | GPL version 3本文、Rust edition 2024とworkspace構成。ライブラリのライセンスとサービス利用権は独立 |
| [Rust Android targets](https://doc.rust-lang.org/rustc/platform-support/android.html) / [Android NDK ABIs](https://developer.android.com/ndk/guides/abis) | RustのAndroidターゲットとNDKクロスビルド、Android ABIを確認。qobine依存のAPI23対応やJNI動作の保証にはならない |

## 実験表

### 認証・identity・情報保護

| ID / 操作 | 取得した観測 / エラー | 技術判定 | 用途許諾 | Android移植 | 依存ライセンス |
|---|---|---|---|---|---|
| Q20-01 固定ソース照合 | 9ファイルのハッシュ一致、誤commit・改変digestの拒否。ローカル8検査成功 | PASS（静的検査のみ） | 対象外 | NOT_RUN | LICENSE本文を照合 |
| Q20-02 browser callback確認 | client.rs:216–308/1345付近。全interfaceへのbind、codeパラメーターをチャネルへ渡す処理、URLとhandlerにstate照合が見当たらない。認可コードをdebugへ出す。期限切れ時のtask後始末も別途検証が必要 | FAIL（そのままの採用条件）。外部からの攻撃実証は未実施 | BLOCKED | BLOCKED：Androidでのredirect受理・再生成/取消・外部ブラウザー未検証 | コード導入なし |
| Q20-03 app identity / code交換確認 | player clientのidentityは候補のweb由来情報に依存。交換処理は別のprivate-key定数を参照する。公開ソースにあることをAudippへの発行・使用許可と扱わない。値の抽出/流用なし | BLOCKED：Audipp用identity/正式な交換方式なし | BLOCKED | NOT_RUN | GPLだけでidentity使用権を得ない |
| Q20-04 本人ログイン・拒否・失効 | 実サービスへの認証要求0。拒否応答そのものも未観測。拒否から別方式へ切り替える実験なし | BLOCKED：資格未確認 | BLOCKED | NOT_RUN | 対象外 |
| Q20-05 ログ/キャッシュ採用条件 | 認可コード、session ID/infos、応答本文をログに渡す箇所あり。FileBasedはpartialを書き、完了時renameし再利用する。ストリーミング専用のAudippへそのまま導入できない | FAIL（静的な採用条件）。実際の漏洩/書込は未実証 | BLOCKED：保存・ログ用途未許可 | NOT_RUN | コード導入なし |

Q20-02/05の検査は危険箇所が存在することを再現するため成功する。安全性の合格検査ではない。固定文字列による静的検査であり、全制御フローや脆弱性の網羅的な解析ではない。

### FileBased / SessionBasedを別々に評価

| 操作 | FileBased | SessionBased |
|---|---|---|
| 解決関数・endpointの確認 | PASS（ソースのみ）：`get_file_based_streaming_info` → `track/getFileUrl`。active secret未設定でエラー | PASS（ソースのみ）：`get_streaming_info` → session確認 → `file/url`。`expires_at`でsession更新 |
| 認可済みURL/メディア取得 | BLOCKED：identity・本人資格・用途未確認 | BLOCKED：identity・本人資格・正規メディア処理条件未確認 |
| Audippによるフル直接再生 | BLOCKED：上記前提不足。URLも音源も未取得 | BLOCKED：上記前提不足。保護メディア処理の実行なし |
| 開始・終了報告の受理 | BLOCKED：実サービス未実行。この経路の報告に必要な情報/条件も未確認 | BLOCKED：関数とreport実装は存在するが応答未取得。有限回の再試行後に破棄する処理あり。受理・二重送信防止・終了の保証は未検証 |
| seek / Stop / 再接続 / 通信断 | BLOCKED：権利のある試料なし | BLOCKED：権利のある試料なし。session再取得/報告との順序も未確認 |
| URL期限切れ / 401 / 403 / 429 | BLOCKED：実応答未取得。再解決は許可済み方式内に限る | BLOCKED：実応答未取得。session更新の存在を回復成功と扱わない |
| catalog/search | BLOCKED：関数とendpointの存在のみ確認。検索資格・用途許諾・実応答なし | BLOCKED：同じ検索関数の確認のみ。メディア方式と検索許可を同一視しない |
| 24bit/192kHz | 候補目標。要求/取得/デコード/OS経路/DAC実測はすべて未観測 | 候補目標。要求/取得/デコード/OS経路/DAC実測はすべて未観測 |

両取得関数は分離されているが、この静的確認から全call siteでのfallback不存在までは保証しない。Audipp側は認可拒否を終端エラーとし、異なる方式・他アプリのidentity・秘密取得へ進めない。実サービスの成功/拒否/失効fixtureは今回作っていない。合成応答で成功実績を埋めることもしない。

### Android API / ABIとGPL導入

| 対象 | 判定 | 根拠 / 次の確認 |
|---|---|---|
| `arm64-v8a` / `aarch64-linux-android` | BLOCKED（候補build NOT_RUN） | cargo/rustc/NDKなし。API23に固定した依存解決・クロスビルド・link/loadが必要 |
| `armeabi-v7a` / `armv7-linux-androideabi` | BLOCKED（候補build NOT_RUN） | 同上。32bit対応をarm64から推定しない |
| `x86` / `i686-linux-android`、`x86_64` / `x86_64-linux-android` | NOT_RUN | エミュレーター等で必要となるABI。採用ABIは本調査で追加決定しない |
| API23 / API36 instrumented | NOT_RUN | 候補のJNI/APK組込みなし、対象serialなし。callback再生成、取消、権限拒否、通信断、Service寿命を後続で実行 |
| GPL本文の確認 | PASS（資料確認のみ） | LICENSEはGPLv3本文。§4–6のnotice、変更表示、対象となる結合作品のライセンス・Corresponding Source等を導入時に確認。§6のInstallation Informationは適用条件を確認し、§10/12の追加制約/他義務との両立も審査 |
| Audippへのソース/バイナリー導入・個人間配布 | BLOCKED | 現在のAudippルートにはLICENSEがない。採用範囲・著作権者の許諾・全推移依存と提供方法を未決定。JNI/別プロセス化だけでGPL義務がなくなるとは判断しない。GPL条件を満たしてもQobuzの資格・用途は別途必要 |

Rust/NDKを今回インストールしていない。候補のビルドやtestには秘密取得・ログインを含む実行経路が混ざらないかを先に確認する必要があるため、将来のツール導入後も候補全体のtest実行へ自動で進まない。

## 用途別台帳

| 用途 | Audipp向けの許諾証跡 | 判定 / 入口の扱い |
|---|---|---|
| 認証・直接再生 | 未提示。公開候補・加入者条件だけでは補完不可 | BLOCKED：Providerを登録・有効化しない |
| カタログ検索 / 横断検索・関連付け | 未提示 | BLOCKED：取得/横断処理を開始しない。検索要求は維持 |
| 混在表示・キュー | 未提示 | BLOCKED：候補データを共通キューへ渡さない |
| 履歴保存・統計 | 未提示 | BLOCKED：永続化・集計しない |
| 推薦・Scrobble | 未提示 | BLOCKED：外部送信しない |
| 同期・サーバー中継 | 未提示 | BLOCKED：サーバーへ送らない |
| PCM加工・DSP・解析・可視化 | 未提示 | BLOCKED：共通DSP/Tapへ接続しない |
| オフライン保存・キャッシュ | 未提示。購入音源と加入ストリームを混同しない | BLOCKED：候補のディスク保存処理を導入しない |

現行SourceResolverの未審査/拒否/失効時の契約試験は既存Unitで検証されるが、用途全体のProvider許諾機構の完成やQobuzの資格取得を意味しない。本PRは製品の許諾ゲートや新しいProvider APIを先行実装しない。

## 再現手順

次の再現コマンドのネットワーク操作は公開gitソースの取得だけ。検査プログラムはgitのrevision読み取りとファイル読取りを行い、候補をimport/compile/実行せず、HTTP・ブラウザー・音源・秘密取得処理も呼び出さない。

```sh
git clone --no-checkout https://github.com/SofusA/qobine.git /tmp/audipp-issue20-qobine
git -C /tmp/audipp-issue20-qobine checkout --detach 81d574b86957b79133a913c197c6a99618cd8dcb
python3 scripts/research/test_qobuz_source.py --source /tmp/audipp-issue20-qobine -v
```

8検査の成功は固定fixtureとの一致。異なるcommit/ファイル改変では失敗する。原典の版を更新する場合はdigestだけ差し替えず再レビューする。通常CIはこの外部checkoutを取得せず、再現コマンドを手動実行する。実行結果は[local.txt](../evidence/issue-20/local.txt)に保存した。

Audipp側の`assembleDebug / testDebugUnitTest / lintDebug`も成功。これは既存アプリの回帰確認であり、Qobuz候補のbuildや接続試験ではない。実機・DAC・性能/音声品質試験はNOT_RUN。IO分離・取消・資源上限も候補の実行で未検証。

## 採用判断・要求追跡・再開箇所

ADR-004はDIRECT必須を維持し、**qobineコード・認証方式の採用は保留**。別の正式方式を含めた比較は可能だが、認可拒否後の総当たりを採用案にしない。単一app、依存、Manifest、schema、公開APIには変更なし。新しい数値基準や製品仕様を確定していない。

| 要求 / 設計 / 試験 | 今回の結果 | 後続への影響 |
|---|---|---|
| FR-SRC-08 / NFR-LEGAL-01/02 / DD-PROV-04/07 / TEST-PROV-01 | 用途台帳と原典を記録。資格・用途許諾はBLOCKED。Q20-02/05は採用条件FAIL | #33の本人認証・保管だけでProviderの利用資格を補わない。ログ・callback・キャッシュをレビューする |
| FR-SRC-09 / TEST-RESEARCH-01 | FileBased/SessionBasedを分離して記録。フル再生は両方BLOCKED | #45はQobuz直接再生を完成扱いしない。技術・許諾・ABI・GPLが独立して成立するまで実装採用を保留 |
| FR-SRC-10 / TEST-PROV-01 | catalog関数のソース確認のみ、検索はBLOCKED | 実応答と検索/横断用途の許諾を確認後に実装。不可なら根拠を残し直接再生要件は維持 |
| R-06 / ADR-004 | 原典の再確認・静的ローカル検査は完了、実環境仮説の検証は未完了 | #21の他サービス調査とは結果を合算しない。研究全体/製品完成/PRレビュー完了とはしない |

#20の受入条件：秘密取得/方式総当たりを行わないこと、24bit192kHzを実績と区別すること、成功/失敗/未実施・ADR/後続を記録することは本研究範囲で満たした。実サービス接続・Android導入の未達は上表のまま残す。

再開は、(1) Audippが使えるidentity・交換仕様・本人資格と用途別許諾の証跡を受領、(2) callbackの照合/取消/ログ非露出と保存しない方式を設計レビュー、(3) ツール版/NDK/API/ABIを固定して認証不要のbuild、(4) 明示serialと権利のある試料で許可された経路だけ実行、の順。認証拒否時はそのエラーを記録して停止する。実験にはフル再生・報告・seek・失効・通信断・検索を含め、契約失効/取消後の破棄も確認する。企業への連絡/申請、購入/契約は実施していない。
