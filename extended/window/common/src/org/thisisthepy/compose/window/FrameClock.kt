package org.thisisthepy.compose.window

import kotlin.time.TimeSource

private val processStarted = TimeSource.Monotonic.markNow()

/**
 * The time now in nanoseconds on the process's monotonic clock, counted from when this
 * module was first used.
 *
 * For a platform that has no better answer to [WindowPlatform.frameTimeNanos]. A platform
 * whose display reports when frames are shown answers on that clock instead.
 */
fun monotonicNanos(): Long = processStarted.elapsedNow().inWholeNanoseconds

/**
 * The time each frame is drawn at, for the scene a window hosts.
 *
 * Asked once a frame, on the thread that draws, and handed to the scene's render. An
 * animation moves by the difference between two of these, so they come from the platform's
 * own account of when the frame reaches the screen ([source], normally
 * [WindowPlatform.frameTimeNanos]) and never from a count of frames: a count runs twice as
 * fast on a 120 Hz display as on a 60 Hz one, and falls behind whenever a frame is late.
 *
 * A source may answer a little less than it did for the frame before, as a display link's
 * next presentation does when it stops and the time now takes over. A scene that saw time
 * go backwards would run its animations backwards, so this answers the larger of the two.
 * Two frames drawn for the same presentation, such as one from a resize and one from the
 * loop, are given the same time, which is when both are shown.
 */
class FrameClock(private val source: () -> Long) {
    private var last = Long.MIN_VALUE

    /** The time the frame about to be drawn is drawn at, in the source's nanoseconds. */
    fun frameNanos(): Long {
        val now = source()
        if (now > last) last = now
        return last
    }
}
