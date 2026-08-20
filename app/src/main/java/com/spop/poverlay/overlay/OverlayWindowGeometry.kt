package com.spop.poverlay.overlay

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
 * Docked to a side the along-axis and cross-axis roles of every measurement swap.
 *
 * Both extents are WRAP_CONTENT when docked to a side. The window used to be MATCH_PARENT
 * tall there, but the HUD only draws ~467dp of that, and FLAG_NOT_TOUCH_MODAL makes every
 * pixel inside the window swallow touches - so a full-height window killed a column of the
 * Peloton UI above and below the visible bar. [OverlayLocation.Left]/[OverlayLocation.Right]
 * already carry [android.view.Gravity.CENTER_VERTICAL], which centres a wrapped window in
 * exactly the place the full-height window drew its content.
 *
 * The touch target extends along the axis the overlay slides off on. [touchExtent] alone
 * only describes how much of the overlay is still on screen mid-slide plus a margin; once
 * the slide finishes it collapses to the margin, which does not reach the chevron drawn on
 * the minimized tab (78dp = 117px wide when docked to a side). So the along-axis extent is
 * at least the minimized content's own along-axis size plus that margin. A zero
 * [touchExtent] still means the overlay is expanded and the target must stay GONE, or it
 * would steal touches from the app underneath.
 *
 * Sizes are width to height. [WRAP_CONTENT] is a compile-time constant, so this
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
    val isTargetShown = touchExtent > 0
    val minimizedAlongAxis = if (location.isVertical) minimizedWidth else minimizedHeight
    val alongAxisExtent = if (isTargetShown) {
        maxOf(
            touchExtent,
            minimizedAlongAxis.coerceAtLeast(0) + OverlayService.HiddenTouchTargetMarginPx
        )
    } else {
        0
    }
    return if (location.isVertical) {
        OverlayWindowGeometry(
            overlayWidth = WRAP_CONTENT,
            overlayHeight = WRAP_CONTENT,
            touchWidth = alongAxisExtent,
            touchHeight = minimizedHeight,
            touchTargetVisible = isTargetShown
        )
    } else {
        OverlayWindowGeometry(
            overlayWidth = expandedWidth,
            overlayHeight = WRAP_CONTENT,
            touchWidth = minimizedWidth,
            touchHeight = alongAxisExtent,
            touchTargetVisible = isTargetShown
        )
    }
}
