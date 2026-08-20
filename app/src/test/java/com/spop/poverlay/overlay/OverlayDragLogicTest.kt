package com.spop.poverlay.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragLogicTest {

    private val screenWidth = 1000f
    private val screenHeight = 600f

    // The overlay is 600px wide on a 1000px screen, so it may slide +-200px
    private val slideRange = -200f..200f

    private fun drag(
        location: OverlayLocation,
        accumulatedX: Float = 0f,
        accumulatedY: Float = 0f,
        slideRange: ClosedFloatingPointRange<Float> = this.slideRange
    ) = processOverlayDrag(
        location = location,
        accumulatedX = accumulatedX,
        accumulatedY = accumulatedY,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        slideRange = slideRange
    )

    @Test
    fun `vertical drag past half the screen redocks bottom to top`() {
        val result = drag(OverlayLocation.Bottom, accumulatedY = -301f)

        assertEquals(OverlayLocation.Top, result.location)
        assertEquals(0f, result.accumulatedX, 0f)
        assertEquals(0f, result.accumulatedY, 0f)
        assertEquals(0f, result.originX, 0f)
        assertEquals(0f, result.originY, 0f)
    }

    @Test
    fun `vertical drag past half the screen redocks top to bottom`() {
        val result = drag(OverlayLocation.Top, accumulatedY = 301f)

        assertEquals(OverlayLocation.Bottom, result.location)
        assertEquals(0f, result.accumulatedY, 0f)
    }

    @Test
    fun `vertical drag below the threshold keeps the location and the accumulator`() {
        val result = drag(OverlayLocation.Bottom, accumulatedY = -299f)

        assertEquals(OverlayLocation.Bottom, result.location)
        assertEquals(-299f, result.accumulatedY, 0f)
    }

    @Test
    fun `horizontal overshoot past the slide clamp redocks bottom to left`() {
        // Clamp is 200px, overshoot threshold is another 200px
        val result = drag(OverlayLocation.Bottom, accumulatedX = -401f)

        assertEquals(OverlayLocation.Left, result.location)
        assertEquals(0f, result.accumulatedX, 0f)
        assertEquals(0f, result.accumulatedY, 0f)
        assertEquals(0f, result.originX, 0f)
    }

    @Test
    fun `horizontal overshoot past the slide clamp redocks bottom to right`() {
        val result = drag(OverlayLocation.Bottom, accumulatedX = 401f)

        assertEquals(OverlayLocation.Right, result.location)
        assertEquals(0f, result.originX, 0f)
    }

    @Test
    fun `horizontal overshoot below the threshold clamps the origin but keeps the raw accumulator`() {
        val result = drag(OverlayLocation.Bottom, accumulatedX = -399f)

        assertEquals(OverlayLocation.Bottom, result.location)
        // The accumulator must stay raw or the overshoot can never be reached
        assertEquals(-399f, result.accumulatedX, 0f)
        assertEquals(-200f, result.originX, 0f)
    }

    @Test
    fun `horizontal drag within the slide range follows the gesture`() {
        val result = drag(OverlayLocation.Top, accumulatedX = 120f)

        assertEquals(OverlayLocation.Top, result.location)
        assertEquals(120f, result.originX, 0f)
        assertEquals(120f, result.accumulatedX, 0f)
    }

    @Test
    fun `horizontal drag near the center snaps the origin to zero without losing the accumulator`() {
        val result = drag(OverlayLocation.Bottom, accumulatedX = 15f)

        assertEquals(0f, result.originX, 0f)
        assertEquals(15f, result.accumulatedX, 0f)
    }

    @Test
    fun `horizontal drag past the redock threshold flips left to right`() {
        val result = drag(OverlayLocation.Left, accumulatedX = 201f)

        assertEquals(OverlayLocation.Right, result.location)
        assertEquals(0f, result.accumulatedX, 0f)
        assertEquals(0f, result.accumulatedY, 0f)
        assertEquals(0f, result.originX, 0f)
    }

    @Test
    fun `horizontal drag past the redock threshold flips right to left`() {
        val result = drag(OverlayLocation.Right, accumulatedX = -201f)

        assertEquals(OverlayLocation.Left, result.location)
    }

    @Test
    fun `vertical drag past half the screen redocks left to bottom`() {
        val result = drag(OverlayLocation.Left, accumulatedY = 301f)

        assertEquals(OverlayLocation.Bottom, result.location)
        assertEquals(0f, result.accumulatedX, 0f)
        assertEquals(0f, result.accumulatedY, 0f)
        assertEquals(0f, result.originX, 0f)
    }

    @Test
    fun `vertical drag past half the screen redocks right to top`() {
        val result = drag(OverlayLocation.Right, accumulatedY = -301f)

        assertEquals(OverlayLocation.Top, result.location)
    }

    @Test
    fun `a docked vertical overlay never slides along either axis`() {
        // The window is already full height, so origin must stay pinned at the edge
        val result = drag(OverlayLocation.Left, accumulatedX = 120f, accumulatedY = 90f)

        assertEquals(OverlayLocation.Left, result.location)
        assertEquals(0f, result.originX, 0f)
        assertEquals(0f, result.originY, 0f)
        assertEquals(120f, result.accumulatedX, 0f)
        assertEquals(90f, result.accumulatedY, 0f)
    }

    @Test
    fun `redocking to a side clears a stale slide origin`() {
        val result = drag(OverlayLocation.Bottom, accumulatedX = -900f)

        assertEquals(OverlayLocation.Left, result.location)
        assertEquals(0f, result.originX, 0f)
    }

    @Test
    fun `a vertical redock wins over a simultaneous horizontal overshoot from the bottom`() {
        val result = drag(OverlayLocation.Bottom, accumulatedX = -900f, accumulatedY = 400f)

        assertEquals(OverlayLocation.Top, result.location)
    }

    @Test
    fun `an accumulator seeded from the current origin keeps sliding from there`() {
        // A new gesture seeded at -175 and nudged 30px further left
        val result = drag(OverlayLocation.Bottom, accumulatedX = -205f)

        assertEquals(OverlayLocation.Bottom, result.location)
        assertEquals(-200f, result.originX, 0f)
        assertEquals(-205f, result.accumulatedX, 0f)
    }

    @Test
    fun `an accumulator seeded at the slide clamp can still overshoot into a redock`() {
        // Seeded at the -200 clamp, then dragged another 250px: overshoot is 250 > 200
        val result = drag(OverlayLocation.Bottom, accumulatedX = -450f)

        assertEquals(OverlayLocation.Left, result.location)
        assertEquals(0f, result.originX, 0f)
        assertEquals(0f, result.accumulatedX, 0f)
    }
}
