@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import org.thisisthepy.compose.window.WindowApplication
import org.thisisthepy.compose.window.WindowBounds
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.AppKit.NSApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FR-53: several windows in one process, each its own NSWindow, through [MacosWindowPlatform].
 *
 * The coordinate and follow arithmetic is tested as plain functions, because the runner has no
 * pointer to move. Everything else opens real windows, as the other tests of this module do.
 */
class MultiWindowTest {
    private class Recorder(var allowClose: Boolean = true) : WindowListener {
        var closeRequests = 0
        val focus = mutableListOf<Boolean>()
        val moved = mutableListOf<WindowBounds>()
        var followEnded = 0

        override fun onEvent(event: WindowEvent) {}
        override fun onCloseRequested(): Boolean {
            closeRequests++
            return allowClose
        }
        override fun onFocusChanged(focused: Boolean) {
            focus += focused
        }
        override fun onMoved(bounds: WindowBounds) {
            moved += bounds
        }
        override fun onFollowEnded(cancelled: Boolean) {
            followEnded++
        }
    }

    private fun open(
        recorder: Recorder,
        title: String = "multi",
        x: Int? = null,
        y: Int? = null,
        width: Int = 320,
        height: Int = 200,
    ): MacosWindowPlatform {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        assertTrue(platform.open(WindowConfig(title = title, width = width, height = height, x = x, y = y), recorder))
        return platform
    }

    private fun pumpUntil(platform: MacosWindowPlatform, turns: Int = 60, until: () -> Boolean) {
        var n = 0
        while (!until() && n < turns) {
            platform.pump(50)
            n++
        }
    }

    private fun visible(platform: MacosWindowPlatform): Boolean = platform.nativeWindow?.visible == true

    @Test
    fun fr53_screen_conversion_flips_y_and_is_its_own_inverse() {
        val rect = appKitRectToTopLeft(100.0, 700.0, 300.0, 200.0, primaryHeight = 1000.0)
        assertEquals(TopLeftRect(100.0, 100.0, 300.0, 200.0), rect)
        val there = flipPoint(40.0, 250.0, 1000.0)
        assertEquals(40.0 to 750.0, there)
        assertEquals(40.0 to 250.0, flipPoint(there.first, there.second, 1000.0))
    }

    @Test
    fun fr53_follow_pointer_keeps_anchor_under_pointer() {
        val titleBar = 28.0
        val top = followFrameTopLeft(
            pointerX = 500.0, pointerY = 400.0, anchorX = 30.0, anchorY = 20.0, titleBarHeight = titleBar,
        )
        assertEquals(470.0 to 352.0, top)
        // The content point (30, 20) is the frame's top left plus the title bar plus the anchor.
        assertEquals(500.0, top.first + 30.0)
        assertEquals(400.0, top.second + titleBar + 20.0)
        // A window with no title bar over its content (full size content view) has none to step over.
        assertEquals(470.0 to 380.0, followFrameTopLeft(500.0, 400.0, 30.0, 20.0, 0.0))
    }

    @Test
    fun fr53_primary_button_is_bit_zero_of_the_pressed_buttons() {
        assertTrue(primaryButtonDown(1uL))
        assertTrue(primaryButtonDown(3uL))
        assertFalse(primaryButtonDown(0uL))
        assertFalse(primaryButtonDown(2uL))
    }

    @Test
    fun fr53_pump_turn_lets_one_instance_wait_per_turn() {
        val turn = PumpTurn(graceNanos = 1_000_000L)
        val a = Any()
        val b = Any()
        assertTrue(turn.claim(a, nowNanos = 0L, timeoutMillis = 100L))
        assertFalse(turn.claim(b, nowNanos = 10_000_000L, timeoutMillis = 100L), "second instance in the same turn")
        assertTrue(turn.claim(a, nowNanos = 20_000_000L, timeoutMillis = 100L), "the waiter starts the next turn")
        assertFalse(turn.claim(b, nowNanos = 30_000_000L, timeoutMillis = 100L))
        // The waiter is gone and nobody released it: its claim goes stale and b takes over.
        assertTrue(turn.claim(b, nowNanos = 500_000_000L, timeoutMillis = 100L))
        turn.release(b)
        assertTrue(turn.claim(a, nowNanos = 500_000_001L, timeoutMillis = 100L))
    }

    @Test
    fun fr53_two_windows_open_and_close_independently() {
        val ra = Recorder()
        val rb = Recorder()
        val a = open(ra, "multi a")
        val b = open(rb, "multi b")
        try {
            assertTrue(a.supportsMultipleWindows)
            assertTrue(a.nativeWindow !== b.nativeWindow, "each platform has its own NSWindow")
            assertTrue(visible(a))
            assertTrue(visible(b))
            assertEquals(2, MacosProcess.platforms.size)
            a.close()
            assertEquals(1, MacosProcess.platforms.size)
            assertTrue(visible(b))
            assertNotNull(b.bounds())
            b.pump(10)
            assertEquals(null, a.bounds(), "a closed platform has no frame")
        } finally {
            a.close()
            b.close()
        }
        assertEquals(0, MacosProcess.platforms.size)
    }

    @Test
    fun fr53_window_position_round_trips() {
        val recorder = Recorder()
        val platform = open(recorder, x = 100, y = 120)
        try {
            val opened = assertNotNull(platform.bounds())
            assertEquals(100, opened.x)
            assertEquals(120, opened.y)
            recorder.moved.clear()
            platform.setPosition(220, 160)
            val after = assertNotNull(platform.bounds())
            assertEquals(220, after.x)
            assertEquals(160, after.y)
            assertEquals(opened.width, after.width)
            pumpUntil(platform) { recorder.moved.isNotEmpty() }
            assertTrue(recorder.moved.isNotEmpty(), "onMoved reached the listener")
            assertEquals(220, recorder.moved.last().x)
            assertEquals(160, recorder.moved.last().y)
        } finally {
            platform.close()
        }
    }

    @Test
    fun fr53_content_size_is_set_and_the_top_left_corner_stays() {
        val platform = open(Recorder(), x = 90, y = 110)
        try {
            platform.setContentSize(400, 260)
            val bounds = assertNotNull(platform.bounds())
            assertEquals(90, bounds.x)
            assertEquals(110, bounds.y)
            assertEquals(400, bounds.width)
            assertTrue(bounds.height >= 260, "the frame is the content plus the title bar")
        } finally {
            platform.close()
        }
    }

    @Test
    fun fr53_pointer_is_read_in_the_top_left_space() {
        val platform = open(Recorder())
        try {
            val pointer = assertNotNull(platform.pointerOnScreen())
            assertTrue(pointer.x.isFinite() && pointer.y.isFinite())
        } finally {
            platform.close()
        }
    }

    @Test
    fun fr53_follow_pointer_is_refused_while_the_button_is_up() {
        val platform = open(Recorder())
        try {
            if (platform.pointerOnScreen()?.primaryDown != true) {
                assertFalse(platform.followPointer(10f, 10f))
            }
        } finally {
            platform.close()
        }
    }

    @Test
    fun fr53_close_button_reaches_listener() {
        val recorder = Recorder(allowClose = false)
        val platform = open(recorder)
        try {
            platform.nativeWindow!!.performClose(null)
            assertEquals(1, recorder.closeRequests)
            assertTrue(visible(platform), "the listener said no, so the window stays")
            recorder.allowClose = true
            platform.nativeWindow!!.performClose(null)
            assertEquals(2, recorder.closeRequests)
            assertFalse(visible(platform), "the listener said yes, so the window closed")
        } finally {
            platform.close()
        }
    }

    @Test
    fun fr53_closing_one_window_keeps_the_other() {
        val ra = Recorder()
        val rb = Recorder()
        val a = open(ra, "multi a")
        val b = open(rb, "multi b")
        try {
            a.nativeWindow!!.performClose(null)
            assertEquals(1, ra.closeRequests)
            assertEquals(0, rb.closeRequests)
            assertFalse(visible(a))
            assertTrue(visible(b))
            b.pump(20)
            assertTrue(visible(b), "the application did not end with the first window")
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun fr53_focus_makes_the_window_key_when_the_system_allows_it() {
        val recorder = Recorder()
        val platform = open(recorder)
        try {
            platform.focus()
            pumpUntil(platform, turns = 20) { platform.isKey }
            // A runner without a logged-in session may refuse key status; what must hold is that
            // the report and the state agree.
            if (platform.isKey) assertTrue(recorder.focus.lastOrNull() == true)
        } finally {
            platform.close()
        }
    }

    @Test
    fun fr53_application_delegate_reports_reopen_and_does_not_end_the_app() {
        val delegate = MacosAppDelegate()
        val app = NSApplication.sharedApplication()
        var reopened = 0
        WindowApplication.onReopen = { reopened++ }
        try {
            delegate.applicationShouldHandleReopen(app, hasVisibleWindows = false)
            assertEquals(1, reopened)
            assertFalse(delegate.applicationShouldTerminateAfterLastWindowClosed(app))
        } finally {
            WindowApplication.onReopen = null
        }
    }

    @Test
    fun fr53_pumping_every_instance_in_a_turn_waits_once() {
        val a = open(Recorder(), "multi a")
        val b = open(Recorder(), "multi b")
        try {
            a.pump(10)
            b.pump(10)
            val start = uptimeNanos()
            a.pump(250)
            b.pump(250)
            val elapsedMillis = (uptimeNanos() - start) / 1_000_000.0
            assertTrue(elapsedMillis < 450.0, "two pumps in one turn took ${elapsedMillis}ms; one wait is 250")
        } finally {
            a.close()
            b.close()
        }
    }
}
