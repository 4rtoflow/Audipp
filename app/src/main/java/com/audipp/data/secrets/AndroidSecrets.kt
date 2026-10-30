package com.audipp.data.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private class AndroidSecretKeys : SecretKeys {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun find(alias: String): SecretKey? = store().getKey(alias, null) as? SecretKey

    override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generateKey()
    }

    override fun delete(alias: String) = store().deleteEntry(alias)
}

@Volatile private var appSecretStore: SecretStore? = null

val Context.secretStore: SecretStore
    get() = appSecretStore ?: synchronized(SecretStore::class.java) {
        appSecretStore ?: SecretStore(
            SecretFiles(File(applicationContext.noBackupFilesDir, "secrets-v1")),
            AndroidSecretKeys(),
        ).also { appSecretStore = it }
    }
