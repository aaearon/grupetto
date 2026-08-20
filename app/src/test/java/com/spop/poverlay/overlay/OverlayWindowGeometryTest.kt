package com.spop.poverlay.overlay

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // Regression: a MATCH_PARENT vertical window is 1080px tall while the HUD only draws
    // ~700px of that. FLAG_NOT_TOUCH_MODAL makes every pixel inside the window consume
    // touches, so the undrawn remainder killed a full-height column of the Peloton UI
    // (measured: the profile button at (89,1038) and the "..." menu at (1849,1038) were
    // both dead). The window must size to its content and let CENTER_VERTICAL centre it.
    @Test
    fun `vertical wraps its height so the window does not span the screen`() {
        val result = geometry(OverlayLocation.Left)

        assertEquals(WRAP_CONTENT, result.overlayHeight)
        assertNotEquals(MATCH_PARENT, result.overlayHeight)
        assertEquals(WRAP_CONTENT, result.overlayWidth)
    }

    @Test
    fun `vertical wraps its height on both sides when expanded`() {
        for (location in listOf(OverlayLocation.Left, OverlayLocation.Right)) {
            val result = geometry(location, touchExtent = 0)

            assertEquals(WRAP_CONTENT, result.overlayHeight)
            assertNotEquals(MATCH_PARENT, result.overlayHeight)
        }
    }

    @Test
    fun `vertical wraps its height on both sides when minimized`() {
        for (location in listOf(OverlayLocation.Left, OverlayLocation.Right)) {
            val result = overlayWindowGeometry(
                location = location,
                expandedSize = expanded,
                minimizedSize = 117 to 321,
                touchExtent = OverlayService.HiddenTouchTargetMarginPx
            )

            assertEquals(WRAP_CONTENT, result.overlayHeight)
            assertNotEquals(MATCH_PARENT, result.overlayHeight)
        }
    }

    // The cross axis is untouched by the fix: only the main content reports a measured
    // width, so pinning the window to it would clip the timer bar off screen.
    @Test
    fun `vertical still wraps its width rather than using the measured width`() {
        val result = geometry(OverlayLocation.Right)

        assertEquals(WRAP_CONTENT, result.overlayWidth)
        assertNotEquals(expanded.first, result.overlayWidth)
    }

    // Horizontal docking never had the bug and must not change.
    @Test
    fun `horizontal geometry is unchanged by the vertical wrap fix`() {
        for (location in listOf(OverlayLocation.Top, OverlayLocation.Bottom)) {
            val result = geometry(location)

            assertEquals(800, result.overlayWidth)
            assertEquals(WRAP_CONTENT, result.overlayHeight)
            assertEquals(300, result.touchWidth)
            assertEquals(70, result.touchHeight)
        }
    }

    @Test
    fun `vertical touch target takes the touch extent as its width`() {
        val result = geometry(OverlayLocation.Left)

        assertTrue(result.touchWidth >= 300)
        assertEquals(30, result.touchHeight)
    }

    // Measured on a Bike (1920x1080, density 240): the minimized vertical tab is 117px
    // (78dp) wide and the chevron sits at its center, x=59. A touch window that is only
    // the margin wide (40px) does not reach it.
    @Test
    fun `vertical minimized touch target covers the minimized tab width plus the margin`() {
        val result = overlayWindowGeometry(
            location = OverlayLocation.Left,
            expandedSize = expanded,
            minimizedSize = 117 to 321,
            touchExtent = OverlayService.HiddenTouchTargetMarginPx
        )

        assertEquals(117 + OverlayService.HiddenTouchTargetMarginPx, result.touchWidth)
        assertTrue(
            "touch target must reach the chevron at x=59",
            result.touchWidth >= 117
        )
        // Cross-axis unchanged: the tab's full height.
        assertEquals(321, result.touchHeight)
    }

    @Test
    fun `horizontal minimized touch target covers the minimized bar height plus the margin`() {
        val result = overlayWindowGeometry(
            location = OverlayLocation.Bottom,
            expandedSize = expanded,
            minimizedSize = 667 to 40,
            touchExtent = OverlayService.HiddenTouchTargetMarginPx
        )

        assertEquals(40 + OverlayService.HiddenTouchTargetMarginPx, result.touchHeight)
        assertTrue(result.touchHeight >= 40)
        // Cross-axis unchanged: the bar's full width.
        assertEquals(667, result.touchWidth)
    }

    @Test
    fun `expanded state keeps the touch target gone in both orientations`() {
        val vertical = overlayWindowGeometry(
            location = OverlayLocation.Left,
            expandedSize = expanded,
            minimizedSize = 117 to 321,
            touchExtent = 0
        )
        val horizontal = overlayWindowGeometry(
            location = OverlayLocation.Bottom,
            expandedSize = expanded,
            minimizedSize = 667 to 40,
            touchExtent = 0
        )

        // Safety property: while the HUD is expanded the invisible target must never be
        // tappable, or it steals touches from the Peloton UI underneath.
        assertFalse(vertical.touchTargetVisible)
        assertFalse(horizontal.touchTargetVisible)
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
