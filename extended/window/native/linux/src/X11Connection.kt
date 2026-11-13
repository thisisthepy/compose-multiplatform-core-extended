@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.X11Scale
import platform.posix.getenv
import platform.posix.POLLIN
import platform.posix.poll
import platform.posix.pollfd
import x11.Display
import x11.Window
import x11.XCloseDisplay
import x11.XConnectionNumber
import x11.XDefaultScreen
import x11.XEvent
import x11.XFilterEvent
import x11.XFree
import x11.XGetWindowProperty
import x11.XInternAtom
import x11.XNextEvent
import x11.XOpenDisplay
import x11.PropertyChangeMask
import x11.PropertyNotify
import x11.XPending
import x11.XRootWindow
import x11.XSelectInput

/**
 * What the process has once however many windows it opens: the connection to the display
 * server, the atoms interned on it and the input method.
 *
 * Windows [acquire] it when they open and [release] it when they close; the last release closes
 * the connection. Events are read here, once per turn, and handed to the window named by the
 * event's window id, so a window's listener hears only its own window.
 *
 * Everything runs on the thread that pumps. Nothing here is locked.
 */
internal class X11Connection private constructor(val display: CPointer<Display>) {

    val screen: Int = XDefaultScreen(display)
    val root: Window = XRootWindow(display, screen)

    val deleteWindow = XInternAtom(display, "WM_DELETE_WINDOW", 0)
    val protocols = XInternAtom(display, "WM_PROTOCOLS", 0)
    val syncRequest = XInternAtom(display, "_NET_WM_SYNC_REQUEST", 0)
    val clipboard = XInternAtom(display, "CLIPBOARD", 0)
    val utf8 = XInternAtom(display, "UTF8_STRING", 0)
    val targets = XInternAtom(display, "TARGETS", 0)
    val transfer = XInternAtom(display, "COMPOSE_WINDOW_TRANSFER", 0)
    val frameExtents = XInternAtom(display, "_NET_FRAME_EXTENTS", 0)
    private val supportingCheck = XInternAtom(display, "_NET_SUPPORTING_WM_CHECK", 0)

    /**
     * How many pixels go to a point, as the root window's `RESOURCE_MANAGER` says now (see
     * [X11Scale]). Read when the connection opens and again whenever a desktop rewrites the
     * property, which is how a change of `Xft.dpi` in its settings arrives.
     */
    var scale: Float = readScale()
        private set

    init {
        // Only this client's interest in the root window: what other clients select there is theirs.
        XSelectInput(display, root, PropertyChangeMask)
    }

    private val windows = LinkedHashMap<Window, X11Window>()
    private var references = 0
    private var reading = false
    private var method: XimMethod? = null
    private var methodTried = false

    /** How many windows are using the connection. */
    val users: Int get() = references

    /** The windows registered now, in the order they opened. */
    fun openWindows(): List<X11Window> = windows.values.toList()

    /** The window with this X id, or null for one that is not ours or is gone. */
    fun windowFor(id: Window): X11Window? = windows[id]

    fun register(id: Window, window: X11Window) {
        windows[id] = window
    }

    fun unregister(id: Window) {
        windows.remove(id)
    }

    /** The one input method, opened when the first window asks; null where there is none. */
    fun inputMethod(): XimMethod? {
        if (!methodTried) {
            methodTried = true
            method = XimContext.openMethod(display)
        }
        return method
    }

    /** Whether an EWMH window manager is running, which is what makes `_NET_ACTIVE_WINDOW` mean anything. */
    fun hasWindowManager(): Boolean = memScoped {
        val type = alloc<ULongVar>()
        val format = alloc<IntVar>()
        val count = alloc<ULongVar>()
        val remaining = alloc<ULongVar>()
        val data = alloc<CPointerVar<UByteVar>>()
        val status = XGetWindowProperty(
            display, root, supportingCheck, 0, 1, 0, 0uL,
            type.ptr, format.ptr, count.ptr, remaining.ptr, data.ptr,
        )
        val bytes = data.value
        if (bytes != null) XFree(bytes)
        status == 0 && count.value > 0uL
    }

    /**
     * The scale the root window's resources give now. Asked of the server every time rather
     * than through `XResourceManagerString`, which is the copy Xlib took when the connection
     * opened and never changes after.
     */
    private fun readScale(): Float {
        val resources = memScoped {
            val type = alloc<ULongVar>()
            val format = alloc<IntVar>()
            val count = alloc<ULongVar>()
            val remaining = alloc<ULongVar>()
            val data = alloc<CPointerVar<UByteVar>>()
            val status = XGetWindowProperty(
                display, root, RESOURCE_MANAGER, 0, RESOURCE_MAX_WORDS, 0, STRING,
                type.ptr, format.ptr, count.ptr, remaining.ptr, data.ptr,
            )
            val bytes = data.value
            val text = if (status == 0 && bytes != null && format.value == 8 && count.value > 0uL) {
                bytes.reinterpret<ByteVar>().readBytes(count.value.toInt()).decodeToString()
            } else {
                null
            }
            if (bytes != null) XFree(bytes)
            text
        }
        // The desktop's text size (GNOME's factor, KDE's forced font DPI) is in Xft.dpi too; the
        // window reports it as its text scale, so it is taken out of the window scale here. Read
        // now: the desktop rewrites the resources when it changes, which brings this here again.
        val text = LinuxTextScale.factor()
        return X11Scale.scaleOf(resources, getenv("GDK_SCALE")?.toKString(), text?.factor, text?.desktopScale)
    }

    /** A property of the root window changed: when it is the resources, the scale is read again and every window told. */
    private fun rootPropertyChanged(atom: ULong) {
        if (atom != RESOURCE_MANAGER) return
        rescale()
    }

    /**
     * Reads the scale again and tells every window when it changed: on a rewrite of the
     * resources, and when a window sees the text size change, since the desktop's text size is
     * taken out of `Xft.dpi` (see [readScale]).
     */
    fun rescale() {
        val now = readScale()
        if (now == scale) return
        scale = now
        for (window in openWindows()) window.scaleChanged(now)
    }

    /**
     * Gives up one use of the connection. The last one closes the input method and the display.
     * Windows are destroyed by their owners before this is called.
     */
    fun release() {
        references -= 1
        if (references > 0) return
        method?.close()
        method = null
        XCloseDisplay(display)
        if (shared === this) shared = null
    }

    /**
     * Waits at most [timeoutMillis] for the server (or a wake of any open window), reads every
     * event there is and routes each to its window, then lets each window deliver what it heard.
     */
    fun pump(timeoutMillis: Long) {
        if (timeoutMillis > 0 && XPending(display) == 0) wait(timeoutMillis)
        for (window in openWindows()) window.drainWakes()
        if (!reading) {
            reading = true
            try {
                memScoped {
                    val event = alloc<XEvent>()
                    while (XPending(display) > 0) {
                        XNextEvent(display, event.ptr)
                        val owner = windows[event.xany.window]
                        if (owner != null) {
                            owner.handle(event)
                        } else if (event.xany.window == root && event.type == PropertyNotify) {
                            rootPropertyChanged(event.xproperty.atom)
                        } else if (method != null) {
                            // The input method's own messages arrive as ordinary events on windows
                            // that are not ours; left unfiltered, its conversation never ends.
                            XFilterEvent(event.ptr, 0uL)
                        }
                    }
                }
            } finally {
                reading = false
            }
        }
        for (window in openWindows()) window.deliver()
    }

    private fun wait(timeoutMillis: Long) {
        val windows = openWindows()
        // The wake pipes, and the bus sockets of open file dialogs: a dialog's answer ends
        // the wait the way an event does, so it is not held until the next one.
        val others = windows.map { it.wakeDescriptor }.filter { it >= 0 } +
            windows.flatMap { it.busDescriptors() }.filter { it >= 0 }
        memScoped {
            val watched = allocArray<pollfd>(1 + others.size)
            watched[0].fd = XConnectionNumber(display)
            watched[0].events = POLLIN.toShort()
            for ((index, descriptor) in others.withIndex()) {
                watched[index + 1].fd = descriptor
                watched[index + 1].events = POLLIN.toShort()
            }
            poll(watched, (1 + others.size).convert(), timeoutMillis.toInt())
        }
    }

    companion object {
        /** The predefined atoms `RESOURCE_MANAGER` and `STRING`, which the header gives as casts. */
        private const val RESOURCE_MANAGER = 23uL
        private const val STRING = 31uL

        /** Far more than any desktop writes into the resources, in 32-bit units as the request counts. */
        private const val RESOURCE_MAX_WORDS = 1L shl 20

        private var shared: X11Connection? = null

        /** The process's connection with one more user, opening it if there is none; null where there is no display server. */
        fun acquire(): X11Connection? {
            val existing = shared
            if (existing != null) {
                existing.references += 1
                return existing
            }
            val display = XOpenDisplay(null) ?: return null
            val made = X11Connection(display)
            made.references = 1
            shared = made
            return made
        }

        /**
         * Lets the next [acquire] open a connection of its own, leaving the one in use to the
         * windows that hold it. For a test that must see what opening does with no display
         * server while other tests' windows are still open.
         */
        fun forgetShared() {
            shared = null
        }
    }
}
