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
}
