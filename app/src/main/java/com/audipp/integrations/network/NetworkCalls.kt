package com.audipp.integrations.network

import com.audipp.data.secrets.SecretFailure
import com.audipp.data.secrets.SecretStorageException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class NetworkCallPolicy(
    val operationTimeoutMs: Long = 30_000,
    val connectTimeoutMs: Long = 10_000,
    val maxAttempts: Int = 3,
    val initialBackoffMs: Long = 250,
    val maxBackoffMs: Long = 2_000,
) {
    init {
        require(operationTimeoutMs in 1..60_000 && connectTimeoutMs in 1..60_000)
        require(maxAttempts in 1..5 && initialBackoffMs in 1..10_000 && maxBackoffMs in initialBackoffMs..10_000)
    }
}

class NetworkCalls(val policy: NetworkCallPolicy = NetworkCallPolicy()) {
    internal suspend fun <T> run(
        retrySafe: Boolean = true,
        timeoutMs: Long = policy.operationTimeoutMs,
        rejection: () -> NetworkFailure? = { null },
        operation: suspend () -> NetworkResult<T>,
    ): NetworkResult<T> {
        var backoff = policy.initialBackoffMs
        repeat(if (retrySafe) policy.maxAttempts else 1) { attempt ->
            currentCoroutineContext().ensureActive()
            rejection()?.let { return NetworkResult.Failure(it) }
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.IO) { operation() }
                } ?: NetworkResult.Failure(NetworkFailure.TIMEOUT)
            } catch (error: SecretStorageException) {
                NetworkResult.Failure(if (error.failure == SecretFailure.AUTH_REQUIRED) NetworkFailure.AUTH_REQUIRED else NetworkFailure.IO)
            } catch (_: IOException) {
                NetworkResult.Failure(NetworkFailure.IO)
            } catch (_: SecurityException) {
                NetworkResult.Failure(NetworkFailure.PERMISSION_REQUIRED)
            }
            currentCoroutineContext().ensureActive()
            rejection()?.let { return NetworkResult.Failure(it) }
            if (result !is NetworkResult.Failure || !retrySafe || attempt == policy.maxAttempts - 1 ||
                result.reason !in setOf(NetworkFailure.OFFLINE, NetworkFailure.IO, NetworkFailure.TIMEOUT)
            ) return result
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(policy.maxBackoffMs)
        }
        error("Unreachable")
    }
}
