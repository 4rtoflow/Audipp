package com.audipp.audio.usb

import com.audipp.domain.model.Measured
import kotlinx.serialization.Serializable

@Serializable
enum class UsbPermission { NOT_REQUESTED, REQUESTING, GRANTED, DENIED, CANCELLED, TIMED_OUT }

@Serializable
enum class UsbInspectionState { NOT_READ, READING, READ, MALFORMED, PERMISSION_REQUIRED, OPEN_FAILED, FAILED }

@Serializable
enum class UsbDiscoveryState { STARTING, ACTIVE, UNAVAILABLE, FAILED, STOPPED }

@Serializable
enum class UsbGainSetting { MANUAL }

@Serializable
data class UsbInterfaceRecord(
    val configuration: Int,
    val number: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val hasIsochronousAudioOutput: Boolean,
) {
    val isAudioStreaming: Boolean get() = interfaceClass == 1 && subclass == 2
}

@Serializable
enum class UsbAudioEncoding { PCM, PCM8, IEEE_FLOAT, ALAW, MULAW }

@Serializable
sealed interface UsbSampleRates {
    @Serializable
    data class Discrete(val hz: List<Int>) : UsbSampleRates

    @Serializable
    data class Continuous(val minimumHz: Int, val maximumHz: Int) : UsbSampleRates
}

@Serializable
data class UsbAudioFormat(
    val configuration: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val audioClassVersion: Int,
    val encodings: List<UsbAudioEncoding>?,
    val subslotBytes: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val sampleRates: UsbSampleRates?,
)

@Serializable
enum class UsbDescriptorStatus { PARSED, PARTIAL, MALFORMED }

@Serializable
data class UsbAudioDescriptors(
    val status: UsbDescriptorStatus,
    val outputFormats: List<UsbAudioFormat>,
)

@Serializable
data class UsbDeviceRecord(
    val connectionId: String,
    val vendorId: Measured<Int>,
    val productId: Measured<Int>,
    val manufacturerName: Measured<String>,
    val productName: Measured<String>,
    val interfaces: Measured<List<UsbInterfaceRecord>>,
    val permission: UsbPermission,
    val inspection: UsbInspectionState,
    val audioDescriptors: Measured<UsbAudioDescriptors>,
    val hardwareGainDb: Measured<Double>,
    val gainSetting: UsbGainSetting = UsbGainSetting.MANUAL,
) {
    val isAudioOutputCandidate: Boolean
        get() = interfaces.value?.any { it.isAudioStreaming && it.hasIsochronousAudioOutput } == true
}

@Serializable
data class UsbDevicesSnapshot(
    val revision: Long = 0,
    val discovery: UsbDiscoveryState = UsbDiscoveryState.STARTING,
    val devices: List<UsbDeviceRecord> = emptyList(),
)
