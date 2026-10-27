@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowBounds
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.F_GETFD
import platform.posix.fcntl
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import platform.posix.system
import x11.ClientMessage
import x11.MotionNotify
import x11.PointerMotionMask
import x11.XCloseDisplay
import x11.XConnectionNumber
import x11.XEvent
import x11.XFlush
import x11.XInternAtom
import x11.XOpenDisplay
import x11.XSendEvent

/**
 * FR-53, several windows in one process on X11: one connection, events routed by window,
 * position, focus and the pointer follow. Needs a display; CI runs them under Xvfb with no window
 * manager, so a window is where it was put and a frame has no extents. The follow tests press
 * the pointer with xdotool (XTest).
 */
class MultiWindowTest {

    private class Recorder(private val answerClose: Boolean = true) : WindowListener {
        val events = mutableListOf<WindowEvent>()
        val focus = mutableListOf<Boolean>()
        val moves = mutableListOf<WindowBounds>()
        val ended = mutableListOf<Boolean>()
        var closeRequests = 0

        override fun onEvent(event: WindowEvent) {
            events += event
        }

        override fun onCloseRequested(): Boolean {
            closeRequests += 1
            return answerClose
        }

        override fun onFocusChanged(focused: Boolean) {
            focus += focused
        }

        override fun onMoved(bounds: WindowBounds) {
            moves += bounds
        }

        override fun onFollowEnded(cancelled: Boolean) {
            ended += cancelled
        }

        fun pointerMoves() = events.filter { it.kind == WindowEvent.POINTER_MOVE }
    }

    /** Another client of the display server, to send a window the events the server would. */
    private class Peer {
        val display = XOpenDisplay(null)!!

        fun motion(id: ULong, x: Int, y: Int) {
            memScoped {
                val event = alloc<XEvent>()
                event.xmotion.type = MotionNotify
                event.xmotion.window = id
                event.xmotion.x = x
                event.xmotion.y = y
                XSendEvent(display, id, 0, PointerMotionMask, event.ptr)
            }
            XFlush(display)
        }

        fun deleteRequest(id: ULong) {
            memScoped {
                val event = alloc<XEvent>()
                event.xclient.type = ClientMessage
                event.xclient.window = id
                event.xclient.message_type = XInternAtom(display, "WM_PROTOCOLS", 0)
                event.xclient.format = 32
                event.xclient.data.l[0] = XInternAtom(display, "WM_DELETE_WINDOW", 0).toLong()
                event.xclient.data.l[1] = 0
                XSendEvent(display, id, 0, 0, event.ptr)
            }
            XFlush(display)
        }

        fun close() {
            XCloseDisplay(display)
        }
    }

    private fun hasDisplay(): Boolean {
        val present = !getenv("DISPLAY")?.toKString().isNullOrEmpty()
        if (!present) assertTrue(getenv("CI") == null, "no DISPLAY under CI, so FR-53 on X11 was not tested")
        return present
    }

    private fun open(listener: Recorder, x: Int? = 20, y: Int? = 20, width: Int = 200, height: Int = 150): X11Window {
        val window = X11Window()
        assertTrue(window.open(WindowConfig("fr53", width, height, x = x, y = y), listener), "no window on a display that is there")
        return window
    }

    private fun pumpUntil(on: X11Window, millis: Long = 3_000, done: () -> Boolean): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < millis) {
            on.pump(10)
            if (done()) return true
        }
        return done()
    }

    /** Mapping, exposure and the first configure, read and answered, so what follows is idle. */
    private fun settle(on: X11Window) {
        repeat(30) { on.pump(10) }
    }

    private fun millisOf(block: () -> Unit): Long {
        val started = TimeSource.Monotonic.markNow()
        block()
        return started.elapsedNow().inWholeMilliseconds
    }

    private fun xdotool(arguments: String): Boolean {
        if (system("command -v xdotool >/dev/null 2>&1") != 0) {
            assertTrue(getenv("CI") == null, "no xdotool under CI, so the pointer could not be driven")
            return false
        }
        return system("xdotool $arguments") == 0
    }

    @Test
    fun fr53_x11_window_supports_multiple_windows() {
        assertTrue(X11Window().supportsMultipleWindows)
    }

    @Test
    fun fr53_two_x11_windows_share_one_connection() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val first = open(Recorder())
        val second = open(Recorder())
        try {
            val shared = assertNotNull(first.sharedConnection)
            assertSame(shared, second.sharedConnection, "two windows opened two connections")
            assertEquals(2, shared.users)
            assertTrue(first.windowId != second.windowId)
            assertSame(first, shared.windowFor(first.windowId))
            assertSame(second, shared.windowFor(second.windowId))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun fr53_events_reach_the_window_they_belong_to() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heardFirst = Recorder()
        val heardSecond = Recorder()
        val first = open(heardFirst)
        val second = open(heardSecond)
        val peer = Peer()
        try {
            settle(first)
            peer.motion(second.windowId, 77, 33)
            // Pumped through the first window: the second hears its own event.
            assertTrue(pumpUntil(first) { heardSecond.pointerMoves().isNotEmpty() }, "the second window heard nothing")
            val moved = heardSecond.pointerMoves().single()
            assertEquals(77f, moved.x)
            assertEquals(33f, moved.y)
            assertTrue(heardFirst.pointerMoves().isEmpty(), "the first window heard the second one's pointer")
            peer.motion(first.windowId, 5, 6)
            assertTrue(pumpUntil(second) { heardFirst.pointerMoves().isNotEmpty() })
            assertEquals(5f, heardFirst.pointerMoves().single().x)
            assertEquals(1, heardSecond.pointerMoves().size)
        } finally {
            peer.close()
            first.close()
            second.close()
        }
    }

    @Test
    fun fr53_closing_one_window_keeps_the_other() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heardFirst = Recorder()
        val heardSecond = Recorder()
        val first = open(heardFirst)
        val second = open(heardSecond)
        val peer = Peer()
        try {
            settle(first)
            val shared = assertNotNull(first.sharedConnection)
            first.close()
            assertEquals(1, shared.users)
            assertTrue(first.isClosed)
            assertFalse(second.isClosed)
            assertTrue(second.makeCurrent(), "the other window lost its context when the first closed")
            peer.motion(second.windowId, 9, 8)
            assertTrue(pumpUntil(second) { heardSecond.pointerMoves().isNotEmpty() }, "the other window stopped hearing")
            first.pump(0)
            second.close()
            assertNull(second.sharedConnection)
            // The last window closed the connection; a new window opens a new one.
            val third = open(Recorder())
            try {
                assertEquals(1, assertNotNull(third.sharedConnection).users)
            } finally {
                third.close()
            }
        } finally {
            peer.close()
            first.close()
            second.close()
        }
    }

    @Test
    fun fr53_last_window_closes_the_connection_and_its_descriptor() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val window = open(Recorder())
        val shared = assertNotNull(window.sharedConnection)
        val descriptor = XConnectionNumber(shared.display)
        assertTrue(fcntl(descriptor, F_GETFD) >= 0, "the connection's descriptor is not open while a window uses it")
        window.close()
        assertNull(window.sharedConnection)
        assertEquals(-1, fcntl(descriptor, F_GETFD), "the last window closed and the connection's descriptor leaked")
    }

    @Test
    fun nfr1_a_window_opened_after_the_last_one_closed_reconnects_and_fails_cleanly_without_a_display() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val first = open(Recorder())
        first.close()
        val display = getenv("DISPLAY")?.toKString()
        unsetenv("DISPLAY")
        try {
            // Nothing is shared any more, so this goes to the server again and finds none, and
            // answers false instead of reusing a closed connection or throwing.
            assertFalse(X11Window().open(WindowConfig("no display", 200, 150), Recorder()))
        } finally {
            if (display != null) setenv("DISPLAY", display, 1)
        }
        // With the display back, the next window connects again.
        val again = open(Recorder())
        try {
            assertEquals(1, assertNotNull(again.sharedConnection).users)
        } finally {
            again.close()
        }
    }

    @Test
    fun fr53_delete_window_reaches_only_its_window() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heardFirst = Recorder(answerClose = true)
        val heardSecond = Recorder(answerClose = false)
        val first = open(heardFirst)
        val second = open(heardSecond)
        val peer = Peer()
        try {
            settle(first)
            peer.deleteRequest(second.windowId)
            assertTrue(pumpUntil(first) { heardSecond.closeRequests == 1 }, "the close request did not arrive")
            assertEquals(0, heardFirst.closeRequests, "the first window was asked to close for the second's request")
            assertFalse(second.isClosed, "false from onCloseRequested must keep the window")
            assertFalse(first.isClosed)
            peer.deleteRequest(first.windowId)
            assertTrue(pumpUntil(second) { heardFirst.closeRequests == 1 })
            assertEquals(1, heardSecond.closeRequests)
            assertTrue(first.isClosed, "true from onCloseRequested lets the window close")
            assertFalse(second.isClosed)
        } finally {
            peer.close()
            first.close()
            second.close()
        }
    }

    @Test
    fun fr53_one_pump_waits_once_for_all_windows() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val windows = List(3) { open(Recorder(), x = 20 + it * 10, y = 20) }
        try {
            settle(windows[0])
            val waited = millisOf { windows[0].pump(300) }
            assertTrue(waited in 250..600, "one pump over three windows waited $waited ms for a 300 ms timeout")
        } finally {
            for (window in windows) window.close()
        }
    }

    @Test
    fun fr53_wake_of_any_window_ends_the_shared_wait() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val first = open(Recorder())
        val second = open(Recorder())
        try {
            settle(first)
            second.wake()
            assertTrue(millisOf { first.pump(5_000) } < 1_000, "a wake of the second window did not end the first's wait")
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun fr53_window_position_round_trips() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heard = Recorder()
        val window = open(heard, x = 120, y = 90, width = 220, height = 140)
        try {
            assertTrue(pumpUntil(window) { window.bounds() == WindowBounds(120, 90, 220, 140) }, "opened at ${window.bounds()}, not at 120, 90")
            settle(window)
            window.setPosition(300, 200)
            assertTrue(pumpUntil(window) { heard.moves.lastOrNull() == WindowBounds(300, 200, 220, 140) }, "moved to ${window.bounds()}")
            assertEquals(WindowBounds(300, 200, 220, 140), window.bounds())

            // Several moves inside one turn are one report, the last place.
            settle(window)
            val before = heard.moves.size
            for (step in 1..5) window.setPosition(300 + step * 10, 200)
            window.pump(0)
            assertEquals(before + 1, heard.moves.size, "five moves in a turn were not coalesced")
            assertEquals(WindowBounds(350, 200, 220, 140), heard.moves.last())
            settle(window)
            assertEquals(before + 1, heard.moves.size, "the configure events of the moves were reported again")

            window.setContentSize(333, 222)
            assertTrue(pumpUntil(window) { window.measure().width == 333 && window.measure().height == 222 })
            assertEquals(WindowBounds(350, 200, 333, 222), window.bounds())
        } finally {
            window.close()
        }
    }

    @Test
    fun fr53_focus_moves_between_windows() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heardFirst = Recorder()
        val heardSecond = Recorder()
        val first = open(heardFirst)
        val second = open(heardSecond, x = 260)
        try {
            settle(first)
            second.focus()
            assertTrue(pumpUntil(first) { second.isKey && !first.isKey }, "the second window did not become key")
            assertEquals(true, heardSecond.focus.lastOrNull())
            first.focus()
            assertTrue(pumpUntil(first) { first.isKey && !second.isKey }, "the first window did not become key")
            assertEquals(true, heardFirst.focus.lastOrNull())
            assertEquals(false, heardSecond.focus.lastOrNull())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun fr53_follow_pointer_keeps_anchor_under_pointer() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heard = Recorder()
        val window = open(heard, x = 20, y = 20, width = 200, height = 150)
        try {
            settle(window)
            if (!xdotool("mousemove 500 400 mouseup 1")) return
            assertFalse(window.followPointer(20f, 10f), "a follow started with the button up")
            assertEquals(false, window.pointerOnScreen()?.primaryDown)
            if (!xdotool("mousemove 500 400 mousedown 1")) return
            val pointer = assertNotNull(window.pointerOnScreen())
            assertEquals(500f, pointer.x)
            assertEquals(400f, pointer.y)
            assertTrue(pointer.primaryDown)
            assertTrue(window.followPointer(20f, 10f))

            window.pump(0)
            assertEquals(WindowBounds(480, 390, 200, 150), window.bounds())
            xdotool("mousemove 650 300")
            window.pump(0)
            assertEquals(WindowBounds(630, 290, 200, 150), window.bounds())
            assertTrue(heard.ended.isEmpty())

            xdotool("mouseup 1")
            window.pump(0)
            assertEquals(listOf(false), heard.ended)
            xdotool("mousemove 700 350")
            window.pump(0)
            assertEquals(WindowBounds(630, 290, 200, 150), window.bounds(), "the window followed after the button was released")
        } finally {
            xdotool("mouseup 1")
            window.close()
        }
    }

    @Test
    fun fr53_follow_pointer_ends_cancelled_on_escape() {
        if (!hasDisplay()) return
        X11Connection.forgetShared()
        val heard = Recorder()
        val window = open(heard, x = 20, y = 20)
        try {
            settle(window)
            if (!xdotool("mousemove 500 400 mousedown 1")) return
            assertTrue(window.followPointer(5f, 5f))
            xdotool("keydown Escape")
            window.pump(0)
            assertEquals(listOf(true), heard.ended)
        } finally {
            xdotool("keyup Escape")
            xdotool("mouseup 1")
            window.close()
        }
    }

    @Test
    fun fr53_follow_origin_puts_the_anchor_under_the_pointer() {
        assertEquals(480 to 390, followOrigin(500f, 400f, 20f, 10f, 1f))
        assertEquals(960 to 780, followOrigin(500f, 400f, 20f, 10f, 2f))
    }

    @Test
    fun fr53_frame_extents_move_the_content_not_the_frame() {
        val extents = FrameExtents(left = 4, right = 4, top = 24, bottom = 4)
        assertEquals(104 to 124, clientOriginFor(100, 100, extents))
        assertEquals(WindowBounds(100, 100, 208, 178), outerBoundsOf(104, 124, 200, 150, extents))
        val (cx, cy) = clientOriginFor(37, 52, extents)
        assertEquals(WindowBounds(37, 52, 208, 178), outerBoundsOf(cx, cy, 200, 150, extents))
    }

    @Test
    fun fr53_keymap_bit_names_the_key() {
        val keymap = ByteArray(32)
        keymap[9] = 0x02 // keycode 73
        assertTrue(keyIsDown(keymap, 73))
        assertFalse(keyIsDown(keymap, 72))
        assertFalse(keyIsDown(keymap, 9))
        assertFalse(keyIsDown(keymap, 4000))
    }
}
