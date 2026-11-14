package org.thisisthepy.compose.window

/**
 * Platform views placed in a window: an `NSView`, an `HWND` or an X11 window the caller made,
 * such as a web view.
 *
 * The window only places, clips, orders and focuses them. It never creates one and links
 * nothing for one: the caller makes the view against [parentHandle], hands it over with
 * [attach], and still owns and destroys it after [detach].
 *
 * A native view always draws above the scene, so a Compose overlay (a menu, a dialog, a sheet)
 * that has to cover it is made a hole in it with [setVisibleRegion]: the view is cut out there
 * and the scene shows through.
 *
 * Ids are the caller's and unique per window. Every call is made on the thread that pumps the
 * window. Positions are in the pixels the scene measures in (Compose's `boundsInWindow`),
 * from the top left of the window's content; the platform converts them to its own units with
 * the scale it has when the call is made. When the scale changes (the window moved to another
 * display, reported through [WindowListener.onScaleChanged]) the caller sends the frame and the
 * region again, as Compose's `onGloballyPositioned` does anyway.
 *
 * Focus is reported through [WindowEvent.NATIVE_VIEW_FOCUS_IN] and
 * [WindowEvent.NATIVE_VIEW_FOCUS_OUT], with the slot id in [WindowEvent.keyCode].
 */
interface NativeViews {
    /**
     * The handle a caller creates its view against: the `NSView*` of the content container on
     * macOS, the top-level `HWND` on Windows, the X11 window id. 0 while the window is not open.
     */
    val parentHandle: Long

    /**
     * Adds [handle] (an `NSView*`, `HWND` or X window id the caller made) as slot [id], hidden,
     * above every slot attached before it. False when the handle is not usable here: 0, the
     * wrong kind, already attached, or [id] already in use.
     */
    fun attach(id: Int, handle: Long): Boolean

    /** Places slot [id] at [x], [y], [width] by [height] pixels of the window's content. */
    fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int)

    /**
     * The part of slot [id] that shows, in the same window pixels as [setFrame]: [clip] is its
     * ancestors' clip (the view is cut to it; null for none) and [holes] are the overlays drawn
     * above it, where it is cut out so the scene shows through and gets the pointer. A caller
     * sizes a hole to take in the overlay's shadow.
     */
    fun setVisibleRegion(id: Int, clip: PixelRect?, holes: List<PixelRoundRect>)

    /**
     * Hides or shows slot [id]. A hidden view is kept, with its state, and gets no input; if it
     * had keyboard focus the focus goes back to the scene first.
     */
    fun setVisible(id: Int, visible: Boolean)

    /** Gives keyboard focus to slot [id]. */
    fun focus(id: Int)

    /** Gives keyboard focus back to the scene. */
    fun focusScene()

    /**
     * Removes slot [id] from the window. If it had keyboard focus the focus goes back to the
     * scene first. The caller still owns the view and destroys it.
     */
    fun detach(id: Int)
}

/** A rectangle in window pixels, from the top left of the window's content. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** Whether the pixel position ([px], [py]) is inside, the right and bottom edges excluded. */
    fun contains(px: Double, py: Double): Boolean =
        !isEmpty && px >= x && px < right && py >= y && py < bottom

    /** The part this rectangle shares with [other], empty when they do not meet. */
    fun intersect(other: PixelRect): PixelRect {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return PixelRect(left, top, maxOf(0, r - left), maxOf(0, b - top))
    }

    fun intersects(other: PixelRect): Boolean = !intersect(other).isEmpty
}

/**
 * A rectangle in window pixels with its four corners rounded by [cornerRadius] pixels: the shape
 * of a rounded menu or sheet, so the view is not cut square around it.
 */
data class PixelRoundRect(val rect: PixelRect, val cornerRadius: Int = 0) {
    constructor(x: Int, y: Int, width: Int, height: Int, cornerRadius: Int = 0) :
        this(PixelRect(x, y, width, height), cornerRadius)

    /** The radius actually drawn: never more than half the shorter side. */
    val radius: Int get() = cornerRadius.coerceIn(0, minOf(rect.width, rect.height) / 2)

    fun contains(px: Double, py: Double): Boolean {
        if (!rect.contains(px, py)) return false
        val r = radius.toDouble()
        if (r <= 0.0) return true
        // Inside the rectangle; outside the shape only in a corner square, beyond its arc.
        val cx = when {
            px < rect.x + r -> rect.x + r
            px > rect.right - r -> rect.right - r
            else -> return true
        }
        val cy = when {
            py < rect.y + r -> rect.y + r
            py > rect.bottom - r -> rect.bottom - r
            else -> return true
        }
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy <= r * r
    }
}

/**
 * Where a slot shows, in window pixels: inside its [frame] and its [clip], outside every hole.
 * The platform cuts the view to this and sends the pointer to it only here.
 */
data class NativeViewRegion(
    val frame: PixelRect,
    val clip: PixelRect? = null,
    val holes: List<PixelRoundRect> = emptyList(),
) {
    /** The rectangle the view is cut to: its frame, within the clip when there is one. */
    val bounds: PixelRect get() = if (clip == null) frame else frame.intersect(clip)

    /** Whether the whole frame shows, so nothing has to be masked. */
    val isWholeFrame: Boolean get() = bounds == frame && holes.none { it.rect.intersects(frame) }

    /** Whether the pixel position ([px], [py]) shows the view. */
    fun contains(px: Double, py: Double): Boolean =
        bounds.contains(px, py) && holes.none { it.contains(px, py) }

    /**
     * Whether no two holes touch, so one path filled even-odd (the bounds, then each hole) cuts
     * them all out. Where two meet, even-odd would show the view again in their overlap, and the
     * platform cuts them out one by one instead.
     */
    val holesAreDisjoint: Boolean
        get() {
            for (i in holes.indices) for (j in i + 1 until holes.size) {
                if (holes[i].rect.intersects(holes[j].rect)) return false
            }
            return true
        }
}

/**
 * Which slot has keyboard focus, and the events that say so.
 *
 * The platform tells it what the system did: the first responder (or focused child window)
 * moved to a slot or to the scene, or the window became or stopped being the key window. It
 * answers the [WindowEvent]s to report. While the window is not key nothing is reported, and
 * the slot that had focus is remembered: the window says it lost it when it stops being key
 * and has it again when it is key again.
 */
class NativeViewFocus(key: Boolean = true) {
    /** Whether the window is the key window. */
    var isKey: Boolean = key
        private set

    /** The slot that holds the first responder, or null for the scene. */
    var focused: Int? = null
        private set

    /** The first responder moved to slot [id], or to the scene when null. */
    fun responderChanged(id: Int?): List<WindowEvent> {
        val before = focused
        if (before == id) return emptyList()
        focused = id
        if (!isKey) return emptyList()
        val events = ArrayList<WindowEvent>(2)
        if (before != null) events += WindowEvent.NativeViewFocusOut(before)
        if (id != null) events += WindowEvent.NativeViewFocusIn(id)
        return events
    }

    /** The window became the key window ([key] true) or stopped being it. */
    fun keyChanged(key: Boolean): List<WindowEvent> {
        if (key == isKey) return emptyList()
        isKey = key
        val slot = focused ?: return emptyList()
        return listOf(if (key) WindowEvent.NativeViewFocusIn(slot) else WindowEvent.NativeViewFocusOut(slot))
    }
}
