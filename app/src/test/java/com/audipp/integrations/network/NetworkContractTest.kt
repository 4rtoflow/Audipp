package com.audipp.integrations.network

import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.NetworkLibrarySourceAdapter
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkContractTest {
    private val nas = NetworkEndpoint(NetworkProtocol.WEBDAV, "https://nas.test/music/")
    private val variant = TrackVariant(VariantId("nas-version"), TrackId("song"),
        SourceRef(SourceId("nas-song"), SourceKind.NETWORK, availability = Availability.PLAYABLE))
    private val renderer = RendererDevice("room-a", NetworkEndpoint(NetworkProtocol.MPD, "mpd://renderer.test:6600"), 10_000)
    private var context = ResolutionContext(PlaybackTarget("phone", TargetKind.DEVICE), 1, TransitionId("play-1"))
    private val policy = NetworkCallPolicy(operationTimeoutMs = 500, connectTimeoutMs = 500, initialBackoffMs = 1, maxBackoffMs = 2)
    private val calls get() = NetworkCalls(policy)
    private val rendererCapabilities = RendererCapabilities(setOf(RendererAction.PLAY, RendererAction.PAUSE,
        RendererAction.STOP, RendererAction.SET_RESOURCE), canObserveState = true, independentQueue = true)

    @Test fun networkInputRejectsCredentialsQueryInjectionMalformedUnicodeAndUnboundedValues() {
        for (uri in listOf("https://user:password@nas.test/music", "https://nas.test/music?token=secret",
            "https://nas.test/music#password", "https://nas.test/%0aattack", "https://nas.test/%00attack",
            "https://nas.test/music\r\nplay", "https://nas.test:0/music", "https://nas.test:65536/music",
            "https:///missing-host", "smb://nas.test/music", "https://nas.test/\uD800", "x".repeat(2049))) {
            val error = assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint(NetworkProtocol.WEBDAV, uri) }
            assertFalse(error.toString().contains(uri))
        }
        for (id in listOf("", "\nplay", "x".repeat(1025), "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { DirectoryRequest(id) }
        }
        for (limit in listOf(0, 1001)) assertThrows(IllegalArgumentException::class.java) { DirectoryRequest("root", limit = limit) }
        assertThrows(IllegalArgumentException::class.java) { DirectoryRequest("root", offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { RendererCommand.Seek(-1) }
        for (volume in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { RendererCommand.SetVolume(volume) }
        }
        assertThrows(IllegalArgumentException::class.java) { NetworkCallPolicy(operationTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { NetworkCallPolicy(maxAttempts = 6) }
        assertThrows(IllegalArgumentException::class.java) { NetworkCallPolicy(maxBackoffMs = Long.MAX_VALUE) }
    }

    @Test fun usableFakeListsPagesAndResolvesSameNasToDeviceOrExplicitRenderer() = runBlocking {
        val fake = libraryFake()
        val client = NetworkLibraryClient(fake, calls, nowEpochMs = { 100 })
        val directories = success(client.listDirectory(DirectoryRequest("root", limit = 1)))
        assertEquals(listOf("music"), directories.map { it.id })
        assertTrue(success(client.listDirectory(DirectoryRequest("root", offset = 1))).isEmpty())
        val media = success(client.listMedia(DirectoryRequest("music")))
        assertEquals(variant, media.single().variant)
        val adapter = NetworkLibrarySourceAdapter(client)
        val resolver = SourceResolver(mapOf(AdapterKey(SourceKind.NETWORK, TargetKind.DEVICE) to adapter,
            AdapterKey(SourceKind.NETWORK, TargetKind.RENDERER) to adapter), { context }, nowEpochMs = { 100 })
        val local = resolver.resolve(ResolutionRequest(media.single().variant, context)) as SourceResolution.Resolved.Playback
        assertEquals(ExecutionMode.LOCAL_DIRECT, local.plan.mode)
        assertEquals("phone", local.plan.targetId)
        assertTrue(local.plan.capabilities.canAccessPcm)

        selectRenderer()
        val remote = resolver.resolve(ResolutionRequest(variant, context)) as SourceResolution.Resolved.Playback
        assertEquals(ExecutionMode.NETWORK_REMOTE, remote.plan.mode)
        assertEquals(renderer.id, remote.plan.targetId)
        assertFalse(remote.plan.capabilities.canAccessPcm)
        assertEquals(local.plan.source, remote.plan.source)
        assertEquals(ResolutionFailure.STALE_ROUTE, resolver.rejectionFor(local))
        context = context.copy(target = PlaybackTarget("room-b", TargetKind.RENDERER))
        val unavailable = resolver.resolve(ResolutionRequest(variant, context)) as SourceResolution.Unavailable
        assertEquals(ResolutionFailure.PLAYBACK_UNSUPPORTED, unavailable.reason)
    }

    @Test fun libraryRejectsOversizedDuplicateAndWrongSourceResponses() = runBlocking {
        val fake = libraryFake()
        for (entries in listOf(List(2) { NetworkDirectory("same", "same") },
            listOf(NetworkDirectory("one", "one"), NetworkDirectory("two", "two")))) {
            val adapter = object : NetworkLibraryAdapter by fake {
                override suspend fun listDirectory(request: DirectoryRequest, credential: ByteArray?) = NetworkResult.Success(entries)
            }
            assertFailure(NetworkFailure.INVALID_RESPONSE, NetworkLibraryClient(adapter, calls).listDirectory(DirectoryRequest("root", limit = 1)))
        }
        val duplicateMedia = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?) = NetworkResult.Success(List(2) { NetworkMedia(variant) })
        }
        assertFailure(NetworkFailure.INVALID_RESPONSE, NetworkLibraryClient(duplicateMedia, calls).listMedia(DirectoryRequest("root")))
        val wrongSource = object : NetworkLibraryAdapter by fake {
            override suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?) = NetworkResult.Success(
                AdapterResolution.Available(variant.variantId, SourceId("other-song"), SourceDescriptor.DeviceResource("handle"), PlaybackCapabilities(canPlay = true)))
        }
        assertFailure(NetworkFailure.INVALID_RESPONSE, NetworkLibraryClient(wrongSource, calls).resolveStream(ResolutionRequest(variant, context)))
    }

    @Test fun sourceResolutionRejectsInjectedIdsAndResponsesFromStoppedTransition() = runBlocking {
        val fake = libraryFake()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val adapter = object : NetworkLibraryAdapter by fake {
            override suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?): NetworkResult<AdapterResolution.Available> {
                count.incrementAndGet()
                entered.complete(Unit)
                release.await()
                return fake.resolveStream(request, credential)
            }
        }
        val client = NetworkLibraryClient(adapter, calls)
        val injected = variant.copy(source = variant.source.copy(sourceId = SourceId("song\nplay")))
        assertFailure(NetworkFailure.INVALID_INPUT, client.resolveStream(ResolutionRequest(injected, context)))
        assertEquals(0, count.get())
        val resolver = SourceResolver(mapOf(AdapterKey(SourceKind.NETWORK, TargetKind.DEVICE) to NetworkLibrarySourceAdapter(client)), { context })
        val pending = async { resolver.resolve(ResolutionRequest(variant, context)) }
        entered.await()
        context = context.copy(transitionId = TransitionId("stop-2"))
        release.complete(Unit)
        assertEquals(ResolutionFailure.STALE_TRANSITION, (pending.await() as SourceResolution.Unavailable).reason)
        assertEquals(1, count.get())
    }

    @Test fun transientReadFailureRecoversWithinBoundWithoutChangingLibraryOrOutput() = runBlocking {
        val fake = libraryFake()
        val count = AtomicInteger()
        val adapter = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> =
                if (count.incrementAndGet() < 3) NetworkResult.Failure(NetworkFailure.OFFLINE) else fake.listMedia(request, credential)
        }
        assertEquals(variant, success(NetworkLibraryClient(adapter, calls).listMedia(DirectoryRequest("music"))).single().variant)
        assertEquals(3, count.get())
        assertEquals("phone", context.target.id)
    }

    @Test fun lanCannotForwardSafCredentialsUnreachableOrExpiredResourcesToRenderer() = runBlocking {
        selectRenderer()
        val fake = libraryFake()
        val descriptors = listOf<SourceDescriptor>(
            SourceDescriptor.LocalDocument("content://music/song"),
            resource(uri = "https://user:password@nas.test/song"),
            resource(uri = "file:///song"), resource(targetId = "room-b"), resource(revision = 9),
            resource(confirmed = false), resource(expires = 100),
        )
        for (descriptor in descriptors) {
            val adapter = object : NetworkLibraryAdapter by fake {
                override suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?) = NetworkResult.Success(
                    AdapterResolution.Available(variant.variantId, variant.source.sourceId, descriptor, PlaybackCapabilities(canPlay = true)))
            }
            assertFailure(NetworkFailure.INVALID_RESPONSE, NetworkLibraryClient(adapter, calls, nowEpochMs = { 100 })
                .resolveStream(ResolutionRequest(variant, context)))
        }
    }

    @Test fun retriesAreBoundedOnWorkerAndExceptionsContainNoTransportSecrets() = runBlocking {
        val count = AtomicInteger()
        val caller = Thread.currentThread()
        val fake = libraryFake()
        val adapter = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                assertTrue(Thread.currentThread() !== caller)
                count.incrementAndGet()
                throw IOException("https://user:private-password@nas.test/path")
            }
        }
        val result = NetworkLibraryClient(adapter, calls).listMedia(DirectoryRequest("root"))
        assertFailure(NetworkFailure.IO, result)
        assertEquals(3, count.get())
        assertFalse(result.toString().contains("private-password"))
        val denied = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                count.incrementAndGet()
                throw SecurityException("private-password")
            }
        }
        count.set(0)
        assertFailure(NetworkFailure.PERMISSION_REQUIRED, NetworkLibraryClient(denied, calls).listMedia(DirectoryRequest("root")))
        assertEquals(1, count.get())
    }

    @Test fun timeoutCallerCancellationAndNonCooperativeLateSuccessNeverBecomeSuccess() = runBlocking {
        val fake = libraryFake()
        val completed = AtomicInteger()
        val adapter = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                try { awaitCancellation() } finally { completed.incrementAndGet() }
            }
        }
        val oneAttempt = NetworkCalls(policy.copy(operationTimeoutMs = 40, maxAttempts = 1))
        assertFailure(NetworkFailure.TIMEOUT, NetworkLibraryClient(adapter, oneAttempt).listMedia(DirectoryRequest("root")))
        assertEquals(1, completed.get())
        val gate = CompletableDeferred<Unit>()
        val cancellable = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> {
                gate.complete(Unit)
                awaitCancellation()
            }
        }
        val cancelled = async { NetworkLibraryClient(cancellable, calls).listMedia(DirectoryRequest("root")) }
        gate.await()
        cancelled.cancelAndJoin()
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.await() } }
        val late = object : NetworkLibraryAdapter by fake {
            override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?) = withContext(NonCancellable) {
                delay(100)
                NetworkResult.Success(listOf(NetworkMedia(variant)))
            }
        }
        assertFailure(NetworkFailure.TIMEOUT, NetworkLibraryClient(late, oneAttempt).listMedia(DirectoryRequest("root")))
    }

    @Test fun ackAndUnconfirmedCommandNeverInventPlayingAndUnsupportedCommandsNeverReachAdapter() = runBlocking {
        selectRenderer()
        val fake = rendererFake()
        val count = AtomicInteger()
        val adapter = object : RendererAdapter by fake {
            override suspend fun sendCommand(device: RendererDevice, command: RendererCommand, credential: ByteArray?): NetworkResult<CommandReceipt> {
                count.incrementAndGet()
                return fake.sendCommand(device, command, credential)
            }
        }
        val client = rendererClient(adapter)
        success(client.connect())
        success(client.getCapabilities())
        assertEquals(CommandReceipt.ACKNOWLEDGED, success(client.sendCommand(RendererCommand.Play)))
        assertNull(client.state.value.observation)
        fake.commandReceipt = CommandReceipt.UNCONFIRMED
        assertEquals(CommandReceipt.UNCONFIRMED, success(client.sendCommand(RendererCommand.Play)))
        assertNull(client.state.value.observation)
        assertFailure(NetworkFailure.UNSUPPORTED, client.sendCommand(RendererCommand.Seek(1)))
        assertEquals(2, count.get())
        fake.report(RendererObservation(renderer.id, 1, RendererPhase.PLAYING, 1234, variant.source.sourceId, "queue-1"))
        success(client.observeState())
        assertEquals(RendererPhase.PLAYING, client.state.value.observation?.phase)
        fake.report(RendererObservation(renderer.id, 2, RendererPhase.PAUSED, queueVersion = "queue-2"))
        success(client.observeState())
        assertTrue(client.state.value.requiresQueueResync)
        assertEquals(RendererPhase.PAUSED, client.state.value.observation?.phase)
        assertEquals(2, count.get())
    }

    @Test fun lostAckIsNeverRetriedAndOfflineRequiresExplicitReconnectToSameId() = runBlocking {
        selectRenderer()
        val fake = rendererFake()
        val commands = mutableListOf<String>()
        val adapter = object : RendererAdapter by fake {
            override suspend fun sendCommand(device: RendererDevice, command: RendererCommand, credential: ByteArray?): NetworkResult<CommandReceipt> {
                commands += device.id
                awaitCancellation()
            }
        }
        val client = rendererClient(adapter, NetworkCalls(policy.copy(operationTimeoutMs = 40)))
        success(client.connect())
        success(client.getCapabilities())
        assertFailure(NetworkFailure.TIMEOUT, client.sendCommand(RendererCommand.Play))
        assertEquals(listOf(renderer.id), commands)
        assertEquals(CommandReceipt.UNCONFIRMED, client.state.value.commandReceipt)
        assertNull(client.state.value.observation)
        fake.failure = NetworkFailure.OFFLINE
        assertFailure(NetworkFailure.OFFLINE, client.observeState())
        assertEquals(RendererConnection.OFFLINE, client.state.value.connection)
        fake.failure = null
        assertFailure(NetworkFailure.OFFLINE, client.sendCommand(RendererCommand.Play))
        assertEquals(1, commands.size)
        success(client.connect())
        assertNull(client.state.value.observation)
        success(client.getCapabilities())
        fake.report(RendererObservation(renderer.id, 1, RendererPhase.STOPPED))
        success(client.observeState())
        assertEquals(RendererPhase.STOPPED, client.state.value.observation?.phase)
    }

    @Test fun staleRouteTransitionExpiredDiscoveryAndWrongHandshakeCannotControlOtherRenderer() = runBlocking {
        selectRenderer()
        val fake = rendererFake()
        assertEquals(listOf(renderer.id), success(RendererDiscovery(fake, calls).discover()).map { it.id })
        val client = rendererClient(fake)
        success(client.connect())
        success(client.getCapabilities())
        context = context.copy(target = PlaybackTarget("room-b", TargetKind.RENDERER), routeRevision = 2)
        assertFailure(NetworkFailure.STALE_RESPONSE, client.sendCommand(RendererCommand.Play))
        assertNull(client.state.value.observation)
        selectRenderer()
        val wrong = object : RendererAdapter by fake {
            override suspend fun connect(device: RendererDevice, credential: ByteArray?) = NetworkResult.Success("room-b")
        }
        val wrongClient = rendererClient(wrong)
        assertFailure(NetworkFailure.INVALID_RESPONSE, wrongClient.connect())
        assertEquals(RendererConnection.DISCONNECTED, wrongClient.state.value.connection)
        val expired = NetworkRendererClient(fake, renderer, context, { context }, calls, nowEpochMs = { 10_000 })
        assertFailure(NetworkFailure.EXPIRED, expired.connect())
        val transitionClient = rendererClient(fake)
        success(transitionClient.connect())
        success(transitionClient.getCapabilities())
        context = context.copy(transitionId = TransitionId("stop-2"))
        assertFailure(NetworkFailure.STALE_RESPONSE, transitionClient.sendCommand(RendererCommand.Play))
    }

    @Test fun delayedObservationAfterDisconnectOrSelectionChangeIsDiscarded() = runBlocking {
        selectRenderer()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = rendererFake()
        val adapter = object : RendererAdapter by fake {
            override suspend fun observeState(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererObservation> {
                entered.complete(Unit)
                release.await()
                return NetworkResult.Success(RendererObservation(device.id, 1, RendererPhase.PLAYING))
            }
        }
        val client = rendererClient(adapter)
        success(client.connect())
        success(client.getCapabilities())
        val pending = async { client.observeState() }
        entered.await()
        success(client.disconnect())
        release.complete(Unit)
        assertFailure(NetworkFailure.STALE_RESPONSE, pending.await())
        assertEquals(RendererConnection.DISCONNECTED, client.state.value.connection)
        assertNull(client.state.value.observation)
    }

    @Test fun olderOrWrongRendererObservationsCannotReplaceNewerEvidence() = runBlocking {
        selectRenderer()
        val fake = rendererFake()
        var report = RendererObservation(renderer.id, 2, RendererPhase.PAUSED)
        val adapter = object : RendererAdapter by fake {
            override suspend fun observeState(device: RendererDevice, credential: ByteArray?) = NetworkResult.Success(report)
        }
        val client = rendererClient(adapter)
        success(client.connect())
        success(client.getCapabilities())
        success(client.observeState())
        for (invalid in listOf(RendererObservation(renderer.id, 1, RendererPhase.PLAYING), RendererObservation("room-b", 3, RendererPhase.PLAYING))) {
            report = invalid
            assertFailure(NetworkFailure.STALE_RESPONSE, client.observeState())
            assertEquals(RendererPhase.PAUSED, client.state.value.observation?.phase)
            assertEquals(2L, client.state.value.observation?.sequence)
        }
    }

    @Test fun setResourceRequiresCurrentReachabilityWithoutForwardingCredentials() = runBlocking {
        selectRenderer()
        val client = rendererClient(rendererFake())
        success(client.connect())
        success(client.getCapabilities())
        for (invalid in listOf(resource(targetId = "other"), resource(uri = "https://user:password@nas.test/song"),
            resource(uri = "content://music/song"), resource(confirmed = false), resource(expires = 100),
            resource(uri = "https://nas.test/" + "x".repeat(8192)), resource(uri = "https://nas.test/\uD800"),
            resource(uri = "https://nas.test:0/song"), resource(uri = "https://nas.test/song?value=%0aattack"))) {
            assertFailure(NetworkFailure.INVALID_INPUT, client.sendCommand(RendererCommand.SetResource(invalid)))
        }
        assertEquals(CommandReceipt.ACKNOWLEDGED, success(client.sendCommand(RendererCommand.SetResource(resource()))))
        assertNull(client.state.value.observation)
    }

    private fun libraryFake() = FakeNetworkLibraryAdapter(nas,
        directories = mapOf("root" to listOf(NetworkDirectory("music", "Music"))),
        media = mapOf("music" to listOf(NetworkMedia(variant))),
        streams = mapOf(variant.variantId to FakeNetworkStream(variant, "nas-reader",
            PlaybackCapabilities(canPlay = true, canAccessPcm = true), mapOf(renderer.id to resource()))))

    private fun rendererFake() = FakeRendererAdapter(listOf(renderer), mapOf(renderer.id to rendererCapabilities))
    private fun rendererClient(adapter: RendererAdapter, networkCalls: NetworkCalls = calls) =
        NetworkRendererClient(adapter, renderer, context, { context }, networkCalls, nowEpochMs = { 100 })
    private fun selectRenderer() { context = ResolutionContext(PlaybackTarget(renderer.id, TargetKind.RENDERER), 1, TransitionId("play-1")) }
    private fun resource(uri: String = "https://nas.test/public/song", targetId: String = renderer.id, revision: Long = 1,
        confirmed: Boolean = true, expires: Long? = null) = SourceDescriptor.RendererResource(uri, targetId, revision, confirmed, expires)
    private fun <T> success(result: NetworkResult<T>): T {
        assertTrue(result.toString(), result is NetworkResult.Success)
        return (result as NetworkResult.Success).value
    }
    private fun assertFailure(expected: NetworkFailure, result: NetworkResult<*>) {
        assertEquals(NetworkResult.Failure(expected), result)
    }
}
