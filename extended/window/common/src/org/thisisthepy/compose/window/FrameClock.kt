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
