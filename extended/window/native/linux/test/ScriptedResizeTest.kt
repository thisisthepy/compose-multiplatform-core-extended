@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * The scripted drag the parity check measures with, and the count it is measured by: each
 * size the server gives the window is a step, and a frame presented inside its handling
 * answers it.
 *
 * Skipped where there is no display. CI runs these under Xvfb, where no window manager stands
 * between the request and the size, so every step arrives.
 */
class ScriptedResizeTest {
    private val noDisplay: Boolean get() = getenv("DISPLAY")?.toKString().isNullOrEmpty()

    private fun openWindow(onResize: (X11Window, WindowEvent) -> Unit): X11Window {
        val window = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {
                if (event.kind == WindowEvent.RESIZE) onResize(window, event)
            }
        }
        assertTrue(window.open(WindowConfig("resize", 320, 240), listener), "no window on a display that is there")
        repeat(30) { window.pump(10) }
        return window
    }

    @Test
    fun parity_a_drag_answered_with_a_frame_at_every_size_counts_nothing_stretched_or_stale() {
        if (noDisplay) return
        val window = openWindow { target, event -> target.present(event.x.toInt(), event.y.toInt()) }
        try {
            window.scriptedResize(360, 420, 10, 5)
            val stats = window.resizeStats
            assertTrue(stats.steps >= 1, "the scripted drag took the window through no sizes")
            assertEquals(0L, stats.stretched, stats.report())
            assertEquals(0L, stats.stale, stats.report())
            assertEquals(360 to 420, window.measure().let { it.width to it.height })
        } finally {
            window.close()
        }
    }

    /**
     * A drag with no pause between sizes, judged against what the server says at each present:
     * the next size is asked for only once this one is confirmed and drawn, so no frame is
     * presented at a size the server has already left.
     */
    @Test
    fun parity_a_drag_presents_no_frame_at_a_size_the_server_has_left() {
        if (noDisplay) return
        // Read when the window is made: each present asks the server for the window's size.
        setenv("DXC_REPORT_RESIZE", "1", 1)
        val window = try {
            openWindow { target, event -> target.present(event.x.toInt(), event.y.toInt()) }
        } finally {
            unsetenv("DXC_REPORT_RESIZE")
        }
        try {
            window.scriptedResize(300, 360, 30, 0)
            val stats = window.resizeStats
            assertTrue(stats.presented >= 1, "the drag presented nothing: ${stats.report()}")
            assertEquals(0L, stats.stale, stats.report())
            assertEquals(300 to 360, window.measure().let { it.width to it.height })
        } finally {
            window.close()
        }
    }

    @Test
    fun parity_a_drag_with_no_frames_counts_every_size_stretched() {
        if (noDisplay) return
        val window = openWindow { _, _ -> }
        try {
            window.scriptedResize(360, 420, 10, 5)
            val stats = window.resizeStats
            assertTrue(stats.steps >= 1, "the scripted drag took the window through no sizes")
            assertEquals(stats.steps, stats.stretched, stats.report())
            assertEquals(0L, stats.presented)
        } finally {
            window.close()
        }
    }
}
