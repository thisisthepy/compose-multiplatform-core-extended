@file:OptIn(
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlin.ExperimentalStdlibApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AppKit.NSApplication
import platform.AppKit.NSEvent
import platform.AppKit.NSEventType
import platform.AppKit.NSWindow
import platform.AppKit.postEvent
import platform.Foundation.NSProcessInfo
import kotlin.native.runtime.GC

/**
 * A long drag of the window's edge, asked for with DXC_RESIZE_SOAK=<rounds>, for telling
 * memory that levels off from memory that keeps growing.
 *
 * Each round is one drag made of mouse events, as a hand makes it: the right edge is
 * pressed, moved 450 points out and back in 100 events at 60 a second, and let go. AppKit
 * runs its own live resize for that, so every event is a frame drawn at a new size from
 * inside the resize. The process's memory is read with the button still held at the end of
 * each round ("held"), and two seconds after it is let go ("settled"), once the run loop has
 * turned and the window server has given back the surfaces of the sizes it left.
 *
 * Five seconds of rest come first and are read twice ("idle"), so the rounds are measured
 * against a window that has finished starting. After the last round the collector runs once
 * and the reading is taken again ("collected"): what that gives back was garbage waiting for
 * a collection, and what it does not is held.
 *
 * DXC_SKIA_CACHE_MB sets Skia's resource cache limit before the first round, so a run with
 * a small cache says how much of what the rounds hold is that cache.
 *
 * Every reading is one line on standard error, "compose-rust: soak phase ...", which
 * .github/scripts/resize-soak-check.sh in compose-rust reads. With DXC_METRICS_BREAKDOWN=1
 * each settled reading also prints the system's own account of the footprint by kind.
 */
internal object ResizeSoak {
    val rounds: Int = platform.posix.getenv("DXC_RESIZE_SOAK")?.toKString()?.toIntOrNull() ?: 0
    val enabled: Boolean get() = rounds > 0

    /**
     * Events in a round: 100, or DXC_RESIZE_SOAK_EVENTS, which tells memory kept per drag from
     * memory kept per size by changing how many sizes a drag passes through.
     */
    private val EVENTS_PER_ROUND: Int =
        (platform.posix.getenv("DXC_RESIZE_SOAK_EVENTS")?.toKString()?.toIntOrNull() ?: 100)
            .coerceAtLeast(2).let { it - it % 2 }
    private const val DISTANCE = 450.0

    fun start(window: NSWindow, metal: MetalSurface) {
        platform.posix.getenv("DXC_SKIA_CACHE_MB")?.toKString()?.toLongOrNull()?.let {
            metal.cacheLimitBytes = it * 1048576
        }
        after(5_000) {
            read("idle", 0, 0, window, metal)
            after(2_000) {
                read("idle", 0, 0, window, metal, breakdown = true)
                round(1, window, metal)
            }
        }
    }

    private fun round(number: Int, window: NSWindow, metal: MetalSurface) {
        val startWidth = window.frame.useContents { size.width }
        val y = window.frame.useContents { size.height } / 2
        // Two points inside the edge, which AppKit counts as the edge.
        val x0 = startWidth - 2
        var sent = 0
        var widest = startWidth
        fun post(type: NSEventType, x: Double) {
            val event = NSEvent.mouseEventWithType(
                type = type,
                location = platform.Foundation.NSMakePoint(x, y),
                modifierFlags = 0u,
                timestamp = NSProcessInfo.processInfo.systemUptime,
                windowNumber = window.windowNumber,
                context = null,
                eventNumber = 0,
                clickCount = 1,
                pressure = 1f,
            ) ?: return
            NSApplication.sharedApplication().postEvent(event, atStart = false)
        }
        post(platform.AppKit.NSEventTypeLeftMouseDown, x0)
        val timer = platform.Foundation.NSTimer.timerWithTimeInterval(1.0 / 60, repeats = true) { timer ->
            if (sent < EVENTS_PER_ROUND) {
                sent++
                // Out for the first half, back for the second: a triangle, so every round
                // ends where it started and the window's own size is not what grows.
                val half = EVENTS_PER_ROUND / 2
                val along = if (sent <= half) sent.toDouble() / half else (EVENTS_PER_ROUND - sent).toDouble() / half
                post(platform.AppKit.NSEventTypeLeftMouseDragged, x0 + DISTANCE * along)
                widest = maxOf(widest, window.frame.useContents { size.width })
            } else {
                timer?.invalidate()
                val resizes = number * EVENTS_PER_ROUND
                read("held", number, resizes, window, metal, extra = "widened_by=${(widest - startWidth).toInt()}")
                post(platform.AppKit.NSEventTypeLeftMouseUp, x0)
                after(2_000) {
                    read("settled", number, resizes, window, metal, breakdown = true)
                    if (number < rounds) {
                        round(number + 1, window, metal)
                    } else {
                        GC.collect()
                        after(2_000) {
                            read("collected", number, resizes, window, metal, breakdown = true)
                            printError("compose-rust: soak done rounds=$rounds")
                        }
                    }
                }
            }
        }
        platform.Foundation.NSRunLoop.currentRunLoop.addTimer(timer, forMode = platform.Foundation.NSRunLoopCommonModes)
    }

    private fun read(
        phase: String,
        round: Int,
        resizes: Int,
        window: NSWindow,
        metal: MetalSurface,
        extra: String = "",
        breakdown: Boolean = false,
    ) {
        val (footprint, resident) = processMemory()
        val info = GC.lastGCInfo
        val live = info?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
        val width = window.frame.useContents { size.width }.toInt()
        printError(
            "compose-rust: soak phase $phase round=$round resizes=$resizes width=$width " +
                "footprint_mb=${mb(footprint)} resident_mb=${mb(resident)} " +
                "metal_allocated_mb=${mb(metal.allocatedBytes)} " +
                "skia_cache_limit_mb=${mb(metal.cacheLimitBytes)} " +
                "skia_cpu_cache_mb=${mb(org.jetbrains.skia.Graphics.resourceCacheTotalUsed.toLong())} " +
                "skia_font_cache_mb=${mb(org.jetbrains.skia.Graphics.fontCacheUsed.toLong())} " +
                "heap_live_mb=${mb(live)} heap_target_mb=${mb(GC.targetHeapBytes)} " +
                "gcs=${info?.epoch ?: 0}" + (if (extra.isEmpty()) "" else " $extra"),
        )
        if (breakdown && platform.posix.getenv("DXC_METRICS_BREAKDOWN")?.toKString() == "1") {
            platform.posix.fflush(null)
            platform.posix.system(
                "/usr/bin/footprint ${platform.posix.getpid()} 2>/dev/null | " +
                    "sed 's/^/compose-rust: soak breakdown round=$round $phase /' 1>&2",
            )
        }
    }

    private fun after(millis: Long, block: () -> Unit) {
        platform.darwin.dispatch_after(
            platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, millis * 1_000_000L),
            platform.darwin.dispatch_get_main_queue(),
            block,
        )
    }

    /** Megabytes, rounded to the nearest one, which is the grain the gate is written in. */
    private fun mb(bytes: Long): Long = if (bytes < 0) -1 else (bytes + 524288) / 1048576

    /** Physical footprint and resident size of this process, in bytes. */
    internal fun processMemory(): Pair<Long, Long> = memScoped {
        val info = alloc<platform.darwin.task_vm_info_data_t>()
        val count = alloc<platform.darwin.mach_msg_type_number_tVar>()
        count.value = (sizeOf<platform.darwin.task_vm_info_data_t>() / 4).toUInt()
        // TASK_VM_INFO
        platform.darwin.task_info(platform.darwin.mach_task_self_, 22u, info.ptr.reinterpret(), count.ptr)
        info.phys_footprint.toLong() to info.resident_size.toLong()
    }

    internal fun roundMb(bytes: Long): Long = mb(bytes)
}

internal class IdleSoak {
    val seconds: Int =
        platform.posix.getenv("DXC_IDLE_SOAK_SECONDS")?.toKString()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    val enabled: Boolean get() = seconds > 0

    private var openedAtNanos: Long = 0L
    private var idleStartedAtNanos: Long = 0L
    private var lastSampleElapsed: Int = -1
    private var typed = false
    private var done = false
    var framesPresented: Long = 0L

    fun reset() {
        openedAtNanos = uptimeNanos()
        idleStartedAtNanos = 0L
        lastSampleElapsed = -1
        typed = false
        done = false
        framesPresented = 0L
    }

    fun step(
        window: MacosWindow,
        listener: org.thisisthepy.compose.window.WindowListener,
    ) {
        if (!enabled || done) return
        val now = uptimeNanos()
        val sinceOpenMillis = (now - openedAtNanos) / 1_000_000L
        if (!typed && sinceOpenMillis >= 1_500L && platform.posix.getenv("DXC_SYNTH")?.toKString() != "none") {
            typed = true
            val scale = window.measure().scale.coerceAtLeast(1f)
            val x = 80f * scale
            val y = 80f * scale
            listener.onEvent(
                org.thisisthepy.compose.window.WindowEvent(
                    org.thisisthepy.compose.window.WindowEvent.POINTER_DOWN,
                    x,
                    y,
                    1,
                    0,
                    0,
                    0,
                    "",
                ),
            )
            listener.onEvent(
                org.thisisthepy.compose.window.WindowEvent(
                    org.thisisthepy.compose.window.WindowEvent.POINTER_UP,
                    x,
                    y,
                    0,
                    0,
                    0,
                    0,
                    "",
                ),
            )
            listener.onEvent(
                org.thisisthepy.compose.window.WindowEvent(
                    org.thisisthepy.compose.window.WindowEvent.TEXT_COMMIT,
                    0f,
                    0f,
                    0,
                    0,
                    0,
                    0,
                    "abcde",
                ),
            )
        }
        if (sinceOpenMillis < 8_000L) return
        if (idleStartedAtNanos == 0L) {
            GC.collect()
            idleStartedAtNanos = now
            lastSampleElapsed = 0
            read("start", 0, window.metal, breakdown = true)
            return
        }
        val elapsed = ((now - idleStartedAtNanos) / 1_000_000_000L).toInt()
        val step = (elapsed / 10) * 10
        if (step > lastSampleElapsed && elapsed < seconds) {
            lastSampleElapsed = step
            read("sample", step, window.metal, breakdown = false)
        }
        if (elapsed >= seconds) {
            done = true
            GC.collect()
            read("end", seconds, window.metal, breakdown = true)
            printError("compose-rust: idle-soak done seconds=$seconds")
            listener.onCloseRequested()
        }
    }

    private fun read(phase: String, elapsedS: Int, metal: MetalSurface, breakdown: Boolean) {
        val (footprint, resident) = ResizeSoak.processMemory()
        val info = GC.lastGCInfo
        val live = info?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
        printError(
            "compose-rust: idle-soak phase $phase elapsed_s=$elapsedS frames=$framesPresented " +
                "footprint_mb=${ResizeSoak.roundMb(footprint)} resident_mb=${ResizeSoak.roundMb(resident)} " +
                "metal_allocated_mb=${ResizeSoak.roundMb(metal.allocatedBytes)} " +
                "skia_cache_limit_mb=${ResizeSoak.roundMb(metal.cacheLimitBytes)} " +
                "skia_cpu_cache_mb=${ResizeSoak.roundMb(org.jetbrains.skia.Graphics.resourceCacheTotalUsed.toLong())} " +
                "skia_font_cache_mb=${ResizeSoak.roundMb(org.jetbrains.skia.Graphics.fontCacheUsed.toLong())} " +
                "heap_live_mb=${ResizeSoak.roundMb(live)} heap_target_mb=${ResizeSoak.roundMb(GC.targetHeapBytes)} " +
                "gcs=${info?.epoch ?: 0}",
        )
        if (breakdown && platform.posix.getenv("DXC_METRICS_BREAKDOWN")?.toKString() == "1") {
            platform.posix.fflush(null)
            platform.posix.system(
                "/usr/bin/footprint ${platform.posix.getpid()} 2>/dev/null | " +
                    "sed 's/^/compose-rust: idle-soak breakdown elapsed_s=$elapsedS $phase /' 1>&2",
            )
        }
    }
}
