@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.ui.unit.dp
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowToolbarStyle
import platform.AppKit.NSWindowZoomButton
import platform.Foundation.NSMakeRect
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import org.thisisthepy.compose.window.macosCornerRadius
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin/Native window builds its title bar from a [MacosWindowChrome] and measures its
 * caption with [macosWindowCaption].
 *
 * It used to have no toolbar and clip its corners by hand, so on macOS 26 it came up
 * rounder than the GraalVM window and with its content starting higher.
 */
class MacosWindowChromeTest {

    private fun window(chrome: MacosWindowChrome): NSWindow {
        val window = NSWindow(
            contentRect = NSMakeRect(240.0, 360.0, 480.0, 640.0),
            styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskMiniaturizable or
                NSWindowStyleMaskClosable or NSWindowStyleMaskResizable or
                (if (chrome.fullSizeContentView) NSWindowStyleMaskFullSizeContentView else 0uL),
            backing = NSBackingStoreBuffered,
            defer = true,
        )
        applyChrome(window, chrome)
        return window
    }

    private fun caption(window: NSWindow, chrome: MacosWindowChrome) = macosWindowCaption(
        chrome = chrome,
        windowHeight = window.frame.useContents { size.height },
        contentLayoutHeight = window.contentLayoutRect.useContents { size.height },
        closeMinX = window.standardWindowButton(NSWindowCloseButton)?.frame?.useContents { origin.x },
        zoomMaxX = window.standardWindowButton(NSWindowZoomButton)?.frame?.useContents {
            origin.x + size.width
        },
        cornerRadius = systemCornerRadius(chrome),
    )

    private val macosMajor: Long =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }

    @Test
    fun fr19_the_ordinary_window_has_the_unified_toolbar_a_modern_window_needs() {
        val chrome = MacosWindowChrome.Modern
        val window = window(chrome)
        val toolbar = assertNotNull(window.toolbar, "the window is built with a toolbar")
        assertEquals(false, toolbar.showsBaselineSeparator)
        assertEquals(NSWindowToolbarStyle.NSWindowToolbarStyleUnified, window.toolbarStyle)
        assertTrue(window.titlebarAppearsTransparent)
        assertEquals(NSWindowTitleHidden, window.titleVisibility)
        assertTrue(window.styleMask and NSWindowStyleMaskFullSizeContentView != 0uL)
    }

    @Test
    fun fr19_7_the_simple_window_has_no_toolbar() {
        val window = window(MacosWindowChrome.Simple)
        assertNull(window.toolbar)
    }

    @Test
    fun fr19_2_the_toolbar_window_reserves_the_taller_strip_for_its_content() {
        val normal = MacosWindowChrome.Modern
        val simple = MacosWindowChrome.Simple
        val tall = assertNotNull(caption(window(normal), normal))
        val plain = assertNotNull(caption(window(simple), simple))
        assertTrue(plain.height.value > 0f, "the content steps clear of the plain bar too")
        assertTrue(
            tall.height > plain.height,
            "a unified toolbar makes the bar taller (${tall.height} against ${plain.height})",
        )
        assertTrue(tall.buttonsWidth.value > 0f)
    }

    @Test
    fun fr19_7_both_styles_take_their_corner_radius_from_the_table() {
        val normal = MacosWindowChrome.Modern
        val simple = MacosWindowChrome.Simple
        val toolbar = assertNotNull(caption(window(normal), normal))
        val plain = assertNotNull(caption(window(simple), simple))
        assertTrue(plain.cornerRadius.value > 0f, "the table has no radius for the plain window on macOS $macosMajor")
        assertTrue(
            toolbar.cornerRadius >= plain.cornerRadius,
            "a toolbar window is less round (${toolbar.cornerRadius}) than a plain one (${plain.cornerRadius})",
        )
        if (macosMajor >= 26) {
            assertTrue(
                toolbar.cornerRadius > plain.cornerRadius,
                "macOS $macosMajor rounds a toolbar window more than a plain one",
            )
        }
    }

    @Test
    fun fr19_7_the_toolbar_style_sets_the_buttons_in_from_the_corner() {
        val normal = MacosWindowChrome.Modern
        val simple = MacosWindowChrome.Simple
        val toolbar = assertNotNull(caption(window(normal), normal))
        val plain = assertNotNull(caption(window(simple), simple))
        assertTrue(plain.buttonsWidth.value > 0f)
        if (macosMajor >= 26) {
            assertTrue(
                toolbar.buttonsWidth > plain.buttonsWidth,
                "macOS $macosMajor sets a toolbar window's buttons further in",
            )
        }
    }

    @Test
    fun fr19_7_the_radius_is_the_tables_for_the_style_and_release() {
        val major = macosMajor.toInt()
        assertEquals(macosCornerRadius(toolbar = true, macosMajor = major), systemCornerRadius(MacosWindowChrome.Modern))
        assertEquals(macosCornerRadius(toolbar = false, macosMajor = major), systemCornerRadius(MacosWindowChrome.Simple))
    }

    @Test
    fun fr19_7_the_corner_radius_is_the_one_the_window_reported() {
        for (chrome in listOf(MacosWindowChrome.Modern, MacosWindowChrome.Simple)) {
            val caption = macosWindowCaption(chrome, 700.0, 648.0, 20.0, 88.0, cornerRadius = 26.0)
            assertEquals(26.dp, caption?.cornerRadius, "at $chrome")
        }
    }

    @Test
    fun fr19_7_a_window_that_reports_no_radius_has_none() {
        val chrome = MacosWindowChrome.Simple
        assertEquals(0.dp, macosWindowCaption(chrome, 700.0, 672.0, 7.0, 75.0, cornerRadius = null)?.cornerRadius)
        assertEquals(0.dp, macosWindowCaption(chrome, 700.0, 672.0, 7.0, 75.0, cornerRadius = -1.0)?.cornerRadius)
    }

    @Test
    fun fr19_7_the_content_top_and_inset_follow_the_style_the_window_reported() {
        val toolbar = macosWindowCaption(MacosWindowChrome.Modern, 700.0, 648.0, 20.0, 88.0, 26.0)
        val plain = macosWindowCaption(MacosWindowChrome.Simple, 700.0, 672.0, 7.0, 75.0, 16.0)
        assertEquals(WindowCaption(52.dp, 108.dp, true, cornerRadius = 26.dp), toolbar)
        assertEquals(WindowCaption(28.dp, 82.dp, true, cornerRadius = 16.dp), plain)
    }
}
