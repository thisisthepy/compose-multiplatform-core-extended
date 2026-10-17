package org.thisisthepy.compose.window

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A counter that Host worker threads bump to ask for a frame.
 *
 * Domain work runs on Host worker threads, never on the UI thread. This counter is the one
 * signal those threads send across: they update their state and ask for a frame, and the UI
 * thread does the rest. Any number of requests between two frames coalesce into one: the
 * UI thread calls [take] once per turn and gets one answer however many requests came.
 *
 * Only the counting is here. How a pass waits for the frame clock, and what it does when the
 * clock has stopped, belongs to the layer that has a clock.
 */
@OptIn(ExperimentalAtomicApi::class)
class FrameRequestCoalescer {
    private val requested = AtomicLong(0L)
    private var served = 0L

    /** Thread-safe: any thread may ask. */
    fun request() {
        requested.addAndFetch(1L)
    }

    /** How many requests have ever been made. */
    val requestCount: Long get() = requested.load()

    /**
     * True when at least one request arrived since the last time this returned true, and
     * marks all of them served. Call it from the UI thread only, once per turn.
     */
    fun take(): Boolean {
        val now = requested.load()
        if (now == served) return false
        served = now
        return true
    }

    /** Treats everything requested so far as served, such as before the first frame. */
    fun markServed() {
        served = requested.load()
    }
}
