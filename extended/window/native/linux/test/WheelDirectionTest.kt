@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.getenv
import x11.ButtonPress
import x11.ButtonPressMask
import x11.ButtonRelease
import x11.ButtonReleaseMask
import x11.XCloseDisplay
import x11.XEvent
import x11.XFlush
import x11.XOpenDisplay
import x11.XSendEvent

/**
 * The X11 wheel reaches the scene with the sign WindowEvent.SCROLL documents: a positive y asks
 * for what is below. X11 reports a wheel as a press and a release of a button that does not
 * exist: 4 is a notch up (away from the reader, what is above), 5 down, 6 left and 7 right.
 * Each press is three lines, what other programs on this platform move a notch; the release is
 * nothing.
 *
 * Needs a display; CI runs it under Xvfb.
 */
class WheelDirectionTest {

    private class Recorder : WindowListener {
        val events = mutableListOf<WindowEvent>()
        override fun onEvent(event: WindowEvent) {
            events += event
        }
        fun scrolls() = events.filter { it.kind == WindowEvent.SCROLL }.map { (it.x + 0f) to (it.y + 0f) }
    }

    private fun hasDisplay(): Boolean {
        val present = !getenv("DISPLAY")?.toKString().isNullOrEmpty()
        if (!present) assertTrue(getenv("CI") == null, "no DISPLAY under CI, so the X11 wheel was not tested")
        return present
    }

    /** Another client of the display server, sending the window the presses the server would. */
    private fun sendButton(id: ULong, button: Int) {
        val display = XOpenDisplay(null)!!
        try {
            for (type in listOf(ButtonPress, ButtonRelease)) memScoped {
                val event = alloc<XEvent>()
                event.xbutton.type = type
                event.xbutton.window = id
                event.xbutton.x = 40
                event.xbutton.y = 40
                event.xbutton.button = button.toUInt()
                val mask = if (type == ButtonPress) ButtonPressMask else ButtonReleaseMask
                XSendEvent(display, id, 0, mask, event.ptr)
            }
            XFlush(display)
        } finally {
            XCloseDisplay(display)
        }
    }

    @Test
    fun a_wheel_notch_up_asks_for_what_is_above_and_down_for_what_is_below() {
        if (!hasDisplay()) return
        val recorder = Recorder()
        val window = X11Window()
        assertTrue(window.open(WindowConfig("wheel", 200, 150, x = 20, y = 20), recorder), "no window on a display that is there")
        try {
            repeat(30) { window.pump(10) }
            recorder.events.clear()
            sendButton(window.windowId, 4)
            sendButton(window.windowId, 5)
            sendButton(window.windowId, 6)
            sendButton(window.windowId, 7)
            val started = TimeSource.Monotonic.markNow()
            while (recorder.scrolls().size < 4 && started.elapsedNow().inWholeMilliseconds < 3_000) window.pump(10)
            assertEquals(
                listOf(0f to -3f, 0f to 3f, -3f to 0f, 3f to 0f),
                recorder.scrolls(),
                "button 4 is up (negative y), 5 down, 6 left (negative x), 7 right, three lines a notch",
            )
        } finally {
            window.close()
        }
    }
}
