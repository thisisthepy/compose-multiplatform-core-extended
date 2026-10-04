@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos.test

import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.macos.MacosWindowPlatform
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.Foundation.NSMakeSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens a real window, which needs a window server, and resizes it through a script while
 * presenting frames. A frame drawn at a size other than the window's at present time is the
 * trailing edge this window exists to avoid.
 */
class MacosWindowResizeTest {
    @Test
    fun everyFramePresentedDuringAScriptedResizeIsDrawnAtTheWindowSize() {
        NSApplication.sharedApplication().setActivationPolicy(
            NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory,
        )
        val platform = MacosWindowPlatform {}
        val events = mutableListOf<WindowEvent>()
        val opened = platform.open(
            WindowConfig(title = "resize", width = 400, height = 300),
            object : WindowListener {
                override fun onEvent(event: WindowEvent) {
                    events += event
                }
            },
        )
        assertTrue(opened)
        platform.pump(50)
        val window = platform.nativeWindow ?: error("the window was not created")
        val sizes = listOf(420 to 310, 480 to 340, 560 to 400, 700 to 520, 520 to 380, 450 to 330)
        for ((w, h) in sizes) {
            window.setContentSize(NSMakeSize(w.toDouble(), h.toDouble()))
            platform.pump(20)
            val (drawnW, drawnH) = platform.drawnSize()
            platform.present(drawnW, drawnH)
        }
        assertEquals(sizes.size, platform.presentLog.frames)
        assertEquals(0, platform.presentLog.mismatched)
        platform.close()
    }
}
