package org.thisisthepy.compose.window.graalvm.macos

/** The title bar strip and the room the window buttons take at the leading edge, in points. */
data class CaptionMetrics(val height: Float, val buttonsWidth: Float)

/**
 * One thing the window says it has, for a reader who cannot see it.
 *
 * [role] is one of [ElementRole]; the geometry is in points.
 */
data class AccessibleElement(
    val role: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
)

object ElementRole {
    const val GROUP = 0
    const val BUTTON = 1
    const val TEXT = 2
    const val FIELD = 3
    const val CHECKBOX = 4
    const val IMAGE = 5
}

/**
 * What a macOS title bar's size is worked out from, in points: the window's frame height,
 * the height of the part below the bar, where the close button starts and where the zoom
 * button ends (null where the window has no buttons), and the radius the system draws on
 * the window's corners, from the table keyed by style and release (null where it has none).
 */
data class TitleBarMetrics(
    val windowHeight: Float,
    val contentLayoutHeight: Float,
    val closeMinX: Float?,
    val zoomMaxX: Float?,
    val cornerRadius: Float? = null,
) {
    /**
     * The caption these measure, or null while the window is changing size: the frame and
     * the layout rect are updated at different moments and the difference can be negative
     * for an instant.
     */
    fun caption(): CaptionMetrics? {
        val height = windowHeight - contentLayoutHeight
        if (height < 0f) return null
        val width = if (closeMinX == null || zoomMaxX == null) 0f else zoomMaxX + closeMinX
        if (width < 0f) return null
        return CaptionMetrics(height, width)
    }
}
