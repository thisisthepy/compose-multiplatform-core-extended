@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.WindowEvent
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeMouseMoved
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGEventCreateScrollWheelEvent2
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGScrollEventUnit
import platform.CoreGraphics.kCGScrollEventUnitLine
import platform.CoreGraphics.kCGScrollEventUnitPixel
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A wheel or a trackpad scrolls a real window's content.
 *
 * Nothing scrolled on this window: every scroll reached the scene at the top left corner, and
 * the scrollable under the pointer was never hit; and Compose's macOS scroll distance came
 * from the NSEvent alone, which this window does not hand the scene, so each scroll that did
 * land moved nothing. The column here keeps clear of the corner, so a scroll that went there
 * would be missed.
 *
 * The scroll events are AppKit's own, made with CGEventCreateScrollWheelEvent2 in lines (a
 * wheel) and in pixels (a trackpad), and handed to the content view's scrollWheel as AppKit
 * would hand them.
 */
class WheelScrollTest {

    private class Fixture(
        val window: MacosWindow,
        val heard: MutableList<WindowEvent>,
        val state: ScrollState,
        val columnHeard: MutableList<Offset>,
    ) {
        val scale: Double get() = window.window.backingScaleFactor
    }

    private fun turn(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    private fun withColumn(body: (Fixture) -> Unit) {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        val window = MacosWindow(name = "wheel scroll", width = 400, height = 400)
        val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
        val state = ScrollState(0)
        val columnHeard = ArrayList<Offset>()
        try {
            window.setContent {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .offset(COLUMN_X.dp, COLUMN_Y.dp)
                            .size(COLUMN_SIZE.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type == PointerEventType.Scroll) columnHeard += event.changes.first().scrollDelta
                                    }
                                }
                            }
                            .verticalScroll(state),
                    ) {
                        Box(Modifier.size(COLUMN_SIZE.dp, 4000.dp))
                    }
                }
            }
            val heard = ArrayList<WindowEvent>()
            val forward = window.onEvent
            window.onEvent = { event ->
                heard += event
                forward?.invoke(event)
            }
            NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
            window.window.orderFront(null)
            turn(0.5)
            body(Fixture(window, heard, state, columnHeard))
        } finally {
            timer.invalidate()
            window.window.orderOut(null)
        }
    }

    /** A point [x], [y] in content points from the top left, in the window's coordinates. */
    private fun Fixture.inWindow(x: Double, y: Double): CValue<CGPoint> {
        val content = window.window.contentView!!
        val height = content.bounds.useContents { size.height }
        return content.convertPoint(NSMakePoint(x, height - y), toView = null)
    }

    private fun Fixture.moveTo(x: Double, y: Double) {
        window.view.mouseMoved(
            NSEvent.mouseEventWithType(
                type = NSEventTypeMouseMoved,
                location = inWindow(x, y),
                modifierFlags = 0u,
                timestamp = NSProcessInfo.processInfo.systemUptime,
                windowNumber = window.window.windowNumber,
                context = null,
                eventNumber = 0L,
                clickCount = 1,
                pressure = 0f,
            )!!,
        )
    }

    /**
     * AppKit's scroll of [vertical] in [unit]: positive asks for what is above, as AppKit's
     * scrollingDeltaY counts, and negative for what is below.
     */
    private fun Fixture.scroll(vertical: Int, unit: CGScrollEventUnit) {
        val made = CGEventCreateScrollWheelEvent2(
            source = null,
            units = unit,
            wheelCount = 1u,
            wheel1 = vertical,
            wheel2 = 0,
            wheel3 = 0,
        )!!
        try {
            window.view.scrollWheel(NSEvent.eventWithCGEvent(made)!!)
        } finally {
            CFRelease(made)
        }
    }

    // AppKit's zero horizontal travel arrives negated, as -0, which an Offset tells apart from 0.
    private fun plain(x: Float, y: Float) = Offset(x + 0f, y + 0f)

    private fun Fixture.scrolls() = heard.filter { it.kind == WindowEvent.SCROLL }

    /** The scroll position settles once the scroll's animation ends; waits for that. */
    private fun Fixture.settled(): Int {
        var last = state.value
        var still = 0
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (still < 10 && begun.elapsedNow().inWholeMilliseconds < 3000) {
            turn(0.05)
            if (state.value == last) still++ else still = 0
            last = state.value
        }
        return last
    }

    /** Ten dp a line, in the scene's pixels; the scene's density is the backing scale. */
    private fun Fixture.lines(count: Int): Double = count * 10 * scale

    @Test
    fun a_wheel_over_the_column_reaches_it_with_the_travel_in_lines() = withColumn { f ->
        f.moveTo(COLUMN_X + 75.0, COLUMN_Y + 75.0)
        f.heard.clear()
        f.scroll(-3, kCGScrollEventUnitLine)
        f.scroll(3, kCGScrollEventUnitLine)
        turn(0.3)
        assertEquals(
            listOf(Offset(0f, 3f), Offset(0f, -3f)),
            f.scrolls().map { plain(it.x, it.y) },
            "the window sends three lines down and three up",
        )
        assertEquals(
            listOf(Offset(0f, 3f), Offset(0f, -3f)),
            f.columnHeard.map { plain(it.x, it.y) },
            "both reached the column under the pointer, not the corner",
        )
    }

    @Test
    fun a_trackpad_over_the_column_reaches_it_with_ten_points_a_line() = withColumn { f ->
        f.moveTo(COLUMN_X + 75.0, COLUMN_Y + 75.0)
        f.heard.clear()
        f.scroll(-30, kCGScrollEventUnitPixel)
        turn(0.3)
        assertEquals(listOf(Offset(0f, 3f)), f.scrolls().map { plain(it.x, it.y) }, "thirty points are three lines")
        assertEquals(listOf(Offset(0f, 3f)), f.columnHeard.map { plain(it.x, it.y) }, "it reached the column under the pointer")
    }

    @Test
    fun a_scroll_before_any_move_is_placed_where_the_pointer_is_now() = withColumn { f ->
        f.scroll(-1, kCGScrollEventUnitLine)
        val scroll = f.heard.indexOfFirst { it.kind == WindowEvent.SCROLL }
        assertTrue(scroll >= 0, "the scroll was sent: ${f.heard}")
        val place = assertNotNull(
            f.heard.subList(0, scroll).lastOrNull {
                it.kind == WindowEvent.POINTER_MOVE || it.kind == WindowEvent.POINTER_ENTER
            },
            "the scene was told where the pointer is before the scroll: ${f.heard}",
        )
        val now = scenePoint(f.window.view, f.window.window.mouseLocationOutsideOfEventStream, f.scale)
        assertEquals(now.x, place.x, 1f, "at the pointer's place now")
        assertEquals(now.y, place.y, 1f, "at the pointer's place now")
    }

    // The two below scroll the column end to end. They need the fork's foundation with the
    // macOS scroll distance worked out from the travel when no NSEvent comes with it. CI builds
    // that foundation from the same commit before it runs these; a local run against an older
    // published foundation fails them with the position still at 0.

    @Test
    fun a_wheel_moves_the_column_the_way_an_appkit_view_moves() = withColumn { f ->
        f.moveTo(COLUMN_X + 75.0, COLUMN_Y + 75.0)
        // A wheel turned toward the reader (natural scrolling off): AppKit's -3 lines, what is
        // below comes into view and the scroll position grows by three lines.
        f.scroll(-3, kCGScrollEventUnitLine)
        val down = f.settled()
        assertTrue(abs(down - f.lines(3)) <= 1.0, "three lines down is ${f.lines(3)} pixels, was $down")
        f.scroll(2, kCGScrollEventUnitLine)
        val up = f.settled()
        assertTrue(abs(up - f.lines(1)) <= 1.0, "two lines back up leaves ${f.lines(1)} pixels, was $up")
    }

    @Test
    fun a_trackpad_moves_the_column_as_far_as_the_fingers_moved() = withColumn { f ->
        f.moveTo(COLUMN_X + 75.0, COLUMN_Y + 75.0)
        // Two fingers pushed up with natural scrolling on: AppKit's -40 points, the content
        // follows them up by forty points.
        f.scroll(-40, kCGScrollEventUnitPixel)
        val moved = f.settled()
        val expected = 40 * f.scale
        assertTrue(abs(moved - expected) <= 1.0, "forty points are $expected pixels, was $moved")
    }

    private companion object {
        // Clear of the top left corner, where every scroll used to land.
        const val COLUMN_X = 100
        const val COLUMN_Y = 100
        const val COLUMN_SIZE = 150
    }
}
