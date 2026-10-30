package com.audipp.data.secrets

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SecretState { READY, AUTH_REQUIRED }
enum class SecretFailure { AUTH_REQUIRED, IO }

class SecretStorageException(val failure: SecretFailure) : IOException("Secret storage: ${failure.name}")

class SecretSession internal constructor(internal val accountId: String) {
    internal val mutableState = MutableStateFlow(SecretState.READY)
    internal val pending = mutableSetOf<Job>()
    val state: StateFlow<SecretState> = mutableState.asStateFlow()
    override fun toString() = "SecretSession"
}

class SecretStore internal constructor(private val files: SecretFiles, private val keys: SecretKeys) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, SecretSession>()

    suspend fun resume(accountId: String): SecretSession = withContext(Dispatchers.IO) {
        validateAccount(accountId)
        mutex.withLock {
            sessions.getOrPut(accountId) { SecretSession(accountId) }
        }
    }

    suspend fun authorize(accountId: String, kind: SecretKind, value: ByteArray): SecretSession {
        validateAccount(accountId)
        require(value.isNotEmpty() && value.size <= SecretCipher.MAX_SECRET_BYTES)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                sessions[accountId]?.let { invalidate(it) }
                val session = SecretSession(accountId)
                sessions[accountId] = session
                storage(session) {
                    val hash = SecretCipher.accountHash(accountId)
                    keys.delete(alias(hash))
                    files.delete(hash)
                    val record = SecretCipher.encrypt(keys.create(alias(hash)), accountId, kind, value)
                    files.write(hash, kind, record)
                }
                session
            }
        }
    }

    suspend fun update(session: SecretSession, kind: SecretKind, value: ByteArray) = withContext(Dispatchers.IO) {
        require(value.isNotEmpty() && value.size <= SecretCipher.MAX_SECRET_BYTES)
        mutex.withLock {
            checkSession(session)
            storage(session) {
                val hash = SecretCipher.accountHash(session.accountId)
                val key = keys.find(alias(hash)) ?: throw GeneralSecurityException()
                files.write(hash, kind, SecretCipher.encrypt(key, session.accountId, kind, value))
            }
        }
    }

    suspend fun <T> use(
        session: SecretSession,
        kind: SecretKind,
        operation: suspend (ByteArray) -> T,
    ): T = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        var value: ByteArray? = null
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    checkSession(session)
                    value = storage(session) {
                        val hash = SecretCipher.accountHash(session.accountId)
                        val record = files.read(hash, kind) ?: throw GeneralSecurityException()
                        val key = keys.find(alias(hash)) ?: throw GeneralSecurityException()
                        SecretCipher.decrypt(key, session.accountId, kind, record)
                    }
                    session.pending.add(job)
                }
            }
            val result = operation(value!!)
            currentCoroutineContext().ensureActive()
            mutex.withLock { checkSession(session) }
            result
        } finally {
            value?.fill(0)
            withContext(NonCancellable) { mutex.withLock { session.pending.remove(job) } }
        }
    }

    suspend fun requireAuthentication(accountId: String) = withContext(Dispatchers.IO) {
        validateAccount(accountId)
        mutex.withLock {
            val session = sessions.getOrPut(accountId) { SecretSession(accountId) }
            invalidate(session)
            storage(session) {
                val hash = SecretCipher.accountHash(accountId)
                keys.delete(alias(hash))
                files.delete(hash)
            }
        }
    }

    private fun checkSession(session: SecretSession) {
        if (sessions[session.accountId] !== session || session.state.value != SecretState.READY) {
            throw SecretStorageException(SecretFailure.AUTH_REQUIRED)
        }
    }

    private fun invalidate(session: SecretSession) {
        session.mutableState.value = SecretState.AUTH_REQUIRED
        session.pending.forEach { it.cancel(CancellationException("Authentication required")) }
        session.pending.clear()
    }

    private inline fun <T> storage(session: SecretSession, operation: () -> T): T = try {
        operation()
    } catch (_: GeneralSecurityException) {
        invalidate(session)
        throw SecretStorageException(SecretFailure.AUTH_REQUIRED)
    } catch (_: ProviderException) {
        invalidate(session)
        throw SecretStorageException(SecretFailure.AUTH_REQUIRED)
    } catch (_: IOException) {
        invalidate(session)
        throw SecretStorageException(SecretFailure.IO)
    }

    private fun validateAccount(accountId: String) {
        val bytes = accountId.toByteArray(Charsets.UTF_8)
        // Lossy UTF-8 encoding would let different malformed IDs share a key and directory.
        require(accountId.isNotBlank() && bytes.size <= 1024 && bytes.toString(Charsets.UTF_8) == accountId)
    }

    private fun alias(hash: String) = "audipp.secrets.v1.$hash"
}
