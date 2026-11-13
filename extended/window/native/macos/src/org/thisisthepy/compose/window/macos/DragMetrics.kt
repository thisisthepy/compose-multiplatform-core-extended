package org.thisisthepy.compose.window.macos

/**
 * The arithmetic behind the lines the DXC_METRICS run prints for its drag made of mouse
 * events. Kept apart from the window so a test can cover it without a screen.
 */
internal object DragMetrics {
    /** Points the window grew by between the start of the drag and its last position. */
    fun widenedBy(startWidth: Double, widthAtLastPosition: Double): Int =
        (widthAtLastPosition - startWidth).toInt()

    /** The line the compose-rust check reads the drag's result from. */
    fun eventDragLine(widenedBy: Int, events: Int): String =
        "compose-rust: metrics event-drag widened_by=$widenedBy events=$events"

    /** Colour of the 4 pixel block at block coordinates (bx, by) in the check pattern. */
    fun blockIsWhite(bx: Int, by: Int): Boolean =
        (((bx * 73856093) xor (by * 19349663)) ushr 4) and 1 == 1

    /** Fraction of compared pixels that agreed; nothing compared counts as no match. */
    fun matchFraction(same: Long, total: Long): Double =
        if (total == 0L) 0.0 else same.toDouble() / total

    /** A match rounded to four decimals, as the capture line prints it. */
    fun format(value: Double): String = (kotlin.math.round(value * 10000) / 10000).toString()

    /** The capture line: `match=` is what the compose-rust check bounds at 0.99. */
    fun captureLine(atEvent: Int, width: Int, height: Int, imageScale: Double, backingScale: Double, match: Double): String =
        "compose-rust: metrics capture at=$atEvent window=${width}x$height " +
            "image_scale=${format(imageScale)} backing_scale=${format(backingScale)} match=${format(match)}"
}
