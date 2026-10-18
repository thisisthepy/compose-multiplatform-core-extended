@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.closeButtonInset
import org.thisisthepy.compose.window.formatMacosAxNode
import org.thisisthepy.compose.window.macosChromeFindings
import org.thisisthepy.compose.window.window
import org.thisisthepy.compose.window.TitleBarStyle
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowVisibility
import org.thisisthepy.compose.window.macosCornerRadius
import platform.AppKit.NSApplication
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowMiniaturizeButton
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowTitleVisible
import platform.AppKit.NSWindowZoomButton
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin/Native window opened through [MacosWindowPlatform] from a [WindowConfig], as the
 * GraalVM one is: the chrome and title bar style it asks for, the caption it reports and what
 * a reader is told about its content.
 */
class MacosWindowPlatformChromeTest {
    private val macosMajor: Int =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }.toInt()

    private val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }

    private fun turn(until: () -> Boolean) {
        var turns = 0
        while (!until() && turns < 100) {
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05))
            turns++
        }
    }

    private fun <T> opened(config: WindowConfig, content: @androidx.compose.runtime.Composable () -> Unit = {}, check: (MacosWindowPlatform, NSWindow) -> T): T {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform(content)
        assertTrue(platform.open(config, listener))
        val window = assertNotNull(platform.nativeWindow)
        try {
            platform.requestFrame()
            turn { platform.caption() != WindowCaption.None || config.chrome == WindowChrome.System }
            return check(platform, window)
        } finally {
            // Taken off the screen rather than closed: a closed window releases itself.
            platform.setVisibility(WindowVisibility.Hidden)
        }
    }

    @Test
    fun fr19_4_the_modern_window_keeps_the_traffic_lights_runs_content_under_the_bar_and_draws_no_title() =
        opened(WindowConfig("modern", 480, 320)) { platform, window ->
            assertNotNull(window.standardWindowButton(NSWindowCloseButton), "close button")
            assertNotNull(window.standardWindowButton(NSWindowMiniaturizeButton), "minimise button")
            assertNotNull(window.standardWindowButton(NSWindowZoomButton), "zoom button")
            assertTrue(window.styleMask and NSWindowStyleMaskFullSizeContentView != 0uL, "content under the bar")
            assertTrue(window.titlebarAppearsTransparent)
            assertEquals(NSWindowTitleHidden, window.titleVisibility, "no title text")
            assertEquals("modern", window.title, "the title is carried for the switcher")
            val caption = platform.caption()
            assertTrue(caption.height > 0f, "the content steps clear of the bar: $caption")
            assertTrue(caption.buttonsWidth > 0f, "and of the buttons: $caption")
            assertEquals(false, caption.buttonsDrawnByScene, "the buttons are the system's")
        }

    @Test
    fun fr19_7_normal_and_simple_differ_in_inset_and_radius_as_the_table_says() {
        val normal = opened(WindowConfig("normal", 480, 320, titleBar = TitleBarStyle.Normal)) { platform, window ->
            assertNotNull(window.toolbar, "Normal has the unified toolbar")
            platform.caption()
        }
        val simple = opened(WindowConfig("simple", 480, 320, titleBar = TitleBarStyle.Simple)) { platform, window ->
            assertNull(window.toolbar, "Simple has none")
            platform.caption()
        }
        assertEquals(macosCornerRadius(toolbar = true, macosMajor = macosMajor)?.toFloat() ?: 0f, normal.cornerRadius)
        assertEquals(macosCornerRadius(toolbar = false, macosMajor = macosMajor)?.toFloat() ?: 0f, simple.cornerRadius)
        assertTrue(normal.height > simple.height, "the toolbar bar is taller: $normal against $simple")
        if (macosMajor >= 26) {
            assertTrue(normal.buttonsWidth > simple.buttonsWidth, "macOS $macosMajor sets Normal's buttons further in")
            assertTrue(normal.cornerRadius > simple.cornerRadius, "macOS $macosMajor rounds Normal more")
        }
    }

    @Test
    fun fr19_4_the_system_chrome_draws_its_title_and_reports_no_caption() =
        opened(WindowConfig("system", 480, 320, chrome = WindowChrome.System)) { platform, window ->
            assertEquals(NSWindowTitleVisible, window.titleVisibility)
            assertTrue(window.styleMask and NSWindowStyleMaskFullSizeContentView == 0uL)
            assertEquals(WindowCaption.None, platform.caption())
        }

    @Test
    fun fr19_4_fr19_7_what_a_reader_is_told_about_each_title_bar_matches_its_caption() {
        val insets = HashMap<TitleBarStyle, Double?>()
        for (style in TitleBarStyle.entries) {
            val title = "snapshot $style"
            opened(WindowConfig(title, 480, 320, titleBar = style)) { platform, _ ->
                val snapshot = platform.accessibilitySnapshot()
                val findings = macosChromeFindings(snapshot, title, platform.macosTitleBar, platform.caption())
                assertEquals(
                    emptyList(), findings,
                    "$style:\n" + snapshot.window(title).joinToString("\n") { formatMacosAxNode(it) },
                )
                insets[style] = snapshot.closeButtonInset(title)
            }
        }
        if (macosMajor >= 26) {
            assertTrue(
                (insets[TitleBarStyle.Normal] ?: 0.0) > (insets[TitleBarStyle.Simple] ?: 0.0),
                "macOS $macosMajor sets Normal's buttons further in: $insets",
            )
        }
        val title = "snapshot system"
        opened(WindowConfig(title, 480, 320, chrome = WindowChrome.System)) { platform, _ ->
            val snapshot = platform.accessibilitySnapshot()
            assertEquals(emptyList(), macosChromeFindings(snapshot, title, platform.macosTitleBar, platform.caption()))
        }
    }

    @Test
    fun nfr8_a_reader_asking_the_running_window_finds_the_field_the_button_and_their_labels() {
        var pressed = 0
        opened(
            WindowConfig("a11y", 480, 320),
            content = {
                var text by remember { mutableStateOf("Ada") }
                Column {
                    BasicTextField(text, { text = it }, Modifier.semantics { contentDescription = "Name" })
                    BasicText("Send", Modifier.clickable(role = Role.Button) { pressed++ })
                }
            },
        ) { platform, window ->
            val view = assertNotNull(window.contentView)
            // Asked as a reader asks, which is also what turns the description on.
            view.accessibilityChildren()
            turn { platform.accessibilityTree?.all?.size?.let { it >= 2 } == true }
            assertTrue(platform.accessibilityActive, "a reader asked, so the window says one is listening")
            val elements = view.accessibilityChildren().orEmpty().filterIsInstance<AxElement>()
                .flatMap { listOf(it) + it.accessibilityChildren().orEmpty().filterIsInstance<AxElement>() }
            val field = assertNotNull(elements.firstOrNull { it.accessibilityRole() == MacosAx.TEXT_FIELD }, "a text field in $elements")
            assertEquals("Name", field.accessibilityLabel())
            assertEquals("Ada", field.accessibilityValue())
            val button = assertNotNull(elements.firstOrNull { it.accessibilityRole() == MacosAx.BUTTON }, "a button in $elements")
            assertEquals("Send", button.accessibilityLabel())
            assertTrue(button.accessibilityPerformPress(), "the press reached the scene")
            assertEquals(1, pressed)
        }
    }
}
