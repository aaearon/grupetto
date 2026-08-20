package com.spop.poverlay.overlay

import android.view.WindowManager.LayoutParams
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Owns the overlay window's position and size state. All of the interesting logic lives
 * in the pure [processOverlayDrag] and [overlayWindowGeometry] functions; this class is
 * the flow-backed adapter over them.
 */
class OverlayDialogViewModel(
    private val screenSize: Size,
    private val isMinimized: StateFlow<Boolean>
) {

    val dialogOrigin = MutableStateFlow(Offset.Zero)

    // Defined as width to height
    val dialogSizeParams = MutableStateFlow(LayoutParams.WRAP_CONTENT to LayoutParams.WRAP_CONTENT)
    val minimizedDialogSizeParams =
        MutableStateFlow(LayoutParams.WRAP_CONTENT to LayoutParams.WRAP_CONTENT)
    val partialOverlayFlags = MutableStateFlow(0)
    val dialogLocation = MutableStateFlow(OverlayLocation.Bottom)

    /**
     * How far the touch target extends along the axis the overlay slides off on:
     * its height when docked top or bottom, its width when docked to a side.
     */
    val touchTargetExtent = MutableStateFlow(0f)

    // When overlay is hidden, an invisible touch target appears to accept touches:
    // - Touch target visibility is the opposite of the main view
    // - Overlay has FLAG_NOT_TOUCHABLE if it has started hiding
    // - Touch target extent should match the overlay's, plus margin for ease of use
    fun processHideProgress(hiddenExtent: Float, totalExtent: Float) {
        val remainingExtent = abs(totalExtent - (abs(hiddenExtent)))
        val isMinimizeDone = abs(hiddenExtent) > 0f

        if (isMinimizeDone) {
            touchTargetExtent.value = remainingExtent + OverlayService.HiddenTouchTargetMarginPx
        } else {
            touchTargetExtent.value = 0f
        }

        partialOverlayFlags.value = if (isMinimizeDone) {
            LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            0
        }
    }

    /**
     * Applies a drag to the window position and returns the new drag state so the caller
     * can carry the (raw, unclamped) accumulators forward.
     */
    fun processDrag(accumulatedX: Float, accumulatedY: Float): OverlayDragResult {
        val result = processOverlayDrag(
            location = dialogLocation.value,
            accumulatedX = accumulatedX,
            accumulatedY = accumulatedY,
            screenWidth = screenSize.width,
            screenHeight = screenSize.height,
            slideRange = horizontalDragScreenRange()
        )
        dialogLocation.value = result.location
        dialogOrigin.value = Offset(result.originX, result.originY)
        return result
    }

    fun onOverlayLayout(size: IntSize) {
        dialogSizeParams.value = size.width to size.height
    }

    fun onTimerOverlayLayout(size: IntSize) {
        minimizedDialogSizeParams.value = size.width to size.height
    }

    /**
     * How far the overlay may slide along x before it leaves the screen. Derived from
     * whichever content is currently displayed - expanded and minimized widths differ.
     */
    private fun horizontalDragScreenRange(): ClosedFloatingPointRange<Float> {
        val (expandedWidth, _) = dialogSizeParams.value
        val (minimizedWidth, _) = minimizedDialogSizeParams.value
        val displayedWidth = if (isMinimized.value) minimizedWidth else expandedWidth
        val dragRange = abs(ceil((screenSize.width - displayedWidth.coerceAtLeast(0)) / 2f))
        return -dragRange..dragRange
    }
}
