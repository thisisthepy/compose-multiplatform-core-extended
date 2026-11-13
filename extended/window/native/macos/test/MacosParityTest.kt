@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.scene.LocalSystemTheme
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.AppKit.NSWindow
import platform.AppKit.setAppearance
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSDistributedNotificationCenter
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The four places the Kotlin/Native macOS window trailed the GraalVM one
 * (DarkPyonix/compose-rust#146), each on a real window: the Window node's minimum size, the
 * Dock icon, dark mode followed while the window is open, and the clipboard a field copies to.
 */
class MacosParityTest {
    private var opened: MacosWindowPlatform? = null
    // The window's host takes the place of this listener when it opens the content.
    private val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }

    private fun open(config: WindowConfig, content: @Composable () -> Unit = {}): NSWindow {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform(content)
        opened = platform
        assertTrue(platform.open(config, listener))
        turns(10)
        return assertNotNull(platform.nativeWindow)
    }

    @AfterTest
    fun close() {
        NSApplication.sharedApplication().setAppearance(null)
        opened?.close()
        opened = null
    }

    private fun turns(count: Int, until: () -> Boolean = { false }) {
        var turned = 0
        while (turned < count && !until()) {
            opened?.window?.host?.turn(0)
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02))
            turned++
        }
    }

    @Test
    fun fr19_the_window_node_s_minimum_size_is_the_content_s_minimum() {
        val window = open(WindowConfig("minimum", 480, 320, minWidth = 240, minHeight = 150, chrome = WindowChrome.System))
        window.contentMinSize.useContents {
            assertEquals(240.0, width, "min width")
            assertEquals(150.0, height, "min height")
        }
        // Changed while open, as a Window node's new minimum is.
        opened!!.setMinimumSize(300, 200)
        window.contentMinSize.useContents {
            assertEquals(300.0, width)
            assertEquals(200.0, height)
        }
    }

    @Test
    fun fr19_a_window_made_directly_keeps_its_minimum_size() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "minimum", width = 480, height = 320, minimumSize = 260.0 to 170.0)
        try {
            window.initWindow()
            window.window.contentMinSize.useContents {
                assertEquals(260.0, width)
                assertEquals(170.0, height)
            }
        } finally {
            window.window.orderOut(null)
        }
    }

    @Test
    fun fr19_3_the_dock_icon_is_the_picture_given() {
        val application = NSApplication.sharedApplication()
        val before = application.applicationIconImage
        try {
            val side = 16
            val rgba = ByteArray(side * side * 4) { if (it % 4 == 3) 0xFF.toByte() else 0x40 }
            assertTrue(setApplicationIcon(rgba, side, side))
            val icon = assertNotNull(application.applicationIconImage)
            icon.size.useContents {
                assertEquals(side.toDouble(), width)
                assertEquals(side.toDouble(), height)
            }
            // A buffer that is not the size it says is refused and leaves the icon as it was.
            assertFalse(setApplicationIcon(ByteArray(10), side, side))
            assertEquals(icon, application.applicationIconImage)
        } finally {
            before?.let { application.applicationIconImage = it }
        }
    }

    @Test
    fun fr19_dark_mode_is_followed_while_the_window_is_open() {
        var seen: SystemTheme? = null
        open(WindowConfig("appearance", 320, 200, chrome = WindowChrome.System)) {
            seen = LocalSystemTheme.current
        }
        val application = NSApplication.sharedApplication()
        // The application's appearance stands in for System Settings, and the notification
        // is the one the system posts when the setting changes.
        application.setAppearance(NSAppearance.appearanceNamed(NSAppearanceNameDarkAqua))
        NSDistributedNotificationCenter.defaultCenter().postNotificationName(
            "AppleInterfaceThemeChangedNotification", `object` = null, userInfo = null, deliverImmediately = true,
        )
        turns(200) { seen == SystemTheme.Dark }
        assertEquals(SystemTheme.Dark, seen, "the content was composed again with the new appearance")

        application.setAppearance(null)
        NSDistributedNotificationCenter.defaultCenter().postNotificationName(
            "AppleInterfaceThemeChangedNotification", `object` = null, userInfo = null, deliverImmediately = true,
        )
        val system = if (systemIsDark()) SystemTheme.Dark else SystemTheme.Light
        turns(200) { seen == system }
        assertEquals(system, seen, "back to the system's own")
    }

    @Test
    fun fr33_6_a_field_copies_to_the_pasteboard_through_the_window_s_own_clipboard() {
        var clipboard: Clipboard? = null
        open(WindowConfig("clipboard", 320, 200, chrome = WindowChrome.System)) {
            clipboard = LocalClipboard.current
        }
        assertTrue(clipboard is MacosClipboard, "the content's clipboard is the window's, got $clipboard")
    }
}
