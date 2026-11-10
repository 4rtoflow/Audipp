package com.audipp.integrations.streaming.qobuz

import com.audipp.domain.model.ProviderCapabilities
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import com.audipp.integrations.streaming.ProviderAccessGate
import com.audipp.integrations.streaming.ProviderAccessResult
import com.audipp.integrations.streaming.ProviderCallResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class QobuzCredentials(
    val scope: ProviderScope,
    val userId: Long,
    val appSecret: String,
    val userToken: String,
    val expiresAtEpochMs: Long,
) {
    init {
        require(scope.providerKey == "qobuz" && scope.accountId == userId.toString() && userId > 0)
        require(listOf(scope.appId, appSecret, userToken).all {
            it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl)
        })
        require(expiresAtEpochMs > 0)
    }
    override fun toString(): String = "QobuzCredentials(redacted)"
}

internal enum class QobuzQuality(val formatId: Int) { MP3(5), CD(6), HIRES_96(7), HIRES_192(27) }

internal class QobuzSearchTrack(val id: Long, val title: String) {
    override fun toString(): String = "QobuzSearchTrack(redacted)"
}

internal class QobuzFile internal constructor(
    internal val owner: Any,
    internal val uri: URI,
    val requestedTrackId: Long,
    val formatId: Int,
    val mimeType: String,
    val reportedSamplingRateKhz: Double,
    val reportedBitDepth: Int,
    internal val blob: String?,
) {
    // The candidate does not define a URL expiry field; do not invent a lifetime.
    val expiresAtEpochMs: Long? = null
    override fun toString(): String = "QobuzFile(redacted)"
}

internal class QobuzAudioChunk(val position: Long, val bytes: ByteArray, val totalBytes: Long?) {
    override fun toString(): String = "QobuzAudioChunk(redacted)"
}

internal class QobuzFileClient(
    private val gate: ProviderAccessGate,
    private val credentials: QobuzCredentials,
    mediaHosts: Set<String>,
    private val apiBase: URI = URI("https://www.qobuz.com/api.json/0.2/"),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val ioTimeoutMs: Int = 3000,
) {
    private val owner = Any()
    private val mediaHosts = mediaHosts.toSet()
    @Volatile private var authExpired = false
    private val capabilities = ProviderCapabilities(setOf(ProviderUse.SEARCH, ProviderUse.DIRECT_PLAYBACK))

    init {
        require(allowedUri(apiBase) && apiBase.rawQuery == null && apiBase.path.endsWith('/'))
        require(apiBase.host == "127.0.0.1" || (apiBase.host == "www.qobuz.com" &&
            apiBase.path == "/api.json/0.2/" && apiBase.port == -1))
        require(ioTimeoutMs in 1..10_000)
        require(mediaHosts.isNotEmpty() && mediaHosts.all { it.isNotBlank() })
    }

    suspend fun search(query: String, limit: Int = 20): ProviderAccessResult<List<QobuzSearchTrack>> {
        require(query.isNotBlank() && query.length <= 512 && limit in 1..50)
        return api(ProviderUse.SEARCH, "catalog/search", mapOf("query" to query, "limit" to "$limit")) { json ->
            val items = json.getValue("tracks").jsonObject.getValue("items").jsonArray
            require(items.size <= limit)
            items.map { item ->
                val track = item.jsonObject
                QobuzSearchTrack(track.long("id").also { require(it > 0) }, track.text("title"))
            }
        }
    }

    suspend fun resolve(trackId: Long, quality: QobuzQuality = QobuzQuality.CD): ProviderAccessResult<QobuzFile> {
        require(trackId > 0)
        // MD5 is the candidate API's request signature, not a secret-storage cipher.
        val timestamp = nowEpochMs() / 1000
        val signature = MessageDigest.getInstance("MD5").digest(
            ("trackgetFileUrlformat_id${quality.formatId}intentstreamtrack_id$trackId$timestamp" + credentials.appSecret)
                .toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        return api(ProviderUse.DIRECT_PLAYBACK, "track/getFileUrl", mapOf(
            "track_id" to "$trackId", "format_id" to "${quality.formatId}", "intent" to "stream",
            "request_ts" to "$timestamp", "request_sig" to signature,
        )) { json ->
            val address = json.text("url").also { require(it.length <= 8192) }
            val uri = URI(address)
            require(allowedUri(uri) && uri.host in mediaHosts)
            val mime = json.text("mime_type").also { require(it.startsWith("audio/")) }
            val format = json.getValue("format_id").jsonPrimitive.intOrNull ?: throw IOException("Qobuz format")
            require(QobuzQuality.entries.any { it.formatId == format })
            val rate = json.getValue("sampling_rate").jsonPrimitive.doubleOrNull ?: throw IOException("Qobuz rate")
            val depth = json.getValue("bit_depth").jsonPrimitive.intOrNull ?: throw IOException("Qobuz depth")
            require(rate.isFinite() && rate > 0 && depth >= 0)
            json["track_id"]?.let { require(it.jsonPrimitive.longOrNull == trackId) }
            QobuzFile(owner, uri, trackId, format, mime, rate, depth,
                json["blob"]?.jsonPrimitive?.contentOrNull)
        }
    }

    suspend fun readRange(file: QobuzFile, position: Long, size: Int): ProviderAccessResult<QobuzAudioChunk> {
        require(file.owner === owner && position >= 0 && size in 1..65536 && position <= Long.MAX_VALUE - size)
        return gate.execute(credentials.scope, ProviderUse.DIRECT_PLAYBACK, capabilities) {
            if (expired()) return@execute ProviderCallResult.HttpFailure(401)
            val end = position + size - 1
            // Media/CDN requests never receive the API's app identity or user-token headers.
            val response = request(file.uri, mapOf("Range" to "bytes=$position-$end"), null, size)
            if (response.status in 400..599) return@execute ProviderCallResult.HttpFailure(response.status)
            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(response.contentRange ?: "")
                ?: throw IOException("Qobuz range")
            val start = range.groupValues[1].toLongOrNull() ?: throw IOException("Qobuz range")
            val last = range.groupValues[2].toLongOrNull() ?: throw IOException("Qobuz range")
            val total = range.groupValues[3].let { if (it == "*") null else it.toLongOrNull()
                ?: throw IOException("Qobuz range") }
            if (response.status != 206 || start != position || last !in start..end ||
                response.body.size.toLong() != last - start + 1 || total?.let { it <= last } == true
            ) throw IOException("Qobuz range")
            ProviderCallResult.Value(QobuzAudioChunk(start, response.body, total))
        }
    }

    suspend fun reportStarted(file: QobuzFile): ProviderAccessResult<Unit> {
        require(file.owner === owner)
        val events = buildJsonArray { add(buildJsonObject {
            put("track_id", file.requestedTrackId); put("format_id", file.formatId)
            put("user_id", credentials.userId); put("date", nowEpochMs() / 1000)
        }) }
        return api(ProviderUse.DIRECT_PLAYBACK, "track/reportStreamingStart", emptyMap(),
            "events=${encode(events.toString())}", "application/x-www-form-urlencoded") { json ->
            require(json.text("status") == "success")
        }
    }

    suspend fun reportEnded(file: QobuzFile, startedAtEpochMs: Long, durationSeconds: Long): ProviderAccessResult<Unit> {
        require(file.owner === owner && startedAtEpochMs >= 0 && durationSeconds > 0)
        require(startedAtEpochMs <= nowEpochMs() && durationSeconds <= (nowEpochMs() - startedAtEpochMs) / 1000)
        // The fixed source requires a blob for end reports; a missing blob is not a successful report.
        if (file.blob.isNullOrBlank()) return ProviderAccessResult.Blocked(
            com.audipp.integrations.streaming.ProviderAccessFailure.UNSUPPORTED,
        )
        val started = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(startedAtEpochMs))
        val body = buildJsonObject {
            put("events", buildJsonArray { add(buildJsonObject {
                put("blob", file.blob); put("track_context_uuid", ""); put("start_stream", started)
                put("online", true); put("local", false); put("duration", durationSeconds)
            }) })
            put("renderer_context", buildJsonObject { put("software_version", "Audipp-issue20") })
        }
        return api(ProviderUse.DIRECT_PLAYBACK, "track/reportStreamingEndJson", emptyMap(), body.toString()) { json ->
            require(json.text("status") == "success")
        }
    }

    private suspend fun <T> api(
        use: ProviderUse,
        path: String,
        params: Map<String, String>,
        body: String? = null,
        contentType: String = "application/json",
        decode: (JsonObject) -> T,
    ): ProviderAccessResult<T> = gate.execute(credentials.scope, use, capabilities) {
        if (expired()) return@execute ProviderCallResult.HttpFailure(401)
        val query = params.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val uri = apiBase.resolve(path + if (query.isEmpty()) "" else "?$query")
        val response = request(uri, mapOf("X-App-Id" to credentials.scope.appId,
            "X-User-Auth-Token" to credentials.userToken, "Content-Type" to contentType), body, 262144)
        if (response.status in 400..599) {
            if (response.status == 401) authExpired = true
            return@execute ProviderCallResult.HttpFailure(response.status)
        }
        if (response.status != 200) throw IOException("Qobuz response")
        try {
            ProviderCallResult.Value(decode(Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject))
        } catch (_: IllegalArgumentException) {
            throw IOException("Qobuz response")
        } catch (_: NoSuchElementException) {
            throw IOException("Qobuz response")
        } catch (_: java.net.URISyntaxException) {
            throw IOException("Qobuz response")
        }
    }

    private fun expired(): Boolean = authExpired || nowEpochMs() >= credentials.expiresAtEpochMs

    private suspend fun request(uri: URI, headers: Map<String, String>, body: String?, maxBytes: Int): Response {
        currentCoroutineContext().ensureActive()
        // HttpURLConnection is not thread safe: its IO worker also owns disconnect.
        // Cancellation rejects the payload; an in-flight read may wait for its socket timeout.
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = ioTimeoutMs
            connection.readTimeout = ioTimeoutMs
            connection.setRequestProperty("Accept-Encoding", "identity")
            for ((key, value) in headers) connection.setRequestProperty(key, value)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status !in 200..299) return Response(status, ByteArray(0), null)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw IOException("Qobuz response limit")
                    output.write(buffer, 0, count)
                }
            }
            currentCoroutineContext().ensureActive()
            return Response(status, output.toByteArray(), connection.getHeaderField("Content-Range"))
        } finally { connection.disconnect() }
    }

    private class Response(val status: Int, val body: ByteArray, val contentRange: String?)

    private companion object {
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun allowedUri(uri: URI): Boolean = uri.isAbsolute && uri.host != null && uri.rawUserInfo == null &&
            uri.rawFragment == null && ((uri.scheme == "https" && uri.port in setOf(-1, 443)) ||
            (uri.scheme == "http" && uri.host == "127.0.0.1"))
        fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
            require(it.isString); it.content
        }
        fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.longOrNull ?: throw IOException("Qobuz id")
    }
}
