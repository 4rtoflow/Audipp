package com.audipp.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionMode {
    LOCAL_DIRECT, PARTNER_DIRECT, PARTNER_REMOTE, EXTERNAL_HANDOFF, NETWORK_REMOTE
}

@Serializable
data class PlaybackCapabilities(
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canSeek: Boolean = false,
    val canSkip: Boolean = false,
    val canReadPosition: Boolean = false,
    val canAccessPcm: Boolean = false,
    val canControlVolume: Boolean = false,
    val gaplessWithNext: Boolean = false,
)

@Serializable
enum class PermissionState { ALLOWED, DENIED, UNREVIEWED }

@Serializable
enum class PcmOperation { PROCESSING, ANALYSIS }

@Serializable
data class PcmPermissions(
    val processing: PermissionState = PermissionState.UNREVIEWED,
    val analysis: PermissionState = PermissionState.UNREVIEWED,
) {
    fun allows(operation: PcmOperation): Boolean = when (operation) {
        PcmOperation.PROCESSING -> processing
        PcmOperation.ANALYSIS -> analysis
    } == PermissionState.ALLOWED
}

enum class PlanRejection {
    STALE_ROUTE, STALE_TRANSITION, SOURCE_UNAVAILABLE, PLAYBACK_UNSUPPORTED,
    PCM_UNAVAILABLE, PCM_POLICY_BLOCKED
}

@Serializable
data class ExecutionPlan(
    val variantId: VariantId,
    val source: SourceRef,
    val targetId: String,
    val mode: ExecutionMode,
    val capabilities: PlaybackCapabilities,
    val routeRevision: Long,
    val transitionId: TransitionId,
) {
    init {
        require(targetId.isNotBlank())
        require(routeRevision >= 0)
    }

    // 許諾はPlan作成後にも失効するため、保存済み能力から許可を推定しない。
    fun rejectionFor(
        currentRouteRevision: Long,
        currentTransitionId: TransitionId,
        pcmOperation: PcmOperation? = null,
        currentPcmPermissions: PcmPermissions = PcmPermissions(),
    ): PlanRejection? = when {
        routeRevision != currentRouteRevision -> PlanRejection.STALE_ROUTE
        transitionId != currentTransitionId -> PlanRejection.STALE_TRANSITION
        source.availability != Availability.PLAYABLE -> PlanRejection.SOURCE_UNAVAILABLE
        !capabilities.canPlay -> PlanRejection.PLAYBACK_UNSUPPORTED
        pcmOperation != null && !capabilities.canAccessPcm -> PlanRejection.PCM_UNAVAILABLE
        pcmOperation != null && !currentPcmPermissions.allows(pcmOperation) ->
            PlanRejection.PCM_POLICY_BLOCKED
        else -> null
    }
}

@Serializable
enum class PlaybackPhase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ADVANCING, ERROR }

@Serializable
enum class ErrorDomain {
    SOURCE_IO, FORMAT_UNSUPPORTED, PERMISSION_DENIED, PLAYBACK_ENGINE,
    AUDIO_ROUTE, DSP, PROVIDER_AUTH, PROVIDER_POLICY, PROVIDER_RATE_LIMIT,
    NETWORK, DATABASE, SYNC, UNKNOWN
}

@Serializable
enum class UserAction {
    RESELECT_SOURCE, REQUEST_PERMISSION, AUTHORIZE_PROVIDER, RETRY,
    SELECT_OUTPUT, STOP_EXTERNAL_PLAYBACK, REVIEW_POLICY, EXPORT_DATA
}

@Serializable
data class PlaybackError(
    val domain: ErrorDomain,
    val code: String,
    val retryable: Boolean,
    val userAction: UserAction?,
    val diagnosticId: String,
) {
    init { require(code.isNotBlank() && diagnosticId.isNotBlank()) }
}

@Serializable
data class PlaybackSnapshot(
    val revision: Long,
    val phase: PlaybackPhase,
    val currentEntryId: QueueEntryId? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val effectiveSource: SourceRef? = null,
    val executionPlan: ExecutionPlan? = null,
    val error: PlaybackError? = null,
) {
    init {
        require(revision >= 0)
        require(positionMs == null || positionMs >= 0)
        require(durationMs == null || durationMs >= 0)
        require(positionMs == null || durationMs == null || positionMs <= durationMs)
        require(executionPlan == null || effectiveSource == executionPlan.source)
    }
}
