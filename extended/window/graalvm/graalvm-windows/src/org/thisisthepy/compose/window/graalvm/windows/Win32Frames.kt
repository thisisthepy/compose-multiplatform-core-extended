package org.thisisthepy.compose.window.graalvm.windows

/**
 * The one door a frame is drawn through, and the only thing that decides there is one.
 *
 * Two callers reach it on the same thread. The frame loop asks once a turn, and the
 * window asks from inside a message it is handling, which is how anything is drawn while
 * the reader drags an edge. Nothing inside the drawing hands Windows its messages today,
 * so as the code stands the two do not meet; they are one call apart from meeting,
 * because reading what the window heard is what pumps for more of it. A scene rendered
 * from inside its own render is not something Compose survives, and that is the kind of
 * crash that is found first on somebody else's machine, so the second ask is refused
 * rather than nested.
 *
 * Refused and not queued. What the second caller wanted was the window drawn at the size
 * it now is, and the frame already running is about to do exactly that: it measures the
 * window after taking its buffer, which is after the swapchain has been refitted.
 */
object Win32Frames {

    private var drawing = false

    /**
     * What drawing a frame is, as the loop that owns the scene defines it.
     *
     * Null before a window has been opened and again once it has gone, and both of those
     * are messages arriving with nothing left to draw into rather than mistakes.
     */
    var paint: (() -> Unit)? = null

    /**
     * Draws one frame unless one is already being drawn.
     *
     * False where nothing was drawn, which is either of those two cases.
     */
    fun draw(): Boolean {
        val paint = paint
        if (drawing || paint == null) {
            return false
        }
        drawing = true
        try {
            paint()
        } finally {
            drawing = false
        }
        return true
    }
