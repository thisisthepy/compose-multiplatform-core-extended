package org.thisisthepy.compose.window.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility

/**
 * A window whose display refreshes at 120 Hz, on a clock the test turns by hand.
 *
 * Nothing in it waits: [frameTimeNanos] answers what the test set, which is when the display
 * would show the next frame, and the test runs as fast as the machine allows. A host that
 * reads any other clock, or counts frames instead of reading one, sees a different second
 * from the one the display showed.
 */
private class DisplayAt120Hz : WindowPlatform {
    override val name = "display-120hz"
    var now = 0L
    private var listener: WindowListener? = null

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        this.listener = listener
        return true
    }

    override fun pump(timeoutMillis: Long) {}
    override fun measure() = WindowMeasurement(64, 64, 1f)
    override fun requestFrame() {}
    override fun frameTimeNanos(): Long = now
    override fun present(drawnWidth: Int, drawnHeight: Int) =
        FramePresentRecord(drawnWidth, drawnHeight, 64, 64)

    override fun systemTheme() = SystemTheme.Light
    override fun setTitle(title: String) {}
    override fun setMinimumSize(width: Int, height: Int) {}
    override fun setVisibility(visibility: WindowVisibility) {}
    override fun readClipboardText(): String? = null
    override fun writeClipboardText(text: String) {}
    override fun setImeSpot(x: Int, y: Int) {}
    override fun showContextMenu(items: List<ContextMenuItem>) {}
    override fun close() {}
}

private class RasterSurface : WindowSurface {
    override fun begin(width: Int, height: Int): FrameTarget {
        val surface = Surface.makeRasterN32Premul(width, height)
        return object : FrameTarget {
            override val surface = surface
            override fun close() = surface.close()
        }
    }

    override fun setResizePainter(paint: (() -> Boolean)?) {}
    override fun close() {}
}

class FrameTimeTest {

    /**
     * Runs a linear animation whose value is the seconds it has played, draws frames at the
     * display ticks [ticks] apart (in 120ths of a second, adding up to one second), and
     * answers how far the animation moved across that second.
     */
    private fun secondsAnimatedOver(ticks: List<Int>): Double {
        check(ticks.sum() == TICKS_PER_SECOND) { "the frames must cover one second of the display" }
        val platform = DisplayAt120Hz()
        var played = 0f
        val host = ComposeWindowHost(platform, WindowConfig("frame time", 64, 64), { RasterSurface() }) {
            LaunchedEffect(Unit) {
                val clock = Animatable(0f)
                clock.animateTo(ANIMATION_SECONDS, tween((ANIMATION_SECONDS * 1000).toInt(), easing = LinearEasing)) {
                    played = value
                }
            }
            Box(Modifier.fillMaxSize())
        }
        assertTrue(host.open())
        var tick = 0L
        fun frameAt(at: Long) {
            platform.now = START_NANOS + at * NANOS_PER_SECOND / TICKS_PER_SECOND
            host.requestFrame()
            host.turn(0)
        }
        // The animation starts on a frame and reads its value from the next, so it is
        // running before the second that is measured begins.
        repeat(4) { frameAt(++tick) }
        // The scene's work for the last frame runs at the start of the next turn, so each
        // end of the second is drawn twice at the same time: the second turn reads the
        // value the first one drew.
        frameAt(tick)
        val before = played
        for (step in ticks) {
            tick += step
            frameAt(tick)
        }
        frameAt(tick)
        val after = played
        host.close()
        return (after - before).toDouble()
    }

    private fun assertOneSecond(animated: Double, what: String) {
        assertTrue(
            abs(animated - 1.0) < TOLERANCE_SECONDS,
            "$what: the animation moved ${(animated * 10_000).roundToLong() / 10_000.0} s across one second of the display",
        )
    }

    @Test
    fun pr3_at_120_hz_an_animation_advances_by_the_wall_clock() {
        assertOneSecond(secondsAnimatedOver(List(TICKS_PER_SECOND.toInt()) { 1 }), "every frame drawn")
    }

    @Test
    fun pr3_with_dropped_frames_an_animation_still_advances_by_the_wall_clock() {
        // A second of a 120 Hz display with frames missed: two, three and five refreshes
        // go by without one, as a frame that took too long leaves them.
        val ticks = ArrayList<Int>()
        var left = TICKS_PER_SECOND.toInt()
        val pattern = intArrayOf(1, 1, 3, 1, 2, 5, 1, 1, 2)
        var i = 0
        while (left > 0) {
            val step = minOf(pattern[i++ % pattern.size], left)
            ticks.add(step)
            left -= step
        }
        assertTrue(ticks.size < TICKS_PER_SECOND, "some frames were dropped")
        assertOneSecond(secondsAnimatedOver(ticks), "${TICKS_PER_SECOND - ticks.size} frames dropped")
    }

    private companion object {
        const val TICKS_PER_SECOND = 120L
        const val NANOS_PER_SECOND = 1_000_000_000L
        // Far from zero, as a monotonic clock is, so nothing can pass by assuming it starts there.
        const val START_NANOS = 5_000_000_000_000L
        const val ANIMATION_SECONDS = 10f
        // One display refresh, which is the most a frame's time may be off by.
        const val TOLERANCE_SECONDS = 1.0 / 120
    }
}
