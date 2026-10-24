package org.thisisthepy.compose.window

/**
 * How both X11 windows are framed, decided once so that the Kotlin/Native window and the
 * GraalVM one come up the same.
 *
 * X11 has no title bar of its own that content can run under: the frame belongs to the window
 * manager, and it is either all there or not there at all. So [WindowChrome.Modern] takes the
 * whole frame off (`_MOTIF_WM_HINTS` with no decorations) and the scene draws the caption and
 * its buttons, and [WindowChrome.System] keeps the manager's frame with the content below it.
 * A window asked to have no decorations at all ([WindowConfig.decorated] false) has no frame
 * under either.
 *
 * Moving and resizing stay the window manager's even without its frame. A press in the
 * caption or at an edge is handed to it with `_NET_WM_MOVERESIZE`, so snapping, the keyboard
 * move and every other policy the manager has still apply.
 */
object X11Chrome {
    /**
     * The caption of a [TitleBarStyle.Normal] window, in points: the height of a GNOME header
     * bar, which is what an application on a current Linux desktop has.
     */
    const val NORMAL_CAPTION_HEIGHT = 46f

    /** The caption of a [TitleBarStyle.Simple] window, in points: a plain, compact title bar. */
    const val SIMPLE_CAPTION_HEIGHT = 32f

    /** How many buttons the scene draws: minimise, maximise or restore, close. */
    const val CAPTION_BUTTONS = 3

    /**
     * The `_NET_WM_MOVERESIZE` direction that moves the window. The edges are the numbers
     * [direction] answers; this one is the ninth.
     */
    const val MOVE = 8

    /** Whether the window manager's frame stays on the window. */
    fun keepsFrame(config: WindowConfig): Boolean = config.decorated && config.chrome == WindowChrome.System

    /**
     * The strip the scene draws its caption in, or [WindowCaption.None] where the window
     * manager draws one or there is none. The buttons are square, as tall as the strip, at the
     * trailing end, which is where GNOME, KDE and most themes put them.
     */
    fun caption(config: WindowConfig, fullscreen: Boolean = false): WindowCaption {
        if (config.chrome != WindowChrome.Modern || fullscreen) return WindowCaption.None
        val height = when (config.titleBar) {
            TitleBarStyle.Normal -> NORMAL_CAPTION_HEIGHT
            TitleBarStyle.Simple -> SIMPLE_CAPTION_HEIGHT
        }
        return WindowCaption(
            height = height,
            buttonsWidth = height * CAPTION_BUTTONS,
            buttonsAtStart = false,
            buttonsDrawnByScene = true,
        )
    }

    /** The `_NET_WM_MOVERESIZE` direction for a resize from [edge], as the EWMH numbers them. */
    fun direction(edge: WindowEdge): Int = when (edge) {
        WindowEdge.TopLeft -> 0
        WindowEdge.Top -> 1
        WindowEdge.TopRight -> 2
        WindowEdge.Right -> 3
        WindowEdge.BottomRight -> 4
        WindowEdge.Bottom -> 5
        WindowEdge.BottomLeft -> 6
        WindowEdge.Left -> 7
    }

    /**
     * The `_NET_WM_STATE` toggle for maximising: the message's first word. Toggling rather
     * than setting, because the window does not know for certain which state the manager has
     * put it in (a manager may maximise on a double click of its own, or by a key).
     */
    const val STATE_TOGGLE = 2L
}

/**
 * What a press in the caption or at an edge becomes: a move, a resize, or a double click.
 *
 * The move or the resize is handed to the window manager only once the pointer has moved a few
 * pixels with the button still down, the way GTK's header bar does it. Handed over on the press
 * itself, a click that is let go before the manager has taken the pointer (a quick click, or
 * the first half of a double click) leaves the manager waiting for a release that already
 * happened, and the next press goes to it instead of to the window.
 *
 * A second press in the caption close enough in time and place to the first is a double click,
 * which maximises or restores, as it does on every desktop's own title bar. The time is GTK's
 * default `gtk-double-click-time`, the distance its default `gtk-double-click-distance`, so the
 * caption answers to the same hand as the rest of the desktop.
 */
class CaptionGesture(
    private val intervalNanos: Long = DOUBLE_CLICK_NANOS,
    private val distance: Float = DOUBLE_CLICK_DISTANCE,
    private val threshold: Float = DRAG_THRESHOLD,
) {
    private var lastNanos = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f

    private var pending = NONE
    private var pressX = 0f
    private var pressY = 0f

    /** Whether a press is waiting to become a move or a resize. */
    val waiting: Boolean get() = pending != NONE

    /**
     * A press in the caption at [x], [y] (window pixels) at [nanos]. Answers true when it
     * completes a double click, which the window acts on at once; otherwise the move waits for
     * the pointer to go. A completed double click is forgotten, so a third press starts again.
     */
    fun move(nanos: Long, x: Float, y: Float): Boolean {
        val double = lastNanos != Long.MIN_VALUE &&
            nanos - lastNanos in 0..intervalNanos &&
            kotlin.math.abs(x - lastX) <= distance &&
            kotlin.math.abs(y - lastY) <= distance
        if (double) {
            lastNanos = Long.MIN_VALUE
            pending = NONE
            return true
        }
        lastNanos = nanos
        lastX = x
        lastY = y
        wait(X11Chrome.MOVE, x, y)
        return false
    }

    /** A press at an edge, for the resize in `_NET_WM_MOVERESIZE` [direction]. */
    fun resize(direction: Int, x: Float, y: Float) {
        lastNanos = Long.MIN_VALUE
        wait(direction, x, y)
    }

    /**
     * The pointer is at [x], [y] with the button still down. Answers the direction to hand to
     * the window manager now, or -1 while the pointer has not gone far enough or nothing waits.
     */
    fun moved(x: Float, y: Float): Int {
        if (pending == NONE) return NONE
        if (kotlin.math.abs(x - pressX) < threshold && kotlin.math.abs(y - pressY) < threshold) return NONE
        val direction = pending
        pending = NONE
        return direction
    }

    /** The button was let go, or the press was otherwise over: nothing waits any more. */
    fun released() {
        pending = NONE
    }

    private fun wait(direction: Int, x: Float, y: Float) {
        pending = direction
        pressX = x
        pressY = y
    }

    companion object {
        const val DOUBLE_CLICK_NANOS = 400_000_000L
        const val DOUBLE_CLICK_DISTANCE = 5f

        /** How far the pointer goes with the button down before it is a drag, in pixels. */
        const val DRAG_THRESHOLD = 4f

        const val NONE = -1
    }
}
