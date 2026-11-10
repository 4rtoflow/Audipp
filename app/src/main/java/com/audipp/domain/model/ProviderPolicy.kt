package com.audipp.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderUse {
    SEARCH, MIXED_DISPLAY, MIXED_QUEUE, PLAYBACK_CONTROL, DIRECT_PLAYBACK,
    HISTORY_STORAGE, STATISTICS, RECOMMENDATION, SCROBBLE, SYNC_EXPORT,
    PCM_PROCESSING, PCM_ANALYSIS,
}

@Serializable
data class ProviderScope(
    val providerKey: String,
    val accountId: String,
    val appId: String,
    val region: String,
    val contractId: String,
) {
    init {
        require(listOf(providerKey, accountId, appId, region, contractId).all { it.isNotBlank() })
    }

    override fun toString(): String = "ProviderScope(redacted)"
}

// References identify evidence in private storage; raw contracts, tokens and URLs do not belong here.
@Serializable
data class ProviderPermissionEvidence(
    val evidenceRef: String,
    val conditionsRef: String,
    val reviewedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(evidenceRef.isNotBlank() && conditionsRef.isNotBlank())
        require(reviewedAtEpochMs >= 0 && expiresAtEpochMs > reviewedAtEpochMs)
    }

    override fun toString(): String = "ProviderPermissionEvidence(redacted)"
}

@Serializable
data class UsePermission(
    val state: PermissionState = PermissionState.UNREVIEWED,
    val evidence: ProviderPermissionEvidence? = null,
) {
    init { require(state != PermissionState.ALLOWED || evidence != null) }

    fun currentState(nowEpochMs: Long): PermissionState = when {
        state != PermissionState.ALLOWED -> state
        evidence == null || nowEpochMs < evidence.reviewedAtEpochMs ||
            nowEpochMs >= evidence.expiresAtEpochMs -> PermissionState.UNREVIEWED
        else -> PermissionState.ALLOWED
    }

    override fun toString(): String = "UsePermission(state=$state, evidence=redacted)"
}

@Serializable
data class ProviderPolicy(
    val scope: ProviderScope,
    val permissions: Map<ProviderUse, UsePermission> = emptyMap(),
) {
    override fun toString(): String = "ProviderPolicy(redacted)"
}

@Serializable
data class ProviderPolicySnapshot(
    val revision: Long = 0,
    val policies: List<ProviderPolicy> = emptyList(),
) {
    init {
        require(revision >= 0)
        require(policies.map { it.scope }.distinct().size == policies.size)
    }

    fun permission(scope: ProviderScope, use: ProviderUse): UsePermission =
        policies.singleOrNull { it.scope == scope }?.permissions?.get(use) ?: UsePermission()

    override fun toString(): String = "ProviderPolicySnapshot(revision=$revision, policies=redacted)"
}

// These are technical abilities, supplied live by an adapter, never a license grant.
class ProviderCapabilities(supportedUses: Set<ProviderUse> = emptySet()) {
    val supportedUses: Set<ProviderUse> = supportedUses.toSet()
}
