package com.audipp.audio.usb

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorParserTest {
    @Test
    fun uac1PreservesPublishedFormatAndDiscreteRates() = runBlocking {
        val result = UsbAudioDescriptorParser.parse(uac1)
        assertEquals(UsbDescriptorStatus.PARSED, result.status)
        assertEquals(UsbAudioFormat(1, 1, 1, 0x100, listOf(UsbAudioEncoding.PCM), 3, 24, 2,
            UsbSampleRates.Discrete(listOf(44100, 48000))), result.outputFormats.single())
    }

    @Test
    fun continuousRatesAreARangeRatherThanInventedDiscreteValues() = runBlocking {
        val type = descriptor(14, 0x24, 2, 1, 2, 2, 16, 0, 0x44, 0xac, 0, 0, 0x77, 1)
        val result = UsbAudioDescriptorParser.parse(usbConfiguration(streamingInterface(), uac1General, type, audioOut))
        assertEquals(UsbSampleRates.Continuous(44100, 96000), result.outputFormats.single().sampleRates)
    }

    @Test
    fun uac2PreservesBitDepthAndChannelsButDoesNotGuessClockRates() = runBlocking {
        val result = UsbAudioDescriptorParser.parse(uac2)
        assertEquals(UsbDescriptorStatus.PARTIAL, result.status)
        val format = result.outputFormats.single()
        assertEquals(0x200, format.audioClassVersion)
        assertEquals(32, format.bitDepth)
        assertEquals(2, format.channelCount)
        assertEquals(listOf(UsbAudioEncoding.PCM), format.encodings)
        assertNull(format.sampleRates)
    }

    @Test
    fun formatsNeverLeakAcrossAlternatesConfigurationsOrNonAudioInterfaces() = runBlocking {
        val hid = descriptor(9, 4, 3, 1, 1, 3, 0, 0, 0)
        val bytes = usbConfiguration(streamingInterface(), uac1General, uac1Type, audioIn,
            streamingInterface(alternate = 2), audioOut, hid, uac1General, uac1Type, audioOut) +
            usbConfiguration(streamingInterface(), uac1General, uac1Type, audioOut, id = 2)
        val result = UsbAudioDescriptorParser.parse(bytes)
        assertEquals(UsbDescriptorStatus.PARTIAL, result.status)
        assertEquals(listOf(2), result.outputFormats.map { it.configuration })
        val midi = usbConfiguration(streamingInterface(subclass = 3), uac1General, uac1Type, audioOut)
        assertTrue(UsbAudioDescriptorParser.parse(midi).outputFormats.isEmpty())
    }

    @Test
    fun captureFeedbackAndZeroBandwidthEndpointsDoNotAdvertisePlayback() = runBlocking {
        for (endpoint in listOf(audioIn, descriptor(7, 5, 1, 0x11, 3, 0, 1), descriptor(7, 5, 1, 1, 0, 0, 1))) {
            assertTrue(UsbAudioDescriptorParser.parse(usbConfiguration(streamingInterface(), uac1General,
                uac1Type, endpoint)).outputFormats.isEmpty())
        }
    }

    @Test
    fun unsupportedProtocolsAndEncodingsRemainUnknown() = runBlocking {
        val unknownProtocol = usbConfiguration(streamingInterface(0x30), audioOut)
        val result = UsbAudioDescriptorParser.parse(unknownProtocol)
        assertEquals(UsbDescriptorStatus.PARTIAL, result.status)
        assertTrue(result.outputFormats.isEmpty())
        val unknownEncoding = uac1General.copyOf().apply { this[5] = 0x7f }
        assertNull(UsbAudioDescriptorParser.parse(usbConfiguration(streamingInterface(), unknownEncoding,
            uac1Type, audioOut)).outputFormats.single().encodings)
    }

    @Test
    fun truncatedDescriptorsInvalidLengthsAndImpossibleFormatsMakeNoCapabilityClaims() = runBlocking {
        for (size in 0 until uac1.size) assertMalformed(uac1.copyOf(size))
        val invalid = listOf(
            byteArrayOf(0, 2), descriptor(1, 2), descriptor(9, 2, 1, 0, 1, 1, 0, 0, 0),
            usbConfiguration(streamingInterface(), descriptor(2, 0x24), audioOut),
            usbConfiguration(streamingInterface(), uac1General, uac1General, uac1Type, audioOut),
            usbConfiguration(streamingInterface(), uac1General, uac1Type.copyOf().apply { this[7] = 3 }, audioOut),
            usbConfiguration(streamingInterface(), uac1General, uac1Type.copyOf().apply { this[4] = 0 }, audioOut),
            usbConfiguration(streamingInterface(), uac1General, uac1Type.copyOf().apply { this[6] = 32 }, audioOut),
            usbConfiguration(streamingInterface(), uac1General, uac1Type.copyOf().apply { fill(0, 8, 11) }, audioOut),
            usbConfiguration(streamingInterface(0x20), uac2General, uac2Type.copyOf(5), audioOut),
        )
        invalid.forEach { assertMalformed(it) }
        assertMalformed(ByteArray(UsbAudioDescriptorParser.MAX_BYTES + 1))
    }

    @Test
    fun fixedSeedMalformedInputNeverCrashes() = runBlocking {
        val random = Random(32)
        repeat(1000) { UsbAudioDescriptorParser.parse(random.nextBytes(random.nextInt(512))) }
        repeat(1000) {
            val bytes = uac1.copyOf()
            bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
            UsbAudioDescriptorParser.parse(bytes)
        }
    }

    @Test
    fun cancelledWorkerStopsParsing() {
        val cancelled = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(cancelled) { UsbAudioDescriptorParser.parse(uac1) } }
        }
    }

    private suspend fun assertMalformed(bytes: ByteArray) {
        val result = UsbAudioDescriptorParser.parse(bytes)
        assertEquals(UsbDescriptorStatus.MALFORMED, result.status)
        assertTrue(result.outputFormats.isEmpty())
    }
}
