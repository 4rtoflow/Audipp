package com.audipp.integrations.streaming.qobuz

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

internal sealed interface QobuzCallbackResult {
    class Code(val value: String) : QobuzCallbackResult {
        override fun toString(): String = "QobuzAuthorizationCode(redacted)"
    }
    enum class Rejected : QobuzCallbackResult { NO_REQUEST, EXPIRED, MISMATCH, DENIED, MALFORMED }
}

internal class QobuzAuthorizationRequest(val uri: URI, val expiresAtEpochMs: Long) {
    override fun toString(): String = "QobuzAuthorizationRequest(redacted)"
}

internal class QobuzAuthorization(
    private val appId: String,
    private val redirect: URI,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private var state: String? = null
    private var expiresAt = 0L

    init {
        require(appId.isNotBlank() && appId.length <= 256 && appId.none(Char::isISOControl))
        require(redirect.isAbsolute && !redirect.isOpaque && redirect.host != null)
        require(redirect.scheme == "https" || redirect.scheme == "com.audipp")
        require(redirect.rawUserInfo == null && redirect.rawFragment == null && redirect.rawQuery == null)
    }

    @Synchronized
    fun begin(): QobuzAuthorizationRequest {
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val now = nowEpochMs()
        require(now >= 0 && now <= Long.MAX_VALUE - LIFETIME_MS)
        state = nonce
        expiresAt = now + LIFETIME_MS
        val query = mapOf("ext_app_id" to appId, "redirect_url" to redirect.toASCIIString(), "state" to nonce)
            .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return QobuzAuthorizationRequest(URI("https://www.qobuz.com/signin/oauth?$query"), expiresAt)
    }

    @Synchronized
    fun cancel() { state = null }

    @Synchronized
    fun receive(callback: String): QobuzCallbackResult {
        val expected = state ?: return QobuzCallbackResult.Rejected.NO_REQUEST
        // Even an invalid callback consumes this attempt; it must never exchange a second code.
        state = null
        if (nowEpochMs() !in (expiresAt - LIFETIME_MS) until expiresAt) {
            return QobuzCallbackResult.Rejected.EXPIRED
        }
        if (callback.length > 8192) return QobuzCallbackResult.Rejected.MALFORMED
        return try {
            val uri = URI(callback)
            if (uri.scheme != redirect.scheme || uri.rawAuthority != redirect.rawAuthority ||
                uri.rawPath != redirect.rawPath || uri.rawFragment != null || uri.rawUserInfo != null
            ) return QobuzCallbackResult.Rejected.MISMATCH
            val params = linkedMapOf<String, String>()
            for (part in (uri.rawQuery ?: "").split('&')) {
                val pair = part.split('=', limit = 2)
                if (pair.size != 2) return QobuzCallbackResult.Rejected.MALFORMED
                val key = URLDecoder.decode(pair[0], "UTF-8")
                if (params.put(key, URLDecoder.decode(pair[1], "UTF-8")) != null) {
                    return QobuzCallbackResult.Rejected.MALFORMED
                }
            }
            if (params["state"] != expected) return QobuzCallbackResult.Rejected.MISMATCH
            if ("error" in params) return QobuzCallbackResult.Rejected.DENIED
            val code = params["code_autorisation"]
            if (code.isNullOrBlank() || code.length > 2048 || code.any(Char::isISOControl)) {
                QobuzCallbackResult.Rejected.MALFORMED
            } else QobuzCallbackResult.Code(code)
        } catch (_: IllegalArgumentException) {
            QobuzCallbackResult.Rejected.MALFORMED
        } catch (_: java.net.URISyntaxException) {
            QobuzCallbackResult.Rejected.MALFORMED
        }
    }

    private companion object { const val LIFETIME_MS = 5 * 60 * 1000L }
}
