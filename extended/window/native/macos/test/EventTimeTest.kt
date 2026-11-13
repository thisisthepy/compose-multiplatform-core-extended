@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.DoubleClick
import org.thisisthepy.compose.window.WindowEvent
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSEvent
import platform.AppKit.NSEventType
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A press reaches the scene dated by `NSEvent.timestamp`, not by when it was read, and the
 * platform answers the system's double-click interval.
 */
class EventTimeTest {
    private fun turn(seconds: Double) {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
    }

    @Test
    fun a_press_and_release_carry_the_time_appkit_gave_them() {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        val window = MacosWindow(name = "event time", width = 300, height = 200)
        try {
            window.setContent { Box(Modifier.fillMaxSize()) }
            val heard = ArrayList<WindowEvent>()
            val forward = window.onEvent
            window.onEvent = { event ->
                heard += event
                forward?.invoke(event)
            }
            window.window.makeKeyAndOrderFront(null)
            turn(0.3)
            val content = window.window.contentView!!
            val middle = content.bounds.useContents { NSMakePoint(size.width / 2, size.height / 2) }
            val at = content.convertPoint(middle, toView = null)
            fun mouse(type: NSEventType, seconds: Double) = NSEvent.mouseEventWithType(
                type = type,
                location = at,
                modifierFlags = 0u,
                timestamp = seconds,
                windowNumber = window.window.windowNumber,
                context = null,
                eventNumber = 0L,
                clickCount = 1,
                pressure = if (type == NSEventTypeLeftMouseDown) 1f else 0f,
            )!!
            window.window.sendEvent(mouse(NSEventTypeLeftMouseDown, 1234.5))
            window.window.sendEvent(mouse(NSEventTypeLeftMouseUp, 1234.625))
            val down = heard.first { it.kind == WindowEvent.POINTER_DOWN }
            val up = heard.first { it.kind == WindowEvent.POINTER_UP }
            assertEquals(1_234_500L, down.timeMillis)
            assertEquals(1_234_625L, up.timeMillis)
        } finally {
            window.window.orderOut(null)
        }
    }

    @Test
    fun the_double_click_interval_is_the_systems() {
        val platform = MacosWindowPlatform {}
        assertEquals(DoubleClick.fromSeconds(NSEvent.doubleClickInterval), platform.doubleClickMillis)
        assertTrue(platform.doubleClickMillis in DoubleClick.MIN_MILLIS..DoubleClick.MAX_MILLIS)
    }
}
