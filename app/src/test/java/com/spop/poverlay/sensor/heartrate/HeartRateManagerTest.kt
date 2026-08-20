package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.UUID

/**
 * Regression tests for the HR connect race that leaked GATT client registrations
 * (two `connect()` calls for one tap, status 133, then a permanent silent failure
 * once the ~32-client registry filled up).
 */
class HeartRateManagerTest {

    private val hrService: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val address = "7B:45:52:C8:A1:85"

    private lateinit var context: Context
    private lateinit var prefs: FakePrefs
    private lateinit var transport: FakeTransport
    private lateinit var remoteDevice: BluetoothDevice

    @Before
    fun setup() {
        HeartRateManager.resetForTest()
        prefs = FakePrefs()
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs

        remoteDevice = mockk(relaxed = true)
        every { remoteDevice.address } returns address
        every { remoteDevice.name } returns "HeartCast"

        transport = FakeTransport()
        transport.devices[address] = remoteDevice
        HeartRateManager.transport = transport
    }

    @After
    fun tearDown() {
        HeartRateManager.resetForTest()
    }

    // --- the race -----------------------------------------------------------

    @Test
    fun `connectTo stops discovery before opening a gatt connection`() {
        HeartRateManager.start(context)
        HeartRateManager.startDiscovery()
        transport.events.clear()

        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))

        assertEquals(listOf("stopScan", "connect:$address"), transport.events)
    }

    @Test
    fun `a scan result cannot start a second connect while a manual connect is in flight`() {
        HeartRateManager.start(context)
        HeartRateManager.startDiscovery()
        val scanCallback = requireNotNull(transport.scanCallback)

        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))
        assertEquals(1, transport.connectCount)

        // The picker's scan is still delivering results for the very device we are
        // connecting to; _connectedDevice is still null because the GATT is in flight.
        scanCallback.onScanResult(0, hrScanResult(remoteDevice))

        assertEquals(
            "a second connectGatt leaks a GATT client registration",
            1,
            transport.connectCount,
        )
    }

    @Test
    fun `auto connect from a scan result stops discovery before connecting`() {
        prefs.savedDevices(address)
        // start() kicks off the auto-reconnect scan itself for a saved device.
        HeartRateManager.start(context)
        val scanCallback = awaitScanCallback()
        transport.events.clear()

        scanCallback.onScanResult(0, hrScanResult(remoteDevice))

        assertEquals(listOf("stopScan", "connect:$address"), transport.events)
    }

    // --- connectGatt returning null ----------------------------------------

    @Test
    fun `a null connectGatt does not block every later connect attempt`() {
        HeartRateManager.start(context)
        transport.gattFactory = { null }

        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))
        assertEquals(1, transport.connectCount)

        val gatt = relaxedGatt()
        transport.gattFactory = { gatt }
        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))

        assertEquals(
            "the in-flight guard must be released when connectGatt returns null",
            2,
            transport.connectCount,
        )
    }

    // --- tearing down the previous GATT -------------------------------------

    @Test
    fun `previous gatt is closed even when disconnect throws`() {
        HeartRateManager.start(context)
        val first = relaxedGatt()
        every { first.disconnect() } throws IllegalStateException("dead binder")
        transport.gattFactory = { first }

        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))
        transport.lastGattCallback!!.onConnectionStateChange(
            first,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED,
        )

        val second = relaxedGatt()
        transport.gattFactory = { second }
        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))

        verify { first.close() }
        assertEquals(2, transport.connectCount)
    }

    // --- the guard must not be able to wedge --------------------------------

    @Test
    fun `a connect that never calls back stops blocking after the timeout`() {
        val clock = FakeClock()
        HeartRateManager.nowMs = clock
        HeartRateManager.start(context)
        transport.gattFactory = { relaxedGatt() }

        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))
        assertEquals(1, transport.connectCount)

        // No onConnectionStateChange ever arrives (the leaked-client failure mode).
        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))
        assertEquals("still in flight", 1, transport.connectCount)

        clock.now += HeartRateManager.ConnectTimeoutMs + 1
        HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))
        assertEquals(2, transport.connectCount)
    }

    // --- visibility ---------------------------------------------------------

    @Test
    fun `connection state changes log the address and the status`() {
        val logs = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += message
            }
        }
        Timber.plant(tree)
        try {
            HeartRateManager.start(context)
            val gatt = relaxedGatt()
            transport.gattFactory = { gatt }
            HeartRateManager.connectTo(HeartRateDevice(address, "HeartCast"))

            // status 133 is what the field logs showed; a silent 133 loop is the bug.
            transport.lastGattCallback!!.onConnectionStateChange(
                gatt,
                133,
                BluetoothProfile.STATE_DISCONNECTED,
            )

            assertTrue(
                "expected a log line naming the device and status, got: $logs",
                logs.any { it.contains(address) && it.contains("133") },
            )
        } finally {
            Timber.uproot(tree)
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun relaxedGatt(): BluetoothGatt = mockk(relaxed = true)

    private fun awaitScanCallback(): ScanCallback {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            transport.scanCallback?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("auto-reconnect scan never started")
    }

    private fun hrScanResult(device: BluetoothDevice): ScanResult {
        val parcelUuid = mockk<ParcelUuid>()
        every { parcelUuid.uuid } returns hrService
        val record = mockk<ScanRecord>(relaxed = true)
        every { record.serviceUuids } returns listOf(parcelUuid)
        val result = mockk<ScanResult>(relaxed = true)
        every { result.device } returns device
        every { result.scanRecord } returns record
        return result
    }

    private class FakeClock : () -> Long {
        var now: Long = 1_000L
        override fun invoke(): Long = now
    }

    private class FakeTransport : HeartRateBleTransport {
        val events = mutableListOf<String>()
        val devices = mutableMapOf<String, BluetoothDevice>()
        var gattFactory: (() -> BluetoothGatt?)? = null
        var connectCount = 0
        var lastGattCallback: BluetoothGattCallback? = null
        var scanCallback: ScanCallback? = null

        override fun startHrScan(serviceUuid: UUID, callback: ScanCallback): Boolean {
            scanCallback = callback
            events += "startScan"
            return true
        }

        override fun stopScan(callback: ScanCallback) {
            scanCallback = null
            events += "stopScan"
        }

        override fun getRemoteDevice(address: String): BluetoothDevice? = devices[address]

        override fun connectGatt(
            context: Context,
            device: BluetoothDevice,
            callback: BluetoothGattCallback,
        ): BluetoothGatt? {
            connectCount++
            lastGattCallback = callback
            events += "connect:${device.address}"
            return (gattFactory ?: { mockk<BluetoothGatt>(relaxed = true) }).invoke()
        }
    }

    /** Minimal in-memory SharedPreferences; the android.jar stubs all throw. */
    private class FakePrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        fun savedDevices(vararg addresses: String) {
            values["hr_saved_devices"] = addresses.toSet()
        }

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): SharedPreferences.Editor = Editor(values)

        private class Editor(private val values: MutableMap<String, Any?>) :
            SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) =
                apply { pending[key] = value }

            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { removed += values.keys }
            override fun commit(): Boolean {
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                pending.clear()
                removed.clear()
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
