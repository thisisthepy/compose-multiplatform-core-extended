@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package org.thisisthepy.compose.window.macos

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import kotlin.math.abs
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.AppKit.NSApplication
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeRightMouseDown
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuDidBeginTrackingNotification
import platform.AppKit.NSMenuItem
import platform.AppKit.NSTextInputClientProtocol
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.usleep

class MacosImeAndWakeTest {
    private val noOpListener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }

    private fun millisOf(block: () -> Unit): Long {
        val started = TimeSource.Monotonic.markNow()
        block()
        return started.elapsedNow().inWholeMilliseconds
    }

    @Test
    fun setImeSpotMovesFirstRectForCharacterRange() {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        platform.setImeSpot(60, 100)
        try {
            assertTrue(platform.open(WindowConfig("ime-spot", 400, 300), noOpListener))
            val win = assertNotNull(platform.window)
            assertEquals(60 to 100, win.imeSpot)

            val client = win.view as NSTextInputClientProtocol
            val scale = win.window.backingScaleFactor.takeIf { it > 0.0 } ?: 1.0
            val before = client.firstRectForCharacterRange(NSMakeRange(0u, 0u), null)
            val beforeX = before.useContents { origin.x }
            val beforeY = before.useContents { origin.y }

            platform.setImeSpot(160, 180)
            assertEquals(160 to 180, win.imeSpot)

            val after = client.firstRectForCharacterRange(NSMakeRange(0u, 0u), null)
            val afterX = after.useContents { origin.x }
            val afterY = after.useContents { origin.y }
            val afterW = after.useContents { size.width }
            val afterH = after.useContents { size.height }

            assertTrue(
                abs((afterX - beforeX) - (100.0 / scale)) < 0.01,
                "expected x delta ${100.0 / scale}, got ${afterX - beforeX}",
            )
            assertTrue(
                abs((afterY - beforeY) - (-80.0 / scale)) < 0.01,
                "expected y delta ${-80.0 / scale}, got ${afterY - beforeY}",
            )
            assertTrue(abs(afterW - 1.0) < 0.01, "expected width 1.0, got $afterW")
            assertTrue(abs(afterH - 20.0) < 0.01, "expected height 20.0, got $afterH")
        } finally {
            platform.close()
        }
    }

    @Test
    fun macosTextToolbarBuildsMenuAndConvertsCoordinates() {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        try {
            assertTrue(platform.open(WindowConfig("toolbar", 320, 240), noOpListener))
            val toolbar = MacosTextToolbar { platform.window!!.view }

            val full = toolbar.buildMenu(
                onCopyRequested = {},
                onPasteRequested = {},
                onCutRequested = {},
                onSelectAllRequested = {},
            )
            assertEquals(5, full.numberOfItems.toInt())
            val titles = (0 until full.numberOfItems.toInt()).map { index ->
                val item = full.itemAtIndex(index.toLong()) as NSMenuItem
                if (item.separatorItem) "---" else item.title
            }
            assertEquals(listOf("Cut", "Copy", "Paste", "---", "Select All"), titles)

            val selectOnly = toolbar.buildMenu(
                onCopyRequested = null,
                onPasteRequested = null,
                onCutRequested = null,
                onSelectAllRequested = {},
            )
            assertEquals(1, selectOnly.numberOfItems.toInt())
            assertEquals("Select All", (selectOnly.itemAtIndex(0) as NSMenuItem).title)

            val empty = toolbar.buildMenu(null, null, null, null)
            assertEquals(0, empty.numberOfItems.toInt())

            val flippedPoint = menuLocation(Rect(20f, 10f, 120f, 50f), viewHeight = 300.0, flipped = true, scale = 2.0)
            flippedPoint.useContents {
                assertEquals(10.0, x)
                assertEquals(25.0, y)
            }

            val unflippedPoint = menuLocation(Rect(20f, 10f, 120f, 50f), viewHeight = 300.0, flipped = false, scale = 2.0)
            unflippedPoint.useContents {
                assertEquals(10.0, x)
                assertEquals(275.0, y)
            }
        } finally {
            platform.close()
        }
    }

    @Test
    fun platformProvidesMacosTextToolbarToContent() {
        NSApplication.sharedApplication()
        var provided: TextToolbar? = null
        val platform = MacosWindowPlatform {
            provided = LocalTextToolbar.current
        }
        try {
            assertTrue(platform.open(WindowConfig("toolbar-local", 320, 240), noOpListener))
            repeat(5) { platform.pump(5) }
            assertTrue(provided is MacosTextToolbar, "expected MacosTextToolbar, got $provided")
        } finally {
            platform.close()
        }
    }

    @Test
    fun nativeEditMenuShowsOnRightClickWhenEnabled() {
        val app = NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        platform.nativeEditMenu = true
        var trackedMenus = 0
        var capturedTitles = emptyList<String>()
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSMenuDidBeginTrackingNotification,
            `object` = null,
            queue = null,
        ) { note ->
            val menu = note?.`object` as? NSMenu ?: return@addObserverForName
            if (menu.supermenu != null || menu == app.mainMenu) return@addObserverForName
            trackedMenus++
            capturedTitles = menu.itemArray.mapNotNull { (it as? NSMenuItem)?.title }
            dispatch_async(dispatch_get_main_queue()) { menu.cancelTrackingWithoutAnimation() }
        }
        try {
            assertTrue(platform.open(WindowConfig("edit-menu", 320, 240), noOpListener))
            val win = assertNotNull(platform.window)
            val middle = win.view.bounds.useContents { NSMakePoint(size.width / 2.0, size.height / 2.0) }
            val at = win.view.convertPoint(middle, toView = null)
            val rightDown = NSEvent.mouseEventWithType(
                type = NSEventTypeRightMouseDown,
                location = at,
                modifierFlags = 0u,
                timestamp = NSProcessInfo.processInfo.systemUptime,
                windowNumber = win.window.windowNumber,
                context = null,
                eventNumber = 0,
                clickCount = 1,
                pressure = 1f,
            )!!
            win.view.rightMouseDown(rightDown)
            assertEquals(1, trackedMenus, "expected native edit menu to track once")
            assertTrue("Cut" in capturedTitles, "missing Cut in $capturedTitles")
            assertTrue("Copy" in capturedTitles, "missing Copy in $capturedTitles")
            assertTrue("Paste" in capturedTitles, "missing Paste in $capturedTitles")
            assertTrue("Select All" in capturedTitles, "missing Select All in $capturedTitles")
        } finally {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            platform.close()
        }
    }

    @Test
    fun wakeFromAnotherThreadEndsPumpWaitEarly() {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        try {
            assertTrue(platform.open(WindowConfig("wake-thread", 320, 240), noOpListener))
            repeat(10) { platform.pump(5) }
            assertTrue(millisOf { platform.pump(250) } >= 180, "idle pump returned before timeout")

            val worker = Worker.start()
            try {
                val waited = millisOf {
                    worker.execute(TransferMode.SAFE, { platform }) { target ->
                        usleep(80_000u)
                        target.wake()
                    }
                    platform.pump(5_000)
                }
                assertTrue(waited < 1_000, "wake from another thread did not end 5s wait ($waited ms)")
            } finally {
                worker.requestTermination().result
            }
        } finally {
            platform.close()
        }
    }

    @Test
    fun wakeBeforePumpIsConsumedOnceAndHarmlessWhenClosed() {
        val neverOpened = MacosWindowPlatform()
        neverOpened.wake()
        assertFalse(neverOpened.isKey)

        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        try {
            assertTrue(platform.open(WindowConfig("wake-pre", 320, 240), noOpListener))
            repeat(10) { platform.pump(5) }

            platform.wake()
            platform.wake()
            val preWaited = millisOf { platform.pump(5_000) }
            assertTrue(preWaited < 1_000, "wake sent before pump did not end wait ($preWaited ms)")
            val nextWaited = millisOf { platform.pump(250) }
            assertTrue(nextWaited >= 180, "wake was consumed by more than one pump ($nextWaited ms)")
        } finally {
            platform.close()
        }
        platform.wake()
    }
}
