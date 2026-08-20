package com.spop.poverlay.util

import android.os.Build
import kotlin.math.pow
import kotlin.math.sqrt

private const val PelotonBrand = "Peloton"

val IsRunningOnPeloton by lazy { Build.BRAND == PelotonBrand }

/**
 * Check if the device is a G700 CrossTrainer bike.
 * The G700 uses a different sensor interface than the regular Bike+.
 */
/** G700 model strings include either legacy "G700" or newer "PLTN-ATR" prefixes. */
internal fun isG700CrossTrainerModel(model: String): Boolean {
    return model.contains("G700", ignoreCase = true) || model.startsWith("PLTN-ATR", ignoreCase = true)
}

val IsG700CrossTrainer by lazy { isG700CrossTrainerModel(Build.MODEL) }

/**
 * Check if the device is a Peloton Tread by model string.
 *
 * This is the reliable platform signal: Peloton's decompiled `PlatformConstants` maps
 * the Prism Tread to `TABLET_TOPAZ = "PLTN-TTR01"` and `TABLET_TOPAZ2 = "PLTN-TTR01-2"`,
 * distinct from the Bike v1 (`PLTN-RB1VQ`) and Bike+ (`g700`). Binding `ITreadInterface`
 * is NOT a reliable signal: `AffernetService` returns a non-null binder for interfaces
 * regardless of the actual platform (a bike returns non-null for `ITreadInterface` too),
 * so a bind-probe misdetects a bike as a Tread.
 *
 * Matches both Tread variants ("PLTN-TTR01" covers "PLTN-TTR01-2"), case-insensitive.
 */
internal fun isTreadModel(model: String): Boolean {
    return model.startsWith("PLTN-TTR01", ignoreCase = true)
}

val IsTread by lazy { isTreadModel(Build.MODEL) }

/**
 * All Peloton bikes start with model "PLTN-T". Treadmills start with "PLTN-TR", so this might also
 * apply to them, but it will be interesting if anything works on them here.
 * Note: G700 is handled separately.
 */
val IsBikePlus by lazy { Build.MODEL.contains("PLTN-T") }


fun calculateSpeedFromPelotonV1Power(power: Float) =
        if (power < 0.1f) {
            0f
        } else {
            // https://ihaque.org/posts/2020/12/25/pelomon-part-ib-computing-speed/
            val pwrSqrt = sqrt(power)
            if (power < 26f) {
                0.057f - (0.172f * pwrSqrt) + (0.759f * pwrSqrt.pow(2)) - (0.079f * pwrSqrt.pow(3))
            } else {
                -1.635f + (2.325f * pwrSqrt) - (0.064f * pwrSqrt.pow(2)) + (0.001f * pwrSqrt.pow(3))
            }
        }
