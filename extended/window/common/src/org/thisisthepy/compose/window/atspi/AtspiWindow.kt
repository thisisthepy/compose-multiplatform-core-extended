package org.thisisthepy.compose.window.atspi

import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.WindowListener

/**
 * One X11 window on the AT-SPI bus, fed from the records the scene pushes.
 *
 * Both X11 windows hold one and differ only in the socket under the bus and in how they find
 * out where they are on the screen, so everything else is here: when to join, what to publish,
 * where a reader's actions go and when the scene is told a reader is listening.
 *
 * It joins once the window has drawn its first frame, not when it opens. Joining asks the
 * session bus for the accessibility bus and may wait for it to be started, and a window that
 * waited for that before it was on the screen would come up late on every desktop, read or not.
 * `NO_AT_BRIDGE=1` keeps it off the bus, as it does every toolkit's bridge.
 *
 * Everything runs on the window's thread, inside its pump: the bus is read without waiting, a
 * reader's question is answered in the turn it arrives, and an action it asks for reaches the
 * scene through [WindowListener.onAccessibilityAction] on that same thread.
 */
class AtspiWindow(
    private val openSession: () -> BusConnection?,
    private val openAccessibility: (BusAddress) -> BusConnection?,
    private val userId: () -> Long?,
    private val applicationName: () -> String,
    /** Where the window's top left corner is on the screen, in pixels. */
    private val windowOrigin: () -> Pair<Int, Int>,
    /** Asks the window for a frame, so the scene describes itself after it. */
    private val requestFrame: () -> Unit,
    private val disabled: Boolean,
) {
    /** Who a reader's actions go to. Set when the window opens. */
    var listener: WindowListener? = null

    private var bridge: AtspiBridge? = null
    private var tried = false
    private var listening = false

    private var title = ""
    private var elements: List<AccessibleElement> = emptyList()
    private var scale = 1f
    private var width = 0
    private var height = 0

    /** What this process is called on the accessibility bus; empty until it has joined. */
    val busName: String get() = bridge?.busName ?: ""

    /** Whether the window is on the accessibility bus. */
    val joined: Boolean get() = bridge?.connected == true

    /** Whether a reader is listening, which is when the scene should describe itself. */
    val readerListening: Boolean get() = bridge?.readerListening == true

    /**
     * Joins the bus, once, if it has not tried yet. The window calls it after its first frame;
     * a test that has no frame to draw calls it directly. True when the window is on the bus.
     */
    fun join(): Boolean {
        if (tried) return joined
        tried = true
        if (disabled) return false
        val user = userId() ?: return false
        val actions = object : AtspiActions {
            override fun perform(id: Int, action: Int, argument: String?): Boolean =
                id < UNNAMED_BASE && listener?.onAccessibilityAction(id, action, argument) == true

            override fun windowOrigin(): Pair<Int, Int> = this@AtspiWindow.windowOrigin()
        }
        val made = AtspiBridge(openSession, openAccessibility, user, applicationName(), actions)
        if (!made.start()) return false
        bridge = made
        // An empty window first, so that the scene's first description is announced as
        // controls arriving: a reader that asked before it got here was given an empty window
        // and is waiting to hear what appears in it.
        made.update(AtspiTree(title, emptyMap(), emptyList(), null, width, height))
        if (elements.isNotEmpty()) publish()
        noticeListening()
        return true
    }

    /** The window's title, which is the name a reader gives the frame. */
    fun setTitle(title: String) {
        if (title == this.title) return
        this.title = title
        publish()
    }

    /** The window's size in pixels and its scale, which turn the records' points into pixels. */
    fun setGeometry(width: Int, height: Int, scale: Float) {
        if (width == this.width && height == this.height && scale == this.scale) return
        this.width = width
        this.height = height
        this.scale = scale
        publish()
    }

    /** The scene's newest description. Published at once where the window is on the bus. */
    fun setElements(elements: List<AccessibleElement>) {
        if (elements == this.elements) return
        this.elements = elements
        publish()
    }

    /**
     * Answers whatever a reader asked since the last turn, tells it whether the window has the
     * keyboard, and asks for a frame the moment a reader starts listening.
     */
    fun pump(focused: Boolean) {
        val bus = bridge ?: return
        bus.windowActive(focused)
        bus.pump()
        noticeListening()
    }

    fun close() {
        bridge?.close()
        bridge = null
    }

    private fun noticeListening() {
        val now = readerListening
        if (now && !listening) requestFrame()
        listening = now
    }

    private fun publish() {
        val bus = bridge ?: return
        bus.update(atspiTreeOf(title, elements, scale, width, height))
    }
}
