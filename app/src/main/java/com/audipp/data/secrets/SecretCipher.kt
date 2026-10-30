package com.audipp.data.secrets

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SecretKind { ACCESS_TOKEN, REFRESH_TOKEN, SESSION_KEY, PASSWORD }

internal interface SecretKeys {
    fun find(alias: String): SecretKey?
    fun create(alias: String): SecretKey
    fun delete(alias: String)
}

internal object SecretCipher {
    const val MAX_SECRET_BYTES = 65536
    const val MAX_RECORD_BYTES = 4 + 12 + MAX_SECRET_BYTES + 16

    fun encrypt(key: SecretKey, accountId: String, kind: SecretKind, value: ByteArray): ByteArray {
        require(value.isNotEmpty() && value.size <= MAX_SECRET_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // The provider chooses a fresh IV; Android Keystore rejects caller-supplied encryption IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(accountId, kind))
        val encrypted = cipher.doFinal(value)
        if (cipher.iv.size != 12) throw GeneralSecurityException()
        return ByteBuffer.allocate(4 + 12 + encrypted.size)
            .putInt(1).put(cipher.iv).put(encrypted).array()
    }

    fun decrypt(key: SecretKey, accountId: String, kind: SecretKind, record: ByteArray): ByteArray {
        if (record.size !in 33..MAX_RECORD_BYTES) throw GeneralSecurityException()
        val buffer = ByteBuffer.wrap(record)
        if (buffer.int != 1) throw GeneralSecurityException()
        val iv = ByteArray(12).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(accountId, kind))
        return cipher.doFinal(record, 16, record.size - 16)
    }

    private fun aad(accountId: String, kind: SecretKind): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use { output ->
            output.writeInt(1)
            output.writeUTF(kind.name)
            output.writeUTF(accountId)
        }
    }.toByteArray()

    fun accountHash(accountId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
