@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A window whose backing scale changes with no change of size (moved between a 1x and a 2x
 * screen) gives its layer the new scale and drawable, says the new scale, and draws a frame at
 * it at once, with nothing typed or moved.
 *
 * One screen cannot give two scales, so the layer is left with another screen's scale and
 * drawable by hand and the window is told what AppKit tells it on such a move. Whether the
 * system sends the notice on a real move between two screens is a check by hand.
 */
class BackingScaleChangeTest {
    private fun turn(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    @Test
    fun a_backing_change_rescales_the_layer_reports_the_scale_and_draws_a_frame() {
        val window = MacosWindow(name = "backing", width = 300, height = 200)
        var laid = IntSize.Zero
        val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
        try {
            window.setContent { Box(Modifier.size(50.dp).onSizeChanged { laid = it }) }
            NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
            turn(0.5)
            val scale = window.window.backingScaleFactor
            val stale = if (scale == 1.0) 2.0 else 1.0
            val layer = window.metal.layer
            layer.contentsScale = stale
            layer.drawableSize = CGSizeMake(300 * stale, 200 * stale)
            val heard = ArrayList<Float>()
            val forward = window.onScaleChanged
            window.onScaleChanged = { heard += it; forward?.invoke(it) }
            var drawn = 0
            window.onFrameDrawn = { _, _ -> drawn++ }

            window.backingChanged()
            assertEquals(scale, layer.contentsScale, "the layer takes the window's scale")
            layer.drawableSize.useContents {
                assertTrue(width >= 300 * scale && width < 300 * scale + 256, "the drawable is as wide as the view at the scale, rounded up: $width")
                assertTrue(height >= 200 * scale && height < 200 * scale + 256, "and as high: $height")
            }
            assertEquals(listOf(scale.toFloat()), heard, "the new scale is reported")
            turn(0.3)
            assertTrue(drawn > 0, "a frame was drawn with no input")
            assertEquals(IntSize((50 * scale).toInt(), (50 * scale).toInt()), laid, "laid out at the scale")
        } finally {
            timer.invalidate()
            window.window.orderOut(null)
        }
    }

    @Test
    fun a_frame_drawn_at_another_scale_lays_the_content_out_at_it() {
        val window = MacosWindow(name = "density", width = 300, height = 200)
        var laid = IntSize.Zero
        try {
            window.setContent { Box(Modifier.size(50.dp).onSizeChanged { laid = it }) }
            turn(0.3)
            val host = window.host!!
            for (scale in listOf(1f, 2f, 3f)) {
                val surface = org.jetbrains.skia.Surface.makeRasterN32Premul((300 * scale).toInt(), (200 * scale).toInt())
                host.renderTo(surface.canvas, (300 * scale).toInt(), (200 * scale).toInt(), scale)
                host.renderTo(surface.canvas, (300 * scale).toInt(), (200 * scale).toInt(), scale)
                assertEquals(IntSize((50 * scale).toInt(), (50 * scale).toInt()), laid, "a 50 dp box at scale $scale")
                surface.close()
            }
        } finally {
            window.window.orderOut(null)
        }
    }
}
