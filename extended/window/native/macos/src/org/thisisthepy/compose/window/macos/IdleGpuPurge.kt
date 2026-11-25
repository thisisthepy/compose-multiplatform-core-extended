package org.thisisthepy.compose.window.macos

/**
 * When a window that has stopped drawing gives back the GPU memory Skia keeps for frames.
 *
 * Skia keeps the textures and buffers a frame used in its resource cache, up to the limit
 * the window sets (16 MB), so the next frame can use them again rather than make them anew.
 * A window nobody is looking at keeps all of it for as long as it stays still. Emptied once
 * the window had drawn nothing for a few seconds, an idle Ember window gave back about 5 MB.
 *
 * The answer is "purge now" once, [idleNanos] after the last frame, and then not again
 * until another frame is drawn: a window left alone for an hour purges once, not every turn.
 * Nothing before the first frame either, since there is nothing in the cache yet.
 *
 * Pure, with the time handed in, so it is checked without a window or a clock. It never
 * asks for a turn or a timer of its own: the window asks it on the turns it already takes.
 */
class IdleGpuPurge(private val idleNanos: Long = IDLE_PURGE_NANOS) {
    private var lastFrameNanos = 0L

    // Whether a frame was drawn since the last purge, which is what makes a purge worth doing.
    private var armed = false

    /** A frame was drawn at [nowNanos]. */
    fun frameDrawn(nowNanos: Long) {
        lastFrameNanos = nowNanos
        armed = true
    }

    /**
     * Whether the cache is to be emptied now, at [nowNanos]: true once when no frame has been
     * drawn for [idleNanos], and false after that until [frameDrawn] is called again.
     */
    fun shouldPurge(nowNanos: Long): Boolean {
        if (!armed || nowNanos - lastFrameNanos < idleNanos) return false
        armed = false
        return true
    }

    companion object {
        /** How long a window draws nothing before its cache is emptied, in seconds. */
        const val IDLE_PURGE_SECONDS = 5L

        /** [IDLE_PURGE_SECONDS] in nanoseconds. */
        const val IDLE_PURGE_NANOS = IDLE_PURGE_SECONDS * 1_000_000_000L
    }
}
