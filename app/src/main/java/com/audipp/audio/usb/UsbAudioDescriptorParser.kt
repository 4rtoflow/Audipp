package com.audipp.audio.usb

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object UsbAudioDescriptorParser {
    const val MAX_BYTES = 1_048_576

    suspend fun parse(bytes: ByteArray): UsbAudioDescriptors {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return malformed()
        val formats = mutableListOf<UsbAudioFormat>()
        var partial = false
        var configuration = -1
        var hasConfiguration = false
        var configurationEnd = 0
        var stream: Stream? = null

        fun finishStream() {
            val current = stream ?: return
            if (current.output) {
                val format = current.format(configuration)
                if (format == null) partial = true else {
                    formats += format
                    if (format.encodings == null || format.sampleRates == null) partial = true
                }
            }
            stream = null
        }

        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            if (bytes.size - offset < 2) return malformed()
            val length = bytes.u8(offset)
            val type = bytes.u8(offset + 1)
            if (length < 2 || length > bytes.size - offset) return malformed()
            if (offset < configurationEnd && length > configurationEnd - offset) return malformed()
            if (offset == configurationEnd) {
                finishStream()
                configuration = -1
            }
            val descriptor = bytes.copyOfRange(offset, offset + length)
            when (type) {
                1 -> if (length < 18 || configuration >= 0) return malformed()
                2 -> {
                    if (length < 9 || configuration >= 0) return malformed()
                    val totalLength = descriptor.u16(2)
                    if (totalLength < length || totalLength > bytes.size - offset) return malformed()
                    configuration = descriptor.u8(5)
                    hasConfiguration = true
                    configurationEnd = offset + totalLength
                }
                4 -> {
                    finishStream()
                    if (length < 9 || configuration < 0) return malformed()
                    if (descriptor.u8(5) == 1 && descriptor.u8(6) == 2) {
                        stream = Stream(descriptor.u8(2), descriptor.u8(3), descriptor.u8(7))
                    }
                }
                5 -> {
                    if (length < 7 || configuration < 0) return malformed()
                    // Feedback endpoints do not carry playback samples.
                    if (descriptor.u8(2) and 0x80 == 0 && descriptor.u8(3) and 3 == 1 &&
                        descriptor.u8(3) and 0x30 != 0x10 && descriptor.u16(4) and 0x7ff != 0
                    ) stream?.output = true
                }
                0x24 -> if (stream?.read(descriptor) == false) return malformed()
            }
            offset += length
        }
        finishStream()
        if (!hasConfiguration) return malformed()
        return UsbAudioDescriptors(if (partial) UsbDescriptorStatus.PARTIAL else UsbDescriptorStatus.PARSED, formats)
    }

    private fun malformed() = UsbAudioDescriptors(UsbDescriptorStatus.MALFORMED, emptyList())

    private class Stream(val number: Int, val alternate: Int, val protocol: Int) {
        var output = false
        var general: ByteArray? = null
        var type: ByteArray? = null

        fun read(descriptor: ByteArray): Boolean {
            if (descriptor.size < 3) return false
            when (descriptor.u8(2)) {
                1 -> {
                    if (general != null) return false
                    if (protocol == 0 && descriptor.size < 7 || protocol == 0x20 && descriptor.size < 16) return false
                    general = descriptor
                }
                2 -> {
                    if (type != null || descriptor.size < 4) return false
                    type = descriptor
                    if (descriptor.u8(3) == 1) {
                        if (protocol == 0) {
                            if (descriptor.size < 8) return false
                            val count = descriptor.u8(7)
                            if (descriptor.size != 8 + (if (count == 0) 6 else count * 3)) return false
                            if (descriptor.u8(4) == 0 || !validBits(descriptor.u8(5), descriptor.u8(6))) return false
                            if (count == 0) {
                                if (descriptor.u24(8) == 0 || descriptor.u24(8) > descriptor.u24(11)) return false
                            } else if ((0 until count).any { descriptor.u24(8 + it * 3) == 0 }) return false
                        } else if (protocol == 0x20) {
                            if (descriptor.size != 6 || !validBits(descriptor.u8(4), descriptor.u8(5))) return false
                        }
                    }
                }
            }
            return true
        }

        fun format(configuration: Int): UsbAudioFormat? {
            val header = general ?: return null
            val format = type ?: return null
            if (format.u8(3) != 1) return null
            return when (protocol) {
                0 -> {
                    val encoding = UsbAudioEncoding.entries.getOrNull(header.u16(5) - 1)
                    val rates = if (format.u8(7) == 0) {
                        UsbSampleRates.Continuous(format.u24(8), format.u24(11))
                    } else UsbSampleRates.Discrete((0 until format.u8(7)).map { format.u24(8 + it * 3) }.distinct())
                    UsbAudioFormat(configuration, number, alternate, 0x100, encoding?.let { listOf(it) },
                        format.u8(5), format.u8(6), format.u8(4), rates)
                }
                0x20 -> {
                    if (header.u8(5) != 1 || header.u8(10) == 0) return null
                    val mask = header.u32(6)
                    val encodings = if (mask == 0L || mask and 0xffffffe0L != 0L) null else {
                        UsbAudioEncoding.entries.filterIndexed { index, _ -> mask and (1L shl index) != 0L }
                    }
                    // UAC2 rates belong to clock controls, not the Type I descriptor.
                    UsbAudioFormat(configuration, number, alternate, 0x200, encodings,
                        format.u8(4), format.u8(5), header.u8(10), null)
                }
                else -> null
            }
        }

        private fun validBits(bytes: Int, bits: Int) = bytes in 1..4 && bits in 1..bytes * 8
    }
}

private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 0xff
private fun ByteArray.u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
private fun ByteArray.u24(offset: Int) = u16(offset) or (u8(offset + 2) shl 16)
private fun ByteArray.u32(offset: Int) = u24(offset).toLong() or (u8(offset + 3).toLong() shl 24)
