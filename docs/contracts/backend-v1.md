# 業務API・本人所有権 v1（Issue #34）

対応: FR-USE-03、NFR-SEC-03、NFR-MAINT-01、DD-CLOUD-01/03、ADR-005/006、R-04、TEST-ACC-01/SEC-01。以下は#34の入口基盤の実装提案と検証範囲であり、認証・同期・削除の試験ファミリー全体の完了を意味しない。

## 配置と版のImplementation Proposal

`server/`を独立したKotlin/JVM Gradleビルドにする。ルートのWrapperを共用し、Android側の単一`app`、カタログ、公開domain型は分割・変更しない。#16のSource/Playback/Evidenceは音声用契約であり、認証IDへ流用しない。業務APIは音源情報・再生経路・Bit-perfectを扱わない。

JDK 17、Kotlin 2.3.10、Ktor 3.4.3（Netty）、pgJDBC 42.7.14、Auth0 java-jwt 4.5.1 / jwks-rsa 0.24.1を固定した。Ktorは既存Kotlin 2.3系と対応する3.4系のpatch版を提案する。[当該版の依存定義](https://github.com/ktorio/ktor/blob/3.4.3/gradle/libs.versions.toml)、[JWKライブラリrelease](https://github.com/auth0/jwks-rsa-java/releases/tag/0.24.1)、[pgJDBC](https://jdbc.postgresql.org/download/)を照合し、推移依存を`server/gradle.lockfile`へ固定する。新しい依存をAndroid APKには入れない。

ローカル試験だけにembedded-postgres 2.2.2 / PostgreSQL 14.22.0を使う。専用の一時プロセス・データディレクトリ・空きポートを作り、終了時に閉じる。Docker、既存DB、sudo、常設サービスを操作しない。rootでの試験はPostgreSQL自体が拒否する。

配備形態はADR-005のAudipp専用Composeを維持する。実環境のKeycloak/PostgreSQL/JREイメージ版・digest、Debian/Dockerとの適合、HTTPS終端とバックアップ先は**配備PRで固定する未検証入力**。本PRには配備用イメージや架空のdigestを置かない。代わりにローカル配布物`audipp-server.tar`を`distTar`で作りSHA-256を記録し、CIに7日保存する。これはイメージのdigestでも配備成功でもない。ホスト情報なしでAPI/SQL/HTTPS試験を実行できる。

## HTTP/JSON契約

外部通信はHTTPS。起動プログラムはPKCS12を使うTLS 1.2/1.3のコネクターだけを作る。平文リスナーや`X-Forwarded-Proto`によるHTTPS偽装の受理はない。`testApplication`はインメモリの契約試験であり、別に実リスナーのTLS試験を行う。

| API | 入力 | 本人スコープの出力 |
|---|---|---|
| `GET /v1/me` | Bearer access token | `user_id`（UUID文字列）、`revision`（正の64bit整数）、`created_at`（UTC、末尾Z） |
| `POST /v1/auth/session` | Bearer + JSON `{"device_id":"正規形の小文字UUID"}` | `{"user":上記ユーザー,"device":端末}`。端末は`device_id/revision/created_at` |
| `GET /v1/me/devices/{device_id}` | Bearer + 正規形UUID | 本人が登録済みの端末だけ |

`session`はKeycloakトークンを内部ユーザー・端末に結び付ける。パスワード受付、token発行・refresh、ID連携を行わない。`GET /v1/me`も初回の本人解決時に業務ユーザーを作る。全経路で署名検証済みissuer/subjectだけを使う。token中のemailやuser_idは無視する。queryは未対応で400、POSTの未知JSONキー（user_id/revisionを含む）・欠落・不正UUIDは400。JSON本文上限は1 KiB、超過は413。成功/拒否とも`Cache-Control: no-store`を付ける。

署名は設定したKeycloak realmのHTTPS JWKSでRS256を検証し、issuer完全一致、API用audience、`exp`必須/有効期限、`nbf`、payloadの`typ=Bearer`、非空・制御文字なしで最大255 UTF-16単位のsubjectを検査する。ID token、HMAC、別RSA鍵、未知kid、別issuer/audience、期限切れ、欠落subjectは401。JWKSのURLは設定issuerから固定しtokenのURLを使わない。鍵取得はIO dispatcher上で行い、connect/read timeout 1/2秒、最大10鍵を1時間cache、取得を毎分10回に制限する。鍵取得の通信障害・制限超過は503。ライブラリ内部のJWKS保持もあるため、cacheのTTLを鍵撤回の検知期限とはしない。JWTの即時失効/session logoutの連動は後続認証Issueで検証する。

| HTTP | 意味 / clientの扱い |
|---|---|
| 200 | DB commit後の確定結果 |
| 400 / 413 | 入力不正 / サイズ超過。入力を直す |
| 401 | 認証不成立。`WWW-Authenticate: Bearer`。DB変更なし |
| 403 | 無効化済み本人、他人/未登録の端末。認証refreshだけで自動再試行しない |
| 404 | 未提供のURL（任意のuser_idを指定するAPIも含む） |
| 503 | DB timeout/一時障害、鍵取得一時障害。`Retry-After: 1`。同じdevice_idで再試行可能 |
| 500 | 想定外の失敗。内部例外・SQL・秘密を応答しない |

既知のエラー本文は`{"error":"unauthorized|forbidden|invalid_request|payload_too_large|temporarily_unavailable|internal_error"}`。未知URLの404はKtor既定。自動再試行ループはサーバーに実装しない。

## SQL・所有権・原子性

`V001__ownership.sql`の提案型はuser/device IDをUUID、日時を`timestamptz`、revisionを共通sequenceの`bigint`とする。sequenceの欠番は許容し、連続性や端末時計に依存しない。issuerは最大1024 UTF-8 bytesとし、subjectと合わせてPostgreSQLのB-tree索引上限を超えないようにする。identityの主キーは大文字小文字を区別する`(issuer,subject)`であり、メール列・メール索引はない。user_idへのFKと所有者索引を置き、devicesの全取得は認証本人のuser_idとのAND条件を使う。

初回の同一identityはtransaction advisory lockと一意制約で直列化し、余分なuserを残さない。異なるissuer/subjectは別userを作る。連携APIは提供せず、既存identityの別userへの移動やメール一致による統合は受け付けない。Keycloak側の自動メール連携の無効化・再認証/明示連携は認証担当Issueの責務であり、業務APIが上流の誤設定を検出できるとはしない。

端末UUIDは全体で一意。他人が同じIDをPOSTしても再割当てしない。GETの他人/未登録端末は同じ403を返し、存在を区別しない。sessionの本人解決と端末登録は同一transactionで、後段が拒否/失敗すれば初回user/identityもrollbackする。同じ本人・端末の再送は元のrevision/日時を返す。

JDBC処理は最大8並列のIO dispatcherに置く。接続2秒/ソケット5秒、transaction内のstatement 2秒/lock 500 msで待機を限定し、commit直前の取消を検査する。例外・取消はrollbackして接続を閉じる。commit中の通信断/取消では到達不明になり得るため、同一device_idでの再送で確定結果へ収束させる。API応答の受領自体は取消で保証しない。アカウント削除世代や同期cursorは後続Issueであり、このsequenceを同期プロトコル完成と扱わない。

migrationはtransaction advisory lock下でSQLと版/SQLのSHA-256を同時commitする。再起動では同じ版を再適用せず、checksum不一致は起動失敗にする。失敗後はschemaと版がrollbackされる。既存のV001を書き換えて修復せず、原因を取り除いて再起動する。後続migrationの追加は別PRで実装する。自動rollback/downや本番DBの破壊的再作成は行わない。

## 起動条件と認証DB分離

本番用の値・秘密はGitへ置かず環境へ渡す。必要な変数:

- `AUDIPP_DB_HOST/PORT/NAME/USER/PASSWORD/SSLMODE`: 業務DB。SSLMODEは`verify-full`、専用ローカル/閉じたコンテナーネットワークでのみ`disable`を選べる。URLパラメーターによる接続userの上書きを使わない。
- `AUDIPP_AUTH_DB_NAME/USER`: 同じPostgreSQL cluster内の認証用DB/roleの**名前だけ**。認証DBのパスワードをAPIへ渡さない。
- `AUDIPP_ISSUER/AUDIENCE`: HTTPSのKeycloak realmとAPI対象audience。
- `AUDIPP_TLS_STORE/PASSWORD/ALIAS`: 外部ファイルのPKCS12、store/key共通passwordとalias。
- `AUDIPP_BIND_HOST/TLS_PORT`: 明示bind先、非特権port 1024〜65535。

管理者が専用DB/roleを別に作り、認証DBの`CONNECT`をPUBLICから取り消し、認証roleだけに許可する。業務roleは業務DB/schemaを所有し、認証roleのmemberにならず、superuser/createdb/createroleを持たない。起動時に現接続のDB/userとの相違、role権限、認証DBへのCONNECT不可・認証roleへのmembershipなしをDBへ照会する。一つでも満たさなければmigration前に停止する。別cluster構成はこの初期実装では未対応。実際のDB作成・権限変更は本PRでは行わず、一時試験DBのみを対象にした。

```sh
# JDK 17を指定。SDK不要、リポジトリルートから実行。
./gradlew -p server test installDist distTar
# 環境とTLSファイルを準備した専用開発環境でのみ起動する。
server/build/install/audipp-server/bin/audipp-server
sha256sum server/build/distributions/audipp-server.tar
```

依存変更時だけ`--write-locks`を追加し、通常はlockを更新しない。ログにAuthorization/body/subject/emailを出すCallLoggingやSQL loggingを入れない。応答には内部ID・revision・登録日時以外のトークン/秘密/メール/認証URIを含めない。

## 依存ライセンス

直接依存とlockの推移依存のPOMを確認する。製品runtimeはKotlin/kotlinx・Ktor・Netty・Jackson・Guava・Typesafe Config・Jansi・各種annotations（Apache-2.0）、Jetty ALPN（Apache-2.0 / EPL-1.0の二重ライセンス）、Auth0・SLF4J・Checker（MIT）、pgJDBC（BSD-2-Clause）を含む。テスト専用のJUnit（EPL-1.0）、Hamcrest（BSD）、embedded-postgres（Apache-2.0）、PostgreSQL binariesのwrapper（Apache-2.0）と同梱PostgreSQL（PostgreSQL license）、Commons（Apache-2.0）、XZ（0BSD）は配布tarに含めない。各artifactのLICENSE/NOTICEは元のJAR内を保持する。これはライセンス/依存の確認範囲であり、脆弱性診断や配備イメージの監査完了を表さない。

## 試験・引き継ぎ

`server/src/test/kotlin/com/audipp/server/OwnershipTest.kt`で実PostgreSQLを使用する。結果と対象commit/CIはPR証跡に記録する。

| 要求/試験 | #34で確認する観測結果 |
|---|---|
| FR-USE-03 / DD-CLOUD-01 / TEST-ACC-01 | 同じ本人・端末の再送が同一結果、同時初回ログインが1user/identityへ収束、同じメールでも別user、別issuerの同じsubjectも別user、DB一意制約で既存identityの追加を拒否 |
| DD-CLOUD-03 / TEST-ACC-01/SEC-01 | 別本人のGET/POSTが403、client指定user_id/任意所有者URLを拒否、disabled本人403、JSON/UUID/本文上限を検査 |
| NFR-SEC-03 / TEST-SEC-01 | RSA署名・issuer/audience/exp/nbf/type/subject/kidの拒否、鍵通信失敗503、認証前のDB未変更、応答の秘密/メール非包含、認証DBへの接続不可、同一DB/user・superuser設定拒否 |
| NFR-SEC-03 / TEST-SEC-01 | 実HTTPSコネクターで認証JSON応答、TLS1.2/1.3、不信証明書拒否、平文接続拒否 |
| NFR-MAINT-01 / TEST-ACC-01 | migrationの初回失敗rollback/修復再試行/再起動/checksum、DB lock timeout503と部分変更なし、取消rollback、同一端末での再試行成功、CI・依存lock |

未実施: 実Keycloak realm/招待・復旧・Google・PKCE/Android接続、実運用HTTPS終端、配備/監視/バックアップ・復旧、account連携・削除/失効プロトコル、同期/共有/用途別許諾。これらは#79/#115/#131および#47以降の担当であり、成功表示しない。端末・DAC・音声測定は本Issueでは不適用。実機serialを使用していない。
