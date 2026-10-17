package org.thisisthepy.compose.window

/**
 * What a live resize showed, counted, for the parity check.
 *
 * A drag of the window's edge is a run of sizes the window takes. For the picture to follow
 * the edge, a frame has to be presented at each of those sizes before the next one arrives.
 * A size left without one is shown as the previous frame scaled or cut to fit, which is the
 * stretch a reader sees, and a frame presented at a size the window no longer has is stale.
 * The criterion is that neither happens: [staleRatio] and [stretched] are zero.
 *
 * The same rules as `appkit_resize.h`, which the AppKit window counts with in C, so that the
 * Linux windows' `dxc resize:` line means what the macOS one means. Kept in Kotlin because
 * both Linux windows present from Kotlin.
 */
class ResizeStats {
    /** Sizes the window took. */
    var steps = 0L
        private set

    /** Frames handed to the screen. */
    var presented = 0L
        private set

    /** Presented frames that were not at the window's size at that moment. */
    var stale = 0L
        private set

    private var leftWithoutFrame = 0L
    private var stepUnanswered = false
    private var windowWidth = 0
    private var windowHeight = 0

    fun reset() {
        steps = 0
        presented = 0
        stale = 0
        leftWithoutFrame = 0
        stepUnanswered = false
        windowWidth = 0
        windowHeight = 0
    }

    /** The window took a new size, in pixels. */
    fun step(width: Int, height: Int) {
        if (stepUnanswered) leftWithoutFrame++
        steps++
        stepUnanswered = true
        windowWidth = width
        windowHeight = height
    }

    /** A frame drawn at [width] by [height] was handed to the screen, judged by the last [step]. */
    fun present(width: Int, height: Int) {
        presented++
        if (width != windowWidth || height != windowHeight) stale++ else stepUnanswered = false
    }

    /**
     * A frame drawn at [width] by [height] was handed to the screen while the window measured
     * [windowWidth] by [windowHeight], read at that moment rather than remembered. A frame for
     * a size the window has already left counts as stale even before the event saying so.
     */
    fun presentAgainst(width: Int, height: Int, windowWidth: Int, windowHeight: Int) {
        this.windowWidth = windowWidth
        this.windowHeight = windowHeight
        present(width, height)
    }

    /** Sizes left without a frame, counting the last one, which has no successor to say so. */
    val stretched: Long get() = leftWithoutFrame + if (stepUnanswered) 1 else 0

    /** Presented frames that were stale, as a share of all presented; zero when none were. */
    val staleRatio: Double get() = if (presented > 0) stale.toDouble() / presented else 0.0

    /** The line the parity script reads: `dxc resize: steps= presented= stale= stretched= stale_ratio=`. */
    fun report(): String {
        val ratio = (staleRatio * 10_000).toLong()
        val fraction = (ratio % 10_000).toString().padStart(4, '0')
        return "dxc resize: steps=$steps presented=$presented stale=$stale stretched=$stretched " +
            "stale_ratio=${ratio / 10_000}.$fraction"
    }
}
