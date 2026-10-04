package org.thisisthepy.compose.window

/** The size of a window's drawable in pixels, and how many of them go to a point. */
data class WindowMeasurement(val width: Int, val height: Int, val scale: Float)

/** Where a window is and how large, in pixels. */
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** What a window is asked to be when it is created. Sizes are in points. */
data class WindowConfig(
    val title: String,
    val width: Int,
    val height: Int,
    val minWidth: Int = 0,
    val minHeight: Int = 0,
    val decorated: Boolean = true,
    val transparent: Boolean = false,
)

/** The system's light or dark setting. */
enum class SystemTheme { Light, Dark }

/** How the window is shown: its place in the lifecycle. */
enum class WindowVisibility { Hidden, Visible, Minimized, Fullscreen }

/**
 * The record every presented frame leaves behind.
 *
 * During a resize every frame on screen must be drawn at the size the window has at that
 * moment. A frame whose drawn size differs from the window size at present time is a
 * background fill, a stretched frame or the previous frame, and the parity checks count
 * those as failures: [mismatched] over all frames of a resize must be 0.
 *
 * Both sizes are in pixels. [windowWidth] and [windowHeight] are read at present time, not
 * when the frame started drawing.
 */
data class FramePresentRecord(
    val drawnWidth: Int,
    val drawnHeight: Int,
    val windowWidth: Int,
    val windowHeight: Int,
) {
    val mismatched: Boolean get() = drawnWidth != windowWidth || drawnHeight != windowHeight
}

/** Counts [FramePresentRecord]s so a check can report mismatched over total. */
class FramePresentLog {
    private var total = 0
    private var mismatchedCount = 0
    private var last: FramePresentRecord? = null

    /** Records one presented frame. */
    fun record(frame: FramePresentRecord) {
        total++
        if (frame.mismatched) mismatchedCount++
        last = frame
    }

    val frames: Int get() = total
    val mismatched: Int get() = mismatchedCount
    val lastFrame: FramePresentRecord? get() = last

    fun reset() {
        total = 0
        mismatchedCount = 0
        last = null
    }
}
