package com.audipp.data.secrets

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class SecretFiles(private val root: File) {
    fun read(account: String, kind: SecretKind): ByteArray? {
        val file = file(account, kind)
        if (!file.exists()) return null
        return file.inputStream().use { input ->
            val bytes = ByteArray(SecretCipher.MAX_RECORD_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            bytes.copyOf(count)
        }
    }

    fun write(account: String, kind: SecretKind, value: ByteArray) {
        val target = file(account, kind)
        val directory = target.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
        val temporary = File.createTempFile("pending-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use {
                it.write(value)
                it.fd.sync()
            }
            // Same-directory rename keeps a failed write from truncating the previous record.
            if (!temporary.renameTo(target)) throw IOException()
        } finally {
            temporary.delete()
        }
    }

    fun delete(account: String) {
        val directory = File(root, account)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException()
    }

    private fun file(account: String, kind: SecretKind) = File(File(root, account), "${kind.name}.bin")
}
