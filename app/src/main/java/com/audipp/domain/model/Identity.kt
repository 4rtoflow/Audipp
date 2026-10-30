package com.audipp.domain.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TrackId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
@JvmInline
value class VariantId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
@JvmInline
value class SourceId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
@JvmInline
value class QueueEntryId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
@JvmInline
value class TransitionId(val value: String) {
    init { require(value.isNotBlank()) }
}
