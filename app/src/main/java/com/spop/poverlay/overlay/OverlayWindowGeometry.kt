package com.spop.poverlay.overlay

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The four window dimensions the overlay service applies, plus whether the hidden
 * touch target should be shown.
 */
data class OverlayWindowGeometry(
    val overlayWidth: Int,
    val overlayHeight: Int,
    val touchWidth: Int,
    val touchHeight: Int,
    val touchTargetVisible: Boolean
)

/**
 * Maps the measured content sizes onto window params for the current dock location.
 *
 * The cross-axis extent is always WRAP_CONTENT: the window has to fit the main content
 * *and* the timer bar beside it, and only the main content reports a measured size.
 * Pinning it to that measurement clips the timer bar off the screen. Minimizing does not
 * shrink the window - the main content slides out under an offset, which does not change
 * what the window measures - the hidden touch target covers the collapsed state instead.
 *
 * Docked to a side the overlay is full height, so the along-axis and cross-axis roles of
 * every measurement swap. The touch target always takes [touchExtent] along the axis the
 * overlay slides off on.
 *
 * Sizes are width to height. [MATCH_PARENT] is a compile-time constant, so this
 * function stays JVM-testable.
 */
fun overlayWindowGeometry(
    location: OverlayLocation,
    expandedSize: Pair<Int, Int>,
    minimizedSize: Pair<Int, Int>,
    touchExtent: Int
): OverlayWindowGeometry {
    val (expandedWidth, _) = expandedSize
    val (minimizedWidth, minimizedHeight) = minimizedSize
    return if (location.isVertical) {
        OverlayWindowGeometry(
            overlayWidth = WRAP_CONTENT,
            overlayHeight = MATCH_PARENT,
            touchWidth = touchExtent,
            touchHeight = minimizedHeight,
            touchTargetVisible = touchExtent > 0
        )
    } else {
        OverlayWindowGeometry(
            overlayWidth = expandedWidth,
            overlayHeight = WRAP_CONTENT,
            touchWidth = minimizedWidth,
            touchHeight = touchExtent,
            touchTargetVisible = touchExtent > 0
        )
    }
}
