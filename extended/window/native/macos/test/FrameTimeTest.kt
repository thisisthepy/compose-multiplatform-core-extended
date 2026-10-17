@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface
import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode

/**
 * The Kotlin/Native macOS window, given the same second of a 120 Hz display the scene host
 * and the GraalVM window are given: a linear animation must move one second across it, with
 * every refresh drawn and with refreshes dropped.
 */
class FrameTimeTest {

    private fun secondsAnimatedOver(ticks: List<Int>): Double {
        check(ticks.sum() == 120) { "the frames must cover one second of the display" }
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "frame time", width = 64, height = 64)
        var now = START_NANOS
        window.frameTime = { now }
        var played = 0f
        val surface = Surface.makeRasterN32Premul(64, 64)
        try {
            window.setContent {
                LaunchedEffect(Unit) {
                    Animatable(0f).animateTo(10f, tween(10_000, easing = LinearEasing)) { played = value }
                }
            }
            var tick = 0L
            fun frameAt(at: Long) {
                now = START_NANOS + at * 1_000_000_000L / 120
                window.paintFrame(surface.canvas, 64, 64)
                // The scene's work runs on the main queue, which is served by the run loop.
                repeat(3) {
                    NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.0))
                }
            }
            repeat(4) { frameAt(++tick) }
            frameAt(tick)
            val before = played
            for (step in ticks) {
                tick += step
                frameAt(tick)
            }
            frameAt(tick)
            return (played - before).toDouble()
        } finally {
            surface.close()
            // Taken off the screen rather than closed: a closed window releases itself, and the
            // reference held here would then be released a second time.
            window.window.orderOut(null)
        }
    }

    private fun assertOneSecond(animated: Double, what: String) {
        assertTrue(
            abs(animated - 1.0) < 1.0 / 120,
            "$what: the animation moved ${(animated * 10_000).roundToLong() / 10_000.0} s across one second of the display",
        )
    }

    @Test
    fun pr3_at_120_hz_an_animation_advances_by_the_wall_clock() {
        assertOneSecond(secondsAnimatedOver(List(120) { 1 }), "every frame drawn")
    }

    @Test
    fun pr3_with_dropped_frames_an_animation_still_advances_by_the_wall_clock() {
        val pattern = intArrayOf(1, 1, 3, 1, 2, 5, 1, 1, 2)
        val ticks = ArrayList<Int>()
        var left = 120
        var i = 0
        while (left > 0) {
            val step = minOf(pattern[i++ % pattern.size], left)
            ticks.add(step)
            left -= step
        }
        assertOneSecond(secondsAnimatedOver(ticks), "${120 - ticks.size} frames dropped")
    }

    private companion object {
        const val START_NANOS = 5_000_000_000_000L
    }
}
