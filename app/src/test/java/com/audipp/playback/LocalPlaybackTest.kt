package com.audipp.playback

import com.audipp.domain.model.*
import com.audipp.playback.source.SourceDescriptor
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackTest {
    private val variant = TrackVariant(VariantId("five-minute"), TrackId("song"),
        SourceRef(SourceId("local:song"), SourceKind.LOCAL,
            localDocumentUri = "content://fixture/private-song", availability = Availability.PLAYABLE))
    private val entry = QueueEntryId("entry-1")

    private class Engine : LocalPlaybackEngine {
        val prepared = mutableListOf<QueueEntryId>()
        var active = false
        var failure: RuntimeException? = null
        override fun prepare(entryId: QueueEntryId, document: SourceDescriptor.LocalDocument) {
            failure?.let { throw it }
            active = true
            prepared += entryId
        }
        override fun reset() { active = false }
    }

    @Test
    fun exactVariantAndEntryAreKeptAndCapabilitiesWaitForEngineObservation() = runBlocking {
        val engine = Engine()
        val checked = mutableListOf<String>()
        val playback = LocalPlayback(engine, { checked += it; Availability.PLAYABLE })
        assertNull(playback.start(entry, variant))
        val state = playback.snapshot.value
        assertEquals(listOf(variant.source.localDocumentUri), checked)
        assertEquals(PlaybackPhase.BUFFERING, state.phase)
        assertEquals(entry, state.currentEntryId)
        assertEquals(variant.variantId, state.executionPlan!!.variantId)
        assertEquals(ExecutionMode.LOCAL_DIRECT, state.executionPlan.mode)
        assertNull(state.durationMs)
        assertFalse(state.executionPlan.capabilities.canSeek)
        assertFalse(state.executionPlan.capabilities.canAccessPcm)
        playback.onEngineState(PlaybackPhase.PLAYING, 150, 100, true)
        assertEquals(100L, playback.snapshot.value.positionMs)
        assertTrue(playback.snapshot.value.executionPlan!!.capabilities.canSeek)
        playback.onEngineState(PlaybackPhase.PAUSED, 40, null, false)
        assertNull(playback.snapshot.value.durationMs)
        assertEquals(PlaybackPhase.PAUSED, playback.snapshot.value.phase)
    }

    @Test
    fun permissionDenialStopsPreviousAudioAndRetryRequiresFreshAccess() = runBlocking {
        val engine = Engine()
        var allowed = true
        val playback = LocalPlayback(engine, {
            if (!allowed) throw SecurityException("private URI and provider details")
            Availability.PLAYABLE
        })
        playback.start(entry, variant)
        allowed = false
        val error = playback.start(QueueEntryId("entry-2"), variant)!!
        assertEquals(ErrorDomain.PERMISSION_DENIED, error.domain)
        assertEquals(UserAction.REQUEST_PERMISSION, error.userAction)
        assertFalse(engine.active)
        assertEquals(PlaybackPhase.ERROR, playback.snapshot.value.phase)
        assertNull(playback.snapshot.value.executionPlan)
        assertFalse(ModelJson.encode(error).contains("private"))
        allowed = true
        assertNull(playback.start(entry, variant))
        assertTrue(engine.active)
        assertNull(playback.snapshot.value.error)
    }

    @Test
    fun invalidSchemesAndUnregisteredSourcesNeverReachAccessOrEngine() = runBlocking {
        val engine = Engine()
        val playback = LocalPlayback(engine, { error("must not open") })
        for (uri in listOf("file:///private/song", "https://example.com/song", "content://",
            "content://user:secret@fixture/song", "content://fixture/song#fragment")) {
            val error = playback.start(entry, variant.copy(source = variant.source.copy(localDocumentUri = uri)))!!
            assertEquals(ErrorDomain.FORMAT_UNSUPPORTED, error.domain)
        }
        playback.start(entry, variant.copy(source = SourceRef(SourceId("network:song"),
            SourceKind.NETWORK, availability = Availability.PLAYABLE)))
        assertTrue(engine.prepared.isEmpty())
    }

    @Test
    fun missingAndIoFailuresAreCommonErrorsAndNeverPrepare() = runBlocking {
        for (access in listOf<suspend (String) -> Availability>(
            { Availability.MISSING }, { throw IOException("content://private") })) {
            val engine = Engine()
            val playback = LocalPlayback(engine, access)
            assertEquals(ErrorDomain.SOURCE_IO, playback.start(entry, variant)!!.domain)
            assertFalse(engine.active)
            assertTrue(engine.prepared.isEmpty())
        }
    }

    @Test
    fun stopDuringSlowAccessCannotRestartEvenIfProviderIgnoresCancellation() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = Engine()
        val playback = LocalPlayback(engine, {
            opened.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            Availability.PLAYABLE
        })
        val pending = async { playback.start(entry, variant) }
        withTimeout(2_000) { opened.await() }
        playback.stop()
        pending.cancel()
        finish.complete(Unit)
        pending.join()
        assertEquals(PlaybackPhase.IDLE, playback.snapshot.value.phase)
        assertTrue(engine.prepared.isEmpty())
        assertFalse(engine.active)
    }

    @Test
    fun supersededUncancelledResponseCannotResetNewPlayback() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = Engine()
        var first = true
        val playback = LocalPlayback(engine, {
            if (first) {
                first = false
                opened.complete(Unit)
                finish.await()
            }
            Availability.PLAYABLE
        })
        val pending = async { playback.start(entry, variant) }
        withTimeout(2_000) { opened.await() }
        val next = QueueEntryId("entry-2")
        playback.start(next, variant)
        finish.complete(Unit)
        try { pending.await(); fail("old response must cancel") } catch (_: CancellationException) { }
        assertEquals(listOf(next), engine.prepared)
        assertEquals(next, playback.snapshot.value.currentEntryId)
        assertTrue(engine.active)
    }

    @Test
    fun timeoutAndCancellationLeaveNoPreparedSource() = runBlocking {
        val engine = Engine()
        val playback = LocalPlayback(engine, { awaitCancellation() }, timeoutMs = 20)
        assertEquals("TIMEOUT", playback.start(entry, variant)!!.code)
        assertFalse(engine.active)
        val pending = async { playback.start(entry, variant) }
        pending.cancel()
        pending.join()
        assertTrue(engine.prepared.isEmpty())
    }

    @Test
    fun engineFailureClearsResourcesAndLateStateDoesNotHideError() = runBlocking {
        val engine = Engine()
        val playback = LocalPlayback(engine, { Availability.PLAYABLE })
        playback.start(entry, variant)
        val error = playbackError(ErrorDomain.FORMAT_UNSUPPORTED, "LOCAL_FORMAT_UNSUPPORTED", false)
        playback.fail(error)
        playback.onEngineState(PlaybackPhase.PLAYING, 0, null, true)
        assertFalse(engine.active)
        assertEquals(error, playback.snapshot.value.error)
        assertEquals(PlaybackPhase.ERROR, playback.snapshot.value.phase)
        engine.failure = SecurityException("content://private")
        assertEquals(ErrorDomain.PERMISSION_DENIED, playback.start(entry, variant)!!.domain)
        assertFalse(engine.active)
    }

    @Test
    fun sessionSnapshotNeverContainsDocumentUriOrInventedOutputEvidence() = runBlocking {
        val playback = LocalPlayback(Engine(), { Availability.PLAYABLE })
        playback.start(entry, variant)
        val snapshot = playback.snapshot.value.withoutDocumentUri()
        assertNull(snapshot.effectiveSource!!.localDocumentUri)
        assertEquals(snapshot.effectiveSource, snapshot.executionPlan!!.source)
        assertFalse(ModelJson.encode(snapshot).contains("content://"))
        assertEquals(variant.variantId, snapshot.executionPlan.variantId)
    }
}
