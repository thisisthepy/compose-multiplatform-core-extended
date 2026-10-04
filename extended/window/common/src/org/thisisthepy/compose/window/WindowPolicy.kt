package org.thisisthepy.compose.window

/**
 * The decisions the platform layers do not make for themselves.
 *
 * A platform layer asks the policy and applies the answer. Nothing here knows how a
 * window is created; it knows what a window is allowed to be.
 */
interface WindowPolicy {
    /**
     * The smallest size, in points, the window may be given. The content decides how small
     * it can usefully be, so the platform layer applies this to the OS rather than
     * inventing a number of its own.
     */
    fun minimumSize(): WindowSize

    /** Clamps a requested size to [minimumSize]. */
    fun clamp(requested: WindowSize): WindowSize {
        val minimum = minimumSize()
        return WindowSize(
            requested.width.coerceAtLeast(minimum.width),
            requested.height.coerceAtLeast(minimum.height),
        )
    }
}

/** A size in points. */
data class WindowSize(val width: Int, val height: Int)

/** One entry of a context menu. [id] comes back in [WindowListener.onContextMenuChosen]. */
data class ContextMenuItem(
    val id: Int,
    val label: String,
    val enabled: Boolean = true,
    val separatorAfter: Boolean = false,
)
