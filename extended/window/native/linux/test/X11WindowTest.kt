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
import kotlinx.cinterop.alloc
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

    private fun openWindow(title: String, width: Int, height: Int): X11Window? {
        val window = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        return if (window.open(WindowConfig(title, width, height), listener)) window else null
    }
}
