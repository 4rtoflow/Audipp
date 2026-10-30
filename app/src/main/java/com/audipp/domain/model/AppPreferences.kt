package com.audipp.domain.model

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class SharingScope { PRIVATE, FRIENDS }

enum class ConsentPurpose {
    METADATA_LOOKUP,
    ACOUSTIC_FINGERPRINT,
    ARTWORK_LOOKUP,
    CLOUD_SYNC,
    SCROBBLING,
    DEVICE_PROFILE_SHARING,
}

data class ConsentDecision(val version: Int, val granted: Boolean) {
    init {
        require(version > 0)
    }

    fun permits(requiredVersion: Int): Boolean {
        require(requiredVersion > 0)
        return granted && version == requiredVersion
    }
}

data class VisualizerPreferences(val enabled: Boolean = false, val framesPerSecond: Int = 30) {
    init {
        require(framesPerSecond in setOf(15, 30, 60))
    }
}

data class AppPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val visualizer: VisualizerPreferences = VisualizerPreferences(),
    val sharingScope: SharingScope = SharingScope.PRIVATE,
    val consents: Map<ConsentPurpose, ConsentDecision> = emptyMap(),
) {
    fun permitsExternalTransmission(purpose: ConsentPurpose, requiredVersion: Int): Boolean {
        require(requiredVersion > 0)
        return consents[purpose]?.permits(requiredVersion) == true
    }
}
