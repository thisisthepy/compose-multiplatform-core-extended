@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package org.thisisthepy.compose.window.linux

import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEdge
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.X11Chrome
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.system
import platform.posix.unsetenv

/**
 * The window's chrome, driven the way a person drives it, through openbox
 * (scripts/wm-chrome.sh, the same script the GraalVM window's test runs).
 *
 * The listener does what a scene does with a press: within [EDGE] pixels of an edge it calls
 * startResize for that edge, elsewhere in the caption it calls startMove. The script then
 * checks the frame, the caption drag, every edge and corner, and the double click, and each
 * frame presented on the way is judged against the size the server has at that moment.
 *
 * Needs a display and WM_CHROME_SCRIPT naming scripts/wm-chrome.sh; CI sets both, and a CI
 * run without them fails rather than skipping.
 */
class ChromeTest {
    private class Run(val command: String) {
        val done = AtomicInt(0)
        val status = AtomicInt(-1)
    }

    private fun script(): String? {
        val script = getenv("WM_CHROME_SCRIPT")?.toKString()
        val display = getenv("DISPLAY")?.toKString()
        if (script.isNullOrEmpty() || display.isNullOrEmpty()) {
            assertTrue(getenv("CI") == null, "no WM_CHROME_SCRIPT or DISPLAY under CI, so the chrome was not tested")
            return null
        }
        return script
    }

    /** The edge a press at [x], [y] is on, in a window [width] by [height], or null for none. */
    private fun edgeAt(x: Float, y: Float, width: Int, height: Int): WindowEdge? {
        val left = x < EDGE
        val right = x >= width - EDGE
        val top = y < EDGE
        val bottom = y >= height - EDGE
        return when {
            top && left -> WindowEdge.TopLeft
            top && right -> WindowEdge.TopRight
            bottom && left -> WindowEdge.BottomLeft
            bottom && right -> WindowEdge.BottomRight
            left -> WindowEdge.Left
            right -> WindowEdge.Right
            top -> WindowEdge.Top
            bottom -> WindowEdge.Bottom
            else -> null
        }
    }

    private fun drive(config: WindowConfig, mode: String): X11Window {
        val script = script() ?: return X11Window()
        setenv("DXC_REPORT_RESIZE", "1", 1)
        val window = X11Window()
        unsetenv("DXC_REPORT_RESIZE")
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {
                when (event.kind) {
                    WindowEvent.RESIZE -> window.present(event.x.toInt(), event.y.toInt())
                    WindowEvent.POINTER_DOWN -> if (event.buttons and 1 != 0) {
                        val size = window.measure()
                        val edge = edgeAt(event.x, event.y, size.width, size.height)
                        when {
                            edge != null -> window.startResize(edge)
                            event.y < window.caption().height * size.scale -> window.startMove()
                        }
                    }
                }
            }
        }
        assertTrue(window.open(config, listener), "no window on a display that is there")
        val worker = Worker.start()
        try {
            repeat(30) { window.pump(10) }
            window.resizeStats.reset()
            val caption = window.caption().height.toInt()
            val run = Run("'$script' '${config.title}' $mode $caption")
            worker.execute(TransferMode.SAFE, { run }) { state ->
                state.status.store(system(state.command))
                state.done.store(1)
            }
            val started = TimeSource.Monotonic.markNow()
            while (run.done.load() == 0 && started.elapsedNow().inWholeSeconds < 180) window.pump(16)
            repeat(30) { window.pump(16) }
            println("kotlin/native x11 chrome ($mode): ${window.resizeStats.report()}")
            assertEquals(0, run.status.load(), "wm-chrome.sh found the $mode chrome wrong; its output says what")
        } finally {
            worker.requestTermination().result
        }
        return window
    }

    @Test
    fun fr19_4_fr19_7_linux_a_modern_window_moves_resizes_and_maximises_from_its_own_caption() {
        val window = drive(WindowConfig("chrome modern", 400, 300), "modern")
        try {
            if (getenv("WM_CHROME_SCRIPT") == null) return
            val stats = window.resizeStats
            assertTrue(stats.steps >= 8, "the edges took the window through fewer than eight sizes: ${stats.report()}")
            assertEquals(0L, stats.stale, "a frame was presented at a size the server had already left: ${stats.report()}")
            assertEquals(0L, stats.stretched, "a size the server confirmed was left without a frame: ${stats.report()}")
        } finally {
            window.close()
        }
    }

    @Test
    fun fr19_4_linux_a_system_window_keeps_the_window_managers_frame_and_has_no_caption() {
        val window = drive(WindowConfig("chrome system", 400, 300, chrome = WindowChrome.System), "system")
        try {
            assertEquals(WindowCaption.None, window.caption())
        } finally {
            window.close()
        }
    }

    @Test
    fun fr19_4_linux_a_modern_window_reports_the_caption_the_scene_draws() {
        val window = X11Window()
        // Answered from the config before the window is on a display, so no display is needed.
        assertEquals(WindowCaption.None, window.caption(), "a window that is not open has no caption")
        assertEquals(X11Chrome.caption(WindowConfig("w", 1, 1)).height, X11Chrome.NORMAL_CAPTION_HEIGHT)
    }

    private companion object {
        const val EDGE = 8
    }
}
