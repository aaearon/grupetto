package com.spop.poverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceModePolicyTest {

    private fun decide(
        showOverlay: Boolean = true,
        bleTxEnabled: Boolean = true,
        dirConEnabled: Boolean = true,
        canDrawOverlays: Boolean = true,
        bleTransportState: BleTransportState = BleTransportState.Advertising,
        dirConRunning: Boolean = true,
        isServiceRunning: Boolean = false
    ) = decideServiceMode(
        showOverlay = showOverlay,
        bleTxEnabled = bleTxEnabled,
        dirConEnabled = dirConEnabled,
        canDrawOverlays = canDrawOverlays,
        bleTransportState = bleTransportState,
        dirConRunning = dirConRunning,
        isServiceRunning = isServiceRunning
    )

    @Test
    fun `overlay window is attached only when wanted and permitted`() {
        assertTrue(decide(showOverlay = true, canDrawOverlays = true).attachOverlayWindow)
        assertFalse(decide(showOverlay = false, canDrawOverlays = true).attachOverlayWindow)
        assertFalse(decide(showOverlay = true, canDrawOverlays = false).attachOverlayWindow)
    }

    @Test
    fun `permission prompt is shown only when the overlay is wanted`() {
        assertTrue(decide(showOverlay = true, canDrawOverlays = false).showOverlayPermissionPrompt)
        assertFalse(decide(showOverlay = false, canDrawOverlays = false).showOverlayPermissionPrompt)
        assertFalse(decide(showOverlay = true, canDrawOverlays = true).showOverlayPermissionPrompt)
    }

    @Test
    fun `missing overlay permission does not block a transport-only start`() {
        val decision = decide(showOverlay = true, canDrawOverlays = false, bleTxEnabled = true)

        assertTrue(decision.mayStartService)
        assertNull(decision.blockedReason)
    }

    @Test
    fun `service may not start when nothing would run`() {
        val decision = decide(
            showOverlay = false,
            bleTxEnabled = false,
            dirConEnabled = false
        )

        assertFalse(decision.mayStartService)
        assertEquals(NothingToRunMessage, decision.blockedReason)
    }

    @Test
    fun `service may not start when the overlay is wanted but unpermitted and no transport is on`() {
        val decision = decide(
            showOverlay = true,
            canDrawOverlays = false,
            bleTxEnabled = false,
            dirConEnabled = false
        )

        assertFalse(decision.mayStartService)
    }

    @Test
    fun `overlay alone is enough to start`() {
        val decision = decide(bleTxEnabled = false, dirConEnabled = false)

        assertTrue(decision.mayStartService)
        assertNull(decision.blockedReason)
    }

    @Test
    fun `start button label follows what will actually run`() {
        assertEquals("Start Overlay", decide(isServiceRunning = false).startButtonLabel)
        assertEquals("Restart Overlay", decide(isServiceRunning = true).startButtonLabel)
        assertEquals(
            "Start Broadcasting",
            decide(showOverlay = false, isServiceRunning = false).startButtonLabel
        )
        assertEquals(
            "Restart Broadcasting",
            decide(showOverlay = false, isServiceRunning = true).startButtonLabel
        )
        assertEquals(
            "Start Broadcasting",
            decide(canDrawOverlays = false, isServiceRunning = false).startButtonLabel
        )
    }

    @Test
    fun `ble status is not claimed active merely because the toggle is on`() {
        val decision = decide(
            showOverlay = false,
            bleTxEnabled = true,
            dirConEnabled = false,
            bleTransportState = BleTransportState.Stopped
        )

        assertEquals("BLE is enabled but not running", decision.transportStatus)
    }

    @Test
    fun `ble status reports each real transport state`() {
        fun statusFor(state: BleTransportState) = decide(
            bleTxEnabled = true,
            dirConEnabled = false,
            bleTransportState = state
        ).transportStatus

        assertEquals("BLE transmission is active", statusFor(BleTransportState.Advertising))
        assertEquals("BLE is starting", statusFor(BleTransportState.Starting))
        assertEquals(
            "BLE unavailable: no Bluetooth adapter",
            statusFor(BleTransportState.AdapterUnavailable)
        )
        assertEquals(
            "BLE unavailable: this device cannot advertise",
            statusFor(BleTransportState.AdvertiserUnavailable)
        )
        assertEquals(
            "BLE blocked: Bluetooth permission not granted",
            statusFor(BleTransportState.PermissionDenied)
        )
        assertEquals("BLE failed to start", statusFor(BleTransportState.Failed))
    }

    @Test
    fun `dircon status follows the running server, not the toggle`() {
        assertEquals(
            "DIRCON WiFi is active",
            decide(bleTxEnabled = false, dirConEnabled = true, dirConRunning = true).transportStatus
        )
        assertEquals(
            "DIRCON WiFi is enabled but not running",
            decide(bleTxEnabled = false, dirConEnabled = true, dirConRunning = false).transportStatus
        )
    }

    @Test
    fun `both transports are reported on their own lines`() {
        val decision = decide(
            bleTxEnabled = true,
            bleTransportState = BleTransportState.Advertising,
            dirConEnabled = true,
            dirConRunning = false
        )

        assertEquals(
            "BLE transmission is active\nDIRCON WiFi is enabled but not running",
            decision.transportStatus
        )
    }

    @Test
    fun `no transport status when no transport is enabled`() {
        assertNull(decide(bleTxEnabled = false, dirConEnabled = false).transportStatus)
    }
}
