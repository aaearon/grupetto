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
        isServiceRunning: Boolean = false
    ) = decideServiceMode(
        showOverlay = showOverlay,
        bleTxEnabled = bleTxEnabled,
        dirConEnabled = dirConEnabled,
        canDrawOverlays = canDrawOverlays,
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
}
