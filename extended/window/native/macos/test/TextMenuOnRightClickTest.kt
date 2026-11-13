@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSEvent
import platform.AppKit.NSEventMaskAny
import platform.AppKit.nextEventMatchingMask
import platform.AppKit.sendEvent
import platform.AppKit.NSEventTypeRightMouseDown
import platform.AppKit.NSEventTypeRightMouseUp
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuDidBeginTrackingNotification
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGWarpMouseCursorPosition
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A right click in a text field of the Kotlin/Native window puts up one menu (FR-33.1,
 * DarkPyonix/compose-rust#180): the system's `NSMenu` that Compose builds from what the field
 * allows, and the application's own where it gives one. The window adds no menu of its own.
 *
 * Every menu that starts tracking is counted and closed again at once, so the test never waits
 * on a menu. Compose puts its menu up in the key window, so the test makes its window key.
 *
 * With `DXC_TEXT_MENU_CAPTURE` set to a file path, the pointer is moved onto the field, the
 * menu is left up for a moment and the window's part of the screen is written there.
 */
class TextMenuOnRightClickTest {
    private var value by mutableStateOf(TextFieldValue("hello world", TextRange(0, 5)))
    private var windowPlatform: MacosWindowPlatform? = null
    private var menus = 0
    private var observer: Any? = null

    private val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }

    private fun open(wrap: @Composable (@Composable () -> Unit) -> Unit = { it() }): NSWindow {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        application.activateIgnoringOtherApps(true)
        val opened = MacosWindowPlatform {
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                wrap {
                    val focus = remember { FocusRequester() }
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxSize().focusRequester(focus),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        windowPlatform = opened
        assertTrue(opened.open(WindowConfig("one menu", 400, 300, chrome = WindowChrome.System), listener))
        val window = opened.nativeWindow!!
        window.makeKeyAndOrderFront(null)
        application.activateIgnoringOtherApps(true)
        val capture = platform.posix.getenv("DXC_TEXT_MENU_CAPTURE")?.toKString()
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSMenuDidBeginTrackingNotification,
            `object` = null,
            queue = null,
        ) { note ->
            menus++
            val menu = note?.`object` as? NSMenu
            if (capture != null && menus == 1) {
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 700_000_000L), dispatch_get_main_queue()) {
                    captureWindow(window, capture)
                    menu?.cancelTracking()
                }
            } else {
                dispatch_async(dispatch_get_main_queue()) { menu?.cancelTracking() }
            }
        }
        turns(300) { window.keyWindow }
        if (capture != null) pointAtMiddle(window)
        return window
    }

    @AfterTest
    fun close() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        windowPlatform?.close()
        windowPlatform = null
    }

    private fun turns(count: Int = 100, until: () -> Boolean = { false }) {
        var turned = 0
        val application = NSApplication.sharedApplication()
        while (turned < count && !until()) {
            // The application's own events, which is how it becomes active and its window key.
            while (true) {
                val event = application.nextEventMatchingMask(
                    NSEventMaskAny, untilDate = NSDate.dateWithTimeIntervalSinceNow(0.0), inMode = NSDefaultRunLoopMode, dequeue = true,
                ) ?: break
                application.sendEvent(event)
            }
            windowPlatform?.window?.host?.turn(0)
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.01))
            turned++
        }
    }

    /** A right press and release on the middle of the field, through the view's own handlers. */
    private fun rightClick(window: NSWindow) {
        val view = window.contentView!!
        val middle = view.bounds.useContents { NSMakePoint(size.width / 2, size.height / 2) }
        val at = view.convertPoint(middle, toView = null)
        fun mouse(type: platform.AppKit.NSEventType) = NSEvent.mouseEventWithType(
            type = type,
            location = at,
            modifierFlags = 0u,
            timestamp = NSProcessInfo.processInfo.systemUptime,
            windowNumber = window.windowNumber,
            context = null,
            eventNumber = 0,
            clickCount = 1,
            pressure = 1f,
        )!!
        val target = windowPlatform!!.window!!.view
        target.rightMouseDown(mouse(NSEventTypeRightMouseDown))
        turns(30)
        target.rightMouseUp(mouse(NSEventTypeRightMouseUp))
        turns(30)
    }

    @Test
    fun fr33_1_a_right_click_in_a_field_puts_up_one_menu_the_system_s() {
        val window = open()
        assertTrue(window.keyWindow, "Compose puts its menu up in the key window")
        assertFalse(windowPlatform!!.nativeEditMenu, "the window adds no menu of its own by default")
        // Compose's old path draws a menu of its own; the new one puts up the system's.
        assertTrue(ComposeFoundationFlags.isNewContextMenuEnabled, "the field's menu is the system's")
        rightClick(window)
        assertEquals(1, menus, "one NSMenu, Compose's, and not the window's beside it")
        assertEquals(TextRange(0, 5), value.selection, "a press inside the selection keeps it")
        // And again: the menu that closed left nothing behind that would add another.
        rightClick(window)
        assertEquals(2, menus)
    }

    @Test
    fun fr33_1_an_application_s_own_menu_is_shown_alone() {
        var asked = 0
        val own = object : TextContextMenuProvider {
            override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
                asked++
            }
        }
        val window = open { content ->
            CompositionLocalProvider(LocalTextContextMenuDropdownProvider provides own) { content() }
        }
        rightClick(window)
        assertEquals(1, asked, "the application's menu was asked for")
        assertEquals(0, menus, "and no system menu came up beside it")
    }

    /** Puts the pointer on the middle of [window], where Compose's menu opens. For the capture only. */
    private fun pointAtMiddle(window: NSWindow) {
        val primaryHeight = (NSScreen.screens.first() as NSScreen).frame.useContents { size.height }
        window.frame.useContents {
            CGWarpMouseCursorPosition(CGPointMake(origin.x + size.width / 2, primaryHeight - (origin.y + size.height / 2)))
        }
    }

    /** Writes the part of the screen [window] covers, as the user sees it, to [path]. */
    private fun captureWindow(window: NSWindow, path: String) {
        val primaryHeight = (NSScreen.screens.first() as NSScreen).frame.useContents { size.height }
        val region = window.frame.useContents {
            "${origin.x.toInt()},${(primaryHeight - origin.y - size.height).toInt()},${size.width.toInt()},${size.height.toInt()}"
        }
        platform.posix.system("/usr/sbin/screencapture -x -R$region '$path'")
    }
}
