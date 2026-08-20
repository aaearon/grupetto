package com.spop.poverlay.overlay

import kotlin.math.abs

/**
 * Pure drag state machine for the overlay window. Android-free so it can be unit tested
 * on the JVM; [OverlayDialogViewModel] is a thin adapter over it.
 */
data class OverlayDragResult(
    val location: OverlayLocation,
    val originX: Float,
    val originY: Float,
    val accumulatedX: Float,
    val accumulatedY: Float
)

// If the overlay is dragged within this range of pixels from the center of the screen
// snap to the center of the screen instead
private val HorizontalDragSnapRange = -20f..20f

/**
 * Maps the accumulated drag distance onto a dock location and a window origin.
 *
 * The accumulators are always returned raw (never clamped) unless a redock resets them:
 * clamping them would cap the horizontal accumulator at the slide range and make the
 * sideways-overshoot redock unreachable.
 *
 * @param slideRange how far the window may slide along x while docked top or bottom
 */
fun processOverlayDrag(
    location: OverlayLocation,
    accumulatedX: Float,
    accumulatedY: Float,
    screenWidth: Float,
    screenHeight: Float,
    slideRange: ClosedFloatingPointRange<Float>
): OverlayDragResult {
    val verticalThreshold = screenHeight * OverlayService.VerticalMoveDragThreshold
    val horizontalThreshold = screenWidth * OverlayService.HorizontalMoveDragThreshold

    fun redock(to: OverlayLocation) = OverlayDragResult(to, 0f, 0f, 0f, 0f)

    if (location.isVertical) {
        // The window is already full height, so there is nothing to slide along:
        // both axes only ever redock.
        if (abs(accumulatedX) > horizontalThreshold) {
            return redock(if (accumulatedX < 0) OverlayLocation.Left else OverlayLocation.Right)
        }
        if (abs(accumulatedY) > verticalThreshold) {
            return redock(if (accumulatedY < 0) OverlayLocation.Top else OverlayLocation.Bottom)
        }
        return OverlayDragResult(location, 0f, 0f, accumulatedX, accumulatedY)
    }

    if (abs(accumulatedY) > verticalThreshold) {
        return redock(if (location == OverlayLocation.Bottom) OverlayLocation.Top else OverlayLocation.Bottom)
    }

    // Overshooting the slide clamp by half a screen docks the overlay to that side
    val overshoot = abs(accumulatedX) - slideRange.endInclusive
    if (overshoot > horizontalThreshold) {
        return redock(if (accumulatedX < 0) OverlayLocation.Left else OverlayLocation.Right)
    }

    val originX = when {
        // Near the center of the screen, snap to center
        HorizontalDragSnapRange.contains(accumulatedX) -> 0f
        // The overlay still fits on screen, follow the gesture
        slideRange.contains(accumulatedX) -> accumulatedX
        // The overlay would go off screen, pin it to the screen bounds
        else -> accumulatedX.coerceIn(slideRange)
    }
    return OverlayDragResult(location, originX, 0f, accumulatedX, accumulatedY)
}
