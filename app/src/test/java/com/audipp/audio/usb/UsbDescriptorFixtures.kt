package com.audipp.audio.usb

internal fun descriptor(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

internal fun usbConfiguration(vararg descriptors: ByteArray, id: Int = 1): ByteArray {
    val body = descriptors.fold(byteArrayOf()) { result, bytes -> result + bytes }
    val length = body.size + 9
    return descriptor(9, 2, length and 0xff, length shr 8, 1, id, 0, 0x80, 50) + body
}

internal fun streamingInterface(protocol: Int = 0, alternate: Int = 1, subclass: Int = 2) =
    descriptor(9, 4, 1, alternate, 1, 1, subclass, protocol, 0)

internal val audioOut = descriptor(7, 5, 1, 1, 0xc0, 0, 1)
internal val audioIn = descriptor(7, 5, 0x81, 1, 0xc0, 0, 1)
internal val uac1General = descriptor(7, 0x24, 1, 1, 0, 1, 0)
internal val uac1Type = descriptor(14, 0x24, 2, 1, 2, 3, 24, 2, 0x44, 0xac, 0, 0x80, 0xbb, 0)
internal val uac1 = usbConfiguration(streamingInterface(), uac1General, uac1Type, audioOut)
internal val uac2General = descriptor(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)
internal val uac2Type = descriptor(6, 0x24, 2, 1, 4, 32)
internal val uac2 = usbConfiguration(streamingInterface(0x20), uac2General, uac2Type, audioOut)
