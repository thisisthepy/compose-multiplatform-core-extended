@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos.test

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.macos.MacosWindowDelegate
import org.thisisthepy.compose.window.macos.MacosWindowPlatform
import org.thisisthepy.compose.window.macos.macosWindowStyleMask
import org.thisisthepy.compose.window.macos.runMacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosWindowPlatformTest {
    @Test
    fun styleMaskReflectsResizableDecoratedAndFullSizeFlags() {
        val defaultMask = macosWindowStyleMask(resizable = true, decorated = true, fullSizeContentView = true)
        assertTrue(defaultMask and NSWindowStyleMaskTitled != 0uL)
        assertTrue(defaultMask and NSWindowStyleMaskResizable != 0uL)
        assertTrue(defaultMask and NSWindowStyleMaskFullSizeContentView != 0uL)

        val fixedMask = macosWindowStyleMask(resizable = false, decorated = true, fullSizeContentView = false)
        assertTrue(fixedMask and NSWindowStyleMaskTitled != 0uL)
        assertEquals(0uL, fixedMask and NSWindowStyleMaskResizable)
        assertEquals(0uL, fixedMask and NSWindowStyleMaskFullSizeContentView)

        val borderlessMask = macosWindowStyleMask(resizable = false, decorated = false, fullSizeContentView = true)
        assertEquals(NSWindowStyleMaskBorderless, borderlessMask)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun openAppliesResizableAndTransparentConfigFlags() {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        try {
            assertTrue(
                platform.open(
                    WindowConfig(
                        title = "fixed-opaque",
                        width = 320,
                        height = 200,
                        resizable = false,
                        transparent = false,
                    ),
                    listener,
                ),
            )
            val nsWindow = assertNotNull(platform.nativeWindow)
            assertEquals(0uL, nsWindow.styleMask and NSWindowStyleMaskResizable)
            assertTrue(nsWindow.opaque)
        } finally {
            platform.close()
        }

        val transparentPlatform = MacosWindowPlatform()
        try {
            assertTrue(
                transparentPlatform.open(
                    WindowConfig(
                        title = "transparent",
                        width = 320,
                        height = 200,
                        resizable = true,
                        transparent = true,
                    ),
                    listener,
                ),
            )
            val nsWindow = assertNotNull(transparentPlatform.nativeWindow)
            assertTrue(nsWindow.styleMask and NSWindowStyleMaskResizable != 0uL)
            assertFalse(nsWindow.opaque)
        } finally {
            transparentPlatform.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun closeDelegateConsultsOnCloseRequestedAndSetImeSpotUpdatesWindow() {
        NSApplication.sharedApplication()
        val platform = MacosWindowPlatform()
        var allowClose = false
        var closeRequests = 0
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
            override fun onCloseRequested(): Boolean {
                closeRequests++
                return allowClose
            }
        }
        try {
            assertTrue(platform.open(WindowConfig(title = "close-veto", width = 300, height = 200), listener))
            val win = assertNotNull(platform.window)
            val nsWindow = win.window
            val delegate = assertNotNull(nsWindow.delegate as? MacosWindowDelegate)

            assertFalse(delegate.windowShouldClose(nsWindow))
            assertEquals(1, closeRequests)

            allowClose = true
            assertTrue(delegate.windowShouldClose(nsWindow))
            assertEquals(2, closeRequests)

            platform.setImeSpot(42, 84)
            assertEquals(42 to 84, platform.imeSpot)
            assertEquals(42 to 84, win.imeSpot)
        } finally {
            platform.close()
        }
    }

    @Test
    fun presentBeforeOpenRecordsTheWindowSizeReadAtThatTime() {
        val platform = MacosWindowPlatform {}
        val record = platform.present(0, 0)
        assertEquals(0, record.windowWidth)
        assertEquals(0, platform.presentLog.mismatched)
        assertEquals(1, platform.presentLog.frames)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun runMacosWindowIdleSoakWhenRequested() {
        val seconds = platform.posix.getenv("DXC_WINDOW_IDLE_SOAK_SECONDS")?.toKString()?.toIntOrNull() ?: 0
        if (seconds <= 0) return
        val ran = runMacosWindow(WindowConfig(title = "idle-soak-kn", width = 520, height = 360)) {
            var value by remember { mutableStateOf("hello") }
            Column(modifier = Modifier.padding(16.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.size(320.dp, 64.dp),
                )
                BasicText(text = "Typed: $value")
            }
        }
        assertTrue(ran)
    }
}
