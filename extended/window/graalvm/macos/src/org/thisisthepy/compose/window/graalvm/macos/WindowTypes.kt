package org.thisisthepy.compose.window.graalvm.macos

/** The size of the drawable in pixels, and how many of them go to a point. */
data class WindowMeasurement(val width: Int, val height: Int, val scale: Float)

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

/** One record the window wrote down since the last time the queue was drained. */
data class WindowEvent(
    val kind: Int,
    val x: Float,
    val y: Float,
    val buttons: Int,
    val modifiers: Int,
    val keyCode: Int,
    val codePoint: Int,
    val text: String,
) {
    companion object {
        const val POINTER_MOVE = 1
        const val POINTER_DOWN = 2
        const val POINTER_UP = 3
        const val SCROLL = 4
        const val KEY_DOWN = 5
        const val KEY_UP = 6
        const val TEXT_COMMIT = 7
        const val TEXT_COMPOSE = 8
        const val RESIZE = 9
        const val FILES_ENTERED = 10
        const val FILES_DROPPED = 11
        const val FILES_EXITED = 12
    }
}
