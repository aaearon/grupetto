package com.spop.poverlay.overlay

import android.view.Gravity

/**
 * The screen edge the overlay is docked to.
 *
 * Absolute [Gravity.LEFT]/[Gravity.RIGHT] are used rather than START/END:
 * WindowManagerService does not resolve the relative-direction bit for overlay
 * windows, and docking here is physical - it follows a finger, not a layout direction.
 *
 * All values are compile-time constants, so this enum loads in plain JVM unit tests.
 */
enum class OverlayLocation(val gravity: Int) {
    Top(Gravity.TOP or Gravity.CENTER_HORIZONTAL),
    Bottom(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
    Left(Gravity.LEFT or Gravity.CENTER_VERTICAL),
    Right(Gravity.RIGHT or Gravity.CENTER_VERTICAL);

    /** True when the overlay runs down a side of the screen instead of across it. */
    val isVertical get() = this == Left || this == Right

    /** Sign the content moves along its axis to slide off screen when minimized. */
    val hideSign get() = if (this == Top || this == Left) -1 else +1
}
