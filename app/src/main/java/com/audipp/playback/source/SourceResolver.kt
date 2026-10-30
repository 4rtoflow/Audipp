package com.audipp.playback.source

import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class TargetKind { DEVICE, RENDERER, PROVIDER_REMOTE, EXTERNAL_APP }

data class PlaybackTarget(val id: String, val kind: TargetKind) {
    init { require(id.isNotBlank()) }
}

data class ResolutionContext(
    val target: PlaybackTarget,
    val routeRevision: Long,
    val transitionId: TransitionId,
) {
    init { require(routeRevision >= 0) }
}

data class ResolutionRequest(val variant: TrackVariant, val context: ResolutionContext)

data class AdapterKey(
    val sourceKind: SourceKind,
    val targetKind: TargetKind,
    val providerKey: String? = null,
) {
    init {
        require((sourceKind == SourceKind.PROVIDER) == (providerKey != null))
        require(providerKey == null || providerKey.isNotBlank())
    }
}

enum class ResolutionUse { DIRECT_PLAYBACK, PLAYBACK_CONTROL }

fun interface PlaybackPermissionChecker {
    suspend fun check(request: ResolutionRequest, use: ResolutionUse): PermissionState
}

enum class ResolutionFailure {
    STALE_ROUTE, STALE_TRANSITION, SOURCE_UNAVAILABLE, ADAPTER_UNAVAILABLE,
    POLICY_BLOCKED, PLAYBACK_UNSUPPORTED, INVALID_DESCRIPTOR, EXPIRED,
    TARGET_UNREACHABLE, SOURCE_IO, SOURCE_MISMATCH, TIMEOUT, AUTH_REQUIRED,
    SOURCE_MISSING, PERMISSION_REQUIRED,
}

// Descriptors may contain credentials or signed URLs; keep them out of serializable plans and logs.
sealed class SourceDescriptor(val expiresAtEpochMs: Long? = null) {
    init { require(expiresAtEpochMs == null || expiresAtEpochMs >= 0) }

    class LocalDocument(val uri: String) : SourceDescriptor()
    class DeviceResource(val handle: String, expiresAtEpochMs: Long? = null) :
        SourceDescriptor(expiresAtEpochMs)
    class RendererResource(
        val uri: String,
        val targetId: String,
        val routeRevision: Long,
        val reachabilityConfirmed: Boolean = false,
        expiresAtEpochMs: Long? = null,
    ) : SourceDescriptor(expiresAtEpochMs)
    class ControlSession(val handle: String, expiresAtEpochMs: Long? = null) :
        SourceDescriptor(expiresAtEpochMs)
    class Handoff(val uri: String, expiresAtEpochMs: Long? = null) :
        SourceDescriptor(expiresAtEpochMs)

    final override fun toString(): String = "SourceDescriptor(redacted)"
}

sealed interface AdapterResolution {
    class Available(
        val variantId: VariantId,
        val sourceId: SourceId,
        val descriptor: SourceDescriptor,
        val capabilities: PlaybackCapabilities,
    ) : AdapterResolution

    data class Unavailable(val reason: ResolutionFailure) : AdapterResolution
}

fun interface SourceAdapter {
    suspend fun resolve(request: ResolutionRequest): AdapterResolution
}

sealed interface SourceResolution {
    sealed class Resolved(
        val request: ResolutionRequest,
        val plan: ExecutionPlan,
        val descriptor: SourceDescriptor,
    ) : SourceResolution {
        class Playback internal constructor(
            request: ResolutionRequest, plan: ExecutionPlan, descriptor: SourceDescriptor,
        ) : Resolved(request, plan, descriptor)

        class Auxiliary internal constructor(
            request: ResolutionRequest, plan: ExecutionPlan, descriptor: SourceDescriptor,
        ) : Resolved(request, plan, descriptor)

        final override fun toString(): String = "SourceResolution.Resolved(redacted)"
    }

    data class Unavailable(
        val reason: ResolutionFailure,
        val alternatives: List<VariantId> = emptyList(),
    ) : SourceResolution
}

class SourceResolver(
    adapters: Map<AdapterKey, SourceAdapter>,
    private val currentContext: () -> ResolutionContext,
    private val permissions: PlaybackPermissionChecker = PlaybackPermissionChecker { _, _ ->
        PermissionState.UNREVIEWED
    },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 10_000,
) {
    init { require(timeoutMs > 0) }
    private val adapters = adapters.toMap()

    suspend fun resolve(
        request: ResolutionRequest,
        alternatives: List<TrackVariant> = emptyList(),
    ): SourceResolution = try {
        withTimeoutOrNull(timeoutMs) { resolveChecked(request, alternatives) }
            ?: unavailable(ResolutionFailure.TIMEOUT, request, alternatives)
    } catch (_: IOException) {
        unavailable(ResolutionFailure.SOURCE_IO, request, alternatives)
    } catch (_: SecurityException) {
        unavailable(ResolutionFailure.PERMISSION_REQUIRED, request, alternatives)
    }

    private suspend fun resolveChecked(
        request: ResolutionRequest, alternatives: List<TrackVariant>,
    ): SourceResolution {
        currentCoroutineContext().ensureActive()
        contextFailure(request.context)?.let { return SourceResolution.Unavailable(it) }
        availabilityFailure(request.variant.source.availability)?.let {
            return unavailable(it, request, alternatives)
        }
        val source = request.variant.source
        val mode = modeFor(source.kind, request.context.target.kind)
            ?: return unavailable(ResolutionFailure.PLAYBACK_UNSUPPORTED, request, alternatives)
        policyFailure(request, mode)?.let { return unavailable(it, request, alternatives) }
        currentCoroutineContext().ensureActive()
        contextFailure(request.context)?.let { return SourceResolution.Unavailable(it) }
        val key = AdapterKey(source.kind, request.context.target.kind, source.providerKey)
        val adapter = adapters[key]
            ?: return unavailable(ResolutionFailure.ADAPTER_UNAVAILABLE, request, alternatives)
        val response = withContext(Dispatchers.IO) { adapter.resolve(request) }
        currentCoroutineContext().ensureActive()
        contextFailure(request.context)?.let { return SourceResolution.Unavailable(it) }
        if (response is AdapterResolution.Unavailable) {
            return unavailable(response.reason, request, alternatives)
        }
        response as AdapterResolution.Available
        if (response.variantId != request.variant.variantId || response.sourceId != source.sourceId) {
            return unavailable(ResolutionFailure.SOURCE_MISMATCH, request, alternatives)
        }
        if (mode in setOf(ExecutionMode.NETWORK_REMOTE, ExecutionMode.PARTNER_REMOTE,
                ExecutionMode.EXTERNAL_HANDOFF) && response.capabilities.canAccessPcm
        ) return unavailable(ResolutionFailure.PLAYBACK_UNSUPPORTED, request, alternatives)
        val plan = ExecutionPlan(
            request.variant.variantId, source, request.context.target.id, mode,
            response.capabilities, request.context.routeRevision, request.context.transitionId,
        )
        val result = when (mode) {
            ExecutionMode.PARTNER_REMOTE, ExecutionMode.EXTERNAL_HANDOFF ->
                SourceResolution.Resolved.Auxiliary(request, plan, response.descriptor)
            else -> SourceResolution.Resolved.Playback(request, plan, response.descriptor)
        }
        return rejectionFor(result)?.let { unavailable(it, request, alternatives) } ?: result
    }

    // Authorization and route state can change while an adapter is waiting, or before playback starts.
    suspend fun rejectionFor(result: SourceResolution.Resolved): ResolutionFailure? {
        var rejection: ResolutionFailure? = ResolutionFailure.TIMEOUT
        try {
            withTimeoutOrNull(timeoutMs) { rejection = checkedRejection(result) }
        } catch (_: IOException) {
            rejection = ResolutionFailure.SOURCE_IO
        } catch (_: SecurityException) {
            rejection = ResolutionFailure.PERMISSION_REQUIRED
        }
        currentCoroutineContext().ensureActive()
        return contextFailure(result.request.context) ?: rejection
    }

    private suspend fun checkedRejection(result: SourceResolution.Resolved): ResolutionFailure? {
        currentCoroutineContext().ensureActive()
        val request = result.request
        contextFailure(request.context)?.let { return it }
        val policy = policyFailure(request, result.plan.mode)
        currentCoroutineContext().ensureActive()
        contextFailure(request.context)?.let { return it }
        if (policy != null) return policy
        if (!result.plan.capabilities.canPlay) return ResolutionFailure.PLAYBACK_UNSUPPORTED
        return descriptorFailure(request, result.descriptor)
    }

    private suspend fun policyFailure(
        request: ResolutionRequest, mode: ExecutionMode,
    ): ResolutionFailure? {
        if (request.variant.source.kind != SourceKind.PROVIDER) return null
        val use = if (mode == ExecutionMode.PARTNER_DIRECT) ResolutionUse.DIRECT_PLAYBACK
            else ResolutionUse.PLAYBACK_CONTROL
        return if (permissions.check(request, use) == PermissionState.ALLOWED) null
            else ResolutionFailure.POLICY_BLOCKED
    }

    private fun contextFailure(context: ResolutionContext): ResolutionFailure? {
        val current = currentContext()
        return when {
            current.target != context.target || current.routeRevision != context.routeRevision ->
                ResolutionFailure.STALE_ROUTE
            current.transitionId != context.transitionId -> ResolutionFailure.STALE_TRANSITION
            else -> null
        }
    }

    private fun descriptorFailure(
        request: ResolutionRequest, descriptor: SourceDescriptor,
    ): ResolutionFailure? {
        if (descriptor.expiresAtEpochMs?.let { it <= nowEpochMs() } == true) {
            return ResolutionFailure.EXPIRED
        }
        val valid = when (request.context.target.kind) {
            TargetKind.DEVICE -> when (descriptor) {
                is SourceDescriptor.LocalDocument ->
                    request.variant.source.kind == SourceKind.LOCAL &&
                        descriptor.uri == request.variant.source.localDocumentUri &&
                        isContentUri(descriptor.uri)
                is SourceDescriptor.DeviceResource ->
                    request.variant.source.kind != SourceKind.LOCAL && descriptor.handle.isNotBlank()
                else -> false
            }
            TargetKind.RENDERER -> {
                if (descriptor is SourceDescriptor.RendererResource) {
                    if (descriptor.targetId != request.context.target.id ||
                        descriptor.routeRevision != request.context.routeRevision ||
                        !descriptor.reachabilityConfirmed
                    ) return ResolutionFailure.TARGET_UNREACHABLE
                    isRendererUri(descriptor.uri)
                } else false
            }
            TargetKind.PROVIDER_REMOTE ->
                descriptor is SourceDescriptor.ControlSession && descriptor.handle.isNotBlank()
            TargetKind.EXTERNAL_APP -> descriptor is SourceDescriptor.Handoff &&
                runCatching { URI(descriptor.uri).isAbsolute }.getOrDefault(false)
        }
        return if (valid) null else ResolutionFailure.INVALID_DESCRIPTOR
    }

    private suspend fun unavailable(
        reason: ResolutionFailure, request: ResolutionRequest, alternatives: List<TrackVariant>,
    ): SourceResolution.Unavailable {
        currentCoroutineContext().ensureActive()
        val failure = contextFailure(request.context) ?: reason
        val candidates = if (failure == ResolutionFailure.STALE_ROUTE || failure == ResolutionFailure.STALE_TRANSITION) {
            emptyList()
        } else alternatives.asSequence().filter {
            it.trackId == request.variant.trackId && it.variantId != request.variant.variantId &&
                it.source.availability == Availability.PLAYABLE
        }.map { it.variantId }.distinct().toList()
        return SourceResolution.Unavailable(failure, candidates)
    }

    private fun modeFor(source: SourceKind, target: TargetKind): ExecutionMode? = when (target) {
        TargetKind.DEVICE -> if (source == SourceKind.PROVIDER) ExecutionMode.PARTNER_DIRECT
            else ExecutionMode.LOCAL_DIRECT
        TargetKind.RENDERER -> if (source == SourceKind.PROVIDER) null else ExecutionMode.NETWORK_REMOTE
        TargetKind.PROVIDER_REMOTE -> if (source == SourceKind.PROVIDER) ExecutionMode.PARTNER_REMOTE else null
        TargetKind.EXTERNAL_APP -> if (source == SourceKind.PROVIDER) ExecutionMode.EXTERNAL_HANDOFF else null
    }
}

internal fun isContentUri(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("content", ignoreCase = true) && !uri.rawAuthority.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null
}.getOrDefault(false)

internal fun availabilityFailure(availability: Availability): ResolutionFailure? = when (availability) {
    Availability.PLAYABLE -> null
    Availability.AUTH_REQUIRED -> ResolutionFailure.AUTH_REQUIRED
    Availability.EXPIRED -> ResolutionFailure.EXPIRED
    Availability.MISSING -> ResolutionFailure.SOURCE_MISSING
    Availability.UNSUPPORTED -> ResolutionFailure.PLAYBACK_UNSUPPORTED
    Availability.POLICY_BLOCKED -> ResolutionFailure.POLICY_BLOCKED
    Availability.UNKNOWN -> ResolutionFailure.SOURCE_UNAVAILABLE
}

private fun isRendererUri(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null
}.getOrDefault(false)
