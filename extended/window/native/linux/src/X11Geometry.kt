package org.thisisthepy.compose.window.linux

import kotlin.math.roundToInt
import org.thisisthepy.compose.window.WindowBounds

/**
 * The window manager's frame around a window's content, in pixels, as `_NET_FRAME_EXTENTS` gives
 * it. A window with no frame, or a manager that does not say, has [NONE].
 */
internal data class FrameExtents(val left: Int, val right: Int, val top: Int, val bottom: Int) {
    companion object {
        val NONE = FrameExtents(0, 0, 0, 0)
    }
}

/**
 * Where the content window goes so that its outer frame has its top left corner at
 * ([outerX], [outerY]). A move of the content window is a move of the frame by the same amount.
 */
internal fun clientOriginFor(outerX: Int, outerY: Int, extents: FrameExtents): Pair<Int, Int> =
    (outerX + extents.left) to (outerY + extents.top)

/** The outer frame of a content window of [width] by [height] whose top left corner is at ([clientX], [clientY]). */
internal fun outerBoundsOf(clientX: Int, clientY: Int, width: Int, height: Int, extents: FrameExtents): WindowBounds =
    WindowBounds(
        clientX - extents.left,
        clientY - extents.top,
        width + extents.left + extents.right,
        height + extents.top + extents.bottom,
    )

/**
 * Where the content window goes so that the content point ([anchorX], [anchorY]), in points
 * from its top left, is under the pointer at ([pointerX], [pointerY]) screen points.
 */
internal fun followOrigin(
    pointerX: Float,
    pointerY: Float,
    anchorX: Float,
    anchorY: Float,
    density: Float,
): Pair<Int, Int> = ((pointerX - anchorX) * density).roundToInt() to ((pointerY - anchorY) * density).roundToInt()

/** Whether [keycode] is down in the 32 bytes `XQueryKeymap` fills: one bit per key, key 0 the lowest bit of byte 0. */
internal fun keyIsDown(keymap: ByteArray, keycode: Int): Boolean {
    if (keycode < 0 || keycode ushr 3 >= keymap.size) return false
    return (keymap[keycode ushr 3].toInt() and (1 shl (keycode and 7))) != 0
}
