package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.windows.c.dxc_element
import org.thisisthepy.compose.window.windows.c.dxc_event
import org.thisisthepy.compose.window.windows.c.dxc_native_debug_hit_test
import org.thisisthepy.compose.window.windows.c.dxc_native_debug_resize
import org.thisisthepy.compose.window.windows.c.dxc_native_frame_begin
import org.thisisthepy.compose.window.windows.c.dxc_native_frame_end
import org.thisisthepy.compose.window.windows.c.dxc_native_frame_mode
import org.thisisthepy.compose.window.windows.c.dxc_native_poll_event
import org.thisisthepy.compose.window.windows.c.dxc_native_pump
import org.thisisthepy.compose.window.windows.c.dxc_native_raster_begin
import org.thisisthepy.compose.window.windows.c.dxc_native_raster_end
import org.thisisthepy.compose.window.windows.c.dxc_native_report_metrics
import org.thisisthepy.compose.window.windows.c.dxc_native_set_accessibility
import org.thisisthepy.compose.window.windows.c.dxc_native_set_cursor
import org.thisisthepy.compose.window.windows.c.dxc_native_set_draw_callback
import org.thisisthepy.compose.window.windows.c.dxc_native_set_raster_resize
import org.thisisthepy.compose.window.windows.c.dxc_native_window
import org.thisisthepy.compose.window.windows.c.dxc_native_window_action
import org.thisisthepy.compose.window.windows.c.dxc_native_window_closed
import org.thisisthepy.compose.window.windows.c.dxc_native_window_configure
import org.thisisthepy.compose.window.windows.c.dxc_native_window_open
import org.thisisthepy.compose.window.windows.c.dxc_native_window_size

/**
 * A Win32 window with a Direct3D 12 swapchain in it and no toolkit in between, opened by the
 * shared C (`c/win32_window.c`).
 *
 * The C side owns the window, the device, the queue and the swapchain; translates messages
 * into [WindowEvent]s; composes text through IMM32; and answers UI Automation from the tree
 * handed to [setAccessibility]. What draws is the application's: it takes a frame from
 * [beginFrame] and gives it back with [endFrame].
 *
 * While the reader drags an edge, Windows runs a loop of its own and the application's loop
 * gets no turn until the drag ends. The C side then calls the function given to
 * [Win32Loop.setDrawCallback] from inside the resize, and asks for that frame on the CPU
 * ([Win32Loop.rasterFrameWanted], [Win32Loop.beginRasterFrame]): it copies those pixels into
 * the window before the resize returns, with the swapchain's child window hidden and nothing
 * presented while the size changes.
 */
class Win32NativeWindow private constructor(
    /** The HWND. */
    val window: COpaquePointer,
    /** The ID3D12Device. */
    val device: COpaquePointer,
    /** The ID3D12CommandQueue the swapchain presents on. */
    val queue: COpaquePointer,
    /** The IDXGIAdapter1 the device was made on. */
    val adapter: COpaquePointer,
    /** The IDXGISwapChain3. */
    val swapchain: COpaquePointer,
) {

    /** The client area in pixels, and how many pixels a point is on the window's monitor. */
    fun measure(): WindowMeasurement = memScoped {
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        val scale = alloc<FloatVar>()
        dxc_native_window_size(window, width.ptr, height.ptr, scale.ptr)
        WindowMeasurement(width.value, height.value, scale.value)
    }

    /**
     * The swapchain buffer to draw the next frame into, as an ID3D12Resource, already moved to
     * the render target state.
     *
     * Null where there is nothing to draw into: a minimised window answers that for as long as
     * it stays down, and a swapchain that could not be made to fit a new size answers it once.
     * Measure after this call, not before it: this is where a swapchain waiting to be refitted
     * is refitted.
     */
    fun beginFrame(): COpaquePointer? = memScoped {
        val out = alloc<COpaquePointerVar>()
        if (dxc_native_frame_begin(swapchain, out.ptr) != 0) return null
        out.value
    }

    /** Presents the frame [beginFrame] handed out. Waits for the screen. */
    fun endFrame() = dxc_native_frame_end(queue)

    /** The accessibility tree UI Automation answers from, at most [Win32Records.MAX_ELEMENTS]. */
    fun setAccessibility(elements: List<AccessibleElement>) {
        val capped = if (elements.size > Win32Records.MAX_ELEMENTS) {
            elements.take(Win32Records.MAX_ELEMENTS)
        } else {
            elements
        }
        memScoped {
            val records = allocArray<dxc_element>(Win32Records.MAX_ELEMENTS)
            for ((index, element) in capped.withIndex()) Win32Records.writeElement(element, records[index])
            dxc_native_set_accessibility(records, capped.size, window)
        }
    }

    /**
     * Resizes the window in [steps] steps through the same messages a drag of its edge sends,
     * for measuring and checking a resize without a person. [pauseMicros] waits between steps.
     */
    fun debugResize(fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int, steps: Int, pauseMicros: Int = 0) =
        dxc_native_debug_resize(window, null, fromWidth, fromHeight, toWidth, toHeight, steps, pauseMicros)

    /** Prints what Windows answers to a hit test where a reader would press, against what this caption should answer. */
    fun debugHitTest() = dxc_native_debug_hit_test(window)

    companion object {
        /**
         * How the next window opens. Called before [open]. `backdrop` is the system material
         * behind the window, 0 for none.
         */
        fun configure(
            resizable: Boolean = true,
            minWidth: Int = 0,
            minHeight: Int = 0,
            systemChrome: Boolean = true,
            backdrop: Int = 0,
        ) = dxc_native_window_configure(
            if (resizable) 1 else 0,
            minWidth,
            minHeight,
            if (systemChrome) 1 else 0,
            backdrop,
        )

        /**
         * Opens the window on this thread, which is then the thread its messages arrive on.
         *
         * Null where this machine has no Direct3D 12 adapter (set `DXC_D3D12_WARP=1` to use
         * the software one): a machine without one is not a mistake in the caller.
         */
        fun open(title: String, width: Int, height: Int): Win32NativeWindow? = memScoped {
            val out = alloc<dxc_native_window>()
            if (dxc_native_window_open(title, width, height, out.ptr) != 0) return null
            Win32NativeWindow(
                window = out.window ?: return null,
                device = out.device ?: return null,
                queue = out.queue ?: return null,
                adapter = out.adapter ?: return null,
                swapchain = out.swapchain ?: return null,
            )
        }
    }
}

/** Pixels the C side hands out for a frame drawn on the CPU: BGRA, top row first. */
class RasterTarget(val pixels: COpaquePointer, val rowBytes: Int, val width: Int, val height: Int)

/** What belongs to the thread that opened the window rather than to one window. */
object Win32Loop {

    /**
     * What the C side calls to have a frame drawn from inside a resize. It cannot capture
     * anything, so it is a `staticCFunction` that finds the window itself. Null stops the
     * calls; set it to null before closing what it draws.
     */
    fun setDrawCallback(callback: CPointer<CFunction<(COpaquePointer?) -> Unit>>?, context: COpaquePointer? = null) =
        dxc_native_set_draw_callback(callback, context)

    /** Dispatches the window's messages, waiting up to [seconds] for the first one. */
    fun pump(seconds: Double) = dxc_native_pump(seconds)

    /** True once the window has been closed. */
    val closed: Boolean get() = dxc_native_window_closed() != 0

    /** Everything the window heard since the last call, in order. */
    fun drainEvents(): List<WindowEvent> {
        val events = ArrayList<WindowEvent>()
        memScoped {
            val record = alloc<dxc_event>()
            while (dxc_native_poll_event(record.ptr) != 0) events.add(Win32Records.readEvent(record))
        }
        return events
    }

    /** The pointer's shape: [Win32Cursor.ARROW], [Win32Cursor.HAND], [Win32Cursor.TEXT] or [Win32Cursor.CROSSHAIR]. */
    fun setCursor(shape: Int) = dxc_native_set_cursor(shape)

    /** What a caption button asks of the window: 0 minimises, 1 maximises or restores, 2 closes. */
    fun action(which: Int) = dxc_native_window_action(which)

    /** Whether frames during a resize are drawn on the CPU (the default) or on the GPU. */
    fun setRasterResize(enabled: Boolean) = dxc_native_set_raster_resize(if (enabled) 1 else 0)

    /** True while the C side wants the frame on the CPU, from [beginRasterFrame]. */
    val rasterFrameWanted: Boolean get() = dxc_native_frame_mode() != 0

    /** The pixels to draw the resize frame into, at the size the window is becoming. */
    fun beginRasterFrame(): RasterTarget? = memScoped {
        val pixels = alloc<COpaquePointerVar>()
        val rowBytes = alloc<IntVar>()
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        if (dxc_native_raster_begin(pixels.ptr, rowBytes.ptr, width.ptr, height.ptr) != 0) return null
        RasterTarget(pixels.value ?: return null, rowBytes.value, width.value, height.value)
    }

    /** Copies the pixels [beginRasterFrame] handed out into the window. */
    fun endRasterFrame() = dxc_native_raster_end()

    /** Prints one line of the process's working set, private bytes and video memory under [label], to standard error. */
    fun reportMetrics(label: String) = dxc_native_report_metrics(label)
}

/** The pointer shapes [Win32Loop.setCursor] takes, numbered as the C side numbers them. */
object Win32Cursor {
    const val ARROW: Int = 0
    const val HAND: Int = 1
    const val TEXT: Int = 2
    const val CROSSHAIR: Int = 3
}
