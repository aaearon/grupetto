package com.spop.poverlay.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGattServer
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.spop.poverlay.BleTransportState
import com.spop.poverlay.sensor.interfaces.SensorInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class BleServerTransportStateTest {

    private lateinit var context: Context
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var sensorInterface: SensorInterface
    private lateinit var bleServer: BleServer

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        bluetoothManager = mockk(relaxed = true)
        sensorInterface = mockk(relaxed = true)
        bleServer = BleServer(context, bluetoothManager, sensorInterface, FakeTimeProvider())
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `transport state starts stopped`() {
        assertEquals(BleTransportState.Stopped, bleServer.transportState.value)
        assertFalse(bleServer.dirConRunning.value)
    }

    @Test
    fun `missing adapter is reported, not silently swallowed`() {
        every { bluetoothManager.adapter } returns null

        bleServer.start()

        assertEquals(BleTransportState.AdapterUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `missing advertiser is reported`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.bluetoothLeAdvertiser } returns null
        every { bluetoothManager.adapter } returns adapter

        bleServer.start()

        assertEquals(BleTransportState.AdvertiserUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `missing bluetooth permission is reported`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.bluetoothLeAdvertiser } returns mockk(relaxed = true)
        every { bluetoothManager.adapter } returns adapter
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns
                PackageManager.PERMISSION_DENIED

        bleServer.start()

        assertEquals(BleTransportState.PermissionDenied, bleServer.transportState.value)
    }

    @Test
    fun `stopping returns the transport to stopped`() {
        every { bluetoothManager.adapter } returns null
        bleServer.start()

        bleServer.stop()

        assertEquals(BleTransportState.Stopped, bleServer.transportState.value)
        assertFalse(bleServer.dirConRunning.value)
    }

    // --- Bluetooth adapter transitions -------------------------------------------------
    //
    // The status line has to report the radio, not the toggle. A server left saying
    // "BLE transmission is active" with Bluetooth switched off is the exact lie this
    // feature exists to prevent.

    @Test
    fun `advertising server reports the adapter is gone when bluetooth turns off`() {
        markServerStarted()
        simulateAdvertisingStarted()
        assertEquals(BleTransportState.Advertising, bleServer.transportState.value)

        fireAdapterState(BluetoothAdapter.STATE_OFF)

        assertNotEquals(BleTransportState.Advertising, bleServer.transportState.value)
        assertEquals(BleTransportState.AdapterUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `advertising server reports the adapter is gone while bluetooth is turning off`() {
        markServerStarted()
        simulateAdvertisingStarted()

        fireAdapterState(BluetoothAdapter.STATE_TURNING_OFF)

        assertNotEquals(BleTransportState.Advertising, bleServer.transportState.value)
        assertEquals(BleTransportState.AdapterUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `turning on is not yet a recovery`() {
        markServerStarted()
        simulateAdvertisingStarted()
        fireAdapterState(BluetoothAdapter.STATE_OFF)

        fireAdapterState(BluetoothAdapter.STATE_TURNING_ON)

        assertEquals(BleTransportState.AdapterUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `recovery reports starting, not advertising, until the advertise callback confirms`() {
        givenAUsableAdapter()
        markServerStarted()
        simulateAdvertisingStarted()
        fireAdapterState(BluetoothAdapter.STATE_OFF)

        fireAdapterState(BluetoothAdapter.STATE_ON)

        // The GATT restart is asynchronous: only advertisingCallback.onStartSuccess may
        // claim Advertising. Saying so here would report a recovery that has not happened.
        assertEquals(BleTransportState.Starting, bleServer.transportState.value)
    }

    @Test
    fun `recovery that cannot use the adapter keeps reporting it unavailable`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.isEnabled } returns false
        every { bluetoothManager.adapter } returns adapter
        markServerStarted()
        simulateAdvertisingStarted()

        fireAdapterState(BluetoothAdapter.STATE_ON)

        assertNotEquals(BleTransportState.Advertising, bleServer.transportState.value)
        assertEquals(BleTransportState.AdapterUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `recovery without an advertiser reports the advertiser missing`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.isEnabled } returns true
        every { adapter.bluetoothLeAdvertiser } returns null
        every { bluetoothManager.adapter } returns adapter
        markServerStarted()
        simulateAdvertisingStarted()

        fireAdapterState(BluetoothAdapter.STATE_ON)

        assertEquals(BleTransportState.AdvertiserUnavailable, bleServer.transportState.value)
    }

    @Test
    fun `adapter transitions leave a stopped server stopped`() {
        assertEquals(BleTransportState.Stopped, bleServer.transportState.value)

        fireAdapterState(BluetoothAdapter.STATE_OFF)
        assertEquals(BleTransportState.Stopped, bleServer.transportState.value)

        fireAdapterState(BluetoothAdapter.STATE_ON)
        assertEquals(BleTransportState.Stopped, bleServer.transportState.value)
    }

    private fun givenAUsableAdapter() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.isEnabled } returns true
        every { adapter.bluetoothLeAdvertiser } returns mockk<BluetoothLeAdvertiser>(relaxed = true)
        every { bluetoothManager.adapter } returns adapter
        every { bluetoothManager.openGattServer(any(), any()) } returns
                mockk<BluetoothGattServer>(relaxed = true)
    }

    /** Puts the server in the state `start()` reaches once the GATT server is open. */
    private fun markServerStarted() {
        val field = BleServer::class.java.getDeclaredField("isServerStarted")
        field.isAccessible = true
        field.setBoolean(bleServer, true)
    }

    /** Drives the real production writer of [BleTransportState.Advertising]. */
    private fun simulateAdvertisingStarted() {
        val field = BleServer::class.java.getDeclaredField("advertisingCallback")
        field.isAccessible = true
        (field.get(bleServer) as AdvertiseCallback).onStartSuccess(null)
    }

    private fun fireAdapterState(state: Int) {
        val method = BleServer::class.java.getDeclaredMethod(
            "handleBluetoothStateChange",
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(bleServer, state)
    }
}
