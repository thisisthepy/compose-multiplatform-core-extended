@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.scene.LocalWindowCaption
import platform.AppKit.NSApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A window that is never resized has its caption measured all the same (fork issue 181).
 *
 * With the content under the title bar, the first reads can come before the bar is laid
 * out: the content layout rect is then as tall as the frame and the strip reads 0. The
 * window used to read the strip when it opened and when it changed size, so one that kept
 * its size kept the 0, and an application that steps its content clear of the strip saw
 * none for the whole session (compose-rust 468, a 390 by 844 window).
 *
 * The platform now reads it from the window on every turn of the host, with the window laid
 * out first. Checked here on a window the size of that report, never resized: what the
 * platform answers at once, and that a strip lost as in the report is back, in the window's
 * state and in what the content is composed with, after one turn of the host.
 */
class CaptionWithoutResizeTest {
    private val listener = object : WindowListener {
        override fun onEvent(event: WindowEvent) {}
    }

    private fun titleBar(window: platform.AppKit.NSWindow): Float =
        (window.frame.useContents { size.height } - window.contentLayoutRect.useContents { size.height }).toFloat()

    @Test
    fun the_platform_answers_with_the_title_bar_the_first_time_it_is_asked() {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        assertTrue(platform.open(WindowConfig("never resized", 390, 844), listener))
        val window = assertNotNull(platform.nativeWindow)
        try {
            val size = window.frame.useContents { size.width to size.height }
            // Asked straight away: no turn of the run loop, no resize, no key window yet.
            val caption = platform.caption()
            assertTrue(caption.height > 0f, "the strip has a height the first time it is asked for")
            assertEquals(titleBar(window), caption.height, "and it is the title bar's")
            assertEquals(size, window.frame.useContents { this.size.width to this.size.height }, "the window kept its size")
        } finally {
            platform.close()
        }
    }

    @Test
    fun a_strip_that_read_zero_is_measured_again_on_the_hosts_next_turn() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "never resized", width = 390, height = 844)
        var composedWith = -1f
        var composedWithWindows = -1f
        try {
            window.setContent {
                composedWith = LocalWindowCaption.current.height
                composedWithWindows = window.caption.value.height.value
                Box(Modifier.fillMaxSize())
            }
            val size = window.window.frame.useContents { size.width to size.height }
            window.pumpHost(0L)
            val bar = titleBar(window.window)
            assertTrue(bar > 0f, "the window has a title bar over its content")
            assertEquals(bar, composedWith, "the content is composed with the strip the host read")

            // The state the report describes: the strip read before the title bar was there.
            window.caption.value = WindowCaption.None
            window.pumpHost(0L)
            assertEquals(bar, window.caption.value.height.value, "one turn of the host measures the strip again")
            window.pumpHost(0L)
            assertEquals(bar, composedWithWindows, "and the content is composed with it")
            assertEquals(size, window.window.frame.useContents { this.size.width to this.size.height }, "the window kept its size")
        } finally {
            // Taken off the screen rather than closed: a closed window releases itself, and the
            // reference held here would then be released a second time.
            window.window.orderOut(null)
        }
    }
}
