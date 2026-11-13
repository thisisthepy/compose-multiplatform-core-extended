@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import x11.*

/**
 * What the window does when there is no display server to open one on.
 *
 * The only thing about the window itself that can be asserted where no X server is running, which
 * is every continuous integration machine and most of the machines this is developed on. It is
 * worth asserting because the alternative behaviours are both bad and both have happened to
 * windowing code: a throw that unwinds into the C entry point, which is undefined, and a process
 * that carries on with a null display and dies at the first call that dereferences it.
 *
 * Everything else about this window needs a screen and a hand: whether the drawing arrives with
 * the moved edge is a fact about what a compositor showed. `ResizeSyncTest` and
 * `WindowEventLogTest` on the JVM cover the two ways the code can get that wrong.
 */
class X11WindowTest {

    @Test
    fun fr53_linux_live_resizable_and_minimum_size_update_window_manager_hints() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val window = assertNotNull(openWindow("size hints", 320, 240))
        try {
            val display = assertNotNull(glXGetCurrentDisplay())
            val drawable = glXGetCurrentDrawable()
            fun hints(): Triple<Long, Pair<Int, Int>, Pair<Int, Int>> = memScoped {
                val hints = assertNotNull(XAllocSizeHints())
                try {
                    val supplied = alloc<LongVar>()
                    assertTrue(XGetWMNormalHints(display, drawable, hints, supplied.ptr) != 0)
                    Triple(hints.pointed.flags, hints.pointed.min_width to hints.pointed.min_height,
                        hints.pointed.max_width to hints.pointed.max_height)
                } finally {
                    XFree(hints)
                }
            }
            window.setMinimumSize(100, 80)
            window.setResizable(false)
            val fixed = hints()
            assertTrue(fixed.first and PMaxSize.toLong() != 0L)
            assertEquals(320 to 240, fixed.second)
            assertEquals(fixed.second, fixed.third)
            window.setMinimumSize(120, 90)
            window.setResizable(true)
            val restored = hints()
            assertTrue(restored.first and PMinSize.toLong() != 0L)
            assertEquals(0L, restored.first and PMaxSize.toLong())
            assertEquals(120 to 90, restored.second)
            window.setMinimumSize(0, 0)
            assertEquals(0L, hints().first and (PMinSize.toLong() or PMaxSize.toLong()))
        } finally {
            window.close()
        }
    }

    /**
     * A window opens where there is a display server to open it on.
     *
     * The other test here is about the machine that has none. This is about the one that has,
     * and what it covers is everything `open` does before a frame is ever asked for: choosing a
     * visual with a depth buffer, making a colormap for it, creating the window, and making a
     * GLX context current on it. Every one of those can fail against a real server and none of
     * them can fail without one, which is why this could not be written until there was a Linux
     * to run it on.
     *
     * Skipped where there is no display. The machine that has one answers this; the machine that
     * has none is answered by the test below.
     */
    @Test
    fun nfr1_a_window_opens_where_there_is_a_display_server() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val window = openWindow("opens", 320, 240)
        assertNotNull(
            window,
            "a display server is present and the window did not open: the visual, the colormap, " +
                "the window or the GL context was refused",
        )
    }

    @Test
    fun nfr1_a_window_refuses_to_open_where_there_is_no_display_server() {
        // Taken away rather than pointed somewhere wrong. An empty DISPLAY and a DISPLAY naming a
        // server that is not there are different failures inside Xlib, and the one a machine with
        // no X server actually has is this one.
        // Put back afterwards, because the tests that need a display run in this process too.
        val display = getenv("DISPLAY")?.toKString()
        // A window another test left open keeps the process's connection alive, and a window
        // opens on that one; this test is about opening on none.
        X11Connection.forgetShared()
        unsetenv("DISPLAY")
        try {
            assertNull(
                openWindow("no display", 520, 360),
                "a window with no display server to open on answers null rather than throwing, " +
                    "because what called in is a C entry point and a Kotlin exception must not " +
                    "cross back over it",
            )
        } finally {
            if (display != null) setenv("DISPLAY", display, 1)
        }
    }

    /**
     * The scale follows `Xft.dpi` in the root window's resources, read when the window opens
     * and again when a desktop rewrites them, as `xrdb -merge` does: the window keeps its size
     * in points (FR-82), so it is resized to that size times the new scale, measures at the new
     * size and scale and tells its listener. A window opened afterwards is made at its size in
     * points times the scale.
     */
    @Test
    fun fr81_linux_scale_follows_xft_dpi_on_the_root_window() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        // A forced scale wins over Xft.dpi, so there would be nothing to see.
        if (!getenv("GDK_SCALE")?.toKString().isNullOrEmpty()) return
        val peer = assertNotNull(XOpenDisplay(null))
        val root = XDefaultRootWindow(peer)
        val before = resources(peer, root)
        val heard = mutableListOf<Float>()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
            override fun onScaleChanged(scale: Float) {
                heard += scale
            }
        }
        val window = X11Window()
        var second: X11Window? = null
        try {
            setResources(peer, root, "Xft.antialias:\t1\nXft.dpi:\t96\n")
            assertTrue(window.open(WindowConfig("scale", 200, 100), listener))
            // A connection another test left open hears the change of resources on its next turn.
            pumpUntil(window) { window.measure().scale == 1f }
            assertEquals(1f, window.measure().scale)
            val opened = window.measure()

            setResources(peer, root, "Xft.antialias:\t1\nXft.dpi:\t192\n")
            assertTrue(pumpUntil(window) { heard.lastOrNull() == 2f }, "no scale change heard, heard $heard")
            val doubled = window.measure()
            assertEquals(2f, doubled.scale)
            assertEquals(opened.width * 2 to opened.height * 2, doubled.width to doubled.height)
            // The server's size event for the resize would correct the measurement were the
            // window any other size.
            repeat(30) { window.pump(10) }
            assertEquals(Triple(400, 200, 2f), window.measure().let { Triple(it.width, it.height, it.scale) })

            val later = X11Window()
            second = later
            assertTrue(later.open(WindowConfig("scale 2", 100, 50), object : WindowListener {
                override fun onEvent(event: WindowEvent) {}
            }))
            assertEquals(Triple(200, 100, 2f), later.measure().let { Triple(it.width, it.height, it.scale) })

            setResources(peer, root, "Xft.dpi:\t144\n")
            assertTrue(pumpUntil(window) { heard.lastOrNull() == 1.5f }, "no change to 1.5 heard, heard $heard")
            assertEquals(Triple(300, 150, 1.5f), window.measure().let { Triple(it.width, it.height, it.scale) })
        } finally {
            second?.close()
            window.close()
            if (before == null) {
                XDeleteProperty(peer, root, RESOURCE_MANAGER)
            } else {
                setResources(peer, root, before)
            }
            XFlush(peer)
            XCloseDisplay(peer)
        }
    }

    private fun pumpUntil(window: X11Window, done: () -> Boolean): Boolean {
        repeat(300) {
            window.pump(10)
            if (done()) return true
        }
        return done()
    }

    private fun resources(display: CPointer<Display>, root: Window): String? = memScoped {
        val type = alloc<ULongVar>()
        val format = alloc<IntVar>()
        val count = alloc<ULongVar>()
        val remaining = alloc<ULongVar>()
        val data = alloc<CPointerVar<UByteVar>>()
        val status = XGetWindowProperty(
            display, root, RESOURCE_MANAGER, 0, 1L shl 20, 0, XA_STRING_ATOM,
            type.ptr, format.ptr, count.ptr, remaining.ptr, data.ptr,
        )
        val bytes = data.value
        val text = if (status == 0 && bytes != null && count.value > 0uL) {
            bytes.reinterpret<ByteVar>().readBytes(count.value.toInt()).decodeToString()
        } else {
            null
        }
        if (bytes != null) XFree(bytes)
        text
    }

    private fun setResources(display: CPointer<Display>, root: Window, text: String) {
        val bytes = text.encodeToByteArray()
        memScoped {
            val data = allocArray<UByteVar>(bytes.size + 1)
            for (index in bytes.indices) data[index] = bytes[index].toUByte()
            XChangeProperty(display, root, RESOURCE_MANAGER, XA_STRING_ATOM, 8, PropModeReplace, data, bytes.size)
        }
        XFlush(display)
    }

    private fun openWindow(title: String, width: Int, height: Int): X11Window? {
        val window = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        return if (window.open(WindowConfig(title, width, height), listener)) window else null
    }

    private companion object {
        /** The predefined atoms `RESOURCE_MANAGER` and `STRING`. */
        const val RESOURCE_MANAGER = 23uL
        const val XA_STRING_ATOM = 31uL
    }
}
