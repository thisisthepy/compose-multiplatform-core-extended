@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frame on the screen is the last one the window drew.
 *
 * The layer presents with the transaction, and of two frames presented before one
 * transaction commits it shows the first: the second is drawn, the scene counts it as shown,
 * and nothing asks for another. A step of a resize used to draw twice (when the view was
 * given its size and in the display that follows), and a frame the host drew on its turn
 * shared a transaction with whatever the window drew next (fork issue 192).
 *
 * Every frame here carries its number and the height it was drawn at, as two rows of
 * blocks under a magenta mark. They are read back from the window's own image and compared
 * with what the window last drew. Where the image cannot be had, the count of frames drawn
 * is still checked.
 */
class FramePerTransactionTest {
    /** What the window's image shows: -1 where the mark was not found. */
    private class Shown(val frame: Int, val height: Int)

    private var drawn = 0
    private var drawnHeight = 0

    private val magenta = Paint().apply { color = 0xFFFF00FF.toInt() }
    private val white = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val black = Paint().apply { color = 0xFF000000.toInt() }

    private fun mark(canvas: org.jetbrains.skia.Canvas, height: Int, scale: Float) {
        drawn++
        drawnHeight = height
        fun px(points: Float) = kotlin.math.round(points * scale)
        canvas.drawRect(Rect.makeXYWH(px(MARK_X), px(MARK_TOP), px(MARK_SIZE), px(MARK_SIZE)), magenta)
        for ((row, value) in listOf(drawn, height).withIndex()) {
            for (bit in 0 until BITS) {
                val on = (value shr (BITS - 1 - bit)) and 1 == 1
                canvas.drawRect(
                    Rect.makeXYWH(px(MARK_X + bit * BIT), px(BITS_TOP + row * BIT), px(BIT), px(BIT)),
                    if (on) white else black,
                )
            }
        }
    }

    private fun turn(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    private fun shown(window: MacosWindow): Shown? {
        // kCGWindowListOptionIncludingWindow; kCGWindowImageBoundsIgnoreFraming and
        // kCGWindowImageBestResolution, so one pixel of the image is one backing pixel.
        @Suppress("DEPRECATION")
        val image = platform.CoreGraphics.CGWindowListCreateImage(
            platform.CoreGraphics.CGRectNull.readValue(),
            1u shl 3,
            window.window.windowNumber.toUInt(),
            (1u shl 0) or (1u shl 3),
        ) ?: return null
        val width = platform.CoreGraphics.CGImageGetWidth(image).toInt()
        val height = platform.CoreGraphics.CGImageGetHeight(image).toInt()
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
            val context = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            platform.CoreGraphics.CGContextDrawImage(
                context, platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image,
            )
            platform.CoreGraphics.CGContextRelease(context)
            platform.CoreGraphics.CGColorSpaceRelease(space)
        }
        platform.CoreGraphics.CGImageRelease(image)
        val scale = window.window.backingScaleFactor
        fun px(points: Float) = kotlin.math.round(points * scale).toInt()
        val column = px(MARK_X + MARK_SIZE / 2)
        if (column >= width) return null
        val top = (0 until height).firstOrNull {
            val at = (it * width + column) * 4
            (pixels[at].toInt() and 0xFF) > 207 && (pixels[at + 1].toInt() and 0xFF) < 48 &&
                (pixels[at + 2].toInt() and 0xFF) > 207
        } ?: return Shown(-1, -1)
        fun row(index: Int): Int {
            val y = top + px(BITS_TOP - MARK_TOP + index * BIT + BIT / 2)
            if (y >= height) return -1
            var value = 0
            for (bit in 0 until BITS) {
                val at = (y * width + px(MARK_X + bit * BIT + BIT / 2)) * 4
                val sum = (pixels[at].toInt() and 0xFF) + (pixels[at + 1].toInt() and 0xFF) +
                    (pixels[at + 2].toInt() and 0xFF)
                value = (value shl 1) or (if (sum > 3 * 128) 1 else 0)
            }
            return value
        }
        return Shown(row(0), row(1))
    }

    /**
     * Opens a window that numbers its frames, lets it settle with the host taking its turns,
     * runs [act], gives the screen time to show what came of it with nothing asking for
     * another frame, and checks the frame on the screen is the last one drawn. [act] answers
     * how many frames are to be drawn for it, or null where that is not the point.
     */
    private fun theLastFrameIsShown(act: (MacosWindow) -> Int?) {
        val window = MacosWindow(name = "frame per transaction", width = 520, height = 360)
        try {
            window.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF204060))) }
            window.metalSurface.overlay = { canvas, _, height ->
                mark(canvas, height, window.window.backingScaleFactor.toFloat())
            }
            val settled = kotlin.time.TimeSource.Monotonic.markNow()
            while (settled.elapsedNow().inWholeMilliseconds < 800) {
                window.pumpHost(0L)
                turn(0.02)
            }
            window.requestFrame()
            window.pumpHost(0L)
            turn(0.3)
            val canSee = shown(window)?.let { it.frame == drawn } == true
            val before = drawn

            val expected = act(window)
            val inTheCall = drawn - before
            // The display AppKit owes the view after a change of size comes when the
            // transaction commits, which is in here.
            turn(0.4)
            val after = drawn

            println("frame per transaction: drew ${after - before} frame(s), $inTheCall of them in the call, last=$after")
            val scale = window.window.backingScaleFactor
            val windowHeight = kotlin.math.round(window.window.frame.useContents { size.height } * scale).toInt()
            assertEquals(windowHeight, drawnHeight, "the last frame was drawn at the window's height")
            if (canSee) {
                val now = shown(window)
                assertTrue(now != null && now.frame >= 0, "the frame's mark is on the screen")
                println("frame per transaction: shown frame=${now.frame} height=${now.height}")
                assertEquals(after, now.frame, "the frame on the screen is the last one drawn")
                assertEquals(windowHeight, now.height, "the frame on the screen was drawn at the window's height")
            } else {
                println("frame per transaction: no image of the window to read the frame's number from")
            }
            // Last, so a run that draws too many frames still says which one the screen got.
            if (expected != null) assertEquals(expected, after - before, "frames drawn for the step")
        } finally {
            window.metalSurface.overlay = null
            // Taken off the screen rather than closed: a closed window releases itself, and the
            // reference held here would then be released a second time.
            window.window.orderOut(null)
        }
    }

    private fun setFrame(window: MacosWindow, width: Double, height: Double, display: Boolean) {
        val left = window.window.frame.useContents { origin.x }
        val top = window.window.frame.useContents { origin.y + size.height }
        window.window.setFrame(NSMakeRect(left, top - height, width, height), display = display)
    }

    @Test
    fun a_resize_step_draws_one_frame_and_shows_it() = theLastFrameIsShown {
        setFrame(it, 420.0, 600.0, display = true)
        1
    }

    @Test
    fun a_display_asked_for_twice_in_a_row_draws_one_frame_and_shows_it() = theLastFrameIsShown {
        // What AppKit's display of the view comes down to, asked for twice with nothing
        // committed in between.
        val layer = it.metalSurface.layer
        repeat(2) {
            layer.setNeedsDisplay()
            layer.displayIfNeeded()
        }
        1
    }

    @Test
    fun a_resize_step_after_a_frame_from_the_host_shows_the_frame_drawn_for_the_new_size() = theLastFrameIsShown {
        it.requestFrame()
        it.pumpHost(0L)
        setFrame(it, 420.0, 600.0, display = true)
        null
    }

    @Test
    fun a_frame_from_the_host_after_a_resize_step_is_the_one_shown() = theLastFrameIsShown {
        setFrame(it, 420.0, 600.0, display = true)
        // What the host's turn does when it draws, without its pump: the run loop is not
        // turned between the step and this frame, so both are in one transaction.
        it.resizePainter?.invoke()
        // The step itself draws nothing now, and its display finds the host's frame waiting.
        1
    }

    private companion object {
        const val MARK_X = 40f
        const val MARK_TOP = 120f
        const val MARK_SIZE = 16f
        const val BITS_TOP = 144f
        const val BIT = 8f
        const val BITS = 16
    }
}
