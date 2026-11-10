package com.audipp.integrations.streaming.qobuz

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.audipp.data.preferences.ProviderPolicyStore
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.ProviderPermissionEvidence
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import com.audipp.domain.model.UsePermission
import com.audipp.integrations.streaming.ProviderAccessFailure
import com.audipp.integrations.streaming.ProviderAccessGate
import com.audipp.integrations.streaming.ProviderAccessResult
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QobuzClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 100_000L
    private val scope = ProviderScope("qobuz", "1", "fixture-app", "JP", "fixture-contract")
    private val allowed = UsePermission(PermissionState.ALLOWED,
        ProviderPermissionEvidence("fixture-evidence", "fixture-conditions", 0, 200_000))

    @Test fun callbackIsExactSingleUseAndNotLogged() {
        val authorization = authorization()
        val request = authorization.begin()
        val params = query(request.uri)
        assertEquals("fixture-app", params["ext_app_id"])
        assertEquals("com.audipp://qobuz/callback", params["redirect_url"])
        val result = authorization.receive(callback(params.getValue("state")))
        assertEquals("fixture-code", (result as QobuzCallbackResult.Code).value)
        assertFalse(result.toString().contains("fixture-code"))
        assertFalse(request.toString().contains(params.getValue("state")))
        assertEquals(QobuzCallbackResult.Rejected.NO_REQUEST, authorization.receive(callback(params.getValue("state"))))
    }

    @Test fun callbackRejectsWrongStateOriginPathFragmentAndDuplicateFields() {
        for (change in listOf<(String) -> String>(
            { it.replace("state=", "state=wrong") },
            { it.replace("qobuz/", "other/") },
            { it.replace("/callback", "/other") },
            { "$it#fragment" }, { "$it&state=duplicate" },
            { "$it&code_autorisation=duplicate" },
            { it.replace("fixture-code", "%GG") },
        )) {
            val authorization = authorization()
            val state = query(authorization.begin().uri).getValue("state")
            assertTrue(authorization.receive(change(callback(state))) is QobuzCallbackResult.Rejected)
            assertEquals(QobuzCallbackResult.Rejected.NO_REQUEST, authorization.receive(callback(state)))
        }
    }

    @Test fun cancelledExpiredDeniedAndOldCallbackCannotAuthorize() {
        val authorization = authorization()
        val first = query(authorization.begin().uri).getValue("state")
        authorization.cancel()
        assertEquals(QobuzCallbackResult.Rejected.NO_REQUEST, authorization.receive(callback(first)))
        val request = authorization.begin()
        now = request.expiresAtEpochMs
        assertEquals(QobuzCallbackResult.Rejected.EXPIRED,
            authorization.receive(callback(query(request.uri).getValue("state"))))
        val current = query(authorization.begin().uri).getValue("state")
        assertEquals(QobuzCallbackResult.Rejected.DENIED,
            authorization.receive("com.audipp://qobuz/callback?state=$current&error=access_denied"))
        val next = query(authorization.begin().uri).getValue("state")
        assertNotEquals(first, next)
        assertEquals(QobuzCallbackResult.Rejected.MISMATCH, authorization.receive(callback(first)))
    }

    @Test fun searchUsesOwnGrantAndEncodesQueryOnRealHttp() = fixture { f ->
        blocked(ProviderAccessFailure.UNREVIEWED, f.client.search("artist & 日本語"))
        assertTrue(f.requests.isEmpty())
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        blocked(ProviderAccessFailure.UNREVIEWED, f.client.search("artist & 日本語"))
        f.grant(ProviderUse.SEARCH)
        val tracks = value(f.client.search("artist & 日本語", 1))
        assertEquals(42L, tracks.single().id)
        val request = f.requests.single()
        assertEquals("artist & 日本語", query(request.uri)["query"])
        assertEquals("fixture-app", request.appId)
        assertEquals("fixture-user-token", request.userToken)
        assertFalse(tracks.single().toString().contains("Fixture title"))
    }

    @Test fun fileRequestSignatureAndReturnedMetadataAreDistinctFromRequestedQuality() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val file = value(f.client.resolve(42))
        val params = query(f.requests.single().uri)
        assertEquals("6", params["format_id"])
        assertEquals("stream", params["intent"])
        assertEquals("100", params["request_ts"])
        assertEquals("a42fee63d295d60d5fa94869f1a8b416", params["request_sig"])
        assertEquals(44.1, file.reportedSamplingRateKhz, 0.0)
        assertEquals(16, file.reportedBitDepth)
        assertNull(file.expiresAtEpochMs)
        assertFalse(file.toString().contains("signed=fixture"))
    }

    @Test fun wholeOwnedWavIsReadInRangesAndSeekReturnsExactBytesWithoutApiHeaders() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val file = value(f.client.resolve(42, QobuzQuality.HIRES_192))
        assertEquals(6, file.formatId)
        val collected = java.io.ByteArrayOutputStream()
        var position = 0L
        while (position < f.audio.size) {
            val chunk = value(f.client.readRange(file, position, 128))
            assertEquals(f.audio.size.toLong(), chunk.totalBytes)
            collected.write(chunk.bytes)
            position += chunk.bytes.size
        }
        assertArrayEquals(f.audio, collected.toByteArray())
        assertEquals("53f89b885f3d54331cc8cf95f4841611cd59d05f176d603e59b1d4cce131fb06",
            MessageDigest.getInstance("SHA-256").digest(collected.toByteArray()).joinToString("") { "%02x".format(it) })
        val seek = value(f.client.readRange(file, 100, 20))
        assertArrayEquals(f.audio.copyOfRange(100, 120), seek.bytes)
        val media = f.requests.filter { it.uri.path == "/audio" }
        assertTrue(media.size > 1)
        assertTrue(media.all { it.appId == null && it.userToken == null })
        assertFalse(seek.toString().contains("signed"))
        assertEquals(listOf("policy.preferences_pb"), temporary.root.list()!!.toList())
    }

    @Test fun reportsAreExplicitPostsAndUseActualFormatAndRequiredBlob() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val file = value(f.client.resolve(42, QobuzQuality.HIRES_192))
        assertEquals(1, f.requests.size)
        value(f.client.reportStarted(file))
        value(f.client.reportEnded(file, 0, 7))
        val start = f.requests[1]
        assertEquals("POST", start.method)
        val event = Json.parseToJsonElement(query(URI("http://127.0.0.1/?${start.body}")).getValue("events"))
            .jsonArray.single().jsonObject
        assertEquals("6", event.getValue("format_id").jsonPrimitive.content)
        assertEquals("1", event.getValue("user_id").jsonPrimitive.content)
        val end = Json.parseToJsonElement(f.requests[2].body).jsonObject
            .getValue("events").jsonArray.single().jsonObject
        assertEquals("fixture-blob", end.getValue("blob").jsonPrimitive.content)
        assertEquals("7", end.getValue("duration").jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:00Z", end.getValue("start_stream").jsonPrimitive.content)
    }

    @Test fun missingBlobAndNonSuccessReportCannotBecomeReportedSuccess() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        f.fileJson = f.fileJson.replace(",\"blob\":\"fixture-blob\"", "")
        val file = value(f.client.resolve(42))
        blocked(ProviderAccessFailure.UNSUPPORTED, f.client.reportEnded(file, 0, 1))
        assertEquals(1, f.requests.size)
        // A failed end report is checked separately using a response that actually carries a blob.
        f.fileJson = f.fileJson.dropLast(1) + ",\"blob\":\"fixture-blob\"}"
        val withBlob = value(f.client.resolve(42))
        f.reply = { exchange -> respond(exchange, 200, "{\"status\":\"failed\"}") }
        blocked(ProviderAccessFailure.IO, f.client.reportEnded(withBlob, 0, 1))
        blocked(ProviderAccessFailure.IO, f.client.reportStarted(withBlob))
    }

    @Test fun authenticationDenialAndRateLimitNeverFallbackOrRetry() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        f.reply = { exchange -> respond(exchange, 403, "sensitive denial") }
        blocked(ProviderAccessFailure.DENIED, f.client.resolve(42))
        blocked(ProviderAccessFailure.DENIED, f.client.resolve(42))
        assertEquals(1, f.requests.size)
        assertEquals("/api/track/getFileUrl", f.requests.single().uri.path)
        for ((status, failure) in listOf(401 to ProviderAccessFailure.AUTH_REQUIRED, 429 to ProviderAccessFailure.RATE_LIMITED)) {
            f.grant(ProviderUse.DIRECT_PLAYBACK)
            f.reply = { exchange -> respond(exchange, status, "sensitive failure") }
            val before = f.requests.size
            blocked(failure, f.newClient().resolve(42))
            assertEquals(before + 1, f.requests.size)
        }
    }

    @Test fun expiredOrInvalidatedCredentialsDoNotReachTheNetwork() = fixture { f ->
        f.grant(ProviderUse.SEARCH)
        val expired = f.newClient(expiresAt = now)
        blocked(ProviderAccessFailure.AUTH_REQUIRED, expired.search("query"))
        assertTrue(f.requests.isEmpty())
        f.reply = { exchange -> respond(exchange, 401, "sensitive failure") }
        blocked(ProviderAccessFailure.AUTH_REQUIRED, f.client.search("query"))
        blocked(ProviderAccessFailure.AUTH_REQUIRED, f.client.search("query"))
        assertEquals(1, f.requests.size)
    }

    @Test fun expiredMediaRequiresExplicitReresolutionAndNeverChangesMethod() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val file = value(f.client.resolve(42))
        f.reply = { exchange -> respond(exchange, 401, "expired signed URL") }
        blocked(ProviderAccessFailure.AUTH_REQUIRED, f.client.readRange(file, 0, 32))
        assertEquals(2, f.requests.size)
        f.reply = null
        value(f.client.resolve(42))
        assertEquals(listOf("/api/track/getFileUrl", "/audio", "/api/track/getFileUrl"),
            f.requests.map { it.uri.path })
    }

    @Test fun invalidResponseUntrustedMediaAndCrossAccountDescriptorsAreRejected() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val original = f.fileJson
        for (bad in listOf("not json", original.replace("127.0.0.1", "untrusted.invalid"),
            original.replace("audio/wav", "video/mp4"), original.replace("\"format_id\":6", "\"format_id\":99"),
            original.dropLast(1) + ",\"track_id\":999}")) {
            f.fileJson = bad
            blocked(ProviderAccessFailure.IO, f.client.resolve(42))
        }
        f.fileJson = original
        val file = value(f.client.resolve(42))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.newClient().readRange(file, 0, 32) }
        }
    }

    @Test fun ignoredMismatchedTruncatedAndOversizedRangesAreRejected() = fixture { f ->
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val file = value(f.client.resolve(42))
        for ((status, range, bytes) in listOf(Triple(200, "bytes 0-3/100", "abcd"),
            Triple(206, "bytes 1-4/100", "abcd"), Triple(206, "bytes 0-3/100", "abc"),
            Triple(206, "bytes 0-4/100", "abcde"), Triple(206, "bytes 0-3/3", "abcd"))) {
            f.reply = { exchange ->
                exchange.responseHeaders.add("Content-Range", range)
                respond(exchange, status, bytes)
            }
            blocked(ProviderAccessFailure.IO, f.client.readRange(file, 0, 4))
        }
    }

    @Test fun redirectsDoNotForwardCredentialsAndMetadataHasABodyLimit() = fixture { f ->
        f.grant(ProviderUse.SEARCH)
        f.reply = { exchange ->
            exchange.responseHeaders.add("Location", "${f.base}credential-sink")
            respond(exchange, 302, "")
        }
        blocked(ProviderAccessFailure.IO, f.client.search("query"))
        assertEquals(1, f.requests.size)
        f.reply = { exchange -> respond(exchange, 200, "x".repeat(262145)) }
        blocked(ProviderAccessFailure.IO, f.client.search("query"))
    }

    @Test(timeout = 10000) fun revokedPolicyDropsDelayedResponseAndCancellationPublishesNothing() = fixture { f ->
        f.grant(ProviderUse.SEARCH)
        coroutineScope {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.reply = { exchange ->
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                respond(exchange, 200, "{\"tracks\":{\"items\":[]}}")
            }
            try {
                val pending = async { f.client.search("query") }
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                f.store.setPermission(scope, ProviderUse.SEARCH, UsePermission(PermissionState.DENIED))
                release.countDown()
                blocked(ProviderAccessFailure.DENIED, pending.await())
            } finally { release.countDown() }
        }
        f.grant(ProviderUse.SEARCH)
        coroutineScope {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.reply = { exchange ->
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                respond(exchange, 200, "{\"tracks\":{\"items\":[]}}")
            }
            try {
                val pending = async { f.client.search("query") }
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                pending.cancel()
                pending.join()
                assertTrue(pending.isCancelled)
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 5000) fun socketTimeoutDoesNotRetryOrPublishASuccess() = fixture { f ->
        f.grant(ProviderUse.SEARCH)
        val release = CountDownLatch(1)
        f.reply = { exchange ->
            release.await(2, TimeUnit.SECONDS)
            respond(exchange, 200, "{\"tracks\":{\"items\":[]}}")
        }
        try {
            blocked(ProviderAccessFailure.IO, f.newClient(ioTimeout = 100).search("query"))
            assertEquals(1, f.requests.size)
        } finally { release.countDown() }
    }

    @Test fun invalidIdentityEndpointsAndReadBoundsNeverSendARequest() = fixture { f ->
        assertThrows(IllegalArgumentException::class.java) {
            QobuzCredentials(scope.copy(accountId = "2"), 1, "fixture-app-secret", "token", 200_000)
        }
        val credentials = QobuzCredentials(scope, 1, "fixture-app-secret", "token", 200_000)
        assertFalse(credentials.toString().contains("token"))
        for (base in listOf("http://untrusted.invalid/api/", "https://untrusted.invalid/api/",
            "https://www.qobuz.com/api.json/0.2/?query=bad")) {
            assertThrows(IllegalArgumentException::class.java) {
                QobuzFileClient(ProviderAccessGate(f.store, { now }), credentials, setOf("127.0.0.1"), URI(base))
            }
        }
        assertTrue(f.requests.isEmpty())
        f.grant(ProviderUse.DIRECT_PLAYBACK)
        val file = value(f.client.resolve(42))
        for ((position, size) in listOf(-1L to 4, 0L to 65537, Long.MAX_VALUE to 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.client.readRange(file, position, size) }
            }
        }
        assertEquals(1, f.requests.size)
    }

    private fun authorization() = QobuzAuthorization("fixture-app", URI("com.audipp://qobuz/callback"), { now })
    private fun callback(state: String) = "com.audipp://qobuz/callback?state=$state&code_autorisation=fixture-code"
    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { part ->
        val pair = part.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    private fun <T> value(result: ProviderAccessResult<T>): T = (result as ProviderAccessResult.Success<T>).value
    private fun blocked(reason: ProviderAccessFailure, result: ProviderAccessResult<*>) {
        assertEquals(ProviderAccessResult.Blocked(reason), result)
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val job = SupervisorJob()
        val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            File(temporary.root, "policy.preferences_pb")
        }
        val fixture = Fixture(ProviderPolicyStore(raw))
        try { block(fixture) } finally {
            fixture.server.stop()
            job.cancelAndJoin()
        }
    }

    private inner class Fixture(val store: ProviderPolicyStore) {
        val server = LocalServer()
        val base = "http://127.0.0.1:${server.address.port}/"
        val requests = CopyOnWriteArrayList<Request>()
        val audio = ByteBuffer.allocate(44 + 882).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(918); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(882)
            repeat(441) { putShort((it * 37 - 8000).toShort()) }
        }.array()
        var fileJson = """{"url":"${base}audio?signed=fixture","format_id":6,"mime_type":"audio/wav","sampling_rate":44.1,"bit_depth":16,"blob":"fixture-blob"}"""
        @Volatile var reply: ((LocalExchange) -> Unit)? = null
        val client = newClient()

        init {
            server.createContext("/") { exchange ->
                requests.add(Request(exchange.requestURI, exchange.requestMethod,
                    exchange.requestHeaders.getFirst("X-App-Id"), exchange.requestHeaders.getFirst("X-User-Auth-Token"),
                    exchange.requestBody.bufferedReader().use { it.readText() }))
                try {
                    val override = reply
                    if (override != null) override(exchange) else when (exchange.requestURI.path) {
                        "/api/catalog/search" -> respond(exchange, 200, "{\"tracks\":{\"items\":[{\"id\":42,\"title\":\"Fixture title\"}]}}")
                        "/api/track/getFileUrl" -> respond(exchange, 200, fileJson)
                        "/api/track/reportStreamingStart", "/api/track/reportStreamingEndJson" ->
                            respond(exchange, 200, "{\"status\":\"success\"}")
                        "/audio" -> {
                            val requested = Regex("bytes=(\\d+)-(\\d+)").matchEntire(
                                exchange.requestHeaders.getFirst("Range") ?: error("Missing fixture Range"),
                            )!!
                            val first = requested.groupValues[1].toInt()
                            val last = requested.groupValues[2].toInt().coerceAtMost(audio.lastIndex)
                            exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${audio.size}")
                            val bytes = audio.copyOfRange(first, last + 1)
                            exchange.sendResponseHeaders(206, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                        else -> respond(exchange, 404, "")
                    }
                } finally { exchange.close() }
            }
            server.start()
        }

        fun newClient(expiresAt: Long = 200_000, ioTimeout: Int = 3000) = QobuzFileClient(ProviderAccessGate(store, { now }),
            QobuzCredentials(scope, 1, "fixture-app-secret", "fixture-user-token", expiresAt),
            setOf("127.0.0.1"), URI("${base}api/"), { now }, ioTimeout)
        suspend fun grant(use: ProviderUse) { store.setPermission(scope, use, allowed) }
    }

    private class Request(val uri: URI, val method: String, val appId: String?, val userToken: String?, val body: String)

    private class LocalServer {
        private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val address: InetSocketAddress get() = listener.localSocketAddress as InetSocketAddress
        private lateinit var handler: (LocalExchange) -> Unit
        @Volatile private var active: Socket? = null
        private val thread = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 3000
                        handler(LocalExchange(socket))
                    }
                } catch (_: IOException) {
                    // Cancellation can close a connection before the fixture response is written.
                } finally { active = null }
            }
        }.apply { isDaemon = true }

        fun createContext(@Suppress("UNUSED_PARAMETER") path: String, handler: (LocalExchange) -> Unit) {
            this.handler = handler
        }
        fun start() { thread.start() }
        fun stop() {
            listener.close()
            active?.close()
            thread.join(1000)
        }
    }

    private class Headers {
        private val values = linkedMapOf<String, String>()
        fun add(name: String, value: String) { values[name.lowercase(Locale.ROOT)] = value }
        fun getFirst(name: String): String? = values[name.lowercase(Locale.ROOT)]
        fun lines(): String = values.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    }

    private class LocalExchange(private val socket: Socket) {
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        val requestMethod: String
        val requestURI: URI
        val requestBody: ByteArrayInputStream
        val responseBody get() = socket.getOutputStream()

        init {
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() < 16384) {
                    val next = input.read()
                    if (next < 0) throw IOException("Incomplete fixture request")
                    if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
                    bytes.write(next)
                }
                throw IOException("Fixture header limit")
            }
            val first = line().split(' ')
            requestMethod = first[0]
            requestURI = URI(first[1])
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                val parts = header.split(':', limit = 2)
                requestHeaders.add(parts[0], parts[1].trim())
            }
            val length = requestHeaders.getFirst("Content-Length")?.toInt() ?: 0
            require(length in 0..1048576)
            val bytes = ByteArray(length)
            var received = 0
            while (received < length) {
                val count = input.read(bytes, received, length - received)
                if (count < 0) throw IOException("Incomplete fixture body")
                received += count
            }
            requestBody = ByteArrayInputStream(bytes)
        }

        fun sendResponseHeaders(status: Int, length: Long) {
            val head = "HTTP/1.1 $status Fixture\r\nConnection: close\r\nContent-Length: ${length.coerceAtLeast(0)}\r\n" +
                responseHeaders.lines() + "\r\n"
            responseBody.write(head.toByteArray(Charsets.UTF_8))
        }
        fun close() { socket.close() }
    }

    private fun respond(exchange: LocalExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
