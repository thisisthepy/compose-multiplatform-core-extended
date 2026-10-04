@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

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
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.ResizeSync
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowEventLog
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility
import platform.posix.POLLIN
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

    private var measured = WindowMeasurement(0, 0, DENSITY)
    private var presentedSinceEvent = false
    private var frameWanted = true
    private var ownedClipboard: String? = null

    private val log = WindowEventLog()
    private val drained = mutableListOf<WindowEvent>()
    private val ime = ImeSession { event -> log.heard(event) }
    private var xim: XimContext? = null
    private var lastSpot: Pair<Int, Int>? = null
    private var cursorShape = -1
    private val cursors = LongArray(CURSOR_SHAPES)

    private val sync = ResizeSync(
        present = { surface?.present() },
        tell = { value -> setCounter(value) },
    )

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
        measured = WindowMeasurement(config.width, config.height, DENSITY)
        surface = GlSurface(display, window, context)
        xim = XimContext.open(display, window, ime)?.also { input ->
            XSelectInput(display, window, EVENT_MASK or input.filterMask)
        }
        if (config.minWidth > 0 || config.minHeight > 0) setMinimumSize(config.minWidth, config.minHeight)
        XMapWindow(display, window)
        XFlush(display)
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val display = display ?: return
        if (timeoutMillis > 0 && XPending(display) == 0) {
            memScoped {
                val watched = alloc<pollfd>()
                watched.fd = XConnectionNumber(display)
                watched.events = POLLIN.toShort()
                poll(watched.ptr, 1.convert(), timeoutMillis.toInt())
            }
        }
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
        val target = listener ?: return
        for (event in drained) target.onEvent(event)
    }

    override fun measure(): WindowMeasurement = measured

    override fun requestFrame() {
        frameWanted = true
    }

    /** Whether a frame was asked for since the last call. Reading it clears it. */
    // Local flag: replaced by the common FrameRequests coalescing once common part 2 lands.
    fun takeFrameRequest(): Boolean {
        val wanted = frameWanted
        frameWanted = false
        return wanted
    }

    /**
     * Draws one frame at [width] by [height] pixels into the back buffer. Answers false where
     * there was nothing to draw into. Follow a true answer with [present].
     */
    fun draw(width: Int, height: Int, paint: (Canvas) -> Unit): Boolean =
        surface?.draw(width, height, paint) ?: false

    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        sync.frameDrawn()
        presentedSinceEvent = true
        display?.let { XFlush(it) }
        return FramePresentRecord(drawnWidth, drawnHeight, measured.width, measured.height)
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
        val display = display ?: return
        XStoreName(display, window, title)
        XFlush(display)
    }

    override fun setMinimumSize(width: Int, height: Int) {
        val display = display ?: return
        val hints = XAllocSizeHints() ?: return
        hints.pointed.flags = P_MIN_SIZE
        hints.pointed.min_width = width
        hints.pointed.min_height = height
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
        XFlush(display)
    }

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
        // The listener draws and presents inside this call. A listener that draws nothing
        // still owes the manager its counter, which is paid here.
        presentedSinceEvent = false
        listener?.onEvent(event(WindowEvent.RESIZE, measured.width.toFloat(), measured.height.toFloat()))
        if (!presentedSinceEvent) sync.noFrame()
    }

    private fun handle(event: XEvent) {
        if (isClosed) return
        val taken = xim?.filter(event) == true
        if (taken && (event.type == KeyPress || event.type == KeyRelease)) return
        when (event.type) {
            MotionNotify -> log.heard(
                event(
                    WindowEvent.POINTER_MOVE,
                    event.xmotion.x.toFloat(),
                    event.xmotion.y.toFloat(),
                    buttonsOf(event.xmotion.state),
                    modifiersOf(event.xmotion.state),
                ),
            )

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
                frameWanted = true
                resized()
            }

            Expose -> if (event.xexpose.count == 0) {
                frameWanted = true
                resized()
            }

            ClientMessage -> {
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
        return keyEventsFor(press, event.xkey.state, keysym, typed)
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
        const val DENSITY = 1.0f
        const val LOW_HALF = 0xFFFFFFFFuL
        const val KEY_TEXT_BYTES = 32
        const val P_MIN_SIZE = 1L shl 4

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
