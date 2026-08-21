package com.spop.poverlay.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
}
