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
