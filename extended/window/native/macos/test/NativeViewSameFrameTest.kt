@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSColor
import platform.AppKit.NSView
import platform.CoreGraphics.CGPreflightScreenCaptureAccess
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRect
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSArray
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSDictionary
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNumber
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.ScreenCaptureKit.SCContentFilter
import platform.ScreenCaptureKit.SCDisplay
import platform.ScreenCaptureKit.SCFrameStatus
import platform.ScreenCaptureKit.SCShareableContent
import platform.ScreenCaptureKit.SCStream
import platform.ScreenCaptureKit.SCStreamConfiguration
import platform.ScreenCaptureKit.SCStreamFrameInfoStatus
import platform.ScreenCaptureKit.SCStreamOutputProtocol
import platform.ScreenCaptureKit.SCStreamOutputType
import platform.ScreenCaptureKit.SCWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import kotlin.test.Test
import kotlin.test.assertTrue

/** A view of one colour, standing in for whatever an application puts in a slot. */
private class RedView : NSView(NSMakeRect(0.0, 0.0, 10.0, 10.0)) {
    init {
        wantsLayer = true
        layer?.backgroundColor = NSColor.redColor.CGColor
    }
}

/**
 * A native view moved in the same turn as the Metal frame drawn under it reaches the screen in
 * the same picture as that frame.
 *
 * No Compose: each turn of a 60 Hz timer does what a scene's frame does, in the same order. It
 * takes a drawable through [MetalWindowSurface], moves the slot with [MacosNativeViews.setFrame]
 * as a scene's layout does, spends [DRAWING_MICROS] drawing as a scene does, draws a green page
 * with two blue bars the slot's height apart, the slot exactly between them, and presents. The
 * bars and the slot move together by [STEP] points a turn.
 *
 * What the screen shows is read from ScreenCaptureKit, one picture for each time the window
 * server composited the window, down one column. A view a frame ahead of the drawing leaves
 * green between itself and the bar on one side and covers the other bar's edge; the green rows
 * on each side give how far apart the two are, in pixels.
 *
 * Not CGWindowListCreateImage, which the other image checks here use. For this window, whose
 * content view is a vibrant NSVisualEffectView, it draws the window again when asked rather
 * than returning what was composited: with this turn and no drawing time it put the view a
 * step ahead of the drawing in 61 of 96 images, while none of 93 pictures ScreenCaptureKit
 * took of the same turn had the two apart.
 *
 * Recording the screen needs the user's permission. A process without it is told so and skips.
 */
class NativeViewSameFrameTest {
    @Test
    fun the_view_and_the_frame_drawn_in_one_turn_show_in_the_same_picture() {
        if (!CGPreflightScreenCaptureAccess()) {
            println("native view: this process may not record the screen, so what it shows cannot be looked at; skipped")
            return
        }
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        val window = MacosWindow(name = "native view same frame", width = 360, height = 360)
        // The window and its views, with no scene: this test draws every frame itself.
        window.initWindow()
        val surface = MetalWindowSurface(window)
        val views = window.nativeViews
        val view = RedView()
        assertTrue(views.attach(1, view.objcPtr().toLong()), "a plain view can be attached")
        views.setVisible(1, true)
        var top = FIRST
        var direction = 1
        var turns = 0
        var moving = false
        val green = Paint().apply { color = GREEN }
        val blue = Paint().apply { color = BLUE }

        fun turn() {
            val scale = window.window.backingScaleFactor
            val (width, height) = window.measure().let { it.width to it.height }
            if (moving) {
                top += STEP * direction
                if (top >= LAST) direction = -1
                if (top <= FIRST) direction = 1
            }
            // A drawable first, as a scene's frame takes one before it lays out.
            val target = surface.begin(width, height) ?: return
            val px = { points: Double -> (points * scale).toInt().toFloat() }
            // Then the layout, which is where a slot is told its frame.
            views.setFrame(1, px(LEFT).toInt(), px(top + BAR).toInt(), px(WIDTH).toInt(), px(VIEW).toInt())
            // A scene takes a few milliseconds to draw what it laid out.
            platform.posix.usleep(DRAWING_MICROS)
            // Then the drawing, submitted and waited for, as a scene's frame does.
            val canvas = target.surface.canvas
            canvas.drawRect(Rect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat()), green)
            canvas.drawRect(Rect.makeXYWH(px(LEFT), px(top), px(WIDTH), px(BAR)), blue)
            canvas.drawRect(Rect.makeXYWH(px(LEFT), px(top + BAR + VIEW), px(WIDTH), px(BAR)), blue)
            target.surface.flushAndSubmit(true)
            target.close()
            turns++
        }

        val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> turn() }
        val screen = Screen()
        try {
            NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
            window.window.makeKeyAndOrderFront(null)
            spin(0.6)
            screen.start(window)?.let { why ->
                println("native view: $why; skipped")
                return
            }
            spin(0.4)
            val rest = screen.pictures.toList()
            screen.pictures.clear()
            assertTrue(
                rest.isNotEmpty() && rest.all { it == 0 },
                "standing still, the view sits between the two bars: $rest (columns: ${screen.lastColumn})",
            )

            moving = true
            spin(1.5)
            moving = false
            val apart = screen.pictures.toList()
            assertTrue(apart.size >= 20, "the moving view was seen in ${apart.size} pictures over $turns turns")
            val off = apart.count { kotlin.math.abs(it) > 1 }
            println("native view: $off of ${apart.size} pictures on the screen had the view apart from the frame: $apart")
            assertTrue(
                off == 0,
                "in $off of ${apart.size} pictures on the screen the view was not where the frame drawn " +
                    "in the same turn put it (pixels, positive when the view is lower): $apart",
            )
        } finally {
            screen.stop()
            timer.invalidate()
            views.detach(1)
            window.window.orderOut(null)
        }
    }

    private fun spin(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.002))
        }
    }

    /**
     * The window's content as the window server composites it, from a ScreenCaptureKit stream of
     * that window alone, read down the slot's column into [pictures]. Its pictures are handed over
     * on the main queue, so they arrive while the test turns the run loop.
     */
    private inner class Screen : NSObject(), SCStreamOutputProtocol {
        /** For each picture, the view's distance below the drawing in pixels (negative: above). */
        val pictures = ArrayList<Int>()
        var lastColumn = ""
        private var stream: SCStream? = null
        private var scale = 1.0

        /** Starts the stream of [window]'s content, or answers why it could not. */
        fun start(window: MacosWindow): String? {
            var content: SCShareableContent? = null
            var failed: String? = null
            var answered = false
            SCShareableContent.getShareableContentExcludingDesktopWindows(false, onScreenWindowsOnly = true) { found, error ->
                content = found
                failed = error?.localizedDescription
                answered = true
            }
            if (!waitFor { answered }) return "the window server did not say what is on the screen"
            val shareable = content ?: return "the window server would not say what is on the screen: $failed"
            val number = window.window.windowNumber.toUInt()
            val shown = shareable.windows.firstOrNull { (it as SCWindow).windowID == number } as SCWindow?
                ?: return "the window is not among those on the screen"
            val frame = shown.frame.useContents { origin.x to origin.y }
            val display = shareable.displays.firstOrNull { d ->
                (d as SCDisplay).frame.useContents {
                    frame.first >= origin.x && frame.first < origin.x + size.width &&
                        frame.second >= origin.y && frame.second < origin.y + size.height
                }
            } as SCDisplay? ?: return "no display holds the window"
            val filter = SCContentFilter(display = display, includingWindows = listOf(shown))
            scale = filter.pointPixelScale.toDouble()
            // The content view's rectangle, in the display's points from its top left.
            val contentView = window.window.contentView!!
            val inWindow: kotlinx.cinterop.CValue<CGRect> = contentView.convertRect(contentView.bounds, toView = null)
            val windowHeight = window.window.frame.useContents { size.height }
            val (x, y, w, h) = inWindow.useContents {
                listOf(origin.x, windowHeight - (origin.y + size.height), size.width, size.height)
            }
            val displayOrigin = display.frame.useContents { origin.x to origin.y }
            val config = SCStreamConfiguration()
            config.sourceRect = CGRectMake(frame.first - displayOrigin.first + x, frame.second - displayOrigin.second + y, w, h)
            config.width = (w * scale).toULong()
            config.height = (h * scale).toULong()
            config.minimumFrameInterval = CMTimeMake(1, 240)
            config.showsCursor = false
            config.pixelFormat = kCVPixelFormatType_32BGRA
            config.queueDepth = 8
            val made = SCStream(filter, config, null)
            if (!made.addStreamOutput(this, SCStreamOutputType.SCStreamOutputTypeScreen, dispatch_get_main_queue(), null)) {
                return "the stream would not take an output"
            }
            var started = false
            var startError: String? = null
            made.startCaptureWithCompletionHandler { error ->
                startError = error?.localizedDescription
                started = true
            }
            if (!waitFor { started }) return "the screen stream did not start"
            startError?.let { return "the screen stream did not start: $it" }
            stream = made
            return null
        }

        fun stop() {
            val running = stream ?: return
            stream = null
            var stopped = false
            running.stopCaptureWithCompletionHandler { stopped = true }
            waitFor { stopped }
        }

        private fun waitFor(done: () -> Boolean): Boolean {
            val begun = kotlin.time.TimeSource.Monotonic.markNow()
            while (!done()) {
                if (begun.elapsedNow().inWholeMilliseconds > 5000) return false
                NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            return true
        }

        override fun stream(stream: SCStream, didOutputSampleBuffer: CMSampleBufferRef?, ofType: SCStreamOutputType) {
            if (ofType != SCStreamOutputType.SCStreamOutputTypeScreen) return
            val sample = didOutputSampleBuffer ?: return
            // Only a picture the window server composited anew; an idle one repeats the last.
            val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false)
                ?.let { interpretObjCPointerOrNull<NSArray>(it.rawValue) }
            val status = (attachments?.takeIf { it.count > 0uL }?.objectAtIndex(0uL) as? NSDictionary)?.objectForKey(SCStreamFrameInfoStatus) as? NSNumber
            if (status?.longValue != SCFrameStatus.SCFrameStatusComplete.value) return
            val buffer = CMSampleBufferGetImageBuffer(sample) ?: return
            CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            try {
                val width = CVPixelBufferGetWidth(buffer).toInt()
                val height = CVPixelBufferGetHeight(buffer).toInt()
                val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
                val base: CPointer<UByteVar> = CVPixelBufferGetBaseAddress(buffer)?.reinterpret() ?: return
                val x = ((LEFT + WIDTH / 2) * scale).toInt()
                if (x !in 0 until width) return
                val from = ((FIRST - 10) * scale).toInt().coerceAtLeast(0)
                val to = ((LAST + 2 * BAR + VIEW + 10) * scale).toInt().coerceAtMost(height)
                val cells = CharArray(to - from) { row ->
                    val pixel = base + ((from + row) * stride + x * 4)
                    classify(pixel!![2].toInt(), pixel[1].toInt(), pixel[0].toInt())
                }
                val column = Column(cells)
                lastColumn = column.runs()
                column.gaps()?.let { (above, below) -> pictures += above - below }
            } finally {
                CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            }
        }
    }

    /** One column of a picture, down the middle of the slot: 'R' view, 'G' page, 'B' bar. */
    private class Column(val cells: CharArray) {
        /** Runs of one colour, for a failure message. */
        fun runs(): String {
            val out = StringBuilder()
            var i = 0
            while (i < cells.size) {
                var j = i
                while (j < cells.size && cells[j] == cells[i]) j++
                out.append(cells[i]).append(j - i).append(' ')
                i = j
            }
            return out.toString().trim()
        }

        /**
         * The green rows between the view and the bar above it, and between the view and the bar
         * below it, or null when the column does not hold a bar, the view and a bar in that order.
         */
        fun gaps(): Pair<Int, Int>? {
            val first = cells.indexOf('R')
            val last = cells.lastIndexOf('R')
            if (first < 0) return null
            var above = 0
            var i = first - 1
            while (i >= 0 && cells[i] == 'G') { above++; i-- }
            if (i < 0 || cells[i] != 'B') return null
            var below = 0
            i = last + 1
            while (i < cells.size && cells[i] == 'G') { below++; i++ }
            if (i >= cells.size || cells[i] != 'B') return null
            return above to below
        }
    }

    private companion object {
        const val GREEN = 0xFF00C000.toInt()
        const val BLUE = 0xFF0000E0.toInt()

        /** The slot's column and size, and its bars' height, in points. */
        const val LEFT = 60.0
        const val WIDTH = 200.0
        const val VIEW = 80.0
        const val BAR = 20.0

        /** The top bar's range of travel, and how far it moves a turn, in points. */
        const val FIRST = 70.0
        const val LAST = 220.0
        const val STEP = 6.0

        /** How long the stand-in for a scene spends drawing, between laying out and presenting. */
        const val DRAWING_MICROS = 4000u

        fun classify(r: Int, g: Int, b: Int): Char = when {
            r > 180 && g < 80 && b < 80 -> 'R'
            g > 140 && r < 80 && b < 80 -> 'G'
            b > 160 && r < 80 && g < 80 -> 'B'
            else -> '?'
        }
    }
}
