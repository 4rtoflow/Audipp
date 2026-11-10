package com.audipp.audio.usb

import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AttachedUsbDevice(
    val key: String,
    val vendorId: Int,
    val productId: Int,
    val interfaces: List<UsbInterfaceRecord>,
)

internal data class UsbInspection(val manufacturer: String?, val product: String?, val descriptors: ByteArray?)

internal sealed interface UsbHostEvent {
    data object Changed : UsbHostEvent
    data class Detached(val key: String) : UsbHostEvent
    data class Permission(val key: String, val token: String, val granted: Boolean) : UsbHostEvent
}

internal interface UsbHost : AutoCloseable {
    fun start(listener: (UsbHostEvent) -> Unit): Boolean
    fun devices(): List<AttachedUsbDevice>
    fun hasPermission(key: String): Boolean
    fun requestPermission(key: String, token: String)
    fun cancelPermission(token: String)
    fun inspect(key: String): UsbInspection?
}

class UsbDeviceMonitor internal constructor(
    private val host: UsbHost,
    parentScope: CoroutineScope,
    worker: CoroutineDispatcher = Dispatchers.IO,
    private val permissionTimeoutMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + worker)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(UsbDevicesSnapshot())
    val snapshot: StateFlow<UsbDevicesSnapshot> = mutableSnapshot.asStateFlow()
    private val entries = linkedMapOf<String, Entry>()

    init {
        require(permissionTimeoutMs > 0)
        scope.launch {
            try {
                val available = try {
                    host.start { commands.trySend(Command.Host(it)) }
                } catch (_: RuntimeException) {
                    host.close()
                    null
                }
                when (available) {
                    true -> refreshDevices()
                    false -> publish(UsbDiscoveryState.UNAVAILABLE)
                    null -> publish(UsbDiscoveryState.FAILED)
                }
                for (command in commands) {
                    if (available != true) continue
                    try {
                        handle(command)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: RuntimeException) {
                        entries.values.forEach { cancelOperation(it) }
                        entries.clear()
                        publish(UsbDiscoveryState.FAILED)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                publish(UsbDiscoveryState.FAILED)
            } finally {
                entries.values.forEach { cancelOperation(it) }
                try { host.close() } finally {
                    entries.clear()
                    publish(UsbDiscoveryState.STOPPED)
                    commands.close()
                    scope.cancel()
                }
            }
        }
        lifetime.invokeOnCompletion {
            val current = mutableSnapshot.value
            if (current.discovery != UsbDiscoveryState.STOPPED) {
                mutableSnapshot.value = UsbDevicesSnapshot(current.revision + 1, UsbDiscoveryState.STOPPED)
            }
        }
    }

    fun refresh() { commands.trySend(Command.Refresh) }

    fun requestPermission(connectionId: String) { commands.trySend(Command.Request(connectionId)) }

    fun cancelPermission(connectionId: String) { commands.trySend(Command.Cancel(connectionId)) }

    fun retryInspection(connectionId: String) { commands.trySend(Command.Retry(connectionId)) }

    override fun close() { scope.cancel() }

    suspend fun awaitClosed() { lifetime.join() }

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Refresh -> refreshDevices()
            is Command.Host -> when (val event = command.event) {
                UsbHostEvent.Changed -> refreshDevices()
                is UsbHostEvent.Detached -> {
                    entries.remove(event.key)?.let { cancelOperation(it) }
                    publish()
                }
                is UsbHostEvent.Permission -> {
                    val entry = entries[event.key] ?: return
                    if (entry.token != event.token) return
                    cancelOperation(entry)
                    if (event.granted && host.hasPermission(entry.device.key)) {
                        inspect(entry)
                    } else entry.record = entry.record.copy(permission = UsbPermission.DENIED)
                    publish()
                }
            }
            is Command.Request -> {
                val entry = find(command.id) ?: return
                if (entry.record.permission == UsbPermission.REQUESTING || entry.record.inspection == UsbInspectionState.READING) return
                if (host.hasPermission(entry.device.key)) {
                    inspect(entry)
                } else {
                    val token = UUID.randomUUID().toString()
                    entry.token = token
                    entry.record = unread(entry.record).copy(permission = UsbPermission.REQUESTING)
                    try {
                        host.requestPermission(entry.device.key, token)
                        entry.operation = scope.launch {
                            delay(permissionTimeoutMs)
                            commands.send(Command.Timeout(command.id, token))
                        }
                    } catch (_: RuntimeException) {
                        cancelOperation(entry)
                        entry.record = entry.record.copy(permission = UsbPermission.DENIED)
                    }
                }
                publish()
            }
            is Command.Cancel -> {
                val entry = find(command.id) ?: return
                if (entry.record.permission != UsbPermission.REQUESTING) return
                cancelOperation(entry)
                entry.record = entry.record.copy(permission = UsbPermission.CANCELLED)
                publish()
            }
            is Command.Timeout -> {
                val entry = find(command.id) ?: return
                if (entry.token != command.token) return
                cancelOperation(entry)
                entry.record = entry.record.copy(permission = UsbPermission.TIMED_OUT)
                publish()
            }
            is Command.Retry -> {
                val entry = find(command.id) ?: return
                if (entry.record.permission == UsbPermission.REQUESTING) return
                if (host.hasPermission(entry.device.key)) inspect(entry) else {
                    cancelOperation(entry)
                    entry.record = unread(entry.record).copy(permission = UsbPermission.NOT_REQUESTED)
                }
                publish()
            }
            is Command.Inspected -> {
                val entry = find(command.id) ?: return
                if (entry.token != command.token) return
                entry.token = null
                entry.operation = null
                if (command.state == UsbInspectionState.PERMISSION_REQUIRED || !host.hasPermission(entry.device.key)) {
                    entry.record = unread(entry.record).copy(permission = UsbPermission.NOT_REQUESTED,
                        inspection = UsbInspectionState.PERMISSION_REQUIRED)
                } else {
                    val now = clock()
                    entry.record = entry.record.copy(
                        manufacturerName = measured(command.result?.manufacturer?.takeIf { it.isNotBlank() }, "UsbDevice.manufacturerName", now),
                        productName = measured(command.result?.product?.takeIf { it.isNotBlank() }, "UsbDevice.productName", now),
                        audioDescriptors = measured(command.descriptors, "UsbDeviceConnection.rawDescriptors", now),
                        inspection = command.state,
                    )
                }
                publish()
            }
        }
    }

    private fun refreshDevices() {
        try {
            val devices = host.devices()
            val keys = devices.map { it.key }.toSet()
            entries.keys.filter { it !in keys }.forEach { key -> entries.remove(key)?.let { cancelOperation(it) } }
            for (device in devices) {
                val entry = entries.getOrPut(device.key) { Entry(device, initialRecord(device)) }
                if (host.hasPermission(device.key)) {
                    if (entry.record.permission == UsbPermission.NOT_REQUESTED) inspect(entry)
                } else if (entry.record.permission == UsbPermission.GRANTED) {
                    cancelOperation(entry)
                    entry.record = unread(entry.record).copy(permission = UsbPermission.NOT_REQUESTED)
                }
            }
            publish(UsbDiscoveryState.ACTIVE)
        } catch (_: RuntimeException) {
            entries.values.forEach { cancelOperation(it) }
            entries.clear()
            publish(UsbDiscoveryState.FAILED)
        }
    }

    private fun inspect(entry: Entry) {
        cancelOperation(entry)
        val token = UUID.randomUUID().toString()
        entry.token = token
        entry.record = unread(entry.record).copy(permission = UsbPermission.GRANTED, inspection = UsbInspectionState.READING)
        entry.operation = scope.launch {
            var result: UsbInspection? = null
            var descriptors: UsbAudioDescriptors? = null
            val state = try {
                result = host.inspect(entry.device.key)
                ensureActive()
                descriptors = result?.descriptors?.let { UsbAudioDescriptorParser.parse(it) }
                when {
                    result == null -> UsbInspectionState.OPEN_FAILED
                    descriptors?.status == UsbDescriptorStatus.MALFORMED -> UsbInspectionState.MALFORMED
                    else -> UsbInspectionState.READ
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                UsbInspectionState.PERMISSION_REQUIRED
            } catch (_: RuntimeException) {
                UsbInspectionState.FAILED
            }
            commands.send(Command.Inspected(entry.record.connectionId, token, result?.copy(descriptors = null), descriptors, state))
        }
    }

    private fun initialRecord(device: AttachedUsbDevice): UsbDeviceRecord {
        val now = clock()
        return UsbDeviceRecord(UUID.randomUUID().toString(),
            measured(device.vendorId, "UsbDevice.vendorId", now),
            measured(device.productId, "UsbDevice.productId", now),
            measured(null, "UsbDevice.manufacturerName", now),
            measured(null, "UsbDevice.productName", now),
            measured(device.interfaces, "UsbDevice.interfaces", now),
            UsbPermission.NOT_REQUESTED, UsbInspectionState.NOT_READ,
            measured(null, "UsbDeviceConnection.rawDescriptors", now),
            measured(null, "USB hardware gain unavailable; manual setting", now))
    }

    private fun unread(record: UsbDeviceRecord) = record.copy(
        manufacturerName = measured(null, "UsbDevice.manufacturerName", clock()),
        productName = measured(null, "UsbDevice.productName", clock()),
        audioDescriptors = measured(null, "UsbDeviceConnection.rawDescriptors", clock()),
        inspection = UsbInspectionState.NOT_READ,
    )

    private fun cancelOperation(entry: Entry) {
        entry.operation?.cancel()
        entry.operation = null
        entry.token?.let { host.cancelPermission(it) }
        entry.token = null
    }

    private fun find(id: String) = entries.values.find { it.record.connectionId == id }

    private fun publish(discovery: UsbDiscoveryState = mutableSnapshot.value.discovery) {
        mutableSnapshot.value = UsbDevicesSnapshot(mutableSnapshot.value.revision + 1, discovery, entries.values.map { it.record })
    }

    private fun <T> measured(value: T?, source: String, now: Long) = Measured(value,
        if (value == null) EvidenceLevel.UNKNOWN else EvidenceLevel.OS_REPORTED, source, now)

    private class Entry(val device: AttachedUsbDevice, var record: UsbDeviceRecord) {
        var token: String? = null
        var operation: Job? = null
    }

    private sealed interface Command {
        data object Refresh : Command
        data class Host(val event: UsbHostEvent) : Command
        data class Request(val id: String) : Command
        data class Cancel(val id: String) : Command
        data class Retry(val id: String) : Command
        data class Timeout(val id: String, val token: String) : Command
        data class Inspected(val id: String, val token: String, val result: UsbInspection?,
            val descriptors: UsbAudioDescriptors?, val state: UsbInspectionState) : Command
    }
}
