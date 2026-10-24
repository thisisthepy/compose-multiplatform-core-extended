@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import org.thisisthepy.compose.window.FrameRequestCoalescer
import org.thisisthepy.compose.window.ImeSession
import org.thisisthepy.compose.window.keyEventsFor

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import org.jetbrains.skia.Canvas
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.CaptionGesture
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowEdge
import org.thisisthepy.compose.window.X11Chrome
import org.thisisthepy.compose.window.atspi.AtspiWindow
import org.thisisthepy.compose.window.sizeHintsFor
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.ResizeStats
import org.thisisthepy.compose.window.ResizeSync
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowEventLog
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.CLOCK_MONOTONIC
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.FD_CLOEXEC
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.fcntl
import platform.posix.pipe
import platform.posix.read
import platform.posix.write
import platform.posix.clock_gettime
import platform.posix.timespec
import platform.posix.getenv
import platform.posix.poll
import platform.posix.pollfd
import kotlinx.cinterop.toKString
import x11.*

/**
 * The Kotlin/Native window for Linux: Xlib and GLX, the sync extension, XIM and the CLIPBOARD
 * selection, behind [WindowPlatform].
 *
 * This class is the operating system layer only. What it hears is handed to the
 * [WindowListener] and what it draws is whatever the host paints through [draw]; the scene,
 * text fields and semantics belong to whoever hosts it. The host loop is
 * `pump`, then `draw` when something changed, then `present`.
 *
 * Every read of the display server happens inside [pump], through [WindowEventLog], so a read
 * is never reached from inside another one.
 */
class X11Window : WindowPlatform {

    override val name: String = "x11"

    private var display: CPointer<Display>? = null
    private var window: Window = 0uL
    private var context: GLXContext? = null
    private var colormap: Colormap = 0uL
    private var deleteWindow: Atom = 0uL
    private var protocolsAtom: Atom = 0uL
    private var syncRequest: Atom = 0uL
    private var syncCounter: XSyncCounter = 0uL
    private var clipboardAtom: Atom = 0uL
    private var utf8Atom: Atom = 0uL
    private var targetsAtom: Atom = 0uL
    private var transferAtom: Atom = 0uL
    private var surface: GlSurface? = null
    private var listener: WindowListener? = null
    private var title = ""
    private var minWidth = 0
    private var minHeight = 0
    private var resizable = true
    private var openedWidth = 0
    private var openedHeight = 0
    private var config: WindowConfig? = null
    private var fullscreen = false

    // The last press of the primary button, in window pixels and on the monotonic clock, which
    // is where a press in the caption the scene draws was made.
    private var pressX = 0f
    private var pressY = 0f
    private var pressNanos = 0L
    private val gesture = CaptionGesture()

    /** Whether a frame has been presented, after which the window joins the accessibility bus. */
    private var presented = false

    /** This window on the AT-SPI bus, published from the records [setAccessibility] is given. */
    private val accessibility = AtspiWindow(
        openSession = { PosixBusConnection.open() },
        openAccessibility = { address -> PosixBusConnection.open(address) },
        userId = { platform.posix.getuid().toLong() },
        applicationName = { PosixBusConnection.applicationName() },
        windowOrigin = { originOnScreen() },
        requestFrame = { requestFrame() },
        disabled = getenv("NO_AT_BRIDGE")?.toKString() == "1",
    )

    private var measured = WindowMeasurement(0, 0, DENSITY)

    /**
     * What a resize showed: every size the window took and every frame presented, judged
     * against the window's size at that moment. Printed by the host as [ResizeStats.report]
     * when `DXC_REPORT_RESIZE` is set, which is also when the size is asked of the server at
     * each present; without it the last size event answers, which costs nothing.
     */
    val resizeStats = ResizeStats()
    private val resizeDetail = getenv("DXC_REPORT_RESIZE_DETAIL") != null
    private val strictResizeAccounting = getenv("DXC_REPORT_RESIZE") != null || resizeDetail

    /** DXC_REPORT_RESIZE_DETAIL: one line per size asked for, confirmed and presented. */
    private fun detail(line: String) {
        if (!resizeDetail) return
        platform.posix.fputs("dxc resize detail: t=${monotonicClockNanos() / 1000}us $line\n", platform.posix.stderr)
    }
    private var presentedSinceEvent = false
    private val frameRequests = FrameRequestCoalescer().also { it.request() }
    private var ownedClipboard: String? = null

    /**
     * A pipe another thread writes a byte into to end the wait in [pump] early, both ends
     * made before the window so that [wake] is safe from any thread at any time. -1 where the
     * process could not make one; then the wait runs its course, as it did before there was one.
     */
    private val wakeRead: Int
    private val wakeWrite: Int

    init {
        val ends = IntArray(2) { -1 }
        val made = ends.usePinned { pipe(it.addressOf(0)) } == 0
        if (made) {
            for (end in ends) {
                fcntl(end, F_SETFL, O_NONBLOCK)
                fcntl(end, F_SETFD, FD_CLOEXEC)
            }
        }
        wakeRead = if (made) ends[0] else -1
        wakeWrite = if (made) ends[1] else -1
    }

    // Closed with the object rather than in [close], because another thread may still call
    // [wake] after the window has closed, and a number closed here could already name some
    // other file by then.
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    @Suppress("unused")
    private val closeWakePipe = kotlin.native.ref.createCleaner(intArrayOf(wakeRead, wakeWrite)) { ends ->
        for (end in ends) if (end >= 0) platform.posix.close(end)
    }

    private val log = WindowEventLog()
    private val drained = mutableListOf<WindowEvent>()
    private val ime = ImeSession { event -> log.heard(event) }
    private var xim: XimContext? = null
    private var xdnd: XdndTarget? = null
    private var lastSpot: Pair<Int, Int>? = null
    private var cursorShape = -1
    private val cursors = LongArray(CURSOR_SHAPES)

    private val sync = ResizeSync(
        present = { surface?.present() },
        tell = { value -> setCounter(value) },
    )

    /** The X id of the window, for a test that has to address it as another client. */
    internal val windowId: ULong get() = window

    /** Whether this window has the keyboard, as the server last said. */
    var isFocused: Boolean = true
        private set

    /** True once the window is closed or the server destroyed it. */
    var isClosed: Boolean = false
        private set

    /** True while an input method is composing text that has not been committed. */
    val isComposing: Boolean get() = ime.composing

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        if (display != null) return false
        val display = XOpenDisplay(null) ?: return false
        val screen = XDefaultScreen(display)
        val visual = glXChooseVisual(
            display,
            screen,
            cValuesOf(
                GLX_RGBA, GLX_DOUBLEBUFFER,
                GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8,
                0,
            ),
        )
        if (visual == null) {
            XCloseDisplay(display)
            return false
        }
        val colormap = XCreateColormap(display, XRootWindow(display, screen), visual.pointed.visual, AllocNone)
        val window = memScoped {
            val settings = alloc<XSetWindowAttributes>()
            settings.colormap = colormap
            settings.event_mask = EVENT_MASK
            XCreateWindow(
                display,
                XRootWindow(display, screen),
                0, 0,
                config.width.convert(), config.height.convert(),
                0u,
                visual.pointed.depth,
                InputOutput.convert(),
                visual.pointed.visual,
                (CWColormap or CWEventMask).convert(),
                settings.ptr,
            )
        }
        val context = glXCreateContext(display, visual, null, 1)
        XFree(visual)
        if (window == 0uL || context == null || glXMakeCurrent(display, window, context) == 0) {
            if (context != null) glXDestroyContext(display, context)
            if (window != 0uL) XDestroyWindow(display, window)
            XFreeColormap(display, colormap)
            XCloseDisplay(display)
            return false
        }
        this.display = display
        this.window = window
        this.context = context
        this.colormap = colormap
        this.listener = listener
        this.title = config.title
        deleteWindow = XInternAtom(display, "WM_DELETE_WINDOW", 0)
        protocolsAtom = XInternAtom(display, "WM_PROTOCOLS", 0)
        syncRequest = XInternAtom(display, "_NET_WM_SYNC_REQUEST", 0)
        clipboardAtom = XInternAtom(display, "CLIPBOARD", 0)
        utf8Atom = XInternAtom(display, "UTF8_STRING", 0)
        targetsAtom = XInternAtom(display, "TARGETS", 0)
        transferAtom = XInternAtom(display, "COMPOSE_WINDOW_TRANSFER", 0)
        syncCounter = createSyncCounter(display, window)
        memScoped {
            val protocols = allocArray<ULongVar>(2)
            protocols[0] = deleteWindow
            var count = 1
            if (syncCounter != 0uL) {
                protocols[1] = syncRequest
                count = 2
            }
            XSetWMProtocols(display, window, protocols, count)
        }
        XStoreName(display, window, config.title)
        this.config = config
        // Before the window is mapped, because a manager decides on the frame when it maps it.
        if (!X11Chrome.keepsFrame(config)) removeDecorations(display, window)
        accessibility.listener = listener
        accessibility.setTitle(config.title)
        measured = WindowMeasurement(config.width, config.height, DENSITY)
        surface = GlSurface(display, window, context)
        xim = XimContext.open(display, window, ime)?.also { input ->
            XSelectInput(display, window, EVENT_MASK or input.filterMask)
        }
        xdnd = XdndTarget(display, window) { log.heard(it) }.also { it.attach() }
        minWidth = config.minWidth
        minHeight = config.minHeight
        resizable = config.resizable
        openedWidth = config.width
        openedHeight = config.height
        applySizeHints()
        XMapWindow(display, window)
        XFlush(display)
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val display = display ?: return
        if (timeoutMillis > 0 && XPending(display) == 0) {
            memScoped {
                // The display connection, and the wake pipe: a byte from another thread ends
                // the wait as an event would. One sent before the wait began is still in the
                // pipe, so it ends this wait at once rather than the next.
                val watched = allocArray<pollfd>(2)
                watched[0].fd = XConnectionNumber(display)
                watched[0].events = POLLIN.toShort()
                watched[1].fd = wakeRead
                watched[1].events = POLLIN.toShort()
                poll(watched, (if (wakeRead >= 0) 2 else 1).convert(), timeoutMillis.toInt())
            }
        }
        drainWakes()
        log.read {
            memScoped {
                val event = alloc<XEvent>()
                while (XPending(display) > 0) {
                    XNextEvent(display, event.ptr)
                    handle(event)
                }
            }
        }
        drained.clear()
        log.drain(drained)
        listener?.let { target -> for (event in drained) target.onEvent(event) }
        // After the events, so that a reader's action lands after the input that came before
        // it, and on this thread, which is the one the scene is on.
        accessibility.setGeometry(measured.width, measured.height, measured.scale)
        if (presented && !isClosed) accessibility.join()
        accessibility.pump(isFocused)
    }

    /**
     * Ends the wait in [pump] early, so the loop turns now rather than at the end of the
     * frame's wait. Any thread may call it, before the window opens and after it closes too:
     * it is how work sent to the window's thread from another one is noticed at once.
     */
    override fun wake() {
        if (wakeWrite < 0) return
        val one = byteArrayOf(1)
        // A full pipe already holds a wake, so a write that would block is not needed.
        one.usePinned { write(wakeWrite, it.addressOf(0), 1.convert()) }
    }

    /** Every wake up to here is answered by this turn, whether it ended the wait or not. */
    private fun drainWakes() {
        if (wakeRead < 0) return
        val sink = ByteArray(WAKE_DRAIN_BYTES)
        sink.usePinned { pinned ->
            while (read(wakeRead, pinned.addressOf(0), WAKE_DRAIN_BYTES.convert()) > 0) {
                // Read until empty; the count does not matter.
            }
        }
    }

    override fun measure(): WindowMeasurement = measured

    override fun requestFrame() {
        frameRequests.request()
    }

    /**
     * `CLOCK_MONOTONIC` when the frame is started, in nanoseconds.
     *
     * Xlib says nothing about when the server will show a frame: a swap is queued and the
     * compositor, if there is one, presents it on its own schedule. The time a frame is
     * started is the closest answer this window has, and it moves at the speed of the wall
     * clock whatever the refresh rate. It is the clock Rust's `Instant` reads on Linux and
     * the one the GraalVM X11 window answers on.
     */
    override fun frameTimeNanos(): Long = monotonicClockNanos()

    /** Whether a frame was asked for since the last call. Reading it clears it. */
    fun takeFrameRequest(): Boolean {
        return frameRequests.take()
    }

    /**
     * Draws one frame at [width] by [height] pixels into the back buffer. Answers false where
     * there was nothing to draw into. Follow a true answer with [present].
     */
    fun draw(width: Int, height: Int, paint: (Canvas) -> Unit): Boolean =
        surface?.draw(width, height, paint) ?: false

    /**
     * What draws and presents the frame for a new size from inside the handling of it, in place
     * of the RESIZE event the listener would otherwise be given. A scene host sets it through
     * its surface, because its listener only collects events for its next turn, and a frame
     * drawn then leaves the strip the manager has already given the window unpainted.
     */
    var resizePainter: (() -> Boolean)? = null

    /**
     * Makes the window's GL context current on this thread, for a frame drawn by something
     * other than [draw]. False where the window is closed or the server refused.
     */
    fun makeCurrent(): Boolean {
        val display = display ?: return false
        val context = context ?: return false
        if (isClosed) return false
        return glXMakeCurrent(display, window, context) != 0
    }

    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        sync.frameDrawn()
        presentedSinceEvent = true
        presented = true
        display?.let { XFlush(it) }
        val (width, height) = if (strictResizeAccounting) serverSize() else measured.width to measured.height
        resizeStats.presentAgainst(drawnWidth, drawnHeight, width, height)
        detail("present drawn=${drawnWidth}x$drawnHeight configured=${measured.width}x${measured.height} server=${width}x$height")
        return FramePresentRecord(drawnWidth, drawnHeight, width, height)
    }

    /** The window's size now, asked of the server rather than remembered. Costs a round trip. */
    private fun serverSize(): Pair<Int, Int> {
        val display = display ?: return measured.width to measured.height
        return memScoped {
            val attributes = alloc<XWindowAttributes>()
            if (XGetWindowAttributes(display, window, attributes.ptr) == 0) {
                measured.width to measured.height
            } else {
                attributes.width to attributes.height
            }
        }
    }

    /**
     * Takes the window through the sizes a drag would, from its size now to [toWidth] by
     * [toHeight] in [steps], pausing [pauseMillis] on each, for measuring. Each size is asked
     * of the server and its events read before the next, so the frame that belongs to a size
     * is drawn inside the handling of it, as it is for a hand on an edge. The count starts
     * again.
     */
    fun scriptedResize(toWidth: Int, toHeight: Int, steps: Int, pauseMillis: Long) {
        val display = display ?: return
        if (steps <= 0) return
        resizeStats.reset()
        val from = measured
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val width = (from.width + (toWidth - from.width) * t).toInt().coerceAtLeast(1)
            val height = (from.height + (toHeight - from.height) * t).toInt().coerceAtLeast(1)
            detail("request ${width}x$height")
            XResizeWindow(display, window, width.convert(), height.convert())
            XFlush(display)
            // The next size is asked for only once the server has confirmed this one and its
            // frame has been drawn, which is what a window manager holding the frame for the
            // sync counter does; the GraalVM window's drag waits the same way. Asked for
            // earlier, the frame for this size is drawn while the server already has the next
            // one. A pump can return before the confirmation arrives (a wake ends its wait), so
            // it is asked again, for up to a second.
            val asked = monotonicClockNanos()
            while (!isClosed && (measured.width != width || measured.height != height) &&
                monotonicClockNanos() - asked < CONFIRM_TIMEOUT_NANOS
            ) {
                pump(CONFIRM_SLICE_MILLIS)
            }
            pump(pauseMillis)
        }
    }

    /** Pays the window manager for a size change that produced no frame. */
    fun noFrame() {
        sync.noFrame()
        presentedSinceEvent = true
    }

    override fun systemTheme(): SystemTheme {
        val gtk = getenv("GTK_THEME")?.toKString().orEmpty().lowercase()
        return if (gtk.endsWith(":dark") || gtk.endsWith("-dark")) SystemTheme.Dark else SystemTheme.Light
    }

    override fun setTitle(title: String) {
        this.title = title
        accessibility.setTitle(title)
        val display = display ?: return
        XStoreName(display, window, title)
        XFlush(display)
    }

    override fun setMinimumSize(width: Int, height: Int) {
        minWidth = width
        minHeight = height
        applySizeHints()
    }

    /** Tells the window manager the sizes [sizeHintsFor] decides on, when there are any. */
    private fun applySizeHints() {
        val display = display ?: return
        val decided = sizeHintsFor(minWidth, minHeight, resizable, openedWidth, openedHeight, DENSITY) ?: return
        val hints = XAllocSizeHints() ?: return
        decided.min?.let { (width, height) ->
            hints.pointed.flags = hints.pointed.flags or P_MIN_SIZE
            hints.pointed.min_width = width
            hints.pointed.min_height = height
        }
        decided.max?.let { (width, height) ->
            hints.pointed.flags = hints.pointed.flags or P_MAX_SIZE
            hints.pointed.max_width = width
            hints.pointed.max_height = height
        }
        XSetWMNormalHints(display, window, hints)
        XFree(hints)
        XFlush(display)
    }

    override fun setVisibility(visibility: WindowVisibility) {
        val display = display ?: return
        when (visibility) {
            WindowVisibility.Hidden -> XUnmapWindow(display, window)
            WindowVisibility.Visible -> {
                setFullscreen(false)
                XMapRaised(display, window)
            }
            WindowVisibility.Minimized -> XIconifyWindow(display, window, XDefaultScreen(display))
            WindowVisibility.Fullscreen -> {
                XMapRaised(display, window)
                setFullscreen(true)
            }
        }
        when (visibility) {
            WindowVisibility.Visible -> fullscreen = false
            WindowVisibility.Fullscreen -> fullscreen = true
            else -> {}
        }
        XFlush(display)
    }

    // The chrome: what the scene draws instead of the window manager's frame, and the moves
    // and resizes a press in it hands to the manager.

    /**
     * The caption the scene draws, or none where the window manager draws its own frame. None
     * while the window is full screen, which has no caption on any desktop.
     */
    override fun caption(): WindowCaption =
        config?.let { X11Chrome.caption(it, fullscreen) } ?: WindowCaption.None

    /**
     * Hands the move to the window manager, from a press in an empty part of the caption, once
     * the pointer has moved a few pixels with the button down ([CaptionGesture] says why). A
     * second press close in time and place to the one before is a double click, which
     * maximises or restores instead, as a double click on any title bar does.
     */
    override fun startMove(): Boolean {
        if (display == null || isClosed) return false
        if (gesture.move(pressNanos, pressX, pressY)) performCaptionAction(CaptionAction.ToggleMaximize)
        return true
    }

    /** Hands the resize from [edge] to the window manager, from a press at that edge, once the pointer goes. */
    override fun startResize(edge: WindowEdge): Boolean {
        if (display == null || isClosed || !resizable) return false
        gesture.resize(X11Chrome.direction(edge), pressX, pressY)
        return true
    }

    override fun performCaptionAction(action: CaptionAction) {
        val display = display ?: return
        if (isClosed) return
        when (action) {
            CaptionAction.Minimize -> XIconifyWindow(display, window, XDefaultScreen(display))
            CaptionAction.ToggleMaximize -> memScoped {
                val event = alloc<XEvent>()
                event.xclient.type = ClientMessage
                event.xclient.window = window
                event.xclient.message_type = XInternAtom(display, "_NET_WM_STATE", 0)
                event.xclient.format = 32
                event.xclient.data.l[0] = X11Chrome.STATE_TOGGLE
                event.xclient.data.l[1] = XInternAtom(display, "_NET_WM_STATE_MAXIMIZED_VERT", 0).toLong()
                event.xclient.data.l[2] = XInternAtom(display, "_NET_WM_STATE_MAXIMIZED_HORZ", 0).toLong()
                event.xclient.data.l[3] = SOURCE_APPLICATION
                XSendEvent(
                    display,
                    XRootWindow(display, XDefaultScreen(display)),
                    0,
                    SubstructureRedirectMask or SubstructureNotifyMask,
                    event.ptr,
                )
            }
            // The way the manager's own close button does it: the listener is asked first.
            CaptionAction.Close -> if (listener?.onCloseRequested() != false) isClosed = true
        }
        XFlush(display)
    }

    /**
     * Sends `_NET_WM_MOVERESIZE` in [direction] for the primary button. The position is where
     * the button was pressed, on the screen, rather than where the pointer is now: the pointer
     * has already gone a few pixels by the time the drag is handed over, and the manager moves
     * the window by how far the pointer is from the position it is given. The press holds the
     * pointer, so it is let go first, or the manager could not take it.
     */
    private fun moveResize(direction: Int): Boolean {
        val display = display ?: return false
        if (isClosed) return false
        val (originX, originY) = originOnScreen()
        val rootX = originX + pressX.toInt()
        val rootY = originY + pressY.toInt()
        XUngrabPointer(display, CurrentTime.convert())
        memScoped {
            val event = alloc<XEvent>()
            event.xclient.type = ClientMessage
            event.xclient.window = window
            event.xclient.message_type = XInternAtom(display, "_NET_WM_MOVERESIZE", 0)
            event.xclient.format = 32
            event.xclient.data.l[0] = rootX.toLong()
            event.xclient.data.l[1] = rootY.toLong()
            event.xclient.data.l[2] = direction.toLong()
            event.xclient.data.l[3] = PRIMARY_BUTTON
            event.xclient.data.l[4] = SOURCE_APPLICATION
            XSendEvent(
                display,
                XRootWindow(display, XDefaultScreen(display)),
                0,
                SubstructureRedirectMask or SubstructureNotifyMask,
                event.ptr,
            )
        }
        XFlush(display)
        // The manager has the pointer until it is let go, so the release that ends this press
        // never reaches the window. The scene is told it happened, or it would believe the
        // button is still down.
        log.heard(event(WindowEvent.POINTER_UP, pressX, pressY, 0, 0))
        return true
    }

    /** Asks the manager for no frame: `_MOTIF_WM_HINTS` with only the decorations set, to none. */
    private fun removeDecorations(display: CPointer<Display>, window: Window) {
        val hints = XInternAtom(display, "_MOTIF_WM_HINTS", 0)
        memScoped {
            val words = allocArray<ULongVar>(MOTIF_HINT_WORDS)
            words[0] = MOTIF_HINTS_DECORATIONS
            for (index in 1 until MOTIF_HINT_WORDS) words[index] = 0uL
            XChangeProperty(display, window, hints, hints, 32, PropModeReplace, words.reinterpret(), MOTIF_HINT_WORDS)
        }
    }

    // Accessibility: the scene's records, published on the AT-SPI bus.

    override fun setAccessibility(elements: List<AccessibleElement>) = accessibility.setElements(elements)

    override val accessibilityActive: Boolean get() = accessibility.readerListening

    /** What this window is called on the accessibility bus; empty until it has joined. */
    val accessibilityBusName: String get() = accessibility.busName

    /** Joins the accessibility bus now rather than after the first frame, for a test with nothing to draw. */
    fun joinAccessibility(): Boolean = accessibility.join()

    private fun setFullscreen(on: Boolean) {
        val display = display ?: return
        memScoped {
            val event = alloc<XEvent>()
            event.xclient.type = ClientMessage
            event.xclient.window = window
            event.xclient.message_type = XInternAtom(display, "_NET_WM_STATE", 0)
            event.xclient.format = 32
            event.xclient.data.l[0] = if (on) 1L else 0L
            event.xclient.data.l[1] = XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", 0).toLong()
            event.xclient.data.l[2] = 0
            event.xclient.data.l[3] = SOURCE_APPLICATION
            XSendEvent(
                display,
                XRootWindow(display, XDefaultScreen(display)),
                0,
                SubstructureRedirectMask or SubstructureNotifyMask,
                event.ptr,
            )
        }
    }

    /** Brings the window up in front of the others, the way a pager would. */
    fun raise() {
        val display = display ?: return
        if (isClosed) return
        memScoped {
            val event = alloc<XEvent>()
            event.xclient.type = ClientMessage
            event.xclient.window = window
            event.xclient.message_type = XInternAtom(display, "_NET_ACTIVE_WINDOW", 0)
            event.xclient.format = 32
            event.xclient.data.l[0] = SOURCE_PAGER
            event.xclient.data.l[1] = 0
            XSendEvent(
                display,
                XRootWindow(display, XDefaultScreen(display)),
                0,
                SubstructureRedirectMask or SubstructureNotifyMask,
                event.ptr,
            )
        }
        XMapRaised(display, window)
        XFlush(display)
    }

    /**
     * Sets the shape of the pointer over the window: one of the CURSOR_ constants. Asking for
     * the shape the pointer already has sends nothing to the server.
     */
    fun setCursor(shape: Int) {
        val display = display ?: return
        if (isClosed || shape == cursorShape) return
        val wanted = if (shape in cursors.indices) shape else CURSOR_ARROW
        if (cursors[wanted] == 0L) {
            cursors[wanted] = XCreateFontCursor(display, cursorFont(wanted).convert()).toLong()
        }
        XDefineCursor(display, window, cursors[wanted].toULong())
        cursorShape = wanted
        XFlush(display)
    }

    /** Where the window's top left corner is on the screen. */
    fun originOnScreen(): Pair<Int, Int> {
        val display = display ?: return 0 to 0
        return memScoped {
            val x = alloc<IntVar>()
            val y = alloc<IntVar>()
            val child = alloc<ULongVar>()
            XTranslateCoordinates(
                display, window, XRootWindow(display, XDefaultScreen(display)), 0, 0, x.ptr, y.ptr, child.ptr,
            )
            x.value to y.value
        }
    }

    // The input method.

    /** Gives the input method the keyboard when a field wants text and the window has it. */
    fun setImeActive(wanted: Boolean) {
        xim?.focus(wanted && isFocused)
        if (!wanted) lastSpot = null
    }

    override fun setImeSpot(x: Int, y: Int) {
        val method = xim ?: return
        val spot = x to y
        if (spot == lastSpot) return
        lastSpot = spot
        method.spot(x, y)
    }

    /** Ends whatever the input method is composing where it stands, keeping what was typed. */
    fun finishComposition() {
        val method = xim ?: return
        if (!ime.composing) return
        val finished = method.reset()
        if (finished.isNotEmpty()) ime.commit(finished) else ime.preeditDone()
    }

    // The clipboard: the CLIPBOARD selection, as UTF-8 text.

    override fun writeClipboardText(text: String) {
        val display = display ?: return
        ownedClipboard = text
        XSetSelectionOwner(display, clipboardAtom, window, 0uL)
        XFlush(display)
    }

    override fun readClipboardText(): String? {
        val display = display ?: return null
        ownedClipboard?.let { return it }
        if (XGetSelectionOwner(display, clipboardAtom) == 0uL) return null
        XConvertSelection(display, clipboardAtom, utf8Atom, transferAtom, window, 0uL)
        XFlush(display)
        val arrived = memScoped {
            val reply = alloc<XEvent>()
            var waited = 0
            var got = false
            while (waited < CLIPBOARD_WAIT_MILLIS) {
                if (XCheckTypedWindowEvent(display, window, SelectionNotify, reply.ptr) != 0) {
                    got = reply.xselection.property != 0uL
                    break
                }
                val watched = alloc<pollfd>()
                watched.fd = XConnectionNumber(display)
                watched.events = POLLIN.toShort()
                poll(watched.ptr, 1.convert(), CLIPBOARD_SLICE_MILLIS)
                waited += CLIPBOARD_SLICE_MILLIS
                XPending(display)
            }
            got
        }
        if (!arrived) return null
        return memScoped {
            val type = alloc<ULongVar>()
            val format = alloc<IntVar>()
            val count = alloc<ULongVar>()
            val remaining = alloc<ULongVar>()
            val data = alloc<CPointerVar<UByteVar>>()
            val status = XGetWindowProperty(
                display, window, transferAtom, 0, CLIPBOARD_MAX_WORDS, 1, 0uL,
                type.ptr, format.ptr, count.ptr, remaining.ptr, data.ptr,
            )
            val bytes = data.value
            if (status != 0 || bytes == null || format.value != 8) {
                if (bytes != null) XFree(bytes)
                null
            } else {
                val text = bytes.readBytes(count.value.toInt()).decodeToString()
                XFree(bytes)
                text
            }
        }
    }

    // There is no native context menu on bare X11: the items are not shown and the listener is
    // told the menu was dismissed, so a host that wants one draws its own.
    override fun showContextMenu(items: List<ContextMenuItem>) {
        listener?.onContextMenuChosen(-1)
    }

    override fun close() {
        accessibility.close()
        val display = display ?: return
        isClosed = true
        xim?.close()
        xim = null
        surface?.close()
        surface = null
        glXMakeCurrent(display, 0uL, null)
        context?.let { glXDestroyContext(display, it) }
        XDestroyWindow(display, window)
        XFreeColormap(display, colormap)
        XCloseDisplay(display)
        this.display = null
    }

    private fun setCounter(value: Long) {
        val display = display ?: return
        if (syncCounter == 0uL) return
        XSyncSetCounter(
            display,
            syncCounter,
            cValue<XSyncValue> {
                hi = (value ushr 32).toInt()
                lo = (value and LOW_HALF.toLong()).toUInt()
            },
        )
        XFlush(display)
    }

    private fun event(
        kind: Int,
        x: Float = 0f,
        y: Float = 0f,
        buttons: Int = 0,
        modifiers: Int = 0,
    ) = WindowEvent(kind, x, y, buttons, modifiers, 0, 0, "")

    private fun resized() {
        // The painter, or else the listener, draws and presents inside this call. One that
        // draws nothing still owes the manager its counter, which is paid here.
        presentedSinceEvent = false
        val painter = resizePainter
        if (painter != null) {
            painter()
        } else {
            listener?.onEvent(event(WindowEvent.RESIZE, measured.width.toFloat(), measured.height.toFloat()))
        }
        if (!presentedSinceEvent) sync.noFrame()
    }

    private fun handle(event: XEvent) {
        if (isClosed) return
        val taken = xim?.filter(event) == true
        if (taken && (event.type == KeyPress || event.type == KeyRelease)) return
        when (event.type) {
            MotionNotify -> {
                log.heard(
                    event(
                        WindowEvent.POINTER_MOVE,
                        event.xmotion.x.toFloat(),
                        event.xmotion.y.toFloat(),
                        buttonsOf(event.xmotion.state),
                        modifiersOf(event.xmotion.state),
                    ),
                )
                if (gesture.waiting) {
                    if (event.xmotion.state.toLong() and Button1Mask.toLong() == 0L) {
                        gesture.released()
                    } else {
                        val direction = gesture.moved(event.xmotion.x.toFloat(), event.xmotion.y.toFloat())
                        if (direction != CaptionGesture.NONE) moveResize(direction)
                    }
                }
            }

            ButtonPress, ButtonRelease -> {
                val button = event.xbutton.button.toInt()
                if (event.type == ButtonPress && button !in SCROLL_BUTTONS) finishComposition()
                if (button in SCROLL_BUTTONS) {
                    if (event.type == ButtonPress) {
                        log.heard(
                            event(
                                WindowEvent.SCROLL,
                                when (button) {
                                    WHEEL_LEFT -> -SCROLL_LINES
                                    WHEEL_RIGHT -> SCROLL_LINES
                                    else -> 0f
                                },
                                when (button) {
                                    WHEEL_UP -> -SCROLL_LINES
                                    WHEEL_DOWN -> SCROLL_LINES
                                    else -> 0f
                                },
                                0,
                                modifiersOf(event.xbutton.state),
                            ),
                        )
                    }
                } else {
                    if (event.type == ButtonPress && button == 1) {
                        pressX = event.xbutton.x.toFloat()
                        pressY = event.xbutton.y.toFloat()
                        pressNanos = monotonicClockNanos()
                    }
                    if (event.type == ButtonRelease && button == 1) gesture.released()
                    val bit = when (button) {
                        1 -> 1
                        3 -> 2
                        2 -> 4
                        else -> 0
                    }
                    val held = buttonsOf(event.xbutton.state)
                    log.heard(
                        event(
                            if (event.type == ButtonPress) WindowEvent.POINTER_DOWN else WindowEvent.POINTER_UP,
                            event.xbutton.x.toFloat(),
                            event.xbutton.y.toFloat(),
                            if (event.type == ButtonPress) held or bit else held and bit.inv(),
                            modifiersOf(event.xbutton.state),
                        ),
                    )
                }
            }

            KeyPress, KeyRelease -> for (heard in readKey(event)) log.heard(heard)

            ConfigureNotify -> {
                val width = event.xconfigure.width
                val height = event.xconfigure.height
                if (width == measured.width && height == measured.height) {
                    sync.noFrame()
                    return
                }
                measured = WindowMeasurement(width, height, DENSITY)
                resizeStats.step(width, height)
                detail("configure ${width}x$height")
                frameRequests.request()
                resized()
            }

            Expose -> if (event.xexpose.count == 0) {
                frameRequests.request()
                resized()
            }

            ClientMessage -> {
                if (xdnd?.handleClientMessage(event) == true) return
                if (event.xclient.message_type == protocolsAtom) {
                    when (event.xclient.data.l[0].toULong()) {
                        syncRequest -> {
                            val low = event.xclient.data.l[2].toULong() and LOW_HALF
                            val high = event.xclient.data.l[3].toULong() and LOW_HALF
                            sync.requested(((high shl 32) or low).toLong())
                        }

                        deleteWindow -> if (listener?.onCloseRequested() != false) isClosed = true
                    }
                }
            }

            DestroyNotify -> isClosed = true

            FocusIn -> if (event.xfocus.mode == NotifyNormal || event.xfocus.mode == NotifyWhileGrabbed) isFocused = true
            FocusOut -> if (event.xfocus.mode == NotifyNormal || event.xfocus.mode == NotifyWhileGrabbed) isFocused = false

            SelectionNotify -> xdnd?.handleSelectionNotify(event)
            SelectionRequest -> answerSelection(event)
            SelectionClear -> ownedClipboard = null
        }
    }

    /** Answers another client asking for the text this window owns on the clipboard. */
    private fun answerSelection(event: XEvent) {
        val display = display ?: return
        val request = event.xselectionrequest
        val text = ownedClipboard
        var property = if (request.property == 0uL) request.target else request.property
        if (text == null) {
            property = 0uL
        } else if (request.target == targetsAtom) {
            memScoped {
                val atoms = allocArray<ULongVar>(3)
                atoms[0] = targetsAtom
                atoms[1] = utf8Atom
                atoms[2] = XA_STRING_ATOM
                XChangeProperty(
                    display, request.requestor, property, XInternAtom(display, "ATOM", 0), 32,
                    PropModeReplace, atoms.reinterpret(), 3,
                )
            }
        } else if (request.target == utf8Atom || request.target == XA_STRING_ATOM) {
            val bytes = text.encodeToByteArray()
            memScoped {
                val data = allocArray<UByteVar>(bytes.size + 1)
                for (index in bytes.indices) data[index] = bytes[index].toUByte()
                XChangeProperty(
                    display, request.requestor, property, request.target, 8,
                    PropModeReplace, data, bytes.size,
                )
            }
        } else {
            property = 0uL
        }
        memScoped {
            val reply = alloc<XEvent>()
            reply.xselection.type = SelectionNotify
            reply.xselection.requestor = request.requestor
            reply.xselection.selection = request.selection
            reply.xselection.target = request.target
            reply.xselection.property = property
            reply.xselection.time = request.time
            XSendEvent(display, request.requestor, 0, 0, reply.ptr)
        }
        XFlush(display)
    }

    private fun readKey(event: XEvent): List<WindowEvent> {
        val press = event.type == KeyPress
        val keysym = XLookupKeysym(event.xkey.ptr, 0)
        val typed = if (!press) "" else xim?.lookup(event) ?: latinText(event)
        return keyEventsFor(press, event.xkey.state.toInt(), keysym.toLong(), typed)
    }

    /** What the key types without an input method: Latin-1, as `XLookupString` answers. */
    private fun latinText(event: XEvent): String = memScoped {
        val bytes = allocArray<ByteVar>(KEY_TEXT_BYTES)
        val count = XLookupString(event.xkey.ptr, bytes, KEY_TEXT_BYTES, null, null)
        buildString {
            for (index in 0 until count) append((bytes[index].toInt() and 0xFF).toChar())
        }
    }

    private companion object {
        const val EVENT_MASK = ExposureMask or StructureNotifyMask or PointerMotionMask or
            ButtonPressMask or ButtonReleaseMask or KeyPressMask or KeyReleaseMask or FocusChangeMask or
            0L
        const val SOURCE_APPLICATION = 1L
        const val SOURCE_PAGER = 2L
        const val PRIMARY_BUTTON = 1L
        const val MOTIF_HINT_WORDS = 5
        /** The flags word of `_MOTIF_WM_HINTS`: only the decorations field is meant. */
        const val MOTIF_HINTS_DECORATIONS = 2uL
        const val DENSITY = 1.0f
        const val WAKE_DRAIN_BYTES = 64
        const val CONFIRM_TIMEOUT_NANOS = 1_000_000_000L
        const val CONFIRM_SLICE_MILLIS = 50L
        const val LOW_HALF = 0xFFFFFFFFuL
        const val KEY_TEXT_BYTES = 32
        const val P_MIN_SIZE = 1L shl 4
        const val P_MAX_SIZE = 1L shl 5

        /** The predefined atom XA_STRING, which is the number 31 and a cast in the header. */
        const val XA_STRING_ATOM = 31uL
        const val CLIPBOARD_WAIT_MILLIS = 500
        const val CLIPBOARD_SLICE_MILLIS = 20
        const val CLIPBOARD_MAX_WORDS = 1_000_000L

        fun createSyncCounter(display: CPointer<Display>, window: Window): XSyncCounter =
            memScoped {
                val eventBase = alloc<IntVar>()
                val errorBase = alloc<IntVar>()
                val major = alloc<IntVar>()
                val minor = alloc<IntVar>()
                if (XSyncQueryExtension(display, eventBase.ptr, errorBase.ptr) == 0) return 0uL
                if (XSyncInitialize(display, major.ptr, minor.ptr) == 0) return 0uL
                val counter = XSyncCreateCounter(
                    display,
                    cValue<XSyncValue> {
                        hi = 0
                        lo = 0u
                    },
                )
                if (counter == 0uL) return 0uL
                val id = alloc<LongVar>()
                id.value = counter.toLong()
                XChangeProperty(
                    display,
                    window,
                    XInternAtom(display, "_NET_WM_SYNC_REQUEST_COUNTER", 0),
                    XInternAtom(display, "CARDINAL", 0),
                    32,
                    PropModeReplace,
                    id.ptr.reinterpret(),
                    1,
                )
                counter
            }
    }
}

/** `CLOCK_MONOTONIC` now, in nanoseconds. */
internal fun monotonicClockNanos(): Long = memScoped {
    val now = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, now.ptr)
    now.tv_sec * 1_000_000_000L + now.tv_nsec
}
