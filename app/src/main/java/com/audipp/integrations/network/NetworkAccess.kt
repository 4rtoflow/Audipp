package com.audipp.integrations.network

internal class NetworkAccess(
    private val endpoint: NetworkEndpoint,
    private val calls: NetworkCalls,
    private val credentials: NetworkCredentials?,
    private val credential: NetworkCredential?,
) {
    init { require(credential == null || credentials != null) }

    suspend fun <T> call(
        retrySafe: Boolean = true,
        timeoutMs: Long = calls.policy.operationTimeoutMs,
        rejection: () -> NetworkFailure? = { null },
        operation: suspend (ByteArray?) -> NetworkResult<T>,
    ): NetworkResult<T> = calls.run(retrySafe, timeoutMs,
        rejection = { rejection() ?: credentials?.rejection(endpoint, credential) },
    ) {
        val result = if (credentials == null) operation(null) else credentials.use(endpoint, credential, operation)
        if (result is NetworkResult.Failure && result.reason == NetworkFailure.AUTH_REQUIRED && credential != null) {
            credentials!!.requireAuthentication(credential)
        }
        result
    }
}
