package com.audipp.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class EvidenceLevel {
    SOURCE, REQUESTED, OS_REPORTED, APP_OBSERVED, EXTERNAL_VERIFIED, UNKNOWN
}

@Serializable
data class Measured<T>(
    val value: T?,
    val level: EvidenceLevel,
    val sourceName: String,
    val recordedAtEpochMs: Long,
    val testEvidenceId: String? = null,
) {
    init {
        require((level == EvidenceLevel.UNKNOWN) == (value == null))
        require(sourceName.isNotBlank())
        require(recordedAtEpochMs >= 0)
        require(testEvidenceId == null || testEvidenceId.isNotBlank())
        require(level != EvidenceLevel.EXTERNAL_VERIFIED || testEvidenceId != null)
    }
}

@Serializable
enum class BitPerfectState { VERIFIED, NOT_VERIFIED, NOT_APPLICABLE, FAILED }

@Serializable
data class BitPerfectVerification(
    val result: Measured<Boolean>,
    val deviceName: String,
    val routeId: String,
    val routeRevision: Long,
    val format: AudioFormat,
    val settingsDescription: String,
) {
    init {
        require(result.level == EvidenceLevel.EXTERNAL_VERIFIED)
        require(deviceName.isNotBlank() && routeId.isNotBlank())
        require(routeRevision >= 0)
        require(format.sampleRateHz != null && format.bitDepth != null && format.channelCount != null)
        require(settingsDescription.isNotBlank())
    }
}

@Serializable
data class AudioRouteSnapshot(
    val routeId: String?,
    val routeRevision: Long,
    val deviceName: Measured<String>,
    val sampleRateHz: Measured<Int>,
    val bitDepth: Measured<Int>,
    val channelCount: Measured<Int>,
    val dspEnabled: Boolean?,
    val bitPerfectState: BitPerfectState = BitPerfectState.NOT_VERIFIED,
    val verification: BitPerfectVerification? = null,
) {
    init {
        require(routeId == null || routeId.isNotBlank())
        require(routeRevision >= 0)
        require(deviceName.value == null || deviceName.value.isNotBlank())
        require(sampleRateHz.value == null || sampleRateHz.value > 0)
        require(bitDepth.value == null || bitDepth.value > 0)
        require(channelCount.value == null || channelCount.value > 0)
        val hasResult = bitPerfectState == BitPerfectState.VERIFIED || bitPerfectState == BitPerfectState.FAILED
        require(hasResult == (verification != null))
        if (verification != null) {
            require(verification.result.value == (bitPerfectState == BitPerfectState.VERIFIED))
            require(verification.routeId == routeId && verification.routeRevision == routeRevision)
            require(verification.deviceName == deviceName.value)
            require(verification.format.sampleRateHz == sampleRateHz.value)
            require(verification.format.bitDepth == bitDepth.value)
            require(verification.format.channelCount == channelCount.value)
            require(bitPerfectState != BitPerfectState.VERIFIED || dspEnabled == false)
        }
    }
}
