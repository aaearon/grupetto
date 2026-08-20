package com.spop.poverlay.overlay.composables

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.R
import com.spop.poverlay.overlay.BackgroundColorDefault
import com.spop.poverlay.overlay.OverlayLocation
import com.spop.poverlay.overlay.timerFieldFixedWidthDpFor
import com.spop.poverlay.overlay.timerFontSizeSpFor


@Composable
fun OverlayMinimizedContent(
    isMinimized: Boolean,
    showTimerWhenMinimized: Boolean,
    location: OverlayLocation,
    isTread: Boolean,
    powerLabel: String,
    cadenceLabel: String,
    speedLabel: String,
    resistanceLabel: String,
    inclineLabel: String,
    heartRateLabel: String,
    showPowerField: Boolean,
    showCadenceField: Boolean,
    showResistanceField: Boolean,
    showInclineField: Boolean,
    contentAlpha: Float,
    timerLabel: String,
    timerPaused: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onOpenSettings: () -> Unit,
    onMinimizeToggle: () -> Unit,
    onLayout: (IntSize) -> Unit
) {
    val backgroundShape = if (isMinimized) {
        RoundedCornerShape(8.dp)
    } else {
        when (location) {
            OverlayLocation.Top -> RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
            OverlayLocation.Bottom -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            OverlayLocation.Left -> AbsoluteRoundedCornerShape(topRight = 8.dp, bottomRight = 8.dp)
            OverlayLocation.Right -> AbsoluteRoundedCornerShape(topLeft = 8.dp, bottomLeft = 8.dp)
        }
    }
    val expandedVerticalPadding = if (isMinimized) {
        1.dp
    } else {
        0.dp
    }
    val size = remember { mutableStateOf(IntSize.Zero) }

    val containerModifier = Modifier
            .alpha(contentAlpha)
            .wrapContentSize().onSizeChanged {
                if (it.width != size.value.width || it.height != size.value.height) {
                    size.value = it
                    onLayout(size.value)
                }
            }
            .padding(vertical = expandedVerticalPadding)
            .background(
                color = BackgroundColorDefault,
                shape = backgroundShape,
            )
            .padding(horizontal = 10.dp)
            .padding(top = 1.dp)
            .animateContentSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onTap()
                    },
                    onLongPress = {
                        onLongPress()
                    }
                )
            }

    val content = @Composable {
        val infiniteTransition = rememberInfiniteTransition()
        if (!isMinimized || showTimerWhenMinimized || timerPaused) {

            val timerAlpha = if (timerPaused) {
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                ).value
            } else {
                1f
            }

            // No icon, and docked to a side the field is pinned to the chip width so it can
            // never be the widest child of the vertical Column and widen the whole docked tab.
            // Docked top or bottom it still sizes to its content.
            val timerWidthDp = timerFieldFixedWidthDpFor(location)
            OverlayTimerField(
                modifier = Modifier
                    .then(
                        if (timerWidthDp != null) {
                            Modifier.width(timerWidthDp.dp)
                        } else {
                            Modifier.widthIn(min = 48.dp)
                        }
                    )
                    .alpha(timerAlpha),
                timerLabel = timerLabel,
                iconDrawable = null,
                fontSize = timerFontSizeSpFor(timerLabel).sp
            )
        }

        AxisSpacer(location, 6.dp)
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Open settings",
            tint = Color.White,
            modifier = Modifier
                .size(20.dp)
                .clickable { onOpenSettings() }
        )

        // Minimize/Maximize button
        AxisSpacer(location, 8.dp)
        Icon(
            // The chevron always points the way the main content will travel
            imageVector = if (isMinimized) {
                when (location) {
                    OverlayLocation.Top -> Icons.Filled.KeyboardArrowDown
                    OverlayLocation.Bottom -> Icons.Filled.KeyboardArrowUp
                    OverlayLocation.Left -> Icons.Filled.KeyboardArrowRight
                    OverlayLocation.Right -> Icons.Filled.KeyboardArrowLeft
                }
            } else {
                when (location) {
                    OverlayLocation.Top -> Icons.Filled.KeyboardArrowUp
                    OverlayLocation.Bottom -> Icons.Filled.KeyboardArrowDown
                    OverlayLocation.Left -> Icons.Filled.KeyboardArrowLeft
                    OverlayLocation.Right -> Icons.Filled.KeyboardArrowRight
                }
            },
            contentDescription = if (isMinimized) "Expand" else "Minimize",
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable { onMinimizeToggle() }
        )

        if (isMinimized) {
            if (showPowerField) {
                AxisSpacer(location, 4.dp)
                OverlayTimerField(
                    modifier = Modifier.width(58.dp),
                    timerLabel = powerLabel,
                    iconDrawable = R.drawable.ic_power
                )
            }
            if (showCadenceField) {
                AxisSpacer(location, 4.dp)
                OverlayTimerField(
                    modifier = Modifier.width(58.dp),
                    timerLabel = cadenceLabel,
                    iconDrawable = R.drawable.ic_cadence
                )
            }
            if (showResistanceField) {
                AxisSpacer(location, 4.dp)
                OverlayTimerField(
                    modifier = Modifier.width(58.dp),
                    timerLabel = resistanceLabel,
                    iconDrawable = R.drawable.ic_resistance
                )
            }
            // Reuses ic_speed for incline until a dedicated incline drawable is
            // added, matching the main content's incline card.
            val speedField = @Composable {
                AxisSpacer(location, 4.dp)
                OverlayTimerField(
                    modifier = Modifier.width(58.dp),
                    timerLabel = speedLabel,
                    iconDrawable = R.drawable.ic_speed
                )
            }
            val inclineField = @Composable {
                AxisSpacer(location, 4.dp)
                OverlayTimerField(
                    modifier = Modifier.width(58.dp),
                    timerLabel = inclineLabel,
                    iconDrawable = R.drawable.ic_speed
                )
            }
            if (isTread) {
                // Mirror the expanded HUD's left-to-right sense: incline before speed.
                if (showInclineField) {
                    inclineField()
                }
                speedField()
            } else {
                speedField()
                if (showInclineField) {
                    inclineField()
                }
            }
            AxisSpacer(location, 4.dp)
            OverlayTimerField(
                modifier = Modifier.width(58.dp),
                timerLabel = heartRateLabel,
                iconDrawable = R.drawable.ic_hrm
            )
        }
    }

    // Docked to a side the window is only as wide as one chip, so the same chips have
    // to stack instead of running off the edge.
    if (location.isVertical) {
        Column(
            modifier = containerModifier,
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    } else {
        Row(
            modifier = containerModifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

/** A spacer that separates the minimized chips along whichever axis they run on. */
@Composable
private fun AxisSpacer(location: OverlayLocation, size: androidx.compose.ui.unit.Dp) {
    if (location.isVertical) {
        Spacer(modifier = Modifier.height(size))
    } else {
        Spacer(modifier = Modifier.width(size))
    }
}

@Composable
private fun OverlayTimerField(
    modifier: Modifier,
    timerLabel: String,
    iconDrawable: Int?,
    fontSize: TextUnit = 19.sp,
) {
    Row(
        modifier = modifier
            .wrapContentHeight(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (iconDrawable != null) {
            Image(
                modifier = Modifier
                    .requiredHeight(20.dp)
                    .requiredWidth(16.dp)
                    .align(Alignment.CenterVertically)
                    .padding(vertical = 4.dp),
                painter = painterResource(id = iconDrawable),
                contentDescription = null,
            )
        }
        Text(
            timerLabel,
            color = Color.White,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            // A label wider than its box used to wrap onto a second line and double the
            // row height; clipping is far less disruptive than a jumping layout.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
        )
    }
}
