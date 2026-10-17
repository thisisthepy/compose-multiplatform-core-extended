@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.getenv
import platform.posix.usleep

/**
 * The frame's wait in [X11Window.pump], ended early by [X11Window.wake] from another thread.
 *
 * Work sent to the window's thread from another one (a Host worker asking for a frame, a
 * coroutine resumed on the main dispatcher) is noticed when the loop turns. Without a wake the
 * loop turns when the wait runs out, up to a frame later; with one it turns at once.
 *
 * Skipped where there is no display: the wait is on the display connection, and a window that
 * did not open has no wait to end. CI runs these under Xvfb.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)
class WakeTest {
    private val noDisplay: Boolean get() = getenv("DISPLAY")?.toKString().isNullOrEmpty()

    private fun openWindow(): X11Window {
        val window = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        assertTrue(window.open(WindowConfig("wake", 320, 240), listener), "no window on a display that is there")
        // Mapping, exposure and focus, all read and answered, so the waits below are idle ones.
        repeat(30) { window.pump(10) }
        return window
    }

    private fun millisOf(block: () -> Unit): Long {
        val started = TimeSource.Monotonic.markNow()
        block()
        return started.elapsedNow().inWholeMilliseconds
    }

    @Test
    fun wake_from_another_thread_ends_the_wait() {
        if (noDisplay) return
        val window = openWindow()
        try {
            assertTrue(millisOf { window.pump(300) } >= 250, "an idle pump returned before its timeout")
            val worker = Worker.start()
            try {
                val waited = millisOf {
                    worker.execute(TransferMode.SAFE, { window }) { target ->
                        usleep(100_000u)
                        target.wake()
                    }
                    window.pump(5_000)
                }
                assertTrue(waited < 1_000, "a wake from another thread did not end a five second wait ($waited ms)")
            } finally {
                worker.requestTermination().result
            }
        } finally {
            window.close()
        }
    }

    @Test
    fun a_wake_sent_before_the_wait_ends_it_once() {
        if (noDisplay) return
        val window = openWindow()
        try {
            window.wake()
            window.wake()
            assertTrue(millisOf { window.pump(5_000) } < 1_000, "a wake sent before the wait did not end it")
            assertTrue(millisOf { window.pump(300) } >= 250, "a wake was answered by two turns")
        } finally {
            window.close()
        }
    }

    @Test
    fun wake_is_harmless_on_a_window_that_never_opened_or_has_closed() {
        val never = X11Window()
        never.wake()
        never.pump(0)
        if (noDisplay) return
        val window = openWindow()
        window.close()
        window.wake()
        assertNotNull(window)
    }
}
