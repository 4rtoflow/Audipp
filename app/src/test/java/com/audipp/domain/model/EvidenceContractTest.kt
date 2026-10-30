package com.audipp.domain.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceContractTest {
    private val time = 1_793_318_400_000L
    private val unknown = Measured<Int>(null, EvidenceLevel.UNKNOWN, "not measured", time)
    private val osRate = Measured(48_000, EvidenceLevel.OS_REPORTED, "AudioManager", time)
    private val route = AudioRouteSnapshot("usb:fixture", 4,
        Measured("Fixture DAC", EvidenceLevel.OS_REPORTED, "AudioManager", time),
        osRate, unknown, unknown, dspEnabled = null)

    @Test
    fun allSixEvidenceLevelsPreserveTokensValuesProvenanceAndTimestamps() {
        val tokens = listOf("SOURCE", "REQUESTED", "OS_REPORTED", "APP_OBSERVED",
            "EXTERNAL_VERIFIED", "UNKNOWN")
        assertEquals(tokens, EvidenceLevel.entries.map { it.name })
        EvidenceLevel.entries.forEach { level ->
            val measured = Measured(if (level == EvidenceLevel.UNKNOWN) null else 44_100,
                level, "fixture", time,
                if (level == EvidenceLevel.EXTERNAL_VERIFIED) "bench-1" else null)
            val encoded = ModelJson.encode(measured)
            assertTrue(encoded.contains("\"level\":\"${level.name}\""))
            assertEquals(measured, ModelJson.decode<Measured<Int>>(encoded))
        }
    }

    @Test
    fun source44100AndOs48000AndUnmeasuredExternalEvidenceCoexist() {
        val evidence = listOf(Measured(44_100, EvidenceLevel.SOURCE, "decoder", time),
            osRate, unknown)
        val restored = ModelJson.decode<List<Measured<Int>>>(ModelJson.encode(evidence))
        assertEquals(evidence, restored)
        assertEquals(44_100, restored[0].value)
        assertEquals(48_000, restored[1].value)
        assertNull(restored[2].value)
        assertEquals(EvidenceLevel.UNKNOWN, restored[2].level)
        val restoredRoute = ModelJson.decode<AudioRouteSnapshot>(ModelJson.encode(route))
        assertNull(restoredRoute.bitDepth.value)
        assertNull(restoredRoute.dspEnabled)
        assertEquals(BitPerfectState.NOT_VERIFIED, restoredRoute.bitPerfectState)
    }

    @Test
    fun matchingSampleRatesNeverImplyBitPerfect() {
        assertEquals(BitPerfectState.NOT_VERIFIED,
            route.copy(sampleRateHz = Measured(44_100, EvidenceLevel.OS_REPORTED,
                "AudioManager", time)).bitPerfectState)
        assertThrows(IllegalArgumentException::class.java) {
            route.copy(bitPerfectState = BitPerfectState.VERIFIED)
        }
    }

    @Test
    fun verificationRequiresExternalResultAndMatchingDeviceRouteFormatAndSettings() {
        val proof = BitPerfectVerification(Measured(true, EvidenceLevel.EXTERNAL_VERIFIED,
            "fixture bench", time, "bench-1"), "Fixture DAC", "usb:fixture", 4,
            AudioFormat("PCM", 48_000, 24, 2), "unity gain, DSP bypass")
        val verified = route.copy(bitDepth = Measured(24, EvidenceLevel.APP_OBSERVED, "sink", time),
            channelCount = Measured(2, EvidenceLevel.APP_OBSERVED, "sink", time),
            dspEnabled = false, bitPerfectState = BitPerfectState.VERIFIED, verification = proof)
        assertEquals(verified, ModelJson.decode<AudioRouteSnapshot>(ModelJson.encode(verified)))
        listOf<() -> Any>(
            { proof.copy(result = Measured(true, EvidenceLevel.OS_REPORTED, "OS", time)) },
            { proof.copy(settingsDescription = "") },
            { proof.copy(format = AudioFormat(sampleRateHz = 48_000)) },
            { verified.copy(routeRevision = 5) }, { verified.copy(routeId = "other") },
            { verified.copy(sampleRateHz = osRate.copy(value = 96_000)) },
            { verified.copy(dspEnabled = true) }, { verified.copy(dspEnabled = null) },
        ).forEach { construct -> assertThrows(IllegalArgumentException::class.java) { construct() } }
    }

    @Test
    fun unknownCannotCarryFabricatedValuesAndExternalClaimsNeedEvidenceIds() {
        listOf<() -> Any>(
            { unknown.copy(value = 0) }, { unknown.copy(value = 48_000) },
            { unknown.copy(level = EvidenceLevel.OS_REPORTED) },
            { unknown.copy(recordedAtEpochMs = -1) }, { unknown.copy(sourceName = "") },
            { osRate.copy(level = EvidenceLevel.EXTERNAL_VERIFIED) },
            { route.copy(sampleRateHz = osRate.copy(value = 0)) },
        ).forEach { construct -> assertThrows(IllegalArgumentException::class.java) { construct() } }
    }

    @Test
    fun versionedJsonKeepsExplicitNullAndRejectsMissingOrUnsupportedVersions() {
        val encoded = ModelJson.encode(unknown)
        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertTrue(encoded.contains("\"value\":null"))
        assertTrue(encoded.contains("\"testEvidenceId\":null"))
        assertThrows(IllegalArgumentException::class.java) {
            ModelJson.decode<Measured<Int>>(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
        assertThrows(SerializationException::class.java) {
            ModelJson.decode<Measured<Int>>(encoded.replace(",\"schemaVersion\":1", ""))
        }
    }

    @Test
    fun unknownEnumMissingValueAndInvalidNumbersFailClosedWhenDecoding() {
        val encoded = ModelJson.encode(unknown)
        assertThrows(SerializationException::class.java) {
            ModelJson.decode<Measured<Int>>(encoded.replace("UNKNOWN", "FUTURE_VERIFIED"))
        }
        assertThrows(SerializationException::class.java) {
            ModelJson.decode<Measured<Int>>(encoded.replace("\"value\":null,", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelJson.decode<Measured<Int>>(encoded.replace("\"value\":null", "\"value\":0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelJson.decode<TrackId>("""{"schemaVersion":1,"value":""}""")
        }
    }
}
