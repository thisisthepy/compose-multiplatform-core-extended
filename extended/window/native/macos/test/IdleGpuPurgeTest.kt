package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An idle window empties Skia's GPU cache once, a few seconds after its last frame, and not
 * again until it has drawn another.
 */
class IdleGpuPurgeTest {
    private val second = 1_000_000_000L

    @Test
    fun nothing_is_purged_before_the_first_frame() {
        val purge = IdleGpuPurge(idleNanos = 5 * second)
        assertFalse(purge.shouldPurge(0L))
        assertFalse(purge.shouldPurge(60 * second), "a window that never drew has nothing cached")
    }

    @Test
    fun nothing_is_purged_before_the_window_has_been_idle_long_enough() {
        val purge = IdleGpuPurge(idleNanos = 5 * second)
        purge.frameDrawn(10 * second)
        assertFalse(purge.shouldPurge(10 * second))
        assertFalse(purge.shouldPurge(12 * second))
        assertFalse(purge.shouldPurge(15 * second - 1), "one nanosecond short of the idle time")
    }

    @Test
    fun an_idle_window_purges_exactly_once() {
        val purge = IdleGpuPurge(idleNanos = 5 * second)
        purge.frameDrawn(10 * second)
        var purges = 0
        // A turn every 16 ms for a minute after the last frame.
        var now = 10 * second
        while (now < 70 * second) {
            if (purge.shouldPurge(now)) purges++
            now += 16_000_000L
        }
        assertEquals(1, purges, "a window left alone purges once, not on every turn")
        assertTrue(!purge.shouldPurge(3600 * second), "nor an hour later")
    }

    @Test
    fun a_frame_arms_it_again() {
        val purge = IdleGpuPurge(idleNanos = 5 * second)
        purge.frameDrawn(0L)
        assertTrue(purge.shouldPurge(5 * second), "purged once idle for the whole time")
        assertFalse(purge.shouldPurge(6 * second))
        purge.frameDrawn(7 * second)
        assertFalse(purge.shouldPurge(8 * second), "a fresh frame starts the wait again")
        assertFalse(purge.shouldPurge(12 * second - 1))
        assertTrue(purge.shouldPurge(12 * second), "and is purged once the wait is over")
        assertFalse(purge.shouldPurge(20 * second))
    }

    @Test
    fun frames_keep_putting_it_off() {
        val purge = IdleGpuPurge(idleNanos = 5 * second)
        // A frame every second for a minute: never idle for five.
        for (at in 0L until 60L) {
            purge.frameDrawn(at * second)
            assertFalse(purge.shouldPurge(at * second + second / 2), "a window still drawing keeps its cache")
        }
    }

    @Test
    fun the_default_is_about_five_seconds() {
        assertEquals(5L, IdleGpuPurge.IDLE_PURGE_SECONDS)
        assertEquals(5 * second, IdleGpuPurge.IDLE_PURGE_NANOS)
    }

    /** Turns the run loop until [done] or [seconds] have passed; answers [done]. */
    private fun turnUntil(seconds: Double, done: () -> Boolean = { false }): Boolean {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            if (done()) return true
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
        return done()
    }

    /**
     * The window asks on the turns it already takes: a still window empties the cache once
     * it has been idle long enough, a purge never follows a purge without a frame between
     * them, and a frame asked for afterwards arms it again.
     */
    @Test
    fun a_still_window_empties_its_gpu_cache_once_and_again_after_a_frame() {
        val window = MacosWindow(name = "idle purge", width = 320, height = 240)
        // Short, so the test does not sit for five seconds a time.
        window.idlePurge = IdleGpuPurge(idleNanos = 300_000_000L)
        var frames = 0
        val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
        window.onFrameDrawn = { _, _ -> frames++ }
        try {
            window.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF204060))) }
            NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
            val metal = window.metalSurface
            assertTrue(
                turnUntil(5.0) { metal.gpuPurges >= 1 },
                "a window that stopped drawing emptied its cache (frames drawn: $frames)",
            )
            assertTrue(frames > 0, "the window drew before it was idle")
            assertEquals(16L * 1048576L, metal.cacheLimitBytes, "the cache keeps its limit after a purge")

            // Left alone, it purges again only if something drew in between.
            val purges = metal.gpuPurges
            val framesBefore = frames
            turnUntil(1.5)
            assertTrue(
                metal.gpuPurges - purges <= frames - framesBefore,
                "${metal.gpuPurges - purges} purges after ${frames - framesBefore} frames: " +
                    "a purge needs a frame before it",
            )

            // A frame arms it again.
            val armedAt = metal.gpuPurges
            val framesAtRequest = frames
            window.requestFrame()
            assertTrue(turnUntil(2.0) { frames > framesAtRequest }, "the frame asked for was drawn")
            assertTrue(
                turnUntil(5.0) { metal.gpuPurges > armedAt },
                "the window emptied its cache again once idle after the new frame",
            )
        } finally {
            timer.invalidate()
            // Taken off the screen rather than closed, as the other window tests do.
            window.window.orderOut(null)
        }
    }
}
