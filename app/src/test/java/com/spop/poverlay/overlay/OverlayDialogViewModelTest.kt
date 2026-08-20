package com.spop.poverlay.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import android.view.WindowManager.LayoutParams
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDialogViewModelTest {

    private val minimized = MutableStateFlow(false)
    private val viewModel = OverlayDialogViewModel(Size(1000f, 600f), minimized)

    private fun layout(expandedWidth: Int = 600, expandedHeight: Int = 110) {
        viewModel.onOverlayLayout(IntSize(expandedWidth, expandedHeight))
        viewModel.onTimerOverlayLayout(IntSize(300, 30))
    }

    @Test
    fun `overlay layout records both dimensions`() {
        layout()

        assertEquals(600 to 110, viewModel.dialogSizeParams.value)
    }

    @Test
    fun `timer layout records its own measured height rather than the expanded one`() {
        layout()

        assertEquals(300 to 30, viewModel.minimizedDialogSizeParams.value)
    }

    @Test
    fun `dragging sideways moves the origin and keeps the location`() {
        layout()

        val result = viewModel.processDrag(accumulatedX = 100f, accumulatedY = 0f)

        assertEquals(OverlayLocation.Bottom, viewModel.dialogLocation.value)
        assertEquals(Offset(100f, 0f), viewModel.dialogOrigin.value)
        assertEquals(100f, result.accumulatedX, 0f)
    }

    @Test
    fun `dragging far enough sideways docks the overlay to a side and resets the origin`() {
        layout()

        // Slide clamp is (1000 - 600) / 2 = 200, plus a 500px overshoot
        val result = viewModel.processDrag(accumulatedX = -750f, accumulatedY = 0f)

        assertEquals(OverlayLocation.Left, viewModel.dialogLocation.value)
        assertEquals(Offset.Zero, viewModel.dialogOrigin.value)
        assertEquals(0f, result.accumulatedX, 0f)
        assertEquals(0f, result.accumulatedY, 0f)
    }

    @Test
    fun `dragging down from the top docks the overlay to the bottom`() {
        layout()
        viewModel.processDrag(accumulatedX = 0f, accumulatedY = -400f)

        assertEquals(OverlayLocation.Top, viewModel.dialogLocation.value)

        viewModel.processDrag(accumulatedX = 0f, accumulatedY = 400f)

        assertEquals(OverlayLocation.Bottom, viewModel.dialogLocation.value)
    }

    @Test
    fun `the slide range follows the minimized width once the overlay is minimized`() {
        layout()
        minimized.value = true

        // Minimized width is 300, so the clamp is (1000 - 300) / 2 = 350
        viewModel.processDrag(accumulatedX = 500f, accumulatedY = 0f)

        assertEquals(350f, viewModel.dialogOrigin.value.x, 0f)
        assertEquals(OverlayLocation.Bottom, viewModel.dialogLocation.value)
    }

    @Test
    fun `hiding reports a touch target extent and marks the overlay untouchable`() {
        viewModel.processHideProgress(hiddenExtent = -110f, totalExtent = 140f)

        assertEquals(
            30f + OverlayService.HiddenTouchTargetMarginPx,
            viewModel.touchTargetExtent.value,
            0f
        )
        assertEquals(LayoutParams.FLAG_NOT_TOUCHABLE, viewModel.partialOverlayFlags.value)
    }

    @Test
    fun `a fully shown overlay has no touch target`() {
        viewModel.processHideProgress(hiddenExtent = 0f, totalExtent = 140f)

        assertEquals(0f, viewModel.touchTargetExtent.value, 0f)
        assertEquals(0, viewModel.partialOverlayFlags.value)
    }

    // --- Gesture seeding (drag start) ---------------------------------------
    // A Bike-sized screen: 1920x1080 with a 1118px wide overlay gives the 401px
    // slide clamp and the 540px vertical flip threshold measured on hardware.
    private val bikeMinimized = MutableStateFlow(false)
    private val bikeViewModel = OverlayDialogViewModel(Size(1920f, 1080f), bikeMinimized)

    private fun bikeLayout() {
        bikeViewModel.onOverlayLayout(IntSize(1118, 110))
        bikeViewModel.onTimerOverlayLayout(IntSize(300, 30))
    }

    /** Replays a whole gesture: seed from the drag start, then apply the deltas. */
    private fun gesture(
        viewModel: OverlayDialogViewModel,
        vararg deltas: Pair<Float, Float>
    ): OverlayDragResult {
        var (x, y) = viewModel.onDragStart()
        var result = OverlayDragResult(viewModel.dialogLocation.value, 0f, 0f, x, y)
        for ((dx, dy) in deltas) {
            x += dx
            y += dy
            result = viewModel.processDrag(x, y)
            x = result.accumulatedX
            y = result.accumulatedY
        }
        return result
    }

    @Test
    fun `a new gesture continues from the current origin instead of jumping to center`() {
        bikeLayout()
        gesture(bikeViewModel, -175f to 0f)
        assertEquals(-175f, bikeViewModel.dialogOrigin.value.x, 0f)

        // A fresh 30px nudge further left must land at -205, not near 0
        gesture(bikeViewModel, -30f to 0f)

        assertEquals(-205f, bikeViewModel.dialogOrigin.value.x, 0f)
        assertEquals(OverlayLocation.Bottom, bikeViewModel.dialogLocation.value)
    }

    @Test
    fun `successive gestures accumulate until the slide clamp is reached`() {
        bikeLayout()

        gesture(bikeViewModel, -200f to 0f)
        assertEquals(-200f, bikeViewModel.dialogOrigin.value.x, 0f)

        gesture(bikeViewModel, -200f to 0f)
        assertEquals(-400f, bikeViewModel.dialogOrigin.value.x, 0f)

        gesture(bikeViewModel, -200f to 0f)

        // Clamped at (1920 - 1118) / 2 = 401 and still docked bottom
        assertEquals(-401f, bikeViewModel.dialogOrigin.value.x, 0f)
        assertEquals(OverlayLocation.Bottom, bikeViewModel.dialogLocation.value)
    }

    @Test
    fun `an overshoot from the clamp in a later gesture still docks to the left`() {
        bikeLayout()
        gesture(bikeViewModel, -500f to 0f)
        assertEquals(-401f, bikeViewModel.dialogOrigin.value.x, 0f)

        // Threshold is 1920 * .2 = 384 beyond the 401 clamp
        val result = gesture(bikeViewModel, -400f to 0f)

        assertEquals(OverlayLocation.Left, bikeViewModel.dialogLocation.value)
        assertEquals(Offset.Zero, bikeViewModel.dialogOrigin.value)
        assertEquals(0f, result.accumulatedX, 0f)
        assertEquals(0f, result.accumulatedY, 0f)
    }

    @Test
    fun `a redock is not resurrected by the next gestures seed`() {
        bikeLayout()
        gesture(bikeViewModel, -900f to 0f)
        assertEquals(OverlayLocation.Left, bikeViewModel.dialogLocation.value)

        assertEquals(0f to 0f, bikeViewModel.onDragStart())
    }

    @Test
    fun `a gesture while docked to a side seeds zero and does not slide`() {
        bikeLayout()
        gesture(bikeViewModel, -900f to 0f)
        assertEquals(OverlayLocation.Left, bikeViewModel.dialogLocation.value)

        assertEquals(0f to 0f, bikeViewModel.onDragStart())

        gesture(bikeViewModel, 50f to 0f)

        assertEquals(Offset.Zero, bikeViewModel.dialogOrigin.value)
        assertEquals(OverlayLocation.Left, bikeViewModel.dialogLocation.value)
    }

    @Test
    fun `the vertical accumulator is not carried across gestures`() {
        bikeLayout()

        // 400px is short of the 540px flip threshold
        gesture(bikeViewModel, 0f to 400f)
        assertEquals(OverlayLocation.Bottom, bikeViewModel.dialogLocation.value)

        assertEquals(0f, bikeViewModel.onDragStart().second, 0f)

        // A further 200px would trip 540 if it were carried over
        gesture(bikeViewModel, 0f to 200f)

        assertEquals(OverlayLocation.Bottom, bikeViewModel.dialogLocation.value)
    }
}
