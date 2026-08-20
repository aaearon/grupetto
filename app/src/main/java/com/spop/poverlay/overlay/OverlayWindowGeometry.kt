package com.spop.poverlay.overlay

import android.view.ViewGroup.LayoutParams.MATCH_PARENT

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
 * Docked to a side the overlay is full height and minimizing changes its *width*,
 * so the along-axis and cross-axis roles of every measurement swap. The touch target
 * always takes [touchExtent] along the axis the overlay slides off on.
 *
 * Sizes are width to height. [MATCH_PARENT] is a compile-time constant, so this
 * function stays JVM-testable.
 */
fun overlayWindowGeometry(
    location: OverlayLocation,
    isMinimized: Boolean,
    expandedSize: Pair<Int, Int>,
    minimizedSize: Pair<Int, Int>,
    touchExtent: Int
): OverlayWindowGeometry {
    val (expandedWidth, expandedHeight) = expandedSize
    val (minimizedWidth, minimizedHeight) = minimizedSize
    return if (location.isVertical) {
        OverlayWindowGeometry(
            overlayWidth = if (isMinimized) minimizedWidth else expandedWidth,
            overlayHeight = MATCH_PARENT,
            touchWidth = touchExtent,
            touchHeight = minimizedHeight,
            touchTargetVisible = touchExtent > 0
        )
    } else {
        OverlayWindowGeometry(
            overlayWidth = expandedWidth,
            overlayHeight = if (isMinimized) minimizedHeight else expandedHeight,
            touchWidth = minimizedWidth,
            touchHeight = touchExtent,
            touchTargetVisible = touchExtent > 0
        )
    }
}
