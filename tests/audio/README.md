# 音声試験基盤 v1（Issue #17）

対象: [Issue #17](https://github.com/4rtoflow/Audipp/issues/17)。要件1.0.1（`2adc276`）の NFR-TEST-01 / NFR-AUD-04 → DD-TEST-01〜03 → TEST-GOV-01 / TEST-FORMAT-01 / TEST-AUD-02 のうち、生成信号・独立比較・証跡保存の基盤を実装する。配置・JSONキー・生成方式は本Issueでの実装案である。製品の音質基準は `docs/specifications/09_private_completion_review.md` のQLT-01〜14を使う。

## 再生成と権利

Python 3.11以降の標準ライブラリのみを使う。リポジトリルートで実行する。

```sh
python3 -m scripts.audio.fixtures build/audio-fixtures
python3 -m unittest discover -s scripts/audio -p 'test_*.py'
```

生成器 `audipp-fixtures-1`、既定seed `0x41554449`。PRNGは32-bit LCG（乗数1664525、加数1013904223）、下位32-bitを残す。先頭4フレームに最小値・最大値・-1・0の整数境界を置く。sweepは整数位相とDecimalで作った1024点正弦表による20〜20000 Hzの線形掃引で、libmや実行環境の乱数器に依存しない。SRCの阻止域測定を済ませた信号ではない。

15ファイルを生成する。48 kHz / 4096 frames / 16・24・32-bitのstereo PRNG・impulse・sweep・無音12件、24-bit 5.1chのimpulse1件、途中切断・不正headerの破損2件。5.1chはWAVE_FORMAT_EXTENSIBLEのmask `0x3F`でFL/FR/FC/LFE/BL/BRを宣言し、各chの32×(ch番号+1)フレームに0.5のimpulseを置く。正常ファイルにはタグを付けない。破損ファイルの形式・framesは破損前の宣言で、decodeできた属性を表さない。

`manifest.json` はschema_version、seed、生成器版と、各ファイルのSHA-256・container/codec・bit深度・Hz・ch配置・frames/秒数・期待結果・利用条件を持つ。既定seedの台帳をコミットし、CIは再生成した台帳と全ファイルのハッシュを照合する。生成物は無視対象の `build/` に置く。`--seed 0x1234`で別seedの台帳を作れる。生成仕様を変える場合は生成器版と既定台帳を同時に更新しレビューする。

生成される数学的な試験信号・破損データは **CC0-1.0** とし、生成・利用・改変・再配布できる。第三者の録音・音楽・サービスデータを含まず、外部取得も行わない。この指定は生成データにのみ適用し、リポジトリ全体のライセンスを指定するものではない。外部のEBU test set等の権利を代替しない。

## Float64独立基準との比較

`scripts/audio/reference.py` はアプリのDSP・表示値をimportせず、正規化したPCMをFloat64へ読み、直接畳み込みと `math.fsum` でFIR基準を計算する。FFT・partitioned convolutionは使わず、末尾の `taps-1` framesまで含める。tap `[1]` は恒等、単一tapはgainの基準になる。小さなオフラインケース向けで、リアルタイム音声threadでは動かさない。

後続のnative/Android実装から取得した整数PCM WAVを `actual.wav` として比較する例:

```sh
python3 -m scripts.audio.reference build/audio-fixtures/prng-24-stereo.wav actual.wav \
  --absolute-tolerance 0 --output build/audio-comparison.json
```

既知FIRとの比較は `--taps 0.5 0.25 0.125` のように指定する。全chへ同じtapを適用する。最大絶対誤差とRMS誤差、許容値、入力/取得ファイルのSHA-256・形式、基準版とtapをJSONに保存する。全sampleが許容値以内なら終了コード0、不一致・ch数/長さ違いはFAILと終了コード1。不正形式、NaN/Infinity、負/非有限の許容値、レート違いは入力エラーとして失敗する。ch入替・欠落・追加無音・末尾消失を自動補正しない。遅延補償は測定担当が方法と補償sample数をsettingsへ記録してから行う。許容値は対象QLTに従って指定し、自己検査の許容値を製品基準に代用しない。

WAVは整数16/24/32-bit、mono/stereoおよび上記5.1配置を読む。正規化PCMの値が同じなら異なる整数bit深度でも受け入れるため、比較成功だけでBit-perfectと判定しない。外部デジタル取得、整列、形式/全sample一致などQLT-02の条件は別途必要。生成ファイルを自分自身と比較する動作確認は比較器の検査である。

## 端末・測定結果の保存形式

`result-template.json` はschema_version 1の **NOT_RUNテンプレート**。コピーして試験ID・session ID・実行UTC時刻・対象commit・条件を記入する。時刻やcommitを推測で補わない。

```sh
python3 -m scripts.audio.evidence tests/audio/result-template.json
python3 -m scripts.audio.evidence build/device-result.json
```

Pythonから `scripts.audio.evidence.save(record, path)` を使う場合も保存前に同じ検査が走る。validatorは保存物の構造・必須条件・数値と結果の整合を検査する。証跡の真正性や測定方法の適切さはレビューで確認する。

- outcomeはPASS / FAIL / BLOCKED / NOT_RUN / NOT_APPLICABLEのみ。全状態でreason・known_limitations・要求/試験IDを明記する。未測定・未適用はresult=nullとし、合格結果を添付できない。
- PASS/FAILには40桁のapp_commit、capture_point、instrumentのname/version、fixtureのSHA-256/codec/Hz/bit/ch、settings、取得データまたは報告書のartifact_sha256とmetricsが必要。各metricはvalue/minimum/maximum/unitを持ち、片側の境界はnullにできる。非有限値・逆転境界は拒否し、全指標が境界内の場合だけPASSを受理する。FAILには少なくとも1指標の不成立を要求する。
- evidence_typeはOFFLINE_REFERENCE / APP_OBSERVED / EXTERNAL_DIGITAL / EXTERNAL_ANALOG / UNKNOWNを区別し、UNKNOWNのPASS/FAILは拒否する。アプリ内部取得を外部実測へ昇格させない。
- deviceにはDD-TEST-03の端末/OS/build hash/API/ABI/出力/DAC/VID-PID/codec/Hz/bit/再生mode/DSPを保持する。未知・不適用の属性はnull、理由はknown_limitationsへ書く。端末測定のPASS/FAILでは端末・OS・build hash・API・ABI・出力種別・再生mode・DSP状態を必須とし、オフライン比較と区別する。
- 接続・音量・DSP・firmware・遅延補償・測定帯域など必要な条件はsettingsへ記録する。artifact_sha256に対応する実データ/報告書は結果JSONと一緒に保管する。認証情報・URI・未ハッシュのbuild fingerprintを入れない。秘密の自動除去機能はないため、公開前に内容を確認する。

## 要求と検証・後続への引き渡し

| 要求 → 設計 → 試験 | この変更の観測可能な検査 | 自動試験 |
|---|---|---|
| NFR-TEST-01 → DD-TEST-01/03 → TEST-FORMAT-01 | 同seed・既定ハッシュ一致、権利/形式の台帳、整数境界、5.1配置、破損拒否 | FixtureTest |
| NFR-AUD-04 → DD-TEST-01/02 → TEST-AUD-02 | 手計算FIR・Float64蓄積、誤差/長さ/ch/非有限境界 | ReferenceTest / FixtureTestの比較結合 |
| NFR-TEST-01 / NFR-AUD-04 → DD-TEST-02/03 → TEST-GOV-01 | 証跡不足のPASS拒否、5状態、数値不成立、版/未知field拒否、保存/復元 | EvidenceTest |

2026-10-30: ホスト自動試験22件、CLIの成功/失敗終了コード・JSON出力、既存PR検査9件、指定のDebugビルド・全Unit・Lintが成功。CIにも同じホスト検査とテンプレート検証を追加する。対象commitとCI URLは対応PRの証跡で追跡する。これはTESTの各ファミリー全体や音声品質の合格を表さない。

Android API23/36実行・実機・DACは未実施。この実装はホストの生成/データ検査で、Androidコンポーネントの寿命・音声thread・出音へ接続しないため、instrumented/hardware試験は基盤単体には不適用。共通domain型は変更せず、実測結果を外部検証済みの経路へ接続する判断は #69 / #145 が所有する。

後続では #36 でFLAC/MP3/AAC/DSDの対応表・fixture、#82 で連続信号の分割とpadding、#67 で各レートのSRC、#77 でEBU/LUFS/True Peak、#109 でFFT/相関、#91〜93 で各DSPの基準を追加する。未実装oracleや実測を基盤の成功で代用しない。API23/36の実行やDAC/外部収録は接続先の結合/実機Issueで追跡する。
