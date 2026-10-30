package com.audipp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackContractTest {
    private val source = SourceRef(SourceId("local:one"), SourceKind.LOCAL,
        localDocumentUri = "content://fixture/one", availability = Availability.PLAYABLE)
    private val transition = TransitionId("attempt-1")
    private val plan = ExecutionPlan(VariantId("five-minutes"), source, "phone",
        ExecutionMode.LOCAL_DIRECT, PlaybackCapabilities(canPlay = true, canAccessPcm = true),
        routeRevision = 4, transitionId = transition)
    private val five = TrackVariant(plan.variantId, TrackId("track"), source, 300_000)
    private val seven = five.copy(variantId = VariantId("seven-minutes"), durationMs = 420_000)

    @Test
    fun versionsOfOneTrackAndDuplicateQueueEntriesRetainSeparateIdentities() {
        val unknown = five.copy(variantId = VariantId("unknown"), durationMs = null)
        val first = QueueEntry(QueueEntryId("entry-1"), five.variantId)
        val second = QueueEntry(QueueEntryId("entry-2"), five.variantId)
        val variants = listOf(five, seven, unknown)

        assertEquals(variants, ModelJson.decode<List<TrackVariant>>(ModelJson.encode(variants)))
        assertEquals(five.trackId, seven.trackId)
        assertNotEquals(five.variantId, seven.variantId)
        assertNotEquals(first.entryId, second.entryId)
        assertEquals(first.variantId, second.variantId)
        assertNull(unknown.durationMs)
        assertEquals(420_000L, seven.durationMs)
        assertEquals(listOf(first, second), ModelJson.decode<List<QueueEntry>>(
            ModelJson.encode(listOf(first, second))))
    }

    @Test
    fun oneNetworkSourceCanHaveDifferentExecutionTargets() {
        val nas = source.copy(sourceId = SourceId("network:one"), kind = SourceKind.NETWORK,
            localDocumentUri = null)
        val local = plan.copy(source = nas)
        val remote = local.copy(targetId = "renderer", mode = ExecutionMode.NETWORK_REMOTE)
        assertEquals(local.source, remote.source)
        assertNotEquals(local.targetId, remote.targetId)
        assertEquals(remote, ModelJson.decode<ExecutionPlan>(ModelJson.encode(remote)))
    }

    @Test
    fun staleRoutePlanIsRejected() {
        assertEquals(PlanRejection.STALE_ROUTE, plan.rejectionFor(5, transition))
        assertNull(plan.rejectionFor(4, transition))
    }

    @Test
    fun cancellationOrNewTransitionRejectsDelayedSuccess() {
        val service = FakePlaybackConsumer(4, transition, listOf(five, seven))
        val playing = service.accept(plan, QueueEntryId("entry-1"))!!
        service.transitionId = TransitionId("after-stop")
        assertNull(service.accept(plan, QueueEntryId("entry-1")))
        assertEquals(playing, service.snapshot)
        assertEquals(PlanRejection.STALE_TRANSITION,
            plan.rejectionFor(4, service.transitionId))
    }

    @Test
    fun fakeUiAndServiceShareSnapshotIncludingSelectedVersionDuration() {
        val service = FakePlaybackConsumer(4, transition, listOf(five, seven))
        val selectedPlan = plan.copy(variantId = seven.variantId)
        val snapshot = service.accept(selectedPlan, QueueEntryId("entry-2"))!!
        val uiSnapshot = ModelJson.decode<PlaybackSnapshot>(ModelJson.encode(snapshot))
        assertEquals(snapshot, uiSnapshot)
        assertEquals(QueueEntryId("entry-2"), uiSnapshot.currentEntryId)
        assertEquals(420_000L, uiSnapshot.durationMs)
        assertEquals(seven.variantId, uiSnapshot.executionPlan?.variantId)
    }

    @Test
    fun playbackCapabilityDoesNotGrantPcmProcessingOrAnalysis() {
        PcmOperation.entries.forEach { operation ->
            assertEquals(PlanRejection.PCM_POLICY_BLOCKED,
                plan.rejectionFor(4, transition, operation))
            PermissionState.entries.forEach { permission ->
                val permissions = PcmPermissions(permission, permission)
                assertEquals(if (permission == PermissionState.ALLOWED) null
                    else PlanRejection.PCM_POLICY_BLOCKED,
                    plan.rejectionFor(4, transition, operation, permissions))
            }
        }
    }

    @Test
    fun processingAndAnalysisPermissionsAreIndependentAndRechecked() {
        val allowed = PcmPermissions(processing = PermissionState.ALLOWED)
        assertNull(plan.rejectionFor(4, transition, PcmOperation.PROCESSING, allowed))
        assertEquals(PlanRejection.PCM_POLICY_BLOCKED,
            plan.rejectionFor(4, transition, PcmOperation.ANALYSIS, allowed))
        assertEquals(PlanRejection.PCM_POLICY_BLOCKED,
            plan.rejectionFor(4, transition, PcmOperation.PROCESSING, PcmPermissions()))
        val noPcm = plan.copy(capabilities = PlaybackCapabilities(canPlay = true))
        assertEquals(PlanRejection.PCM_UNAVAILABLE,
            noPcm.rejectionFor(4, transition, PcmOperation.PROCESSING, allowed))
    }

    @Test
    fun unknownOrUnavailableSourcesAreNeverPlayable() {
        Availability.entries.filter { it != Availability.PLAYABLE }.forEach { availability ->
            assertEquals(PlanRejection.SOURCE_UNAVAILABLE,
                plan.copy(source = source.copy(availability = availability)).rejectionFor(4, transition))
        }
        assertEquals(PlanRejection.PLAYBACK_UNSUPPORTED,
            plan.copy(capabilities = PlaybackCapabilities()).rejectionFor(4, transition))
    }

    @Test
    fun unknownPositionAndDurationAndErrorActionsRoundTrip() {
        val snapshot = PlaybackSnapshot(3, PlaybackPhase.ERROR, error = PlaybackError(
            ErrorDomain.PROVIDER_POLICY, "PCM_NOT_ALLOWED", false,
            UserAction.REVIEW_POLICY, "diagnostic-1"))
        val restored = ModelJson.decode<PlaybackSnapshot>(ModelJson.encode(snapshot))
        assertEquals(snapshot, restored)
        assertNull(restored.positionMs)
        assertNull(restored.durationMs)
    }

    @Test
    fun invalidIdsFormatsPositionsAndInconsistentSourcesAreRejected() {
        listOf<() -> Any>(
            { TrackId("") }, { VariantId(" ") }, { SourceId("") },
            { QueueEntryId("") }, { TransitionId("") },
            { AudioFormat(sampleRateHz = 0) }, { AudioFormat(bitDepth = 0) },
            { AudioFormat(channelCount = -1) },
            { TrackVariant(plan.variantId, TrackId("track"), source, -1) },
            { plan.copy(routeRevision = -1) }, { plan.copy(targetId = "") },
            { PlaybackSnapshot(-1, PlaybackPhase.IDLE) },
            { PlaybackSnapshot(1, PlaybackPhase.PLAYING, positionMs = -1) },
            { PlaybackSnapshot(1, PlaybackPhase.PLAYING, positionMs = 2, durationMs = 1) },
            { PlaybackSnapshot(1, PlaybackPhase.PLAYING, executionPlan = plan) },
            { SourceRef(SourceId("provider:one"), SourceKind.PROVIDER) },
            { source.copy(providerKey = "provider", providerItemId = "item") },
            { source.copy(kind = SourceKind.NETWORK) },
        ).forEach { construct -> assertThrows(IllegalArgumentException::class.java) { construct() } }
    }

    private class FakePlaybackConsumer(
        var routeRevision: Long,
        var transitionId: TransitionId,
        val variants: List<TrackVariant>,
    ) {
        var snapshot: PlaybackSnapshot? = null
            private set

        fun accept(plan: ExecutionPlan, entryId: QueueEntryId): PlaybackSnapshot? {
            if (plan.rejectionFor(routeRevision, transitionId) != null) return null
            val variant = variants.singleOrNull { it.variantId == plan.variantId } ?: return null
            return PlaybackSnapshot(1, PlaybackPhase.PLAYING, entryId, 0, variant.durationMs,
                plan.source, plan).also { snapshot = it }
        }
    }
}
