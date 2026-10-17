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
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.system
import platform.posix.unsetenv

/**
 * A resize the way a person does it, through a window manager that holds each size until the
 * client has drawn it: openbox, which waits on the _NET_WM_SYNC_REQUEST counter during an
 * interactive resize, drags the window's corner (scripts/wm-drag.sh, the same script the
 * GraalVM window's test runs), and every present is judged against the size the server has
 * at that moment.
 *
 * No frame may be presented at a size the server has already left, and no size it confirmed
 * may be left without a frame. Needs a display and WM_DRAG_SCRIPT naming scripts/wm-drag.sh;
 * CI sets both, and a CI run without them fails rather than skipping.
 */
class WmDragTest {
    private class Drag(val script: String) {
        val done = AtomicInt(0)
        val status = AtomicInt(-1)
    }

    @Test
    fun parity_a_window_manager_drag_presents_every_size_and_only_the_current_one() {
        val script = getenv("WM_DRAG_SCRIPT")?.toKString()
        val display = getenv("DISPLAY")?.toKString()
        if (script.isNullOrEmpty() || display.isNullOrEmpty()) {
            assertTrue(getenv("CI") == null, "no WM_DRAG_SCRIPT or DISPLAY under CI, so the drag was not tested")
            return
        }
        // Read when the window is made: each present asks the server for the window's size.
        setenv("DXC_REPORT_RESIZE", "1", 1)
        val window = X11Window()
        unsetenv("DXC_REPORT_RESIZE")
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {
                // The frame for the size, inside its confirmation; present() sets the counter.
                if (event.kind == WindowEvent.RESIZE) window.present(event.x.toInt(), event.y.toInt())
            }
        }
        assertTrue(window.open(WindowConfig("wm drag", 320, 240), listener), "no window on a display that is there")
        val worker = Worker.start()
        try {
            repeat(30) { window.pump(10) }
            window.resizeStats.reset()
            val drag = Drag(script)
            worker.execute(TransferMode.SAFE, { drag }) { state ->
                state.status.store(system("'${state.script}' 'wm drag'"))
                state.done.store(1)
            }
            val started = TimeSource.Monotonic.markNow()
            while (drag.done.load() == 0 && started.elapsedNow().inWholeSeconds < 60) window.pump(16)
            repeat(30) { window.pump(16) }
            val stats = window.resizeStats
            println("kotlin/native x11 resize through openbox: ${stats.report()}")
            assertEquals(0, drag.status.load(), "the drag script failed")
            assertTrue(stats.steps >= 10, "the window manager took the window through fewer than ten sizes: ${stats.report()}")
            assertEquals(0L, stats.stale, "a frame was presented at a size the server had already left: ${stats.report()}")
            assertEquals(0L, stats.stretched, "a size the server confirmed was left without a frame: ${stats.report()}")
        } finally {
            worker.requestTermination().result
            window.close()
        }
    }
}
