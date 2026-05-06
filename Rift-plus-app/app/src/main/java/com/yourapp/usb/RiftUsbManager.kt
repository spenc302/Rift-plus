package com.yourapp.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log

private const val TAG = "RiftUsbManager"
private const val ACTION_USB_PERMISSION = "com.yourapp.RS_PLUS_USB_PERMISSION"
private const val CV1_PRODUCT_ID = 0x0031
private const val OCULUS_VENDOR_ID = 0x2833

class RiftUsbManager(
    private val context: Context,
    private val onConnectionReady: (UsbDeviceConnection, UsbInterface) -> Unit,
    private val onDisconnect: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null

    // 1. Monitor for physical detachment (vibration/cable pull)
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device?.vendorId == OCULUS_VENDOR_ID) {
                    Log.w(TAG, "HMD Disconnected physically")
                    stop()
                    onDisconnect()
                }
            }
        }
    }

    // 2. Monitor for permission result
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openDevice(it) }
                    } else {
                        onError("USB Permission Denied")
                    }
                }
            }
        }
    }

    fun start() {
        registerReceivers()
        val deviceList = usbManager.deviceList
        val rift = deviceList.values.find { 
            it.vendorId == OCULUS_VENDOR_ID && it.productId == CV1_PRODUCT_ID 
        }

        if (rift != null) {
            if (usbManager.hasPermission(rift)) {
                openDevice(rift)
            } else {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(rift, permissionIntent)
            }
        } else {
            onError("Rift CV1 HMD not found. Check USB connection.")
        }
    }

    private fun registerReceivers() {
        val filterDetach = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val filterPerm = IntentFilter(ACTION_USB_PERMISSION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(detachReceiver, filterDetach, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(permissionReceiver, filterPerm, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(detachReceiver, filterDetach)
            context.registerReceiver(permissionReceiver, filterPerm)
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(detachReceiver)
            context.unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {
            // ignore
        }
        connection?.let {
            usbInterface?.let { inter -> it.releaseInterface(inter) }
            it.close()
        }
        connection = null
        usbInterface = null
    }

    private fun openDevice(device: UsbDevice) {
        // CV1 IMU is typically on Interface 0
        val iface = device.getInterface(0)
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            onError("Failed to open USB device")
            return
        }
        if (conn.claimInterface(iface, true)) {
            connection = conn
            usbInterface = iface
            onConnectionReady(conn, iface)
        } else {
            conn.close()
            onError("Failed to claim USB interface")
        }
    }
}
