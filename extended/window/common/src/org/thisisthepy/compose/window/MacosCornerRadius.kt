package org.thisisthepy.compose.window

/**
 * One row of [MACOS_CORNER_RADII]: the corner radius, in points, macOS gives a window
 * without a toolbar ([simple]) and one with a unified toolbar ([toolbar]), from release
 * [fromMajor] until the next row.
 */
data class MacosCornerRadiusRow(val fromMajor: Int, val simple: Double, val toolbar: Double)

/**
 * The corner radius macOS draws on a window, by title bar style and release.
 *
 * AppKit has no public API that reports a window's corner radius. The value can be read
 * under a private key, but the Mac App Store rejects an application that does, and a
 * private key can change or vanish in any update. So both macOS windows, the
 * Kotlin/Native one and the GraalVM one, take it from this table instead.
 *
 * A release newer than the last row gets the last row. When a new release draws a
 * different corner, the corner check in CI (scripts/check-macos-corner-radius.sh), which
 * measures the corner the system actually drew on screen, fails against this table.
 * Keep each row on one line in this form: that script reads it.
 *
 * How the values are measured (scripts/check-macos-corner-radius.sh, in CI on the GitHub
 * runners macos-14, macos-15 and macos-latest, which is macOS 26): a window of each style is
 * captured with its shadow off, and its two bottom corners are fitted pixel for pixel, by
 * least squares, against reference corners cut into a bare layer, tried in quarter-point
 * steps with both the circular and the continuous corner curve. The radius of the best fit
 * is the value here.
 *
 * Evidence per row: macOS 14 and 15 measured, a circular curve at 10.25pt in both styles.
 * macOS 26 measured, a continuous curve at 16.75pt without a toolbar and 26.75pt with one.
 * macOS 11 to 13 are unmeasured: no runner offers them, so their row repeats the macOS 14
 * and 15 value.
 */
val MACOS_CORNER_RADII: List<MacosCornerRadiusRow> = listOf(
    MacosCornerRadiusRow(fromMajor = 11, simple = 10.25, toolbar = 10.25),
    MacosCornerRadiusRow(fromMajor = 14, simple = 10.25, toolbar = 10.25),
    MacosCornerRadiusRow(fromMajor = 26, simple = 16.75, toolbar = 26.75),
)

/**
 * The corner radius macOS [macosMajor] draws on a window with a unified toolbar
 * ([toolbar]) or without one, in points, or null for a release older than the table,
 * whose windows are not rounded in a way this table describes.
 */
fun macosCornerRadius(toolbar: Boolean, macosMajor: Int): Double? {
    val row = MACOS_CORNER_RADII.lastOrNull { macosMajor >= it.fromMajor } ?: return null
    return if (toolbar) row.toolbar else row.simple
}
