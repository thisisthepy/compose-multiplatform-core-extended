package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals

class EventClockTest {
    private var now = 10_000L
    private val clock = EventClock { now }

    @Test
    fun anUntimedEventIsTimedNow() {
        assertEquals(10_000L, clock.timeOf(WindowEvent.TIME_UNKNOWN))
        now += 25
        assertEquals(10_025L, clock.timeOf(WindowEvent.TIME_UNKNOWN))
    }

    @Test
    fun twoTimedEventsKeepTheirDistanceHoweverLateTheSecondIsSeen() {
        val first = clock.timeOf(7_000_000L)
        // A slow frame between the presses: the second is read two seconds after the first,
        // though the platform says it happened 400 ms after it.
        now += 2_000
        val second = clock.timeOf(7_000_400L)
        assertEquals(400L, second - first)
    }

    @Test
    fun anEventSeenSoonerThanTheFirstMovesTheOffset() {
        clock.timeOf(1_000L)
        now += 10
        // Happened 50 ms after the first and seen only 10 ms after it: dated now, not after.
        assertEquals(now, clock.timeOf(1_050L))
        now += 100
        assertEquals(now - 70, clock.timeOf(1_080L))
    }

    @Test
    fun aClockThatWrappedStartsAgainFromNow() {
        clock.timeOf(0xffffff00L)
        now += 300
        // Win32 and X11 times are 32 bits and wrap: 300 ms later reads as a small number.
        assertEquals(now, clock.timeOf(0x2cL))
        now += 16
        assertEquals(now, clock.timeOf(0x3cL))
    }

    @Test
    fun thirtyTwoBitTimesAreReadUnsigned() {
        assertEquals(0xfffffff0L, WindowEvent.timeFrom32Bits(-16))
        assertEquals(1234L, WindowEvent.timeFromSeconds(1.2345))
    }
}
