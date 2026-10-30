# 秘密保管 v1（Issue #28）

対象: [Issue #28](https://github.com/4rtoflow/Audipp/issues/28)。要件1.0.1の NFR-SEC-02 / NFR-PRIV-01 → DD-DB-03 / 08_design_decisions.md「秘密情報の保存」→ TEST-SEC-01 の端末内秘密保管部分。共通契約 #16 を前提とし、単一app内の `data/secrets` へ実装する。

## 保存契約と実装提案

`Context.secretStore` はapplication Contextからプロセス内で1つのストアを生成する。ディスク・Keystore・暗号操作は `Dispatchers.IO` で行い、Mutexで直列化する。複数プロセスでの共有は提供しない。新しい依存・権限・通信は追加しない。

**以下は本Issueの実装提案**: アカウントごとのAES-256鍵、SHA-256でハッシュ化したファイル名と鍵alias、4種のレコード（ACCESS_TOKEN / REFRESH_TOKEN / SESSION_KEY / PASSWORD）、秘密1件あたり1〜65536 bytes、account IDのUTF-8長1〜1024 bytes、バイナリschema v1、セッションhandleと状態API。IDには認証側が割り当てる提供元込みの一意なaccount IDを渡し、不正なサロゲート文字列を拒否する。メール一致だけで別アカウントを同一視しない。ハッシュはID自体の匿名性を保証するものではない。

- 鍵: Android Keystoreの `audipp.secrets.v1.<account hash>`。暗号化・復号だけを許可し、GCM / NoPadding / randomized encryptionを必須にする。毎回の生体認証は要求せず、background refreshに利用できる。StrongBoxやhardware backingの有無は未測定。
- 暗号文: `noBackupFilesDir/secrets-v1/<account hash>/<kind>.bin`。平文・account ID・tokenをファイル名へ含めない。version（4 bytes）、providerが毎回生成する96-bit nonce、暗号文と128-bit tagを保存する。
- AAD: version、record種別、account IDを長さ付きで符号化する。他account・別種別の暗号文流用、未知版、改変・切詰め・上限超過を拒否する。復号時に欠落した鍵を再生成しない。
- 保存: 同じディレクトリの一時ファイルへ暗号文だけを書き、file sync後のrenameで置換する。失敗時は一時ファイルを除去する。アカウント内の複数レコードをまとめたtransactionや電源断時のdirectory syncまでは保証しない。

[Android Keystoreの公式仕様](https://developer.android.com/privacy-and-security/keystore)と[鍵生成パラメーター](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)に従う。[noBackupFilesDirはAuto Backup対象外](https://developer.android.com/identity/data/autobackup#Files)であり、既存Manifestでもbackupを無効化し、API31以降のcloud backup / device transferを全domainで除外している。鍵はexportしない。秘密保管層に同期・診断・export APIは設けない。後続のexport/診断処理もこの領域やcallback内の平文を収録してはいけない。

## 利用と失効

```kotlin
val store = context.secretStore
// 本人認可成功時だけ。旧セッションを失効し、当該accountの秘密と鍵を置換する。
val session = store.authorize(accountId, SecretKind.REFRESH_TOKEN, tokenBytes)
store.use(session, SecretKind.REFRESH_TOKEN) { refreshToken ->
    val updated = provider.refresh(refreshToken)
    store.update(session, SecretKind.REFRESH_TOKEN, updated)
}
// refresh資格の失効・取消時。古い応答から新しい本人認証を削除しない。
store.requireAuthentication(session)
```

認可方式・tokenの有効期限・providerへのlogout要求は後続Adapterが所有する。`authorize`は明示的な本人再認可の入口であり、古いrefresh callbackから呼び直さない。通常のrefresh保存は元のsessionによる `update` を使う。複数種別が必要な場合も当該sessionで追加保存する。

Issue #49で接続した認証エラーの入口には `requireAuthentication(session)` を使う。現在のsessionと一致する場合だけ失効・削除し、古い応答から新しい本人再認証を削除しない。既存の `requireAuthentication(accountId)` は利用者による当該アカウント全体のlogoutに使える。同じsessionの削除失敗後は再試行できる。

再起動後は `resume(accountId)` でhandleを得て `use` する。`READY` は失効していないhandleの状態であり、認証成功・復号成功の証拠ではない。実際の秘密が必要な操作は必ず `use` から行う。同じプロセスでAUTH_REQUIREDになったhandleはresumeで再有効化されず、明示的なauthorizeだけで置換できる。

復号失敗・鍵欠落/無効化・provider失敗・秘密欠落は `SecretStorageException(AUTH_REQUIRED)`。ディスクIO障害は `SecretStorageException(IO)`。いずれもsessionのStateFlowをAUTH_REQUIREDへ移し、保留中の `use` を取消す。例外には元の本文・パス・鍵alias・account ID・causeを含めない。破損を平文保存や空値の成功へ置き換えない。読取失敗では暗号文を保持し、再認可で置換する。

失効操作は先にhandleを無効化・処理を取消し、対象accountの鍵と暗号ディレクトリだけを削除する。他account、設定、local library/historyには触れない。削除エラーは成功にしない。削除が失敗した場合は再試行が必要で、プロセス終了をまたぐ削除完了は保証しない。削除完了前にUIが「ログアウト完了」と表示してはいけない。

取消は協調動作。外部要求の取り消し可否や、既に相手へ届いた要求の撤回は保証しない。失効前に開始した応答は旧handleでの保存を拒否する。`use` 内で保持した復号bufferは成功・例外・取消時にゼロクリアする。呼出側は平文をcallback外へ返す・コピーする・ログへ載せることを避ける。呼出側が所有する入力buffer、provider内部のコピーや文字列、JCA内部bufferまでゼロ化を保証しない。取消とディスクcommitが競合した場合は成功/rollbackを断定せず、新しいhandleから再確認する。

## 試験と残る検証

`SecretStoreTest` は実JVM AES-GCM、実一時ファイル、差し替え可能な鍵管理境界を用いる。再open、nonceの毎回更新、全種別、AADのaccount/種別束縛、tag改変・切詰め・未知版・上限、鍵喪失/置換/無効化、provider/IO失敗、対象accountだけの削除、旧sessionの拒否、保留refreshの取消・遅延保存拒否、呼出元取消とbuffer清掃、入力境界を検査する。

引き渡しの実行結果・対象commit・CIは対応PRの証跡へ記録する。JVM試験はAndroid Keystoreの実行証拠ではない。API23/36での実Keystore、実プロセス再起動、backup/restore、画面の再認証表示、実providerと期限監視の接続は未実施。指定ADB serialがないため端末操作は行わない。後続 #45 / #49 / #79 / #99 / #114 / #116で各入口へ接続し、#150で秘密漏洩監査を行う。音声sampleを変更しないためDAC・音質測定は本保管層には不適用。Issue #28 / TEST-SEC-01全体の完了とは扱わない。
