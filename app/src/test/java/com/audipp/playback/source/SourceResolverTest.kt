package com.audipp.playback.source

import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolverTest {
    private val local = TrackVariant(VariantId("five-minute"), TrackId("song"),
        SourceRef(SourceId("local:song"), SourceKind.LOCAL,
            localDocumentUri = "content://music/song", availability = Availability.PLAYABLE), 300_000)
    private val network = local.copy(source = SourceRef(SourceId("nas:song"),
        SourceKind.NETWORK, availability = Availability.PLAYABLE))
    private val provider = local.copy(source = SourceRef(SourceId("provider:song"),
        SourceKind.PROVIDER, "fixture", "song", availability = Availability.PLAYABLE))
    private val device = PlaybackTarget("phone", TargetKind.DEVICE)
    private val renderer = PlaybackTarget("living-room", TargetKind.RENDERER)
    private var context = ResolutionContext(device, 3, TransitionId("attempt-1"))
    private val capabilities = PlaybackCapabilities(canPlay = true)
    private val allowed = PlaybackPermissionChecker { _, _ -> PermissionState.ALLOWED }

    @Test
    fun localAdapterChecksTheExactDocumentAndRetainsSelectedVersion() = runBlocking {
        val read = mutableListOf<String>()
        val adapter = LocalDocumentSourceAdapter(capabilities) { read += it; Availability.PLAYABLE }
        val resolver = resolver(mapOf(key(local) to adapter))
        val result = resolver.resolve(request(local), listOf(local.copy(
            variantId = VariantId("seven-minute"), durationMs = 420_000))) as SourceResolution.Resolved.Playback

        assertEquals(listOf("content://music/song"), read)
        assertEquals(local.variantId, result.plan.variantId)
        assertEquals(local.source, result.plan.source)
        assertEquals(ExecutionMode.LOCAL_DIRECT, result.plan.mode)
        assertEquals(context.routeRevision, result.plan.routeRevision)
        assertEquals(context.transitionId, result.plan.transitionId)
        assertNull(resolver.rejectionFor(result))
    }

    @Test
    fun sameNasVariantHasDifferentDeviceAndRendererPlans() = runBlocking {
        val registrations = mapOf(
            key(network) to adapter { SourceDescriptor.DeviceResource("nas-reader") },
            key(network, TargetKind.RENDERER) to adapter { rendererDescriptor() },
        )
        val resolver = resolver(registrations)
        val onDevice = resolver.resolve(request(network)) as SourceResolution.Resolved.Playback
        context = context.copy(target = renderer, routeRevision = 4)
        val onRenderer = resolver.resolve(request(network)) as SourceResolution.Resolved.Playback

        assertEquals(onDevice.plan.variantId, onRenderer.plan.variantId)
        assertEquals(onDevice.plan.source, onRenderer.plan.source)
        assertEquals(ExecutionMode.LOCAL_DIRECT, onDevice.plan.mode)
        assertEquals(ExecutionMode.NETWORK_REMOTE, onRenderer.plan.mode)
        assertEquals(renderer.id, onRenderer.plan.targetId)
        assertFalse(onRenderer.plan.capabilities.canAccessPcm)
        assertEquals(ResolutionFailure.STALE_ROUTE, resolver.rejectionFor(onDevice))
    }

    @Test
    fun missingAdaptersAndOtherProviderRegistrationsNeverPretendToBePlayable() = runBlocking {
        assertFailure(ResolutionFailure.ADAPTER_UNAVAILABLE, resolver().resolve(request(network)))
        val other = key(provider).copy(providerKey = "other-service")
        assertFailure(ResolutionFailure.ADAPTER_UNAVAILABLE,
            resolver(mapOf(other to adapter { error("wrong provider") }), allowed).resolve(request(provider)))
    }

    @Test
    fun unavailableSourceOnlyReturnsCandidateIdsAndNeverResolvesAnotherVariant() = runBlocking {
        var calls = 0
        val resolver = resolver(mapOf(key(local) to adapter { calls++; error("unavailable") }))
        val candidate = local.copy(variantId = VariantId("seven-minute"), durationMs = 420_000)
        val failures = mapOf(Availability.AUTH_REQUIRED to ResolutionFailure.AUTH_REQUIRED,
            Availability.EXPIRED to ResolutionFailure.EXPIRED,
            Availability.MISSING to ResolutionFailure.SOURCE_MISSING,
            Availability.UNSUPPORTED to ResolutionFailure.PLAYBACK_UNSUPPORTED,
            Availability.POLICY_BLOCKED to ResolutionFailure.POLICY_BLOCKED,
            Availability.UNKNOWN to ResolutionFailure.SOURCE_UNAVAILABLE)
        for ((availability, failure) in failures) {
            val missing = local.copy(source = local.source.copy(availability = availability))
            val result = resolver.resolve(request(missing), listOf(local, candidate, candidate,
                candidate.copy(trackId = TrackId("other")),
                candidate.copy(variantId = VariantId("also-missing"), source = missing.source)))
                as SourceResolution.Unavailable
            assertEquals(failure, result.reason)
            assertEquals(listOf(candidate.variantId), result.alternatives)
        }
        assertEquals(0, calls)
    }

    @Test
    fun contentUriCannotBeSentToRendererEvenByRegisteredAdapter() = runBlocking {
        context = context.copy(target = renderer)
        val resolver = resolver(mapOf(key(local, TargetKind.RENDERER) to adapter {
            SourceDescriptor.LocalDocument("content://music/song")
        }))
        assertFailure(ResolutionFailure.INVALID_DESCRIPTOR, resolver.resolve(request(local)))
    }

    @Test
    fun rendererRejectsUnsafeUrlsAndRequiresCurrentReachabilityEvidence() = runBlocking {
        context = context.copy(target = renderer)
        for (uri in listOf("content://music/song", "file:///song", "smb://nas/song",
            "relative/path", "https://user:password@nas/song", "https://nas/song#secret", "https:///song")) {
            val resolver = resolver(mapOf(key(network, TargetKind.RENDERER) to adapter {
                rendererDescriptor(uri = uri)
            }))
            assertFailure(ResolutionFailure.INVALID_DESCRIPTOR, resolver.resolve(request(network)))
        }
        for (descriptor in listOf(rendererDescriptor(confirmed = false),
            rendererDescriptor(targetId = "other-room"), rendererDescriptor(revision = 2))) {
            val resolver = resolver(mapOf(key(network, TargetKind.RENDERER) to adapter { descriptor }))
            assertFailure(ResolutionFailure.TARGET_UNREACHABLE, resolver.resolve(request(network)))
        }
    }

    @Test
    fun deniedUnreviewedAndMissingProviderPermissionsBlockBeforeAdapterIo() = runBlocking {
        val registrations = mapOf(key(provider) to adapter { error("must not acquire stream") })
        assertFailure(ResolutionFailure.POLICY_BLOCKED, resolver(registrations).resolve(request(provider)))
        for (permission in listOf(PermissionState.DENIED, PermissionState.UNREVIEWED)) {
            assertFailure(ResolutionFailure.POLICY_BLOCKED,
                resolver(registrations, PlaybackPermissionChecker { _, _ -> permission })
                    .resolve(request(provider)))
        }
    }

    @Test
    fun allowedProviderDirectPlaybackChecksItsOwnUseAgainBeforeConsumption() = runBlocking {
        var permission = PermissionState.ALLOWED
        val uses = mutableListOf<ResolutionUse>()
        val resolver = resolver(mapOf(key(provider) to adapter { SourceDescriptor.DeviceResource("session") }),
            PlaybackPermissionChecker { req, use ->
                assertEquals(provider.source, req.variant.source)
                uses += use
                permission
            })
        val result = resolver.resolve(request(provider)) as SourceResolution.Resolved.Playback
        assertEquals(ExecutionMode.PARTNER_DIRECT, result.plan.mode)
        assertEquals(listOf(ResolutionUse.DIRECT_PLAYBACK, ResolutionUse.DIRECT_PLAYBACK), uses)
        permission = PermissionState.DENIED
        assertEquals(ResolutionFailure.POLICY_BLOCKED, resolver.rejectionFor(result))
    }

    @Test
    fun remoteAndHandoffAreAuxiliaryResultsAndUseControlPermission() = runBlocking {
        for (kind in listOf(TargetKind.PROVIDER_REMOTE, TargetKind.EXTERNAL_APP)) {
            context = context.copy(target = PlaybackTarget("external", kind))
            val descriptor = if (kind == TargetKind.PROVIDER_REMOTE) SourceDescriptor.ControlSession("control")
                else SourceDescriptor.Handoff("fixture://song")
            val resolver = resolver(mapOf(key(provider, kind) to adapter { descriptor }),
                PlaybackPermissionChecker { _, use ->
                    if (use == ResolutionUse.PLAYBACK_CONTROL) PermissionState.ALLOWED else PermissionState.DENIED
                })
            val result = resolver.resolve(request(provider))
            assertTrue(result is SourceResolution.Resolved.Auxiliary)
            assertFalse(result is SourceResolution.Resolved.Playback)
            assertEquals(if (kind == TargetKind.PROVIDER_REMOTE) ExecutionMode.PARTNER_REMOTE
                else ExecutionMode.EXTERNAL_HANDOFF, (result as SourceResolution.Resolved).plan.mode)
        }
    }

    @Test
    fun aRendererOrExternalControllerCannotClaimLocalPcmAccess() = runBlocking {
        context = context.copy(target = renderer)
        val resolver = resolver(mapOf(key(network, TargetKind.RENDERER) to SourceAdapter {
            available(it, rendererDescriptor(), capabilities.copy(canAccessPcm = true))
        }))
        assertFailure(ResolutionFailure.PLAYBACK_UNSUPPORTED, resolver.resolve(request(network)))
    }

    @Test
    fun adapterCannotReturnAnotherVariantOrAnotherSource() = runBlocking {
        for (response in listOf(
            AdapterResolution.Available(VariantId("seven-minute"), local.source.sourceId,
                SourceDescriptor.LocalDocument("content://music/song"), capabilities),
            AdapterResolution.Available(local.variantId, SourceId("local:other"),
                SourceDescriptor.LocalDocument("content://music/song"), capabilities),
        )) {
            val resolver = resolver(mapOf(key(local) to SourceAdapter { response }))
            assertFailure(ResolutionFailure.SOURCE_MISMATCH, resolver.resolve(request(local)))
        }
    }

    @Test
    fun changedTargetRouteAndStoppedTransitionRejectDelayedAdapterSuccess() = runBlocking {
        val initial = context
        for (changed in listOf(initial.copy(target = renderer), initial.copy(routeRevision = 4),
            initial.copy(transitionId = TransitionId("stop")))) {
            context = initial
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resolver = resolver(mapOf(key(network) to SourceAdapter {
                entered.complete(Unit)
                release.await()
                available(it, SourceDescriptor.DeviceResource("late-session"))
            }))
            val result = async { resolver.resolve(request(network)) }
            entered.await()
            context = changed
            release.complete(Unit)
            val unavailable = result.await() as SourceResolution.Unavailable
            assertEquals(if (changed.transitionId != initial.transitionId) ResolutionFailure.STALE_TRANSITION
                else ResolutionFailure.STALE_ROUTE, unavailable.reason)
            assertTrue(unavailable.alternatives.isEmpty())
        }
    }

    @Test
    fun routeChangeDuringPermissionCheckPreventsAdapterIo() = runBlocking {
        val resolver = resolver(mapOf(key(provider) to adapter { error("stale request") }),
            PlaybackPermissionChecker { _, _ ->
                context = context.copy(routeRevision = 4)
                PermissionState.ALLOWED
            })
        assertFailure(ResolutionFailure.STALE_ROUTE, resolver.resolve(request(provider)))
    }

    @Test
    fun permissionRevokedWhileResolvingRejectsDelayedStream() = runBlocking {
        var permission = PermissionState.ALLOWED
        val resolver = resolver(mapOf(key(provider) to adapter {
            permission = PermissionState.DENIED
            SourceDescriptor.DeviceResource("expired-authorization")
        }), PlaybackPermissionChecker { _, _ -> permission })
        assertFailure(ResolutionFailure.POLICY_BLOCKED, resolver.resolve(request(provider)))
    }

    @Test
    fun descriptorExpiryIsCheckedAtBoundaryAndBeforeConsumption() = runBlocking {
        var now = 100L
        val resolver = resolver(mapOf(key(network) to adapter {
            SourceDescriptor.DeviceResource("temporary", expiresAtEpochMs = 101)
        }), now = { now })
        val result = resolver.resolve(request(network)) as SourceResolution.Resolved.Playback
        now = 101
        assertEquals(ResolutionFailure.EXPIRED, resolver.rejectionFor(result))
        assertFailure(ResolutionFailure.EXPIRED, resolver.resolve(request(network)))
    }

    @Test
    fun revalidationReportsPolicyTimeoutAndIoFailureWithoutAcceptingOldPermission() = runBlocking {
        var suspended = false
        var failed = false
        val resolver = resolver(mapOf(key(provider) to adapter { SourceDescriptor.DeviceResource("session") }),
            PlaybackPermissionChecker { _, _ ->
                if (suspended) delay(5_000)
                if (failed) throw IOException("secret")
                PermissionState.ALLOWED
            }, timeout = 100)
        val result = resolver.resolve(request(provider)) as SourceResolution.Resolved.Playback
        suspended = true
        assertEquals(ResolutionFailure.TIMEOUT, resolver.rejectionFor(result))
        suspended = false
        failed = true
        assertEquals(ResolutionFailure.SOURCE_IO, resolver.rejectionFor(result))
    }

    @Test
    fun invalidSourceTargetCombinationsAndWrongLocalDocumentAreRejected() = runBlocking {
        for ((variant, target) in listOf(provider to renderer,
            network to PlaybackTarget("remote", TargetKind.PROVIDER_REMOTE),
            local to PlaybackTarget("app", TargetKind.EXTERNAL_APP))) {
            context = context.copy(target = target)
            assertFailure(ResolutionFailure.PLAYBACK_UNSUPPORTED, resolver().resolve(request(variant)))
        }
        context = context.copy(target = device)
        val resolver = resolver(mapOf(key(local) to adapter {
            SourceDescriptor.LocalDocument("content://music/another-version")
        }))
        assertFailure(ResolutionFailure.INVALID_DESCRIPTOR, resolver.resolve(request(local)))
    }

    @Test
    fun nonPlayableCapabilitiesAndExplicitAdapterFailuresAreReturned() = runBlocking {
        val resolver = resolver(mapOf(key(network) to SourceAdapter {
            available(it, SourceDescriptor.DeviceResource("reader"), PlaybackCapabilities())
        }))
        assertFailure(ResolutionFailure.PLAYBACK_UNSUPPORTED, resolver.resolve(request(network)))
        assertFailure(ResolutionFailure.TARGET_UNREACHABLE,
            resolver(mapOf(key(network) to SourceAdapter {
                AdapterResolution.Unavailable(ResolutionFailure.TARGET_UNREACHABLE)
            })).resolve(request(network)))
    }

    @Test
    fun localDocumentValidationAndRevokedAccessNeverReturnSuccess() = runBlocking {
        for (uri in listOf(null, "file:///song", "content:///song", "https://nas/song", "content://a/song#x")) {
            val variant = local.copy(source = local.source.copy(localDocumentUri = uri))
            val resolver = resolver(mapOf(key(local) to LocalDocumentSourceAdapter(capabilities) {
                error("invalid URI must not reach provider")
            }))
            assertFailure(ResolutionFailure.INVALID_DESCRIPTOR, resolver.resolve(request(variant)))
        }
        val resolver = resolver(mapOf(key(local) to LocalDocumentSourceAdapter(capabilities) {
            Availability.AUTH_REQUIRED
        }))
        assertFailure(ResolutionFailure.AUTH_REQUIRED, resolver.resolve(request(local)))
    }

    @Test
    fun storageAndPermissionExceptionsReturnClassificationsWithoutSecretMessages() = runBlocking {
        for ((error, reason) in listOf(IOException("https://nas/?token=secret") to ResolutionFailure.SOURCE_IO,
            SecurityException("content://private/song") to ResolutionFailure.PERMISSION_REQUIRED)) {
            val result = resolver(mapOf(key(network) to SourceAdapter { throw error })).resolve(request(network))
            assertFailure(reason, result)
            assertFalse(result.toString().contains(error.message!!))
        }
        val descriptor = SourceDescriptor.DeviceResource("https://nas/?token=secret")
        val result = resolver(mapOf(key(network) to adapter { descriptor })).resolve(request(network))
        assertFalse(descriptor.toString().contains("secret"))
        assertFalse(result.toString().contains("secret"))
    }

    @Test
    fun resolverTimeoutIsReportedButCallerTimeoutRemainsCancellation() = runBlocking {
        val registrations = mapOf(key(network) to SourceAdapter {
            delay(5_000)
            available(it, SourceDescriptor.DeviceResource("late"))
        })
        assertFailure(ResolutionFailure.TIMEOUT,
            resolver(registrations, timeout = 30).resolve(request(network)))
        var cancelled = false
        try {
            withTimeout(30) { resolver(registrations).resolve(request(network)) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun cancelledRequestCannotReturnSuccessEvenIfAdapterIgnoresCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = resolver(mapOf(key(network) to SourceAdapter {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                available(it, SourceDescriptor.DeviceResource("late"))
            }
        }))
        val result = async { resolver.resolve(request(network)) }
        entered.await()
        result.cancel()
        release.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
    }

    @Test
    fun registryIsASnapshotAndInvalidConfigurationIsRejected() = runBlocking<Unit> {
        val registrations = mutableMapOf(key(network) to adapter { SourceDescriptor.DeviceResource("reader") })
        val resolver = resolver(registrations)
        registrations.clear()
        assertTrue(resolver.resolve(request(network)) is SourceResolution.Resolved.Playback)
        assertThrows(IllegalArgumentException::class.java) { PlaybackTarget(" ", TargetKind.DEVICE) }
        assertThrows(IllegalArgumentException::class.java) { context.copy(routeRevision = -1) }
        assertThrows(IllegalArgumentException::class.java) { AdapterKey(SourceKind.PROVIDER, TargetKind.DEVICE) }
        assertThrows(IllegalArgumentException::class.java) { resolver(timeout = 0) }
        assertThrows(IllegalArgumentException::class.java) { SourceDescriptor.DeviceResource("x", -1) }
    }

    private fun resolver(
        adapters: Map<AdapterKey, SourceAdapter> = emptyMap(),
        permissions: PlaybackPermissionChecker = PlaybackPermissionChecker { _, _ -> PermissionState.UNREVIEWED },
        now: () -> Long = { 100L },
        timeout: Long = 10_000,
    ) = SourceResolver(adapters, { context }, permissions, now, timeout)

    private fun request(variant: TrackVariant) = ResolutionRequest(variant, context)

    private fun key(variant: TrackVariant, target: TargetKind = TargetKind.DEVICE) =
        AdapterKey(variant.source.kind, target, variant.source.providerKey)

    private fun adapter(descriptor: () -> SourceDescriptor) = SourceAdapter { available(it, descriptor()) }

    private fun available(request: ResolutionRequest, descriptor: SourceDescriptor,
        capabilities: PlaybackCapabilities = this.capabilities) = AdapterResolution.Available(
        request.variant.variantId, request.variant.source.sourceId, descriptor, capabilities)

    private fun rendererDescriptor(
        uri: String = "https://nas/song", targetId: String = renderer.id,
        revision: Long = context.routeRevision, confirmed: Boolean = true,
    ) = SourceDescriptor.RendererResource(uri, targetId, revision, confirmed)

    private fun assertFailure(reason: ResolutionFailure, result: SourceResolution) {
        assertEquals(reason, (result as SourceResolution.Unavailable).reason)
    }
}
