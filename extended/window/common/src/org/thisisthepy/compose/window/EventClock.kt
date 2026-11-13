package org.thisisthepy.compose.window

/**
 * The time a pointer event is given to the scene at, in milliseconds.
 *
 * Taken from when the operating system says the event happened ([WindowEvent.timeMillis])
 * and not from when the scene got round to it. A frame that takes a long time to lay out
 * between the two presses of a double click would otherwise push the second press late by
 * that long, and Compose, which counts clicks by the time between presses, would see two
 * single clicks.
 *
 * Every platform keeps its event time on a clock of its own (seconds since boot on macOS,
 * a 32 bit count of milliseconds on Win32 and X11 that wraps after 49 days), and an event
 * a platform does not time ([WindowEvent.TIME_UNKNOWN]) is timed by [now]. So the event's
 * time is moved onto [now]'s clock by an offset, fixed when the first timed event arrives:
 * the difference between two timed events is kept exactly, which is what a click count
 * reads, and untimed events are on the same clock as timed ones.
 *
 * The offset is fixed again when an event would be dated after [now] (it was seen sooner
 * after it happened than the one the offset was fixed by, so the new one is the better
 * estimate) or long before it, which is a platform clock that wrapped or jumped.
 */
class EventClock(private val now: () -> Long = { monotonicNanos() / 1_000_000 }) {
    private var offset = 0L
    private var anchored = false

    /** The time [eventMillis], a platform's own event time or [WindowEvent.TIME_UNKNOWN], is given to the scene at. */
    fun timeOf(eventMillis: Long): Long {
        val current = now()
        if (eventMillis == WindowEvent.TIME_UNKNOWN) return current
        if (anchored) {
            val mapped = eventMillis + offset
            if (mapped <= current && mapped >= current - STALE_MILLIS) return mapped
        }
        offset = current - eventMillis
        anchored = true
        return current
    }

    companion object {
        /**
         * How far behind now an event may be dated before its clock is taken to have
         * wrapped or jumped. Far longer than any frame, however slow.
         */
        const val STALE_MILLIS: Long = 10 * 60 * 1000
    }
}
