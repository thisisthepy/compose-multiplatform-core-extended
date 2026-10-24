@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.scene.ComposeWindowHost
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * A Compose scene hosted by `ComposeWindowHost` in the Kotlin/Native X11 window, drawn through
 * [x11WindowSurface]: frames are presented, every one at the size the window had when it was
 * presented, and a resize gets its frame from inside its own handling, so no size the window
 * took is left without one.
 *
 * Needs a display; CI runs it under Xvfb, and a CI run without one fails rather than skipping.
 */
class X11WindowSurfaceTest {
    @Test
    fun fr19_7_linux_a_scene_on_the_kotlin_native_window_presents_every_size_at_that_size() {
        if (getenv("DISPLAY") == null) {
            assertTrue(getenv("CI") == null, "no DISPLAY under CI, so the scene's surface was not tested")
            return
        }
        // Read when the window is made: each present asks the server for the window's size.
        setenv("DXC_REPORT_RESIZE", "1", 1)
        val window = X11Window()
        unsetenv("DXC_REPORT_RESIZE")
        val host = ComposeWindowHost(
            platform = window,
            config = WindowConfig("scene surface", 320, 240),
            surfaceFor = ::x11WindowSurface,
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF12321A))) {
                BasicText("안녕하세요, Kotlin/Native")
            }
        }
        assertTrue(host.open(), "no window on a display that is there")
        try {
            repeat(30) {
                host.requestFrame()
                host.turn(16)
            }
            assertTrue(host.presentLog.frames > 0, "the scene presented no frames")
            window.resizeStats.reset()
            window.scriptedResize(420, 320, 20, 8)
            repeat(10) { host.turn(16) }
        } finally {
            host.close()
        }
        val stats = window.resizeStats
        println("kotlin/native x11 scene surface: ${host.presentLog.frames} frames, ${stats.report()}")
        assertEquals(0, host.presentLog.mismatched, "a frame was presented at a size other than the window's")
        assertTrue(stats.steps >= 10, "the scripted resize took the window through fewer than ten sizes: ${stats.report()}")
        assertEquals(0L, stats.stale, "a frame was presented at a size the window had already left: ${stats.report()}")
        assertEquals(0L, stats.stretched, "a size the window took was left without a frame: ${stats.report()}")
    }
}
