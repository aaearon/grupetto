package com.spop.poverlay.overlay

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowGeometryTest {

    private val expanded = 800 to 110
    private val minimized = 300 to 30

    private fun geometry(location: OverlayLocation, isMinimized: Boolean, touchExtent: Int = 70) =
        overlayWindowGeometry(
            location = location,
            isMinimized = isMinimized,
            expandedSize = expanded,
            minimizedSize = minimized,
            touchExtent = touchExtent
        )

    @Test
    fun `horizontal expanded uses the measured overlay size`() {
        val result = geometry(OverlayLocation.Bottom, isMinimized = false)

        assertEquals(800, result.overlayWidth)
        assertEquals(110, result.overlayHeight)
    }

    @Test
    fun `horizontal minimized shrinks the window to the minimized height`() {
        val result = geometry(OverlayLocation.Top, isMinimized = true)

        assertEquals(800, result.overlayWidth)
        assertEquals(30, result.overlayHeight)
    }

    @Test
    fun `horizontal touch target spans the minimized width and the touch extent`() {
        val result = geometry(OverlayLocation.Bottom, isMinimized = true)

        assertEquals(300, result.touchWidth)
        assertEquals(70, result.touchHeight)
    }

    @Test
    fun `vertical expanded fills the screen height`() {
        val result = geometry(OverlayLocation.Left, isMinimized = false)

        assertEquals(MATCH_PARENT, result.overlayHeight)
        assertEquals(800, result.overlayWidth)
    }

    @Test
    fun `vertical minimized shrinks the window to the minimized width`() {
        val result = geometry(OverlayLocation.Right, isMinimized = true)

        assertEquals(MATCH_PARENT, result.overlayHeight)
        assertEquals(300, result.overlayWidth)
    }

    @Test
    fun `vertical touch target takes the touch extent as its width`() {
        val result = geometry(OverlayLocation.Left, isMinimized = true)

        assertEquals(70, result.touchWidth)
        assertEquals(30, result.touchHeight)
    }

    @Test
    fun `touch target is visible while an extent is reported`() {
        assertTrue(geometry(OverlayLocation.Bottom, isMinimized = true).touchTargetVisible)
        assertTrue(geometry(OverlayLocation.Left, isMinimized = true).touchTargetVisible)
    }

    @Test
    fun `touch target is gone when there is no extent`() {
        assertFalse(
            geometry(OverlayLocation.Bottom, isMinimized = false, touchExtent = 0).touchTargetVisible
        )
        assertFalse(
            geometry(OverlayLocation.Left, isMinimized = false, touchExtent = 0).touchTargetVisible
        )
    }
}
