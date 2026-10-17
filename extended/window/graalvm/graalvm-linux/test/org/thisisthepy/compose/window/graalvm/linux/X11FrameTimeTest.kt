package org.thisisthepy.compose.window.graalvm.linux

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The GraalVM X11 window times its frames on `CLOCK_MONOTONIC` (`System.nanoTime` on Linux),
 * the clock the Kotlin/Native X11 window and Rust's `Instant` read, so a frame drawn later is
 * given a later time by as much as the wall clock moved. Needs no display or native library:
 * the clock is asked before any window opens.
 */
class X11FrameTimeTest {
    @Test
    fun pr3_a_frame_is_timed_on_the_monotonic_clock() {
        val window = X11Window()
        val before = System.nanoTime()
        val first = window.frameTimeNanos()
        Thread.sleep(20)
        val second = window.frameTimeNanos()
        val after = System.nanoTime()
        assertTrue(first in before..after, "the first frame is timed on the monotonic clock")
        assertTrue(second - first >= 20_000_000L, "20 ms of wall clock moved the frame time by ${second - first} ns")
        assertTrue(second <= after)
    }
}
