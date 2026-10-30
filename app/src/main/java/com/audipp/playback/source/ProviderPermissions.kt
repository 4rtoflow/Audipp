package com.audipp.playback.source

import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PcmOperation
import com.audipp.domain.model.PcmPermissions
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlanRejection
import com.audipp.domain.model.ProviderCapabilities
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TransitionId
import com.audipp.integrations.streaming.ProviderAccessGate

fun ProviderAccessGate.playbackPermissionChecker(
    scopeFor: (ResolutionRequest) -> ProviderScope?,
): PlaybackPermissionChecker = PlaybackPermissionChecker { request, use ->
    val scope = scopeFor(request)
    if (scope == null || scope.providerKey != request.variant.source.providerKey) {
        PermissionState.UNREVIEWED
    } else {
        permissionState(scope, when (use) {
            ResolutionUse.DIRECT_PLAYBACK -> ProviderUse.DIRECT_PLAYBACK
            ResolutionUse.PLAYBACK_CONTROL -> ProviderUse.PLAYBACK_CONTROL
        })
    }
}

suspend fun ProviderAccessGate.pcmRejectionFor(
    plan: ExecutionPlan,
    scope: ProviderScope,
    currentRouteRevision: Long,
    currentTransitionId: TransitionId,
    operation: PcmOperation,
    capabilities: ProviderCapabilities,
): PlanRejection? {
    if (plan.source.kind != SourceKind.PROVIDER || plan.source.providerKey != scope.providerKey ||
        plan.mode != ExecutionMode.PARTNER_DIRECT
    ) return PlanRejection.PCM_POLICY_BLOCKED
    val use = when (operation) {
        PcmOperation.PROCESSING -> ProviderUse.PCM_PROCESSING
        PcmOperation.ANALYSIS -> ProviderUse.PCM_ANALYSIS
    }
    val states = permissionStates(scope, setOf(ProviderUse.DIRECT_PLAYBACK, use))
    if (states[ProviderUse.DIRECT_PLAYBACK] != PermissionState.ALLOWED) {
        return PlanRejection.PCM_POLICY_BLOCKED
    }
    val permission = if (use in capabilities.supportedUses) states.getValue(use)
        else PermissionState.UNREVIEWED
    return plan.rejectionFor(
        currentRouteRevision, currentTransitionId, operation,
        PcmPermissions(processing = permission, analysis = permission),
    )
}
