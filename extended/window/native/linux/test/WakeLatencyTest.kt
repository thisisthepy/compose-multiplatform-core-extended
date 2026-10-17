@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package org.thisisthepy.compose.window.linux

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.getenv
import platform.posix.timespec
import platform.posix.usleep

/**
 * How long work posted from another thread waits before the window's thread runs it.
 *
 * The window's thread sits in [X11Window.pump] with a long timeout, which is the loop with
 * nothing happening. Another thread waits until it is in the wait, pauses so it is surely
 * blocked there, notes the monotonic clock and calls [X11Window.wake]. The window's thread
 * notes the clock when the pump returns, which is where the loop runs what was posted. Before
 * the wake, that was up to a frame's wait (16 ms) later.
 *
 * 200 posts. Prints the median, the 99th percentile and the worst, and fails when the 99th
 * percentile is 1 ms or more. The worst is reported rather than asserted: a shared CI machine
 * can deschedule either thread for longer than that, which is a fact about the machine and not
 * about the wake. Skipped where there is no display.
 */
class WakeLatencyTest {
    private class Shared(val window: X11Window) {
        val waiting = AtomicInt(0)
        val postedAt = AtomicLong(0L)
        val finished = AtomicInt(0)
    }

    @Test
    fun pr3_work_posted_from_another_thread_runs_within_a_millisecond() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val window = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        assertTrue(window.open(WindowConfig("wake latency", 320, 240), listener), "no window on a display that is there")
        repeat(30) { window.pump(10) }
        val shared = Shared(window)
        val worker = Worker.start()
        val samples = LongArray(POSTS)
        try {
            worker.execute(TransferMode.SAFE, { shared }) { state ->
                repeat(POSTS) {
                    while (state.waiting.load() == 0) {
                        if (state.finished.load() != 0) return@execute
                        usleep(50u)
                    }
                    // Long enough that the window's thread is blocked in its wait.
                    usleep(3_000u)
                    state.waiting.store(0)
                    state.postedAt.store(monotonicNanos())
                    state.window.wake()
                }
            }
            var taken = 0
            while (taken < POSTS) {
                shared.postedAt.store(0L)
                shared.waiting.store(1)
                window.pump(5_000)
                val ran = monotonicNanos()
                val posted = shared.postedAt.load()
                if (posted == 0L) continue
                samples[taken++] = ran - posted
            }
        } finally {
            shared.finished.store(1)
            worker.requestTermination().result
            window.close()
        }
        samples.sort()
        val median = samples[POSTS / 2] / 1e6
        val p99 = samples[POSTS * 99 / 100 - 1] / 1e6
        val worst = samples[POSTS - 1] / 1e6
        println(
            "kotlin/native x11 wake latency over $POSTS posts: median=${millis(median)} ms " +
                "p99=${millis(p99)} ms max=${millis(worst)} ms",
        )
        assertTrue(p99 < 1.0, "the 99th percentile is ${millis(p99)} ms; work posted from another thread waits too long")
    }

    private fun millis(value: Double): String {
        val thousandths = (value * 1000).toLong()
        return "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
    }

    private companion object {
        const val POSTS = 200

        fun monotonicNanos(): Long = memScoped {
            val now = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC, now.ptr)
            now.tv_sec * 1_000_000_000L + now.tv_nsec
        }
    }
}
