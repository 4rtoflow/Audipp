# 承認ベースラインと実施記録

Issue [#18](https://github.com/4rtoflow/Audipp/issues/18) の担当範囲は、承認済みの要求・設計・試験・判断の所在と、個別ケースの実施結果の追跡です。読者は実装担当者とPRレビュアーです。製品条件・数値・方式の新しい決定は追加していません。

## 承認の固定点

[baseline.json](baseline.json) は、次の版・commit・日付・判断者・対象範囲・承認記録の所在を保持します。

| 版 | 固定commit | 根拠と範囲 |
|---|---|---|
| 1.0.0 | [cc095bf](https://github.com/4rtoflow/Audipp/commit/cc095bf0d62fb39de197299c1755b5b49c22f239) | 2026-10-28、利用者の全レビューComplete・正式版承認。実装・許諾・実測の未確認事項は引継ぎ |
| 1.0.1（現行） | [2adc276](https://github.com/4rtoflow/Audipp/commit/2adc27605793e9934b1efa7f1bcfac3192a80b19) | 2026-10-29、利用者のApple Music除外指示。Qobuz・Spotify・YouTube・YouTube Musicの直接再生などの条件は維持 |

検査対象の原典・仕様・レビュー・UI設計のSHA-256を、改行をLFへ揃えて固定します。実装用の契約資料や実施記録はこの固定対象とは別に更新できます。仕様の承認を、実装完了・用途別許可・実測合格の証明に使いません。

## ID台帳

[inventory.tsv](inventory.tsv) は1 IDにつき1行です。`source` は原文のリポジトリ相対パスと行番号、`links` は原文の対応表から展開したIDです。固定版へのURLは `baseline.repository + /blob/ + baseline.active_commit + / + source` で求められます。

| 種類 | 現行定義数 | 適用上の注意 |
|---|---:|---|
| FR/NFR | 90 | CURRENT 89、対象外OSの範囲規定FR-PLT-02がEXCLUDED 1 |
| F | 229 | CURRENT 223、対象外OS方針6がEXCLUDED。廃止6件はこの229件に含めない |
| TEST | 43 | 試験ファミリーの定義数。実行数・合格数ではない |
| DD | 81 | 現行の領域見出し・番号付き契約。DD-PROV-06は別にRETIREDとして保持 |
| QLT / ADR | 14 / 8 | 基準の採用と実測・導入成功を区別 |
| Q / R / P | 13 / 7 / 5 | Q回答済みと外部条件の成立を区別。P-01はPUBLIC |
| IMP / UAT | 7 / 14 | 導入条件・受入条件の定義。完成を表さない |

- `CURRENT`：私用範囲で追跡する定義。PUBLICの説明も含む混合定義では、私用ケースだけが現在の完成対象です。NFR-STORE-01の署名APK更新と、PUBLICのストア申請を一緒に判定しません。
- `PUBLIC`：P-01の公開名義・一般向け配布等。現在の完成依存へ入れません。その他の混合定義のPUBLIC部分も結果の`stage`をPUBLICに分けます。
- `EXCLUDED`：FR-PLT-02とF-13-02/03/03.01/03.02/04、F-14-11。Android以外の専用アプリを実装する依存には変換しません。
- `RETIRED`：F-01-02.02/02.04/02.06、F-14-02/04/13とDD-PROV-06。再利用・再採用しません。

要求→DD/TEST、F→要求/DD/TEST、QLT→TESTの構造化された参照を記録します。原典229箇条書きとF対応表の行番号・原文も1対1で照合します。Q/R/P/IMP/UAT/ADRは原文の定義への参照を保持します。本文中の全参照、仕様の十分性、Issue担当先の網羅性を自動判定するものではありません。担当Issueは#18の全対象追跡表と個々の実装PRで確認します。

## 結果とcommitの記録

[results.json](results.json) は個別ケースの記録配列です。実装担当者は実施後、関連要求・試験、実際に検査した40桁のcommit、入力/操作/期待値と結果を説明する証跡、制約を追加します。`case_id`はケースと実行を識別する一意な名前にします。同じケースの再実施は別の記録として追加し、失敗を上書きして消しません。

| フィールド | 記入内容 |
|---|---|
| `requirement_ids`, `test_ids` | CURRENTの実在ID。要求ごとに関連TESTを少なくとも1つ含める |
| `stage` | CURRENT / PUBLIC。PUBLICのPASSで私用ケースを埋めない |
| `outcome` | PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE |
| `execution` | EXECUTED / NOT_RUN / UNESTABLISHED（環境・経路等が不成立） |
| `permission` | GRANTED / NOT_REQUIRED / UNKNOWN / DENIED（そのケースの用途別判定） |
| `reason`, `evidence`, `limitations` | 結果の理由、ログ/PR/測定資料の参照配列、未確認の範囲。理由と制約は空欄不可 |

PASSはEXECUTED・GRANTEDまたはNOT_REQUIRED・非空の証跡が揃う場合だけ受け付けます。用途未許可・未実施・不成立をPASSにしません。ホスト上の台帳検査では外部サービス操作がないためNOT_REQUIREDですが、その結果をサービス利用許可へ流用しません。

未記録は結果不明です。既存PRの成果を未実施や失敗に書き換える意味ではありません。記録は個別ケースの結果に限定し、要求・試験ファミリー・製品全体の自動合格は出しません。完成レビューではDD-RELEASE-01に従って全私用ケース、導入条件、用途許可、実機、私用配備・復旧等を別に確認します。

## 検査と更新

配置と形式は#18の実装提案として、原文から再集計するTSV、版/実施結果のJSON、Python標準ライブラリの検査を採用します。リポジトリのルートで実行し、ネット接続・Android・外部資格は不要です。

```sh
python3 -m unittest discover -s scripts/traceability -p 'test_*.py'
python3 scripts/traceability/check.py
```

CIの既存 `PR requirements` ジョブでも実行します。検査プログラムは承認対象文書のハッシュ、定義数・重複、台帳と原文のID/参照/適用区分の一致、参照先の存在、結果の必須項目を比較し、不一致があれば終了コード1を返します。既存の承認・レビュー要件は変更しません。出力の「inventory OK」は参照台帳の一致だけを意味します。既知差異T18-02も毎回表示します。

要求の追加・削除・格下げ・方式/数値の変更が必要なら、担当者は差分、理由、影響するIDと試験、保留事項をPRへ記録します。製品範囲・承認済み条件の変更には、従来どおり利用者の明示指示を記録します。既存の技術判断の委任内で決められる変更は、その委任と判断者を記録し、新しい利用者承認があったとは扱いません。適用する承認/判断の日付・対象範囲・根拠参照を原文の変更履歴へ残し、その文書版とcommitを`approved_history`へ追記します。新しい`active_commit`と全原文ハッシュを記録し、承認対象commitから台帳を再集計して同じPRへ含めます。ハッシュだけの更新、検査を通すための承認の創作、旧承認の上書きはしません。承認根拠の正当性と証跡の内容はレビュー対象で、自動検査は人の承認を代替しません。

承認された増減・廃止・適用区分の変更があれば、検査プログラムの`COUNTS`/`RETIRED`/`EXCLUDED`/`PUBLIC`と本書も同じ差分に合わせます。台帳の再集計には次を使えます。これは承認記録や原文を変更しません。

```sh
python3 - <<'PY'
from scripts.traceability.check import DIRECTORY, ROOT, inventory, ledger_text
(ROOT / DIRECTORY / 'inventory.tsv').write_text(ledger_text(inventory(ROOT)), encoding='utf-8')
PY
```

失敗時は検査が示すIDと原文を確認します。誤編集なら担当PR内の変更だけを戻し、承認を伴う変更なら上記の履歴を追加します。コマンド・失敗理由・対象commit・再開箇所を実施記録へ残し、成功したことにして先へ進めません。

## 決定履歴と残る確認

以下は既存原文からの引継ぎです。#18では新しい解釈を承認済みにしていません。

| 追跡名 | 状態・根拠 | 担当先・再開箇所 |
|---|---|---|
| T18-01 旧件数・一般公開条件 | HISTORY。[現在地](../specifications/00_review_status.md)の233/231/229件、89/90要求、41/43試験は変更時点が異なる。現行は1.0.1の90/229/43。旧レビューの公開条件はPUBLICへの分離で置換 | 範囲変更時は利用者承認を記録。#153の完成レビューで旧条件の混入を確認 |
| T18-02 廃止設計への残存参照 | OPEN。[DD-TRACE](../specifications/03_detailed_design.md)のFR-SRC-09にDD-PROV-06が残る。一方、同じ原文と1.0.1でApple Musicは廃止。検査はこの1辺だけを既知差異として報告し、Apple Musicの復活や完了証拠にしない | #18/#153。承認済み資料を改訂する際にこの参照の削除差分を提示。実装側は現行3群4サービスのみを対象とする |
| T18-03 最終ナビゲーション | OPEN。[UI設計§4.1](../design.md)はPoCのLibrary中心、基本設計はHome中心の案。原文で最終構成は未確定 | #19/#64/#149で差分・理由を記録。#18で最終案を選ばない |
| T18-04 履歴の計測表現 | OPEN。DD-HIST-01は音声ポジションと状態に基づく推定と記述し、参照先[技術判断](../specifications/08_design_decisions.md)は単調時計の聴取区間と音源区間を分離。シーク・Pause・時計変更を加算しない点は共通 | #78/#111/#139の具体ケースで差を確認。新しい計算式を#18で採用しない |
| T18-05 私用配備 | BLOCKED_INPUT。P-02/P-03のDebian/Docker版・接続URL/HTTPS終端・既存構成・バックアップ先は未確認。保持方針の決定と実配備成功は別 | #53/#144。構成案作成後、実配備前に対象と承認を確認。秘密・私用URLを公開台帳へ記入しない |
| T18-06 実機・用途許可 | NOT_VERIFIED。P-04のADB serial・M21 OS/firmware・DAC能力・測定設備、R-06の用途別許可等は未確認 | #26/#150/#152/#153と各provider Issue。NDK/ABI・実機音声・私用復旧をホスト検査の成功で埋めない |

#18の結果と検証commitは`results.json`と[検証記録](validation.md)へ保存します。PRレビューとリモートCIの結果は対応PRで追跡します。
