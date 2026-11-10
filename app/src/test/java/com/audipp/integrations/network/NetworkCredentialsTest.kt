package com.audipp.integrations.network

import com.audipp.data.secrets.SecretFiles
import com.audipp.data.secrets.SecretKeys
import com.audipp.data.secrets.SecretState
import com.audipp.data.secrets.SecretStore
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkCredentialsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint = NetworkEndpoint(NetworkProtocol.WEBDAV, "https://nas.test/private-music/")
    private val secret = "private-user:private-password".toByteArray()
    private val keys = object : SecretKeys {
        val values = mutableMapOf<String, SecretKey>()
        override fun find(alias: String) = values[alias]
        override fun create(alias: String) = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { values[alias] = it }
        override fun delete(alias: String) { values.remove(alias) }
    }
    private val root get() = File(temporary.root, "secrets")
    private fun credentials() = NetworkCredentials(SecretStore(SecretFiles(root), keys))
    private val calls = NetworkCalls(NetworkCallPolicy(initialBackoffMs = 1, maxBackoffMs = 1))

    @Test fun existingEncryptedStoreSurvivesReopenWithoutCredentialsInUriFilesOrLogs() = runBlocking {
        val credentials = credentials()
        val credential = credentials.authorize(endpoint, "alice", secret)
        val reopened = credentials()
        val resumed = reopened.resume(endpoint, "alice")
        var borrowed: ByteArray? = null
        reopened.use(endpoint, resumed) {
            borrowed = it
            assertArrayEquals(secret, it)
        }
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertArrayEquals("private-user:private-password".toByteArray(), secret)
        for (file in root.walkTopDown().filter { it.isFile }) {
            assertFalse(file.path.contains("alice"))
            assertFalse(file.path.contains("nas.test"))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("private-password"))
        }
        for (description in listOf(credential.toString(), resumed.toString(), endpoint.toString())) {
            assertFalse(description.contains("private-password"))
            assertFalse(description.contains("nas.test"))
            assertFalse(description.contains("alice"))
        }
    }

    @Test fun bindingRejectsOtherHostsProtocolsAndRootsBeforeTransportIo() = runBlocking {
        val credentials = credentials()
        val credential = credentials.authorize(endpoint, "alice", secret)
        for (other in listOf(NetworkEndpoint(NetworkProtocol.WEBDAV, "https://other.test/private-music/"),
            NetworkEndpoint(NetworkProtocol.WEBDAV, "https://nas.test/other-root/"),
            NetworkEndpoint(NetworkProtocol.UPNP, endpoint.uri))) {
            val fake = FakeNetworkLibraryAdapter(other)
            var calls = 0
            val adapter = object : NetworkLibraryAdapter by fake {
                override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                    calls++
                    error("Credential must not be sent to another endpoint")
                }
            }
            val result = NetworkLibraryClient(adapter, this@NetworkCredentialsTest.calls, credentials, credential).listMedia(DirectoryRequest("root"))
            assertEquals(NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED), result)
            assertEquals(0, calls)
        }
        assertEquals(SecretState.READY, credential.state.value)
    }

    @Test fun serverAuthRejectionInvalidatesOnlyThatAccountAndNeverRetriesOrResumesIt() = runBlocking {
        val credentials = credentials()
        val alice = credentials.authorize(endpoint, "alice", secret)
        val bob = credentials.authorize(endpoint, "bob", "bob-password".toByteArray())
        var count = 0
        var borrowed: ByteArray? = null
        val fake = FakeNetworkLibraryAdapter(endpoint)
        val adapter = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                count++
                borrowed = credential
                assertArrayEquals(secret, credential)
                return NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED)
            }
        }
        val client = NetworkLibraryClient(adapter, calls, credentials, alice)
        assertEquals(NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED), client.listMedia(DirectoryRequest("root")))
        assertEquals(1, count)
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertEquals(SecretState.AUTH_REQUIRED, alice.state.value)
        assertEquals(SecretState.AUTH_REQUIRED, credentials.resume(endpoint, "alice").state.value)
        assertEquals(NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED), client.listMedia(DirectoryRequest("root")))
        assertEquals(1, count)
        credentials.use(endpoint, bob) { assertArrayEquals("bob-password".toByteArray(), it) }
        val authorized = credentials.authorize(endpoint, "alice", secret)
        assertEquals(SecretState.READY, authorized.state.value)
        assertEquals(SecretState.AUTH_REQUIRED, alice.state.value)
    }

    @Test fun supersededCredentialCannotRevokeFreshAuthorization() = runBlocking {
        val credentials = credentials()
        val old = credentials.authorize(endpoint, "alice", secret)
        val fresh = credentials.authorize(endpoint, "alice", "new-password".toByteArray())
        try { credentials.requireAuthentication(old) } catch (_: com.audipp.data.secrets.SecretStorageException) { }
        assertEquals(SecretState.READY, fresh.state.value)
        credentials.use(endpoint, fresh) { assertArrayEquals("new-password".toByteArray(), it) }
    }

    @Test fun revocationCancelsPendingTransportAndDiscardsLateResponseAndClearsBuffer() = runBlocking {
        val credentials = credentials()
        val credential = credentials.authorize(endpoint, "alice", secret)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var borrowed: ByteArray? = null
        val fake = FakeNetworkLibraryAdapter(endpoint)
        val adapter = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                borrowed = credential
                entered.complete(Unit)
                return withContext(NonCancellable) {
                    release.await()
                    NetworkResult.Success(emptyList())
                }
            }
        }
        val pending = async { NetworkLibraryClient(adapter, calls, credentials, credential).listMedia(DirectoryRequest("root")) }
        entered.await()
        credentials.requireAuthentication(credential)
        release.complete(Unit)
        assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
        assertTrue(borrowed!!.all { it == 0.toByte() })
        assertEquals(SecretState.AUTH_REQUIRED, credential.state.value)
    }

    @Test fun rendererAuthenticationUsesSameEncryptedBoundaryAndCannotInventObservation() = runBlocking {
        val rendererEndpoint = NetworkEndpoint(NetworkProtocol.MPD, "mpd://renderer.test:6600")
        val device = RendererDevice("room-a", rendererEndpoint, 10_000)
        val credentials = credentials()
        val credential = credentials.authorize(rendererEndpoint, "mpd-user", secret)
        val fake = FakeRendererAdapter(listOf(device), emptyMap())
        var count = 0
        val adapter = object : RendererAdapter by fake {
            override suspend fun connect(device: RendererDevice, credential: ByteArray?): NetworkResult<String> {
                count++
                assertArrayEquals(secret, credential)
                return NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED)
            }
        }
        val context = com.audipp.playback.source.ResolutionContext(
            com.audipp.playback.source.PlaybackTarget(device.id, com.audipp.playback.source.TargetKind.RENDERER),
            1, com.audipp.domain.model.TransitionId("play-1"))
        val client = NetworkRendererClient(adapter, device, context, { context }, calls, credentials, credential, { 100 })
        assertEquals(NetworkResult.Failure(NetworkFailure.AUTH_REQUIRED), client.connect())
        assertEquals(1, count)
        assertEquals(RendererConnection.AUTH_REQUIRED, client.state.value.connection)
        assertEquals(null, client.state.value.observation)
    }

    @Test fun cancellationAlwaysClearsCredentialBuffer() = runBlocking {
        val credentials = credentials()
        val credential = credentials.authorize(endpoint, "alice", secret)
        val entered = CompletableDeferred<Unit>()
        var borrowed: ByteArray? = null
        val pending = async {
            credentials.use(endpoint, credential) {
                borrowed = it
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        pending.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
        assertTrue(borrowed!!.all { it == 0.toByte() })
    }
}
