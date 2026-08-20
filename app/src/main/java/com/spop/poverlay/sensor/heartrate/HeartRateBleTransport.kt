package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * The only Android-Bluetooth surface [HeartRateManager] touches.
 *
 * It exists purely as a test seam: every static [BluetoothAdapter] lookup and every
 * android.jar constructor lives behind it, so the manager's connect/scan sequencing can
 * be unit tested on the JVM without Robolectric. Keep it thin - no policy here.
 */
internal interface HeartRateBleTransport {
    /** Starts a low-latency scan filtered to [serviceUuid]. Returns false if no scanner exists. */
    fun startHrScan(serviceUuid: UUID, callback: ScanCallback): Boolean

    fun stopScan(callback: ScanCallback)

    fun getRemoteDevice(address: String): BluetoothDevice?

    fun connectGatt(
        context: Context,
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt?
}

internal object DefaultHeartRateBleTransport : HeartRateBleTransport {

    override fun startHrScan(serviceUuid: UUID, callback: ScanCallback): Boolean {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        return true
    }

    override fun stopScan(callback: ScanCallback) {
        BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(callback)
    }

    override fun getRemoteDevice(address: String): BluetoothDevice? =
        BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)

    override fun connectGatt(
        context: Context,
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt? = device.connectGatt(context.applicationContext, false, callback)
}
