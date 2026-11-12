@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A window given a new height in one step lays its content out at that height.
 *
 * A snap, a zoom and a script that sets a window's size all give it its new frame at once,
 * with no drag in between. The window used to read its caption inside that change, when the
 * frame was new and the rect the caption is worked out from was not, so a window taken from
 * 360 to 808 points drew its content under a strip 448 points taller than the title bar:
 * everything placed against the caption stood that far down, the rest of the page was below
 * the window's bottom edge, and it stayed so until the next resize (compose-rust #534).
 *
 * The content here is what a renderer draws: a mark placed a fixed distance under the
 * caption and one a fixed distance above the bottom. Both are looked for in the window's own
 * image, because what was wrong was on the screen and not in the scene: the scene had been
 * laid out again with the right caption, in a frame that never reached the screen.
 *
 * `DXC_ONE_STEP_RESIZE_BY_HAND=<seconds>` waits that long for something outside the process
 * to resize the window (System Events `set size`, a snap) in place of resizing it here, and
 * then checks the same things.
 */
class OneStepResizeTest {
    /** Where the marks are in the window's image, in pixels: -1 where one was not found. */
    private class Marks(val topMarkTop: Int, val bottomMarkGap: Int)

    private fun turn(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    /** The window's own image, which a process may take of its own windows without permission. */
    private fun marks(window: MacosWindow): Marks? {
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
        val column = ((MARK_X + MARK_SIZE / 2) * scale).toInt()
        if (column >= width) return null
        fun near(y: Int, r: Int, g: Int, b: Int): Boolean {
            val at = (y * width + column) * 4
            return kotlin.math.abs((pixels[at].toInt() and 0xFF) - r) < 48 &&
                kotlin.math.abs((pixels[at + 1].toInt() and 0xFF) - g) < 48 &&
                kotlin.math.abs((pixels[at + 2].toInt() and 0xFF) - b) < 48
        }
        val top = (0 until height).firstOrNull { near(it, 255, 0, 255) } ?: -1
        val bottom = (height - 1 downTo 0).firstOrNull { near(it, 0, 255, 255) }
        return Marks(top, if (bottom == null) -1 else height - 1 - bottom)
    }

    /**
     * Opens a window 520 by 360 with the marks in it, runs it as a renderer does (a timer on
     * the run loop that gives the host a turn), lets [resize] change its size, and checks the
     * marks are where the new size puts them.
     */
    private fun marksFollow(resize: (MacosWindow) -> Unit) {
        val application = NSApplication.sharedApplication()
        val byHand = platform.posix.getenv("DXC_ONE_STEP_RESIZE_BY_HAND")?.toKString()?.toDoubleOrNull()
        if (byHand != null) {
            // What makes the window one System Events can find.
            application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
            application.finishLaunching()
            application.activateIgnoringOtherApps(true)
        }
        val window = MacosWindow(name = "one step", width = 520, height = 360)
        var laid = IntSize.Zero
        var topMarkY = -1f
        val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
        try {
            window.setContent {
                val strip = window.caption.value.height
                Box(Modifier.fillMaxSize().background(Color(0xFF204060)).onSizeChanged { laid = it }) {
                    Box(
                        Modifier.offset(MARK_X.dp, strip + MARK_TOP.dp).size(MARK_SIZE.dp).background(Color.Magenta)
                            .onGloballyPositioned { topMarkY = it.positionInRoot().y },
                    )
                    Box(
                        Modifier.align(Alignment.BottomStart).offset(MARK_X.dp, (-MARK_BOTTOM).dp)
                            .size(MARK_SIZE.dp).background(Color.Cyan),
                    )
                }
            }
            NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
            turn(1.0)
            val scale = window.window.backingScaleFactor
            val captionBefore = window.caption.value
            val before = marks(window)
            assertEquals(
                ((captionBefore.height.value + MARK_TOP) * scale).toFloat(), topMarkY,
                "the top mark is laid out under the caption before the resize",
            )

            if (byHand != null) {
                println("one step resize: waiting ${byHand}s for pid ${platform.posix.getpid()} to be resized")
                turn(byHand)
            } else {
                resize(window)
                turn(1.0)
            }

            val measured = window.measure()
            assertTrue(measured.height != (360 * scale).toInt(), "the window's height changed")
            assertEquals(captionBefore, window.caption.value, "a resize does not change the caption")
            assertEquals(IntSize(measured.width, measured.height), laid, "the content is laid out at the window's size")
            assertEquals(
                ((captionBefore.height.value + MARK_TOP) * scale).toFloat(), topMarkY,
                "the top mark is laid out under the caption after the resize",
            )
            // On the screen too, wherever the window's image can be had and showed the marks
            // before the resize. A runner with no screen to capture from has the checks above.
            if (before != null && before.topMarkTop >= 0 && before.bottomMarkGap >= 0) {
                val after = marks(window)
                assertTrue(after != null && after.topMarkTop >= 0, "the top mark is on the screen after the resize")
                assertEquals(before.topMarkTop, after.topMarkTop, "the top mark keeps its distance from the window's top")
                assertEquals(before.bottomMarkGap, after.bottomMarkGap, "the bottom mark keeps its distance from the bottom")
            } else {
                println("one step resize: no image of the window to look for the marks in")
            }
        } finally {
            timer.invalidate()
            // Taken off the screen rather than closed: a closed window releases itself, and the
            // reference held here would then be released a second time.
            window.window.orderOut(null)
        }
    }

    private fun setFrame(window: MacosWindow, width: Double, height: Double, display: Boolean) {
        val left = window.window.frame.useContents { origin.x }
        val top = window.window.frame.useContents { origin.y + size.height }
        // The top left corner stays where it is, as it does for a window resized by a script.
        window.window.setFrame(NSMakeRect(left, top - height, width, height), display = display)
    }

    @Test
    fun a_height_grown_in_one_step_lays_the_content_out_at_the_new_height() =
        marksFollow { setFrame(it, 420.0, 808.0, display = true) }

    @Test
    fun a_height_grown_in_one_step_without_display_lays_the_content_out_at_the_new_height() =
        marksFollow { setFrame(it, 420.0, 808.0, display = false) }

    @Test
    fun a_height_shrunk_in_one_step_lays_the_content_out_at_the_new_height() =
        marksFollow {
            setFrame(it, 420.0, 808.0, display = true)
            turn(0.5)
            setFrame(it, 520.0, 500.0, display = true)
        }

    private companion object {
        const val MARK_X = 40.0
        const val MARK_TOP = 64.0
        const val MARK_SIZE = 16.0
        const val MARK_BOTTOM = 32.0
    }
}
