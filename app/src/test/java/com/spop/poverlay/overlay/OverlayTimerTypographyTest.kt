package com.spop.poverlay.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayTimerTypographyTest {

    @Test
    fun `minutes and seconds label keeps the full size`() {
        assertEquals(TimerFontSizeSpDefault, timerFontSizeSpFor("9:59"), 0f)
    }

    @Test
    fun `two digit minutes label keeps the full size`() {
        assertEquals(TimerFontSizeSpDefault, timerFontSizeSpFor("45:30"), 0f)
    }

    @Test
    fun `hours label is reduced so it still fits the chip width`() {
        assertEquals(TimerFontSizeSpCompact, timerFontSizeSpFor("1:23:45"), 0f)
    }

    @Test
    fun `pre start placeholder keeps the full size`() {
        // The view model shows this dashed placeholder until the timer first starts
        assertEquals(TimerFontSizeSpDefault, timerFontSizeSpFor("‒ ‒:‒ ‒"), 0f)
    }

    @Test
    fun `size steps exactly once at the one hour boundary`() {
        assertEquals(TimerFontSizeSpDefault, timerFontSizeSpFor("59:59"), 0f)
        assertEquals(TimerFontSizeSpCompact, timerFontSizeSpFor("1:00:00"), 0f)
    }

    @Test
    fun `empty label does not crash and keeps the full size`() {
        assertEquals(TimerFontSizeSpDefault, timerFontSizeSpFor(""), 0f)
    }

    @Test
    fun `compact size is smaller than the default size`() {
        assert(TimerFontSizeSpCompact < TimerFontSizeSpDefault)
    }
}

class OverlayTimerFieldWidthTest {

    @Test
    fun `docked left the timer takes the same fixed width as the metric chips`() {
        assertEquals(MinimizedChipWidthDp, timerFieldFixedWidthDpFor(OverlayLocation.Left))
    }

    @Test
    fun `docked right the timer takes the same fixed width as the metric chips`() {
        assertEquals(MinimizedChipWidthDp, timerFieldFixedWidthDpFor(OverlayLocation.Right))
    }

    @Test
    fun `docked top the timer sizes to its content`() {
        assertNull(timerFieldFixedWidthDpFor(OverlayLocation.Top))
    }

    @Test
    fun `docked bottom the timer sizes to its content`() {
        assertNull(timerFieldFixedWidthDpFor(OverlayLocation.Bottom))
    }

    @Test
    fun `every vertical location is pinned and no horizontal one is`() {
        // Guards the rule rather than the four cases, so a new location can't silently
        // fall through to content sizing and start widening the docked tab mid-ride.
        OverlayLocation.values().forEach { location ->
            assertEquals(
                "unexpected width rule for $location",
                location.isVertical,
                timerFieldFixedWidthDpFor(location) != null
            )
        }
    }

    @Test
    fun `the compact hours label fits the pinned width`() {
        // 1:23:45 is five Roboto digits (1150/2048 em each) and two colons (496/2048 em),
        // i.e. 3.292em; at the compact size that is 56.0dp inside a 58dp field.
        val widthDp = 3.292f * timerFontSizeSpFor("1:23:45")
        assert(widthDp <= MinimizedChipWidthDp) { "1:23:45 measures ${widthDp}dp" }
    }
}
