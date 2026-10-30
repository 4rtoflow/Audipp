package com.audipp.integrations.network

import com.audipp.data.secrets.SecretKind
import com.audipp.data.secrets.SecretSession
import com.audipp.data.secrets.SecretState
import com.audipp.data.secrets.SecretStore
import com.audipp.data.secrets.SecretFailure
import com.audipp.data.secrets.SecretStorageException
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow

class NetworkCredential internal constructor(
    internal val scope: String,
    internal val session: SecretSession,
) {
    val state: StateFlow<SecretState> get() = session.state
    override fun toString() = "NetworkCredential(redacted)"
}

class NetworkCredentials(private val store: SecretStore) {
    suspend fun authorize(endpoint: NetworkEndpoint, accountKey: String, credential: ByteArray): NetworkCredential {
        val account = account(endpoint, accountKey)
        return NetworkCredential(scope(endpoint), store.authorize(account, SecretKind.PASSWORD, credential))
    }

    suspend fun resume(endpoint: NetworkEndpoint, accountKey: String): NetworkCredential {
        val account = account(endpoint, accountKey)
        return NetworkCredential(scope(endpoint), store.resume(account))
    }

    suspend fun requireAuthentication(credential: NetworkCredential) = store.requireAuthentication(credential.session)

    internal fun rejection(endpoint: NetworkEndpoint, credential: NetworkCredential?): NetworkFailure? = when {
        credential == null -> null
        credential.scope != scope(endpoint) -> NetworkFailure.AUTH_REQUIRED
        credential.state.value != SecretState.READY -> NetworkFailure.AUTH_REQUIRED
        else -> null
    }

    internal suspend fun <T> use(
        endpoint: NetworkEndpoint, credential: NetworkCredential?, operation: suspend (ByteArray?) -> T,
    ): T {
        if (rejection(endpoint, credential) != null) throw SecretStorageException(SecretFailure.AUTH_REQUIRED)
        return if (credential == null) operation(null)
            else store.use(credential.session, SecretKind.PASSWORD) { operation(it) }
    }

    private fun scope(endpoint: NetworkEndpoint) = "${endpoint.protocol.name}\u0000${endpoint.uri}"

    private fun account(endpoint: NetworkEndpoint, accountKey: String): String {
        requireNetworkText(accountKey)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${scope(endpoint)}\u0000$accountKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "lan:v1:$hash"
    }
}
