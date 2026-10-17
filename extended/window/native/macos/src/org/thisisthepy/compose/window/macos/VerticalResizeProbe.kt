@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import platform.AppKit.NSApplication
import platform.AppKit.NSEvent
import platform.AppKit.NSEventType
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.postEvent
import platform.Foundation.NSMakePoint
import platform.Foundation.NSProcessInfo
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAMetalLayer

/**
 * DXC_VRESIZE_PROBE=1: drags the window's bottom edge and then its top edge with mouse
 * events, width unchanged, and watches from another thread where the content lands on the
 * screen while it happens.
 *
 * Every frame gets three marks laid over it after the scene has drawn it, at fixed places
 * counted from the frame's top left corner: a magenta square 64 points down, a cyan one 32
 * points up from the frame's bottom edge, and two rows of 16 blocks that spell the frame's
 * number and the height it was drawn at. A second thread takes the window's own image
 * through the public CGWindowListCreateImage as fast as it can, finds the magenta square,
 * and prints how far below the top of the window it is. Content that stays put on the
 * screen keeps that number constant through the drag; content that jumps does not, and the
 * rows say which frame was on the screen when it did and what height it had been drawn for.
 *
 * The main thread prints, at each event of the drag, the window's frame, its content layout
 * rectangle, the view's bounds, and the layer's bounds, anchor point, gravity, flip and
 * drawable size, so a moved frame can be matched with the geometry around it.
 *
 * Beside the marks, a yellow square of the platform's own: a plain AppKit view 80 points in
 * and 64 down, kept at the top of the window by AppKit. Where it stands in each image says
 * what the window server does to content nobody but AppKit draws, for comparison.
 *
 * The same marks, at the same places, as the native image's window lays over its frames
 * (appkit_vresize_probe.h), so one reader checks both.
 */
internal object VerticalResizeProbe {
    val enabled: Boolean = platform.posix.getenv("DXC_VRESIZE_PROBE")?.toKString() != null

    private const val MARK_X = 40.0
    private const val MARK_TOP = 64.0
    private const val MARK_SIZE = 16.0
    private const val MARK_BOTTOM = 32.0
    private const val BITS_TOP = 88.0
    private const val BIT_W = 4.0
    private const val BIT_H = 6.0
    private const val BITS = 16
    private const val EVENTS = 40
    private const val CONTROL_X = 80.0

    @kotlin.concurrent.Volatile private var frames = 0
    @kotlin.concurrent.Volatile private var lastHeight = 0
    @kotlin.concurrent.Volatile private var sampling = false
    @kotlin.concurrent.Volatile private var scale = 1.0
    private var windowNumber = 0u
    private val started = kotlin.time.TimeSource.Monotonic.markNow()

    private fun ms(): String = format(started.elapsedNow().inWholeMicroseconds / 1000.0)

    private val magenta = Paint().apply { color = 0xFFFF00FF.toInt() }
    private val cyan = Paint().apply { color = 0xFF00FFFF.toInt() }
    private val white = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val black = Paint().apply { color = 0xFF000000.toInt() }

    private fun px(points: Double): Float = kotlin.math.round(points * scale).toFloat()

    /** Lays the marks over a frame [width] by [height] pixels, drawn at [pixelsPerPoint]. */
    /** What the frame being drawn came from, for the line printed about it. */
    var via = "loop"

    /** The view was given a size; said only while the drag is being watched. */
    fun setFrame(from: Pair<Double, Double>, to: Pair<Double, Double>) {
        if (!sampling) return
        printError(
            "compose-rust: vresize set-frame t_ms=${ms()} from=${f1(from.first)}x${f1(from.second)} " +
                "to=${f1(to.first)}x${f1(to.second)} changed=${if (from == to) 0 else 1}",
        )
    }

    fun mark(canvas: Canvas, width: Int, height: Int, pixelsPerPoint: Double, drawableWaitMillis: Double) {
        scale = pixelsPerPoint
        val frame = ++frames
        lastHeight = height
        val side = px(MARK_SIZE)
        val left = px(MARK_X)
        canvas.drawRect(Rect.makeXYWH(left, px(MARK_TOP), side, side), magenta)
        canvas.drawRect(Rect.makeXYWH(left, height - px(MARK_BOTTOM) - side, side, side), cyan)
        bits(canvas, 0, frame)
        bits(canvas, 1, height)
        if (sampling) {
            printError(
                "compose-rust: vresize drawn t_ms=${ms()} frame=$frame texture=${width}x$height " +
                    "height_px=$height via=$via drawable_wait_ms=${format(drawableWaitMillis)}",
            )
        }
    }

    private fun bits(canvas: Canvas, row: Int, value: Int) {
        val top = px(BITS_TOP + row * BIT_H)
        val h = px(BIT_H)
        for (bit in 0 until BITS) {
            val on = (value shr (BITS - 1 - bit)) and 1 == 1
            canvas.drawRect(
                Rect.makeXYWH(px(MARK_X + bit * BIT_W), top, px(BIT_W), h),
                if (on) white else black,
            )
        }
    }

    /**
     * Starts the drag three seconds from now. Runs on the main thread from a timer in the
     * common modes, so it keeps posting inside the tracking loop AppKit runs for a live
     * resize, as a hand's events would arrive.
     */
    fun start(window: NSWindow, view: NSView, layer: CAMetalLayer) {
        platform.darwin.dispatch_after(
            platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 3_000_000_000L),
            platform.darwin.dispatch_get_main_queue(),
        ) { begin(window, view, layer) }
    }

    private fun begin(window: NSWindow, view: NSView, layer: CAMetalLayer) {
        windowNumber = window.windowNumber.toUInt()
        val control = NSView(platform.Foundation.NSMakeRect(CONTROL_X, MARK_TOP, MARK_SIZE, MARK_SIZE))
        control.wantsLayer = true
        control.layer?.backgroundColor = platform.CoreGraphics.CGColorCreateSRGB(1.0, 1.0, 0.0, 1.0)
        // The view is flipped, so a subview that is not told to move keeps its distance
        // from the top.
        view.addSubview(control)
        scale = window.backingScaleFactor
        val visible = window.screen?.visibleFrame
        val startX = window.frame.useContents { origin.x }
        val startY = window.frame.useContents { origin.y }
        val startW = window.frame.useContents { size.width }
        val startH = window.frame.useContents { size.height }
        val visibleBottom = visible?.useContents { origin.y } ?: 0.0
        val visibleTop = visible?.useContents { origin.y + size.height } ?: (startY + startH)
        // As far as the screen allows, up to 160 points, with ten to spare.
        val down = minOf(160.0, startY - visibleBottom - 10)
        val up = minOf(160.0, visibleTop - (startY + startH) - 10)
        printError(
            "compose-rust: vresize begin window_frame=${f1(startX)},${f1(startY)},${f1(startW)}x${f1(startH)} " +
                "visible_bottom=${f1(visibleBottom)} visible_top=${f1(visibleTop)} scale=${format(scale)} " +
                "bottom_travel_pt=${f1(down)} top_travel_pt=${f1(up)}",
        )
        sampling = true
        platform.darwin.dispatch_async(
            platform.darwin.dispatch_get_global_queue(platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u),
        ) {
            var index = 0
            while (sampling) sample(index++)
        }

        val x = startX + startW / 2 - 40
        var tick = 0
        val timer = platform.Foundation.NSTimer.timerWithTimeInterval(1.0 / 60, repeats = true) { timer ->
            // Bottom edge: press 2 points inside it, drag down and back. Then the top edge,
            // up and back. Twenty quiet ticks before, between and after.
            val phaseLength = 1 + 2 * EVENTS + 1 + 20
            val at = tick++ - 20
            if (at < 0) return@timerWithTimeInterval
            val edge = at / phaseLength
            val i = at % phaseLength
            if (edge >= 2) {
                timer?.invalidate()
                sampling = false
                platform.darwin.dispatch_after(
                    platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 300_000_000L),
                    platform.darwin.dispatch_get_main_queue(),
                ) { printError("compose-rust: vresize done") }
                return@timerWithTimeInterval
            }
            val name = if (edge == 0) "bottom" else "top"
            val travel = if (edge == 0) -down else up
            val y0 = if (edge == 0) startY + 2 else startY + startH - 2
            when {
                i == 0 -> post(window, platform.AppKit.NSEventTypeLeftMouseDown, x, y0)
                i <= 2 * EVENTS -> {
                    val k = if (i <= EVENTS) i else 2 * EVENTS - i
                    post(window, platform.AppKit.NSEventTypeLeftMouseDragged, x, y0 + travel * k / EVENTS)
                }
                i == 2 * EVENTS + 1 -> post(window, platform.AppKit.NSEventTypeLeftMouseUp, x, y0)
            }
            if (i <= 2 * EVENTS + 1) geometry(window, view, layer, name, i)
        }
        platform.Foundation.NSRunLoop.currentRunLoop.addTimer(timer, forMode = platform.Foundation.NSRunLoopCommonModes)
    }

    /** A mouse event at a point on the screen, given in the window's coordinates as it is now. */
    private fun post(window: NSWindow, type: NSEventType, screenX: Double, screenY: Double) {
        val originX = window.frame.useContents { origin.x }
        val originY = window.frame.useContents { origin.y }
        val event = NSEvent.mouseEventWithType(
            type = type,
            location = NSMakePoint(screenX - originX, screenY - originY),
            modifierFlags = 0u,
            timestamp = NSProcessInfo.processInfo.systemUptime,
            windowNumber = window.windowNumber,
            context = null,
            eventNumber = 0,
            clickCount = 1,
            pressure = 1f,
        ) ?: return
        NSApplication.sharedApplication().postEvent(event, atStart = false)
    }

    private fun geometry(window: NSWindow, view: NSView, layer: CAMetalLayer, edge: String, step: Int) {
        val frame = window.frame.useContents { "${f1(origin.x)},${f1(origin.y)},${f1(size.width)}x${f1(size.height)}" }
        val layout = window.contentLayoutRect.useContents {
            "${f1(origin.x)},${f1(origin.y)},${f1(size.width)}x${f1(size.height)}"
        }
        val bounds = view.bounds.useContents { "${f1(size.width)}x${f1(size.height)}" }
        val layerBounds = layer.bounds.useContents { "${f1(size.width)}x${f1(size.height)}" }
        val position = layer.position.useContents { "${f1(x)},${f1(y)}" }
        val anchor = layer.anchorPoint.useContents { "${format(x)},${format(y)}" }
        val drawable = layer.drawableSize.useContents { "${width.toInt()}x${height.toInt()}" }
        var flips = 0
        var above: CALayer? = layer
        while (above != null) {
            if (above.geometryFlipped) flips++
            above = above.superlayer
        }
        printError(
            "compose-rust: vresize step edge=$edge i=$step t_ms=${ms()} window_frame=$frame " +
                "content_layout=$layout view_bounds=$bounds layer_bounds=$layerBounds " +
                "layer_position=$position anchor=$anchor gravity=${layer.contentsGravity} " +
                "layer_flipped=${if (layer.geometryFlipped) 1 else 0} flips=$flips drawable=$drawable " +
                "frames_drawn=$frames live=${if (view.inLiveResize) 1 else 0}",
        )
    }

    private fun isColour(pixels: ByteArray, at: Int, r: Int, g: Int, b: Int): Boolean =
        kotlin.math.abs((pixels[at].toInt() and 0xFF) - r) < 48 &&
            kotlin.math.abs((pixels[at + 1].toInt() and 0xFF) - g) < 48 &&
            kotlin.math.abs((pixels[at + 2].toInt() and 0xFF) - b) < 48

    private fun readBits(pixels: ByteArray, width: Int, height: Int, markerTop: Int, row: Int): Long {
        val centerY = markerTop + kotlin.math.round((BITS_TOP - MARK_TOP + row * BIT_H + BIT_H / 2) * scale).toInt()
        if (centerY < 0 || centerY >= height) return -1
        var value = 0L
        for (bit in 0 until BITS) {
            val x = kotlin.math.round((MARK_X + bit * BIT_W + BIT_W / 2) * scale).toInt()
            if (x < 0 || x >= width) return -1
            val at = (centerY * width + x) * 4
            val sum = (pixels[at].toInt() and 0xFF) + (pixels[at + 1].toInt() and 0xFF) + (pixels[at + 2].toInt() and 0xFF)
            value = (value shl 1) or (if (sum > 3 * 128) 1L else 0L)
        }
        return value
    }

    /** Takes the window's image once and prints where the marks are on it. */
    private fun sample(index: Int) {
        val at = ms()
        // kCGWindowListOptionIncludingWindow; kCGWindowImageBoundsIgnoreFraming and
        // kCGWindowImageBestResolution, so one pixel of the image is one backing pixel.
        @Suppress("DEPRECATION")
        val image = platform.CoreGraphics.CGWindowListCreateImage(
            platform.CoreGraphics.CGRectNull.readValue(),
            1u shl 3,
            windowNumber,
            (1u shl 0) or (1u shl 3),
        )
        if (image == null) {
            printError("compose-rust: vresize sample i=$index t_ms=$at error=no-image")
            return
        }
        val width = platform.CoreGraphics.CGImageGetWidth(image).toInt()
        val height = platform.CoreGraphics.CGImageGetHeight(image).toInt()
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
            val context = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0),
                width.toULong(),
                height.toULong(),
                8u,
                (width * 4).toULong(),
                space,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            platform.CoreGraphics.CGContextDrawImage(
                context,
                platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                image,
            )
            platform.CoreGraphics.CGContextRelease(context)
            platform.CoreGraphics.CGColorSpaceRelease(space)
        }
        platform.CoreGraphics.CGImageRelease(image)

        val column = kotlin.math.round((MARK_X + MARK_SIZE / 2) * scale).toInt()
        var top = -1
        var bottom = -1
        var cyanBottom = -1
        if (column < width) {
            for (y in 0 until height) {
                if (isColour(pixels, (y * width + column) * 4, 255, 0, 255)) {
                    if (top < 0) top = y
                    bottom = y
                } else if (top >= 0) {
                    break
                }
            }
            for (y in height - 1 downTo 0) {
                if (isColour(pixels, (y * width + column) * 4, 0, 255, 255)) {
                    cyanBottom = y
                    break
                }
            }
        }
        var controlTop = -1
        val controlColumn = kotlin.math.round((CONTROL_X + MARK_SIZE / 2) * scale).toInt()
        if (controlColumn < width) {
            for (y in 0 until height) {
                if (isColour(pixels, (y * width + controlColumn) * 4, 255, 255, 0)) {
                    controlTop = y
                    break
                }
            }
        }
        val frame = if (top >= 0) readBits(pixels, width, height, top, 0) else -1
        val drawn = if (top >= 0) readBits(pixels, width, height, top, 1) else -1
        printError(
            "compose-rust: vresize sample i=$index t_ms=$at image=${width}x$height scale=${format(scale)} " +
                "found=${if (top >= 0) 1 else 0} " +
                "marker_top_pt=${if (top >= 0) format(top / scale) else "-1.00"} " +
                "marker_h_pt=${if (top >= 0) format((bottom - top + 1) / scale) else "-1.00"} " +
                "bottom_gap_pt=${if (cyanBottom >= 0) format((height - 1 - cyanBottom) / scale) else "-1.00"} " +
                "shown_frame=$frame shown_height_px=$drawn image_height_px=$height " +
                "last_drawn_frame=$frames last_drawn_height_px=$lastHeight " +
                "control_top_pt=${if (controlTop >= 0) format(controlTop / scale) else "-1.00"}",
        )
    }

    private fun f1(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toLong()
        val sign = if (tenths < 0) "-" else ""
        val magnitude = kotlin.math.abs(tenths)
        return "$sign${magnitude / 10}.${magnitude % 10}"
    }

    private fun format(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toLong()
        val sign = if (hundredths < 0) "-" else ""
        val magnitude = kotlin.math.abs(hundredths)
        return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
    }
}
