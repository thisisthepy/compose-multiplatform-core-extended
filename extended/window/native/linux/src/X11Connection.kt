@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

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
import kotlinx.cinterop.set
import kotlinx.cinterop.value
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
import x11.XPending
import x11.XRootWindow

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
        val open = openWindows().map { it.wakeDescriptor }.filter { it >= 0 }
        memScoped {
            val watched = allocArray<pollfd>(1 + open.size)
            watched[0].fd = XConnectionNumber(display)
            watched[0].events = POLLIN.toShort()
            for ((index, descriptor) in open.withIndex()) {
                watched[index + 1].fd = descriptor
                watched[index + 1].events = POLLIN.toShort()
            }
            poll(watched, (1 + open.size).convert(), timeoutMillis.toInt())
        }
    }

    companion object {
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
