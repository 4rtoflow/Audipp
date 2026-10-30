package com.audipp.audio.usb

import com.audipp.domain.model.AudioRouteSnapshot
import com.audipp.domain.model.BitPerfectState
import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import com.audipp.domain.model.ModelJson
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceMonitorTest {
    @Test
    fun discoveryDoesNotPromptAndUnavailableGainRequiresManualSetting() = runBlocking {
        fixture { host, monitor ->
            val record = monitor.device { true }
            assertEquals(UsbPermission.NOT_REQUESTED, record.permission)
            assertEquals(UsbInspectionState.NOT_READ, record.inspection)
            assertTrue(record.isAudioOutputCandidate)
            assertTrue(host.requests.isEmpty())
            assertEquals(0x1234, record.vendorId.value)
            assertEquals(EvidenceLevel.OS_REPORTED, record.vendorId.level)
            assertNull(record.productName.value)
            assertNull(record.audioDescriptors.value)
            assertNull(record.hardwareGainDb.value)
            assertEquals(EvidenceLevel.UNKNOWN, record.hardwareGainDb.level)
            assertEquals(UsbGainSetting.MANUAL, record.gainSetting)
        }
    }

    @Test
    fun explicitGrantPublishesOnlyOsReportedCapabilitiesAndLeavesActualRouteAlone() = runBlocking {
        fixture { host, monitor ->
            val route = AudioRouteSnapshot(null, 0, unknown(), unknown(), unknown(), unknown(), null)
            val record = monitor.device { true }
            monitor.requestPermission(record.connectionId)
            val request = host.request()
            host.granted += host.device.key
            host.listener(UsbHostEvent.Permission(request.first, request.second, true))
            val read = monitor.device { it.inspection == UsbInspectionState.READ }
            assertEquals(UsbPermission.GRANTED, read.permission)
            assertEquals("Fixture DAC", read.productName.value)
            assertEquals(EvidenceLevel.OS_REPORTED, read.audioDescriptors.level)
            assertEquals(48000, (read.audioDescriptors.value!!.outputFormats.single().sampleRates as UsbSampleRates.Discrete).hz.last())
            assertEquals(BitPerfectState.NOT_VERIFIED, route.bitPerfectState)
            assertNull(route.deviceName.value)
            assertNull(route.sampleRateHz.value)
            val json = ModelJson.format.encodeToString(monitor.snapshot.value)
            assertFalse(json.contains("private-usb-path"))
            assertFalse(json.contains("serial", ignoreCase = true))
            assertFalse(read.toString().contains("private-usb-path"))
        }
    }

    @Test
    fun denialCancellationAndOldRepliesCannotPublishACompletedInspection() = runBlocking {
        fixture { host, monitor ->
            val id = monitor.device { true }.connectionId
            monitor.requestPermission(id)
            val denied = host.request()
            host.listener(UsbHostEvent.Permission(denied.first, denied.second, false))
            monitor.device { it.permission == UsbPermission.DENIED }
            monitor.requestPermission(id)
            val cancelled = host.request(2)
            monitor.cancelPermission(id)
            monitor.device { it.permission == UsbPermission.CANCELLED }
            host.granted += host.device.key
            host.listener(UsbHostEvent.Permission(cancelled.first, cancelled.second, true))
            val revision = monitor.snapshot.value.revision
            monitor.refresh()
            val unchanged = monitor.state { it.revision > revision }.devices.single()
            assertEquals(UsbPermission.CANCELLED, unchanged.permission)
            assertTrue(host.inspections.isEmpty())
            monitor.requestPermission(id)
            val current = monitor.device { it.inspection == UsbInspectionState.READ }
            assertEquals(id, current.connectionId)
            assertTrue(host.cancelled.contains(cancelled.second))
            assertEquals(1, host.inspections.size)
        }
    }

    @Test
    fun timeoutRetryAndMismatchedPermissionRepliesAreIsolated() = runBlocking {
        fixture(timeout = 80) { host, monitor ->
            val id = monitor.device { true }.connectionId
            monitor.requestPermission(id)
            val old = host.request()
            monitor.device { it.permission == UsbPermission.TIMED_OUT }
            monitor.requestPermission(id)
            val current = host.request(2)
            assertNotEquals(old.second, current.second)
            host.listener(UsbHostEvent.Permission("other-device", current.second, true))
            host.listener(UsbHostEvent.Permission(current.first, old.second, true))
            host.listener(UsbHostEvent.Permission(current.first, current.second, true))
            // The callback cannot substitute for UsbManager.hasPermission.
            monitor.device { it.permission == UsbPermission.DENIED }
            assertTrue(host.inspections.isEmpty())
        }
    }

    @Test
    fun detachAndReattachInvalidateAnUnfinishedReadEvenWhenTheOsReusesItsPath() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture { host, monitor ->
                val old = monitor.device { true }
                host.reader = {
                    if (host.inspections.size == 1) {
                        started.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        UsbInspection("old", "old", uac1)
                    } else UsbInspection("new", "new", uac2)
                }
                host.granted += host.device.key
                monitor.retryInspection(old.connectionId)
                assertTrue(started.await(3, TimeUnit.SECONDS))
                host.attached.clear()
                host.listener(UsbHostEvent.Detached(host.device.key))
                monitor.state { it.devices.isEmpty() }
                host.attached += host.device
                host.listener(UsbHostEvent.Changed)
                val current = monitor.device { it.inspection == UsbInspectionState.READ }
                assertNotEquals(old.connectionId, current.connectionId)
                release.countDown()
                val revision = monitor.snapshot.value.revision
                monitor.refresh()
                val final = monitor.state { it.revision > revision }
                assertEquals("new", final.devices.single().productName.value)
            }
        } finally { release.countDown() }
    }

    @Test
    fun nonAudioDeviceIsNotADacAndMalformedDescriptorClaimsNoFormats() = runBlocking {
        fixture { host, monitor ->
            val originalId = monitor.device { true }.connectionId
            host.listener(UsbHostEvent.Detached(host.device.key))
            monitor.state { it.devices.isEmpty() }
            host.attached[0] = host.device.copy(interfaces = listOf(host.device.interfaces.single().copy(interfaceClass = 3)))
            host.reader = { UsbInspection(null, null, byteArrayOf(0, 2)) }
            host.granted += host.device.key
            host.listener(UsbHostEvent.Changed)
            val record = monitor.device { it.inspection == UsbInspectionState.MALFORMED }
            assertNotEquals(originalId, record.connectionId)
            assertFalse(record.isAudioOutputCandidate)
            assertNull(record.manufacturerName.value)
            val descriptors = record.audioDescriptors.value!!
            assertEquals(UsbDescriptorStatus.MALFORMED, descriptors.status)
            assertTrue(descriptors.outputFormats.isEmpty())
        }
    }

    @Test
    fun openFailureSecurityFailureRevocationAndRetryNeverKeepOldAttributes() = runBlocking {
        fixture { host, monitor ->
            val id = monitor.device { true }.connectionId
            host.granted += host.device.key
            host.reader = { null }
            monitor.retryInspection(id)
            monitor.device { it.inspection == UsbInspectionState.OPEN_FAILED }
            host.reader = { throw IllegalStateException("private platform message") }
            monitor.retryInspection(id)
            val failed = monitor.device { it.inspection == UsbInspectionState.FAILED }
            assertNull(failed.productName.value)
            assertFalse(failed.toString().contains("private platform message"))
            host.reader = { throw SecurityException("sensitive platform exception") }
            monitor.retryInspection(id)
            val denied = monitor.device { it.inspection == UsbInspectionState.PERMISSION_REQUIRED }
            assertEquals(UsbPermission.NOT_REQUESTED, denied.permission)
            host.reader = { UsbInspection("Fixture", "Fixture DAC", uac1) }
            monitor.retryInspection(id)
            monitor.device { it.inspection == UsbInspectionState.READ }
            host.granted.clear()
            monitor.refresh()
            val revoked = monitor.device { it.permission == UsbPermission.NOT_REQUESTED }
            assertNull(revoked.productName.value)
            assertNull(revoked.audioDescriptors.value)
        }
    }

    @Test
    fun failedDiscoveryCanRefreshAndClosingCancelsOutstandingRequests() = runBlocking {
        fixture { host, monitor ->
            monitor.device { true }
            host.failDiscovery = true
            monitor.refresh()
            monitor.state { it.discovery == UsbDiscoveryState.FAILED }
            host.failDiscovery = false
            monitor.refresh()
            val id = monitor.device { true }.connectionId
            monitor.requestPermission(id)
            val request = host.request()
            monitor.close()
            monitor.awaitClosed()
            assertTrue(host.closed)
            assertTrue(host.cancelled.contains(request.second))
            assertEquals(UsbDiscoveryState.STOPPED, monitor.snapshot.value.discovery)
            assertTrue(monitor.snapshot.value.devices.isEmpty())
        }
    }

    @Test
    fun unsupportedHostIsUnavailableRatherThanSuccessful() = runBlocking {
        fixture(available = false) { host, monitor ->
            monitor.state { it.discovery == UsbDiscoveryState.UNAVAILABLE }
            assertTrue(monitor.snapshot.value.devices.isEmpty())
            assertTrue(host.requests.isEmpty())
        }
    }

    private suspend fun fixture(timeout: Long = 5000, available: Boolean = true,
        test: suspend (FakeHost, UsbDeviceMonitor) -> Unit,
    ) {
        val worker = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val parent = CoroutineScope(SupervisorJob() + worker)
        val host = FakeHost(available)
        val monitor = UsbDeviceMonitor(host, parent, worker, timeout, clock = { 32L })
        try { test(host, monitor) } finally {
            monitor.close()
            monitor.awaitClosed()
            parent.cancel()
            worker.close()
        }
    }

    private suspend fun UsbDeviceMonitor.state(predicate: (UsbDevicesSnapshot) -> Boolean) =
        withTimeout(4000) { snapshot.first(predicate) }

    private suspend fun UsbDeviceMonitor.device(predicate: (UsbDeviceRecord) -> Boolean): UsbDeviceRecord =
        state { it.discovery == UsbDiscoveryState.ACTIVE && it.devices.singleOrNull()?.let(predicate) == true }.devices.single()

    private fun <T> unknown() = Measured<T>(null, EvidenceLevel.UNKNOWN, "fixture", 32)

    private class FakeHost(val available: Boolean) : UsbHost {
        val device = AttachedUsbDevice("private-usb-path", 0x1234, 0x5678,
            listOf(UsbInterfaceRecord(1, 1, 1, 1, 2, 0, true)))
        val attached = CopyOnWriteArrayList(listOf(device))
        val granted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val cancelled = CopyOnWriteArrayList<String>()
        val inspections = CopyOnWriteArrayList<String>()
        lateinit var listener: (UsbHostEvent) -> Unit
        @Volatile var reader: () -> UsbInspection? = { UsbInspection("Fixture", "Fixture DAC", uac1) }
        @Volatile var closed = false
        @Volatile var failDiscovery = false

        override fun start(listener: (UsbHostEvent) -> Unit): Boolean { this.listener = listener; return available }
        override fun devices(): List<AttachedUsbDevice> {
            if (failDiscovery) throw SecurityException("private platform message")
            return attached.toList()
        }
        override fun hasPermission(key: String) = key in granted
        override fun requestPermission(key: String, token: String) { requests += key to token }
        override fun cancelPermission(token: String) { cancelled += token }
        override fun inspect(key: String): UsbInspection? { inspections += key; return reader() }
        override fun close() { closed = true }
        suspend fun request(count: Int = 1): Pair<String, String> = withTimeout(3000) {
            while (requests.size < count) delay(1)
            requests[count - 1]
        }
    }
}
