# USB機器の取得 v1 — Issue #32

対象: FR-DEV-02 / NFR-SEC-01 → DD-DEV-01 / DD-DEV-03 → TEST-DEVICE-01（USB取得部分）、TEST-SEC-01（serial非取得・明示許可部分）。

## 入口と所有権

`Context.createUsbDeviceMonitor(scope)` が実 `UsbManager` に接続する。呼出側のscopeに寿命を結び付け、`snapshot: StateFlow<UsbDevicesSnapshot>` で接続中の機器・許可・読取状態を購読する。終了時は `close()`、終了処理の待機には `awaitClosed()` を使う。親scopeの取消でもreceiverとPendingIntentを解放する。生成・列挙・属性読取・descriptor解析はIO workerで行う。

初期列挙と接続イベントで許可ダイアログを自動表示しない。UIの利用者操作から `requestPermission(connectionId)` を呼ぶ。既にOSの許可がある機器は初期列挙で読取できる。拒否・取消・タイムアウト後は新しい明示操作で再試行する。`retryInspection(connectionId)` は許可済みの属性読取だけを再実行し、許可ダイアログは出さない。`refresh()` は列挙失敗や許可失効後の再検査用。

許可状態は `NOT_REQUESTED / REQUESTING / GRANTED / DENIED / CANCELLED / TIMED_OUT`。`GRANTED` はOSの許可だけを示す。機器を開けない場合は `OPEN_FAILED`、読取拒否は `PERMISSION_REQUIRED`、例外は `FAILED` となる。例外本文はSnapshotやログへ出さない。ホスト機能のない端末は `UNAVAILABLE`、列挙失敗は `FAILED`、終了後は空の `STOPPED` となる。未実施の読取は `NOT_READ`、進行中は `READING` と区別する。

許可要求と読取ごとに別のトークンを付け、取消・タイムアウト・抜去・新しい読取で無効化する。返答のトークンと接続IDを検査し、許可の成功返答でも `hasPermission` を再確認する。再接続時のIDは新しく発行し、古い許可返答・読取結果で上書きしない。処理順は単一のコマンドconsumerで確定する。

`cancelPermission` はアプリ側の要求を取り消し、古いPendingIntentを無効化する。Androidのシステムダイアログを閉じたり、OSが既に付与した許可を取り消すAPIではない。同期的なAndroid読取は戻るまで中断できないが、その後に取消を検査し、connectionを `finally` で閉じる。interfaceのclaim、alternate設定の変更、USB転送、再生・音量変更は行わない。

このIssueでは機器取得用APIまでを実装する。Activityからの生成・表示・利用者操作はUIの担当Issueで接続し、アプリ起動だけでこのmonitorが稼働しているとは扱わない。

## 出典とプライバシー

`UsbDeviceRecord` は接続中のUSB機器であり、共通契約の `AudioRouteSnapshot` と別の型である。音源の形式、選択要求、実際の出力先・動作形式、DAC実測やBit-perfectの成立を変更しない。

- VID/PIDは `UsbDevice`、構成・interface・alternate・class/subclass/protocol・出力endpointは `UsbConfiguration / UsbInterface / UsbEndpoint` の申告。
- メーカー名・製品名は許可後の `UsbDevice.manufacturerName / productName`。非公開・空文字はnull。
- 形式能力は `UsbDeviceConnection.rawDescriptors` の申告。すべて既存の `Measured` の `OS_REPORTED` と出典名・取得時刻を持ち、取得不能はnull＋`UNKNOWN` とする。
- `isAudioOutputCandidate` はAudioクラスのAudioStreaming interfaceと、データ用のisochronous OUT endpointがある場合だけtrue。HID、ストレージ、MIDI、入力専用、feedback endpointをDAC候補としない。出力可能性の申告であり、実際の音声経路やハードウェアの成立を保証しない。
- ハードウェアgainは照会しない。`hardwareGainDb = null`、`gainSetting = MANUAL` を返す。UIでは「手動設定」と表示し、ソフトウェア音量や物理ゲインを推定・換算しない。
- `serialNumber` とserial string descriptorは取得しない。raw descriptorをSnapshot・診断・共有へ公開せず、Androidの機器パスも公開しない。`connectionId` は接続寿命内だけのランダムIDで、恒久IDや自動プロファイル一致に使わない。

Manifestには任意のUSB host featureだけを追加し、追加の権限やUSB起動フィルターは設けない。許可返答はpackageを限定した一意URIのPendingIntentと非公開receiverで受ける（API 33以降は `RECEIVER_NOT_EXPORTED`、それ以前もトークンを照合）。システムのattach/detach receiverは保護されたUSBアクションだけを受ける。

## descriptorの取得範囲

UAC1 Type Iのencoding、subframeサイズ、bit depth、channel数、離散レートまたは連続レート範囲を解析する。UAC2 Type Iはencoding、subslotサイズ、bit depth、channel数までを解析する。各configuration/interface/alternateを別の能力として保持し、組合せを合成しない。入力用endpointは出力能力に含めない。

UAC2のclock controlによるレート、UAC3、Type II/III、vendor独自形式、ゲイン・動作モードのcontrol照会は未実装であり、推定しない。UAC2のrateや未知のencodingはnull、未対応の出力形式は欠落した状態の `PARTIAL` とする。Type Iを解析できたことだけで全能力の網羅を表さない。

全長、descriptor長、フィールド長、レート数、ゼロレート、bit数、channel数等の境界を検査する。構造不正なら `MALFORMED`＋空の形式一覧を返し、途中まで読めた能力を成功として残さない。raw descriptor非公開の場合は能力自体がnullであり、空の対応形式として確定しない。

仕様未指定の実装提案は、配置 `audio/usb`、Snapshot・状態enum・接続ID・トークンの型、許可待機30秒、parser入力上限1MiB。待機時間や上限は取得workerの復旧・資源制限であり、製品の音声性能・USB転送上限ではない。新規依存、DB、native USB backendは追加しない。

## 検証と引継ぎ

2026-10-31の検証対象commit・CI URLは対応PRの「証跡」に記録する。各結果はこのIssueの範囲に限定し、TEST-DEVICE-01全体の完了とはしない。

| 要求/試験 | 固定fixtureで検査する範囲 | 証跡 |
| --- | --- | --- |
| FR-DEV-02 / TEST-DEVICE-01 | UAC1離散/連続レート、UAC2不明レート、構成/alternate分離、非audio・入力・feedback除外 | `UsbAudioDescriptorParserTest` |
| FR-DEV-02 / TEST-DEVICE-01 | 切り詰め、不正長・無効値、1MiB上限、固定seedによる不正入力2000件、取消 | `UsbAudioDescriptorParserTest` |
| FR-DEV-02 / TEST-DEVICE-01 | 無断promptなし、拒否・取消・timeout・再試行、古い返答、読取途中の抜去/同じパスへの再接続 | `UsbDeviceMonitorTest`（実monitor＋FakeHost） |
| FR-DEV-02 / TEST-DEVICE-01 | open失敗・読取例外・許可失効・再列挙、停止・PendingIntent取消、host非対応、gain手動 | `UsbDeviceMonitorTest` |
| NFR-SEC-01 / TEST-SEC-01 | 公開Snapshot/JSONにserial・機器パス・例外本文を出さない、OSの許可を再検査、実AudioRoute/Bit-perfectを変更しない | `UsbDeviceMonitorTest`＋AndroidUsbHostのコード確認 |

上記はホストJUnitでの単体・契約試験である。Androidのpermission dialog/receiver/PendingIntentと実UsbManagerを通すAPI23/API36 instrumented試験は **NOT_RUN**（接続された試験環境・明示ADBシリアルなし）。Galaxy S25 / FiiO M21 / S20、ZEN DAC V2 / ECHO-B / ECHO-Aの取得・抜去試験も **NOT_RUN**（指定シリアル・実機fixtureなし）。実機接続、USB転送、音声出力・品質やBit-perfectの実測成功を示さない。CIのbuild/unit/lint成功でもこの制約は解消しない。

後続の #42 / #70 はこのSnapshotのOS申告と実際の経路を区別して接続する。#43 / #97 / #120 は機器属性を共有前に提示し、曖昧な識別を無断確定せず、手動gainと取得不能値を維持する。native backendによるUAC2 clock/ハードウェアcontrol照会は、本取得APIの成功から実装済みと扱わない。
