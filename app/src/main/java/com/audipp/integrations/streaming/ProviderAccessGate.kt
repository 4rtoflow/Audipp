package com.audipp.integrations.streaming

import com.audipp.data.preferences.ProviderPolicyStore
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.ProviderCapabilities
import com.audipp.domain.model.ProviderPolicySnapshot
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ProviderAccessFailure {
    DENIED, UNREVIEWED, EXPIRED, UNSUPPORTED, STALE_POLICY,
    AUTH_REQUIRED, RATE_LIMITED, HTTP_FAILURE, TIMEOUT, IO, STORAGE_UNAVAILABLE,
}

sealed interface ProviderCallResult<out T> {
    // A provider payload may contain credentials, private metadata or a signed URL.
    class Value<T>(val value: T) : ProviderCallResult<T> {
        override fun toString(): String = "ProviderCallResult.Value(redacted)"
    }

    data class HttpFailure(val statusCode: Int) : ProviderCallResult<Nothing> {
        init { require(statusCode in 400..599) }
    }
}

sealed interface ProviderAccessResult<out T> {
    class Success<T>(val value: T) : ProviderAccessResult<T> {
        override fun toString(): String = "ProviderAccessResult.Success(redacted)"
    }

    data class Blocked(val reason: ProviderAccessFailure) : ProviderAccessResult<Nothing>
}

class ProviderAccessGate(
    private val store: ProviderPolicyStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 10_000,
) {
    init { require(timeoutMs > 0) }

    suspend fun permissionState(scope: ProviderScope, use: ProviderUse): PermissionState =
        store.data.first().permission(scope, use).currentState(nowEpochMs())

    suspend fun permissionStates(
        scope: ProviderScope, uses: Set<ProviderUse>,
    ): Map<ProviderUse, PermissionState> {
        val snapshot = store.data.first()
        val now = nowEpochMs()
        return uses.associateWith { snapshot.permission(scope, it).currentState(now) }
    }

    suspend fun <T> execute(
        scope: ProviderScope,
        use: ProviderUse,
        capabilities: ProviderCapabilities,
        operation: suspend () -> ProviderCallResult<T>,
    ): ProviderAccessResult<T> = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) {
            val before = try {
                store.data.first()
            } catch (_: IOException) {
                return@withContext ProviderAccessResult.Blocked(ProviderAccessFailure.STORAGE_UNAVAILABLE)
            }
            rejection(before, scope, use, capabilities)?.let {
                return@withContext ProviderAccessResult.Blocked(it)
            }
            currentCoroutineContext().ensureActive()
            val response = try {
                operation()
            } catch (_: IOException) {
                return@withContext ProviderAccessResult.Blocked(ProviderAccessFailure.IO)
            } catch (_: SecurityException) {
                return@withContext ProviderAccessResult.Blocked(ProviderAccessFailure.DENIED)
            }
            currentCoroutineContext().ensureActive()
            try {
                val after = store.data.first()
                rejection(after, scope, use, capabilities)?.let {
                    return@withContext ProviderAccessResult.Blocked(it)
                }
                if (before.revision != after.revision) {
                    return@withContext ProviderAccessResult.Blocked(ProviderAccessFailure.STALE_POLICY)
                }
                when (response) {
                    is ProviderCallResult.Value -> ProviderAccessResult.Success(response.value)
                    is ProviderCallResult.HttpFailure -> {
                        val reason = when (response.statusCode) {
                            401 -> ProviderAccessFailure.AUTH_REQUIRED
                            403 -> {
                                // A late 403 must not overwrite a newly reviewed grant.
                                val saved = store.setPermission(
                                    scope, use,
                                    before.permission(scope, use).copy(state = PermissionState.DENIED),
                                    expectedRevision = before.revision,
                                )
                                if (saved) ProviderAccessFailure.DENIED else ProviderAccessFailure.STALE_POLICY
                            }
                            429 -> ProviderAccessFailure.RATE_LIMITED
                            else -> ProviderAccessFailure.HTTP_FAILURE
                        }
                        ProviderAccessResult.Blocked(reason)
                    }
                }
            } catch (_: IOException) {
                ProviderAccessResult.Blocked(ProviderAccessFailure.STORAGE_UNAVAILABLE)
            }
        }
    } ?: ProviderAccessResult.Blocked(ProviderAccessFailure.TIMEOUT)

    private fun rejection(
        snapshot: ProviderPolicySnapshot,
        scope: ProviderScope,
        use: ProviderUse,
        capabilities: ProviderCapabilities,
    ): ProviderAccessFailure? {
        val permission = snapshot.permission(scope, use)
        val now = nowEpochMs()
        val state = permission.currentState(now)
        return when {
            state == PermissionState.DENIED -> ProviderAccessFailure.DENIED
            state != PermissionState.ALLOWED -> if (permission.evidence?.let { now >= it.expiresAtEpochMs } == true)
                ProviderAccessFailure.EXPIRED else ProviderAccessFailure.UNREVIEWED
            use !in capabilities.supportedUses -> ProviderAccessFailure.UNSUPPORTED
            else -> null
        }
    }
}
