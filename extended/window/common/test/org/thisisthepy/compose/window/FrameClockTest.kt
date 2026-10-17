package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameClockTest {
    private val second = 1_000_000_000L

    /** The times a 120 Hz display presents at, [ticks] refreshes apart, from far past zero. */
    private fun presentations(ticks: List<Int>): List<Long> {
        var at = 0L
        val start = 7_000 * second
        return listOf(start) + ticks.map { at += it; start + at * second / 120 }
    }

    @Test
    fun pr3_at_120_hz_a_second_of_frames_spans_one_second() {
        val times = presentations(List(120) { 1 }).iterator()
        val clock = FrameClock { times.next() }
        val first = clock.frameNanos()
        var last = first
        repeat(120) { last = clock.frameNanos() }
        assertEquals(second, last - first)
    }

    @Test
    fun pr3_with_dropped_frames_a_second_still_spans_one_second() {
        val ticks = listOf(1, 1, 3, 1, 2, 5, 1, 1, 2).let { pattern ->
            buildList {
                var left = 120
                var i = 0
                while (left > 0) {
                    val step = minOf(pattern[i++ % pattern.size], left)
                    add(step)
                    left -= step
                }
            }
        }
        val times = presentations(ticks).iterator()
        val clock = FrameClock { times.next() }
        val first = clock.frameNanos()
        var last = first
        repeat(ticks.size) { last = clock.frameNanos() }
        assertTrue(ticks.size < 120)
        assertEquals(second, last - first)
    }

    @Test
    fun pr3_the_time_never_goes_backwards() {
        val times = listOf(100L, 200L, 150L, 200L, 300L).iterator()
        val clock = FrameClock { times.next() }
        assertEquals(listOf(100L, 200L, 200L, 200L, 300L), List(5) { clock.frameNanos() })
    }

    @Test
    fun pr3_the_default_platform_time_is_monotonic_and_moves() {
        val first = monotonicNanos()
        var later = first
        // Spun rather than slept: common code has no sleep on every target.
        while (later == first) later = monotonicNanos()
        assertTrue(later > first)
    }
}
