package com.spop.poverlay.overlay

/**
 * Pure sizing rule for the minimized overlay's timer label. Android-free so it can be unit
 * tested on the JVM; the composable only turns the returned value into an `sp`.
 *
 * The minimized metric chips are 58dp wide, and when the overlay is docked left or right the
 * chips stack in a Column whose width is the widest child. A timer that outgrew 58dp would
 * therefore widen the whole tab mid-ride, so the long `H:MM:SS` form has to shrink instead.
 *
 * Measured against Roboto Regular (the overlay renders in Roboto, not Lato, because
 * OverlayService's setContent never wraps the composition in PTONOverlayTheme, so Material2's
 * default TextStyle applies). Roboto's digits all advance 1150/2048 em and ':' advances
 * 496/2048, so `1:23:45` (five digits, two colons) is 3.292em wide:
 *   19sp -> 62.5dp (overflows 58dp), 17sp -> 56.0dp (fits, max fitting size is 17.6sp).
 * Everything shorter - `M:SS`, `MM:SS` and the dashed pre-start placeholder (2.99em, 57.5dp
 * at 19sp) - already fits at the full size.
 */
const val TimerFontSizeSpDefault = 19f

/** Largest whole sp at which `H:MM:SS` still fits the 58dp chip width. */
const val TimerFontSizeSpCompact = 17f

/**
 * `DateUtils.formatElapsedTime` only emits a second colon once the elapsed time reaches an
 * hour, so the colon count is exactly the H:MM:SS predicate. Keying off the label's shape
 * rather than its length matters: the dashed pre-start placeholder is seven characters long
 * but only 57.5dp wide at 19sp, so it must keep the full size. Keying off the label rather
 * than the elapsed seconds means the size changes exactly once - at the one hour mark - and
 * never per tick.
 */
private const val HoursLabelColonCount = 2

/**
 * @param label the formatted timer label, as produced by `DateUtils.formatElapsedTime`, or the
 *   view model's dashed pre-start placeholder.
 * @return the font size in sp for that label.
 */
fun timerFontSizeSpFor(label: String): Float =
    if (label.count { it == ':' } >= HoursLabelColonCount) {
        TimerFontSizeSpCompact
    } else {
        TimerFontSizeSpDefault
    }

/**
 * Width of a minimized metric chip, in dp. The timer borrows it when docked to a side so both
 * overlay states present the same column width.
 */
const val MinimizedChipWidthDp = 58f

/**
 * Width rule for the minimized overlay's timer field.
 *
 * Docked left or right the children stack in a Column whose width is its widest child. Minimized,
 * the 58dp chips pin that width; expanded there are no chips, so a content-sized timer would set
 * it and the tab would visibly step wider at the one hour mark. Pinning the timer to the chip
 * width makes the docked tab a constant width in both states, for any ride length.
 *
 * Docked top or bottom the children run across a Row that is already far wider than its content,
 * so the timer is left to size to itself.
 *
 * @return the fixed width in dp, or null when the field should size to its content.
 */
fun timerFieldFixedWidthDpFor(location: OverlayLocation): Float? =
    if (location.isVertical) MinimizedChipWidthDp else null
