@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertTrue
import platform.posix.usleep

/**
 * The Kotlin/Native X11 window times its frames on `CLOCK_MONOTONIC`, the clock the GraalVM
 * X11 window and Rust's `Instant` read, so a frame drawn later is given a later time by as
 * much as the wall clock moved. Needs no display: the clock is asked before any window opens.
 */
class FrameTimeTest {
    @Test
    fun pr3_a_frame_is_timed_on_the_monotonic_clock() {
        val window = X11Window()
        val before = monotonicClockNanos()
        val first = window.frameTimeNanos()
        usleep(20_000u)
        val second = window.frameTimeNanos()
        val after = monotonicClockNanos()
        assertTrue(first in before..after, "the first frame is timed on CLOCK_MONOTONIC")
        assertTrue(second - first >= 20_000_000L, "20 ms of wall clock moved the frame time by ${second - first} ns")
        assertTrue(second <= after)
    }
}
