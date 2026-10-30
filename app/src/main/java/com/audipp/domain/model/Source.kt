package com.audipp.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind { LOCAL, PROVIDER, NETWORK }

@Serializable
enum class Availability {
    PLAYABLE, AUTH_REQUIRED, EXPIRED, MISSING, UNSUPPORTED, POLICY_BLOCKED, UNKNOWN
}

@Serializable
data class SourceRef(
    val sourceId: SourceId,
    val kind: SourceKind,
    val providerKey: String? = null,
    val providerItemId: String? = null,
    val localDocumentUri: String? = null,
    val availability: Availability = Availability.UNKNOWN,
) {
    init {
        require(providerKey == null || providerKey.isNotBlank())
        require(providerItemId == null || providerItemId.isNotBlank())
        require(localDocumentUri == null || localDocumentUri.isNotBlank())
        require((providerKey == null) == (providerItemId == null))
        require(kind != SourceKind.PROVIDER || providerKey != null)
        require(kind == SourceKind.PROVIDER || providerKey == null)
        require(kind == SourceKind.LOCAL || localDocumentUri == null)
    }
}

@Serializable
data class AudioFormat(
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
) {
    init {
        require(codec == null || codec.isNotBlank())
        require(sampleRateHz == null || sampleRateHz > 0)
        require(bitDepth == null || bitDepth > 0)
        require(channelCount == null || channelCount > 0)
    }
}

@Serializable
data class TrackVariant(
    val variantId: VariantId,
    val trackId: TrackId,
    val source: SourceRef,
    val durationMs: Long? = null,
    val format: AudioFormat = AudioFormat(),
) {
    init { require(durationMs == null || durationMs >= 0) }
}

@Serializable
data class QueueEntry(
    val entryId: QueueEntryId,
    val variantId: VariantId,
)
