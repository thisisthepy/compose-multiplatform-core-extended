package org.thisisthepy.compose.window.graalvm.windows

import org.thisisthepy.compose.window.monotonicNanos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window times its own frames. Needs no Windows and no native library: a window made from
 * zero pointers asks only the clock, which is never reached through C.
 */
class Win32FrameTimeTest {
    private fun windowOn(source: () -> Long = ::monotonicNanos) =
        Win32GraalWindow(0, 0, 0, 0, 0, source)

    @Test
    fun pr3_a_frame_is_timed_on_the_monotonic_clock() {
        val window = Win32GraalWindow(0, 0, 0, 0, 0)
        val first = window.frameTimeNanos()
        Thread.sleep(20)
        val second = window.frameTimeNanos()
        assertTrue(second - first >= 20_000_000L, "20 ms of wall clock moved the frame time by ${second - first} ns")
    }

    @Test
    fun pr3_frame_time_follows_the_clock_and_not_a_fixed_step() {
        var now = 1_000_000_000L
        val window = windowOn { now }

        assertEquals(1_000_000_000L, window.frameTimeNanos())
        // A late frame, 100 ms on, then a quick one 1 ms after it: neither is 16 ms.
        now += 100_000_000L
        assertEquals(1_100_000_000L, window.frameTimeNanos())
        now += 1_000_000L
        assertEquals(1_101_000_000L, window.frameTimeNanos())
    }

    @Test
    fun pr3_two_frames_for_the_same_instant_get_the_same_time() {
        val window = windowOn { 5_000_000L }

        assertEquals(window.frameTimeNanos(), window.frameTimeNanos())
    }

    @Test
    fun pr3_frame_time_never_goes_backwards() {
        var now = 9_000_000L
        val window = windowOn { now }
        val first = window.frameTimeNanos()
        now = 8_000_000L

        assertEquals(first, window.frameTimeNanos())
    }
}
