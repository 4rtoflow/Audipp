package com.audipp.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope

fun Context.createUsbDeviceMonitor(scope: CoroutineScope): UsbDeviceMonitor =
    UsbDeviceMonitor(AndroidUsbHost(applicationContext), scope)

internal class AndroidUsbHost(private val context: Context) : UsbHost {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val permissionAction = "${context.packageName}.USB_DEVICE_PERMISSION"
    private val pending = mutableMapOf<String, PermissionRequest>()
    @Volatile private var listener: ((UsbHostEvent) -> Unit)? = null
    private var devicesRegistered = false
    private var permissionRegistered = false

    private val devicesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> listener?.invoke(UsbHostEvent.Changed)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> intent.usbDevice()?.let {
                    listener?.invoke(UsbHostEvent.Detached(it.deviceName))
                }
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val token = intent.data?.lastPathSegment ?: return
            val request = synchronized(pending) { pending[token] } ?: return
            val device = intent.usbDevice() ?: return
            if (device.deviceName != request.key) return
            listener?.invoke(UsbHostEvent.Permission(request.key, token,
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)))
        }
    }

    override fun start(listener: (UsbHostEvent) -> Unit): Boolean {
        if (manager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) return false
        this.listener = listener
        val devicesFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val permissionFilter = IntentFilter(permissionAction).apply { addDataScheme("audipp-usb-permission") }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(devicesReceiver, devicesFilter, Context.RECEIVER_EXPORTED)
            devicesRegistered = true
            context.registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(devicesReceiver, devicesFilter)
            devicesRegistered = true
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, permissionFilter)
        }
        permissionRegistered = true
        return true
    }

    override fun devices(): List<AttachedUsbDevice> = manager?.deviceList?.values.orEmpty().map { device ->
        val interfaces = buildList {
            for (configurationIndex in 0 until device.configurationCount) {
                val configuration = device.getConfiguration(configurationIndex)
                for (interfaceIndex in 0 until configuration.interfaceCount) {
                    val usbInterface = configuration.getInterface(interfaceIndex)
                    val output = (0 until usbInterface.endpointCount).any { index ->
                        val endpoint = usbInterface.getEndpoint(index)
                        endpoint.direction == UsbConstants.USB_DIR_OUT &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                            endpoint.attributes and 0x30 != 0x10 && endpoint.maxPacketSize and 0x7ff != 0
                    }
                    add(UsbInterfaceRecord(configuration.id, usbInterface.id, usbInterface.alternateSetting,
                        usbInterface.interfaceClass, usbInterface.interfaceSubclass, usbInterface.interfaceProtocol, output))
                }
            }
        }
        AttachedUsbDevice(device.deviceName, device.vendorId, device.productId, interfaces)
    }

    override fun hasPermission(key: String): Boolean =
        manager?.deviceList?.get(key)?.let { manager.hasPermission(it) } == true

    override fun requestPermission(key: String, token: String) {
        val device = manager?.deviceList?.get(key) ?: throw IllegalStateException("USB disconnected")
        val intent = Intent(permissionAction).setPackage(context.packageName)
            .setData(Uri.parse("audipp-usb-permission://request/$token"))
        // UsbManager fills EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED in the callback.
        val mutableFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT or mutableFlag)
        synchronized(pending) { pending[token] = PermissionRequest(key, callback) }
        manager.requestPermission(device, callback)
    }

    override fun cancelPermission(token: String) {
        synchronized(pending) { pending.remove(token) }?.callback?.cancel()
    }

    override fun inspect(key: String): UsbInspection? {
        val device = manager?.deviceList?.get(key) ?: return null
        if (!manager.hasPermission(device)) throw SecurityException("USB permission required")
        val connection = manager.openDevice(device) ?: return null
        return try {
            UsbInspection(publicName { device.manufacturerName }, publicName { device.productName }, connection.rawDescriptors)
        } finally {
            connection.close()
        }
    }

    private fun publicName(read: () -> String?): String? = try { read() } catch (_: SecurityException) { null }

    override fun close() {
        listener = null
        if (devicesRegistered) {
            context.unregisterReceiver(devicesReceiver)
            devicesRegistered = false
        }
        if (permissionRegistered) {
            context.unregisterReceiver(permissionReceiver)
            permissionRegistered = false
        }
        val callbacks = synchronized(pending) { pending.values.map { it.callback }.also { pending.clear() } }
        callbacks.forEach { it.cancel() }
    }

    private data class PermissionRequest(val key: String, val callback: PendingIntent)
}

private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
