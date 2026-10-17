@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What a renderer reads off this window to say how far its start got: the size the window
 * opened at, and that a frame reached it.
 *
 * The native image's window says both, in pixels, and a renderer prints them the same way
 * on either path. A window that opened and never drew is otherwise indistinguishable from
 * one that did: the process starts, the window is there, and the run returns 0.
 */
class MacosWindowStartTest {

    @Test
    fun nfr14_the_window_measures_its_content_in_pixels() {
        val window = MacosWindow(name = "measure", width = 320, height = 200)
        try {
            val scale = window.window.backingScaleFactor
            val measured = window.measure()
            assertEquals((320 * scale).toInt(), measured.width, "width in pixels at ${scale}x")
            assertEquals((200 * scale).toInt(), measured.height, "height in pixels at ${scale}x")
            assertEquals(scale.toFloat(), measured.scale)
        } finally {
            // Taken off the screen rather than closed: a closed window releases itself, and the
            // reference held here would then be released a second time.
            window.window.orderOut(null)
        }
    }

    @Test
    fun nfr14_a_frame_that_reached_the_window_is_reported_at_its_size() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "first frame", width = 320, height = 200)
        var drawn: Pair<Int, Int>? = null
        window.onFrameDrawn = { width, height -> if (drawn == null) drawn = width to height }
        try {
            window.setContent {}
            window.requestFrame()
            // The frame is drawn where AppKit next displays the view, which is a turn of the
            // run loop away. Turned here until it comes, or for five seconds.
            var turns = 0
            while (drawn == null && turns < 100) {
                NSRunLoop.currentRunLoop.runMode(
                    NSDefaultRunLoopMode,
                    beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05),
                )
                turns++
            }
            val frame = assertNotNull(drawn, "no frame reached the window in five seconds")
            val measured = window.measure()
            assertEquals(measured.width to measured.height, frame)
        } finally {
            // Taken off the screen rather than closed: a closed window releases itself, and the
            // reference held here would then be released a second time.
            window.window.orderOut(null)
        }
    }
}
