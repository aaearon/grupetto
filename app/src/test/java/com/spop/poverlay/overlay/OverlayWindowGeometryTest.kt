package com.spop.poverlay.overlay

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowGeometryTest {

    private val expanded = 800 to 110
    private val minimized = 300 to 30

    private fun geometry(location: OverlayLocation, touchExtent: Int = 70) =
        overlayWindowGeometry(
            location = location,
            expandedSize = expanded,
            minimizedSize = minimized,
            touchExtent = touchExtent
        )

    @Test
    fun `horizontal uses the measured width and wraps its height`() {
        val result = geometry(OverlayLocation.Bottom)

        assertEquals(800, result.overlayWidth)
        // The window must fit the main content AND the timer bar below it. Only the main
        // content reports a measured height, so pinning to it clips the timer off screen.
        assertEquals(WRAP_CONTENT, result.overlayHeight)
    }

    @Test
    fun `horizontal touch target spans the minimized width and the touch extent`() {
        val result = geometry(OverlayLocation.Bottom)

        assertEquals(300, result.touchWidth)
        assertEquals(70, result.touchHeight)
    }

    @Test
    fun `vertical fills the screen height and wraps its width`() {
        val result = geometry(OverlayLocation.Left)

        assertEquals(MATCH_PARENT, result.overlayHeight)
        assertEquals(WRAP_CONTENT, result.overlayWidth)
    }

    @Test
    fun `vertical touch target takes the touch extent as its width`() {
        val result = geometry(OverlayLocation.Left)

        assertEquals(70, result.touchWidth)
        assertEquals(30, result.touchHeight)
    }

    @Test
    fun `touch target is visible while an extent is reported`() {
        assertTrue(geometry(OverlayLocation.Bottom).touchTargetVisible)
        assertTrue(geometry(OverlayLocation.Left).touchTargetVisible)
    }

    @Test
    fun `touch target is gone when there is no extent`() {
        assertFalse(geometry(OverlayLocation.Bottom, touchExtent = 0).touchTargetVisible)
        assertFalse(geometry(OverlayLocation.Left, touchExtent = 0).touchTargetVisible)
    }
}
