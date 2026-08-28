package com.spop.poverlay

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSyncPolicyTest {

    private fun transports(
        bleRunning: Boolean,
        dirConRunning: Boolean
    ): OutboundTransports = mockk(relaxed = true) {
        io.mockk.every { isBleServerRunning } returns bleRunning
        io.mockk.every { isDirConServerRunning } returns dirConRunning
    }

    private fun plan(
        bleTxEnabled: Boolean = true,
        hasBluetoothPermissions: Boolean = true,
        dirConEnabled: Boolean = true,
        bleServerRunning: Boolean = false,
        dirConRunning: Boolean = false
    ) = planTransportSync(
        bleTxEnabled = bleTxEnabled,
        hasBluetoothPermissions = hasBluetoothPermissions,
        dirConEnabled = dirConEnabled,
        bleServerRunning = bleServerRunning,
        dirConRunning = dirConRunning
    )

    // The defect this suite exists for: an overlay-only preference change pokes the service,
    // which re-runs the transport sync with identical transport preferences. On real hardware
    // that swapped the DIRCON listening socket on port 8081 for a fresh inode, dropping any
    // connected bike computer mid-ride.
    @Test
    fun `an overlay-only change never touches running transports`() {
        val transports = transports(bleRunning = true, dirConRunning = true)

        applyTransportSync(
            transports = transports,
            bleTxEnabled = true,
            hasBluetoothPermissions = true,
            dirConEnabled = true
        )

        verify(exactly = 0) { transports.stop() }
        verify(exactly = 0) { transports.start() }
        verify(exactly = 0) { transports.setDirConTransportEnabled(any()) }
    }

    @Test
    fun `sync is a no-op when desired state already matches running state`() {
        assertTrue(plan(bleServerRunning = true, dirConRunning = true).isNoOp)
        assertTrue(
            plan(
                bleTxEnabled = false,
                dirConEnabled = false,
                bleServerRunning = false,
                dirConRunning = false
            ).isNoOp
        )
        assertTrue(
            plan(
                bleTxEnabled = false,
                dirConEnabled = true,
                bleServerRunning = false,
                dirConRunning = true
            ).isNoOp
        )
    }

    @Test
    fun `a DIRCON-only change does not bounce a running BLE server`() {
        val transports = transports(bleRunning = true, dirConRunning = true)

        applyTransportSync(
            transports = transports,
            bleTxEnabled = true,
            hasBluetoothPermissions = true,
            dirConEnabled = false
        )

        verify(exactly = 0) { transports.stop() }
        verify(exactly = 0) { transports.start() }
        verify(exactly = 1) { transports.setDirConTransportEnabled(false) }
    }

    @Test
    fun `turning DIRCON on starts it`() {
        val plan = plan(dirConEnabled = true, bleServerRunning = true, dirConRunning = false)

        assertTrue(plan.applyDirConTransport)
        assertFalse(plan.stopBleServer)
        assertFalse(plan.startBleServer)
    }

    @Test
    fun `turning BLE on starts the server`() {
        val transports = transports(bleRunning = false, dirConRunning = true)

        applyTransportSync(
            transports = transports,
            bleTxEnabled = true,
            hasBluetoothPermissions = true,
            dirConEnabled = true
        )

        verify(exactly = 1) { transports.start() }
        verify(exactly = 0) { transports.stop() }
    }

    @Test
    fun `turning BLE off stops the server and keeps DIRCON wanted`() {
        val transports = transports(bleRunning = true, dirConRunning = true)

        applyTransportSync(
            transports = transports,
            bleTxEnabled = false,
            hasBluetoothPermissions = true,
            dirConEnabled = true
        )

        verify(exactly = 1) { transports.stop() }
        verify(exactly = 0) { transports.start() }
        // stop() tears DIRCON down with the GATT server, so it has to be re-applied as
        // DIRCON-only afterwards.
        verify(exactly = 1) { transports.setDirConTransportEnabled(true) }
    }

    @Test
    fun `missing bluetooth permission is treated as BLE off`() {
        assertFalse(plan(bleTxEnabled = true, hasBluetoothPermissions = false).startBleServer)
        assertTrue(
            plan(
                bleTxEnabled = true,
                hasBluetoothPermissions = false,
                bleServerRunning = true
            ).stopBleServer
        )
    }

    // A fresh process defaults BleServer's DIRCON flag to enabled. Starting BLE has to push the
    // real preference down first, or a disabled DIRCON comes up anyway with the GATT server.
    @Test
    fun `starting BLE always applies the DIRCON preference first`() {
        assertTrue(
            plan(
                bleTxEnabled = true,
                dirConEnabled = false,
                bleServerRunning = false,
                dirConRunning = false
            ).applyDirConTransport
        )
    }

    @Test
    fun `applied steps run in stop then dircon then start order`() {
        val transports = transports(bleRunning = true, dirConRunning = true)

        applyTransportSync(
            transports = transports,
            bleTxEnabled = false,
            hasBluetoothPermissions = true,
            dirConEnabled = true
        )

        verify(ordering = io.mockk.Ordering.ORDERED) {
            transports.stop()
            transports.setDirConTransportEnabled(true)
        }
    }
}
