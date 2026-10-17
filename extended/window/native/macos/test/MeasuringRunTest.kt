@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import org.thisisthepy.compose.window.WindowEvent
import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the parity check measures this window with: a scripted drag counted the way the
 * native image's AppKit window counts one, the time each frame took, and text handed in as
 * an input method would.
 */
class MeasuringRunTest {
    private fun turn(seconds: Double) {
        NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    @Test
    fun parity_a_scripted_drag_presents_a_frame_at_every_size() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "drag", width = 320, height = 200)
        var rendered = 0
        window.onFrameRendered = { nanos -> if (nanos >= 0) rendered++ }
        try {
            window.setContent {}
            window.window.orderFront(null)
            window.requestFrame()
            repeat(20) { turn(0.02) }
            window.scriptedResize(360.0, 420.0, 10, 0.008)
            val stats = window.resizeStats
            assertTrue(stats.steps >= 1, "the scripted drag took the window through no sizes: ${stats.report()}")
            assertEquals(0L, stats.stretched, stats.report())
            assertEquals(0L, stats.stale, stats.report())
            assertTrue(rendered > 0, "no frame said how long it took")
            assertTrue(stats.report().startsWith("dxc resize: steps="), stats.report())
        } finally {
            window.window.orderOut(null)
        }
    }

    @Test
    fun parity_injected_text_is_ignored_with_no_field_and_a_press_is_posted() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "inject", width = 320, height = 200)
        try {
            window.setContent {}
            // Neither may throw: with no field focused committed text goes nowhere, and a
            // press goes into the window's own queue.
            window.inject(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "a"))
            window.inject(WindowEvent(WindowEvent.POINTER_DOWN, 10f, 10f, 1, 0, 0, 0, ""))
            window.inject(WindowEvent(WindowEvent.POINTER_UP, 10f, 10f, 0, 0, 0, 0, ""))
            repeat(5) { turn(0.02) }
        } finally {
            window.window.orderOut(null)
        }
    }
}
