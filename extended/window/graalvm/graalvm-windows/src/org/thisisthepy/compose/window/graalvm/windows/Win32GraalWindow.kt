package org.thisisthepy.compose.window.graalvm.windows

import org.graalvm.nativeimage.CurrentIsolate
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.FrameClock
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.monotonicNanos

// A window that is ours on Windows, drawn into with Skia and with no toolkit in between.
//
// The pair of the AppKit and X11 windows of the sibling modules: these are the only places
// that name GraalVM types, so a development run on a JVM never loads them.
//
// The C side is `windows-shared/c/win32_window.c`, compiled into the image by the
// application's build. It owns the window, the Direct3D device, the queue, the adapter and
// the swapchain, and answers with the pointers. Nothing there draws.
//
// The same C symbols the macOS and Linux windows call, because the C files are alternatives:
// exactly one of them is compiled into an image, and each answers for the window its own
// platform knows how to open. What the five pointers mean differs, which is why each
// platform reads them here rather than sharing a struct.

@CFunction("dxc_native_window_open")
private external fun openWindow(
    title: CCharPointer?,
    width: Int,
    height: Int,
    out: Pointer?,
): Int

@CFunction("dxc_native_window_size")
private external fun windowSize(
    window: Pointer?,
    width: CIntPointer?,
    height: CIntPointer?,
    scale: CFloatPointer?,
)

@CFunction("dxc_native_frame_begin")
private external fun beginFrame(swapchain: Pointer?, resourceOut: Pointer?): Int

@CFunction("dxc_native_frame_end")
private external fun endFrame(queue: Pointer?)

@CFunction("dxc_native_set_raster_resize")
private external fun setRasterResize(enabled: Int)

@CFunction("dxc_native_frame_mode")
private external fun frameMode(): Int

@CFunction("dxc_native_raster_begin")
private external fun nativeRasterBegin(pixels: Pointer?, rowBytes: CIntPointer?, width: CIntPointer?, height: CIntPointer?): Int

@CFunction("dxc_native_raster_end")
private external fun nativeRasterEnd()

@CFunction("dxc_native_set_draw_callback")
private external fun setDrawCallback(callback: CFunctionPointer?, isolateThread: IsolateThread?)

@CFunction("dxc_native_poll_event")
private external fun pollEvent(out: Pointer?): Int

@CFunction("dxc_native_pump")
private external fun pumpEvents(seconds: Double)

@CFunction("dxc_native_window_closed")
private external fun windowClosed(): Int

@CFunction("dxc_native_set_cursor")
private external fun setCursorShape(shape: Int)

@CFunction("dxc_native_set_accessibility")
private external fun setAccessibility(elements: Pointer?, count: Int, window: Pointer?)

/**
 * The five pointers a window is, once Win32 and DXGI have made one.
 *
 * Words rather than objects, because that is what crosses: native-image accepts a word
 * value in straight-line code inside one method and nowhere else, so each is read out
 * once, here, and carried as a plain `Long` after that.
 */
class Win32GraalWindow internal constructor(
    val window: Long,
    val device: Long,
    val queue: Long,
    val adapter: Long,
    val swapchain: Long,
    source: () -> Long = ::monotonicNanos,
) {

    private val clock = FrameClock(source)

    /**
     * The time the frame about to be drawn is drawn at, in nanoseconds.
     *
     * Read from the monotonic clock each time it is asked, so a frame drawn later is given a
     * later time by as much as the wall clock moved, whatever the display's refresh rate and
     * however late a frame is. An animation moves by the difference between two of these, so
     * a count of frames in its place runs fast on a fast display and falls behind on a slow
     * one. Never goes backwards.
     */
    fun frameTimeNanos(): Long = clock.frameNanos()


    // A word value is made where it is used and nowhere else. Native-image accepts one
    // in straight-line code inside a single method, so a helper that returned one, or a
    // variable that held one across a call, is rejected: `WordFactory.pointer` is
    // written out at each call rather than wrapped.

    /** The size of the client area in pixels, and how many of them go to a point. */
    fun measure(): WindowMeasurement {
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        val scale = StackValue.get<CFloatPointer>(4)
        windowSize(WordFactory.pointer(window), width, height, scale)
        return WindowMeasurement(width.read(), height.read(), scale.read())
    }

    /**
     * The buffer this frame paints into, or zero where the swapchain had none to give.
     *
     * Zero is not a failure. The window may have been closed or minimised, or it may have
     * just been given a size the swapchain has yet to be made to fit. The answer to all
     * three is to skip the frame rather than to wait.
     */
    fun beginFrame(): Long {
        val resource = StackValue.get<Pointer>(8)
        if (beginFrame(WordFactory.pointer(swapchain), resource) != 0) return 0
        return resource.readWord<Pointer>(0).rawValue()
    }

    /** Puts the painted frame on the screen. */
    fun endFrame() = endFrame(WordFactory.pointer(queue))

    /** True while the window wants its resize frames as CPU pixels. */
    fun rasterFrame(): Boolean = frameMode() != 0

    /** The CPU frame's pixels: address, row bytes, width, height; null where there are none. */
    fun rasterBegin(): RasterTarget? {
        val pixels = StackValue.get<Pointer>(8)
        val rowBytes = StackValue.get<CIntPointer>(4)
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        if (nativeRasterBegin(pixels, rowBytes, width, height) != 0) return null
        return RasterTarget(pixels.readWord<Pointer>(0).rawValue(), rowBytes.read(), width.read(), height.read())
    }

    /** Copies the CPU frame into the window, before the message that asked for it returns. */
    fun rasterEnd() = nativeRasterEnd()

    /**
     * Asks for resize frames as CPU pixels, copied into the window before the resize
     * returns, or for GPU frames only.
     */
    fun setRasterResize(enabled: Boolean) = setRasterResize(if (enabled) 1 else 0)

    /** Sets the mouse cursor to [shape], one of the cursor numbers the C side defines. */
    fun setCursor(shape: Int) = setCursorShape(shape)

    /** True once the window has been closed. */
    fun closed(): Boolean = windowClosed() != 0

    /**
     * Gives the window its turn, waiting up to [seconds] for something to happen.
     *
     * This thread is the one Windows delivers messages to, so the messages of a frame arrive
     * here or not at all.
     */
    fun pump(seconds: Double) = pumpEvents(seconds)

    /**
     * Takes everything the window has heard since the last frame.
     *
     * Drained rather than delivered: Win32 answers on the thread that pumps, and the Host
     * keeps its state on the one that draws. The text is copied out byte by byte because a
     * word value may not leave the method it was made in.
     */
    fun drainEvents(): List<WindowEvent> = drainWindowEvents()

    /**
     * Gives the window an address to ask for a frame at, and takes it away again.
     *
     * The thread goes with the address because the other side of it is a Java runtime: an
     * entry point cannot be called without being told which thread of which isolate is
     * calling, and this is the thread the window, the scene and the Host all live on.
     */
    fun registerFrameCallback() =
        setDrawCallback(Win32DrawCallback.POINTER.functionPointer, CurrentIsolate.getCurrentThread())

    /** Takes the frame address away again, before the scene it draws is closed. */
    fun forgetFrameCallback() = setDrawCallback(
        WordFactory.nullPointer<CFunctionPointer>(),
        WordFactory.nullPointer<IsolateThread>(),
    )

}

/**
 * Opens a window, or null where this machine has no Direct3D 12 adapter.
 *
 * Null rather than an exception: a machine without one is not a mistake in this code, and
 * the caller has an older path it can take instead.
 */
fun openWin32Window(title: String, width: Int, height: Int): Win32GraalWindow? {
    val holder = CTypeConversion.toCString(title)
    try {
        // Five pointers, in the order the C struct declares them.
        val out = StackValue.get<Pointer>(WINDOW_STRUCT_BYTES)
        if (openWindow(holder.get(), width, height, out) != 0) {
            return null
        }
        return Win32GraalWindow(
            window = out.readWord<Pointer>(0).rawValue(),
            device = out.readWord<Pointer>(8).rawValue(),
            queue = out.readWord<Pointer>(16).rawValue(),
            adapter = out.readWord<Pointer>(24).rawValue(),
            swapchain = out.readWord<Pointer>(32).rawValue(),
        )
    } finally {
        holder.close()
    }
}

private const val WINDOW_STRUCT_BYTES = 40

/**
 * Hands the platform what the window would tell a reader who cannot see it.
 *
 * Written into stack storage and copied on the other side. The elements are few, they
 * change when the screen changes rather than when a frame is drawn, and the alternative
 * is the platform asking across threads at a moment nobody chose.
 */
fun Win32GraalWindow.describeTo(elements: List<AccessibleElement>) {
    val capped = if (elements.size > MAX_ELEMENTS) elements.take(MAX_ELEMENTS) else elements
    val records = StackValue.get<Pointer>(MAX_ELEMENTS * ELEMENT_BYTES)
    for ((index, element) in capped.withIndex()) {
        val at = index * ELEMENT_BYTES
        records.writeInt(at, element.role)
        records.writeFloat(at + 4, element.x)
        records.writeFloat(at + 8, element.y)
        records.writeFloat(at + 12, element.width)
        records.writeFloat(at + 16, element.height)
        val bytes = win32LabelBytes(element.label)
        for (offset in bytes.indices) {
            records.writeByte(at + ELEMENT_LABEL_OFFSET + offset, bytes[offset])
        }
        records.writeByte(at + ELEMENT_LABEL_OFFSET + bytes.size, ZERO)
    }
    setAccessibility(records, capped.size, WordFactory.pointer(window))
}

/**
 * Takes everything the window has heard since the last frame.
 *
 * Drained rather than delivered: Win32 answers on the thread that pumps, and the Host keeps
 * its state on the one that draws. The text is copied out byte by byte because a word value
 * may not leave the method it was made in.
 */
private fun drainWindowEvents(): List<WindowEvent> {
    val record = StackValue.get<Pointer>(EVENT_STRUCT_BYTES)
    val events = ArrayList<WindowEvent>()
    val bytes = ByteArray(TEXT_BYTES)
    while (pollEvent(record) != 0) {
        var length = 0
        while (length < TEXT_BYTES) {
            val byte = record.readByte(EVENT_TEXT_OFFSET + length)
            if (byte == ZERO) break
            bytes[length] = byte
            length++
        }
        events.add(
            WindowEvent(
                kind = record.readInt(0),
                x = record.readFloat(4),
                y = record.readFloat(8),
                buttons = record.readInt(12),
                modifiers = record.readInt(16),
                keyCode = record.readInt(20),
                codePoint = record.readInt(24),
                text = if (length == 0) "" else String(bytes, 0, length, Charsets.UTF_8),
            ),
        )
    }
    return events
}

private const val EVENT_TEXT_OFFSET = 28
private const val EVENT_STRUCT_BYTES = 124

/**
 * How many things a screen may say it has.
 *
 * Enough for a screen and not for a document. A list of ten thousand rows is windowed
 * before it reaches the scene, so what is here is what is on screen.
 */
private const val MAX_ELEMENTS = 256
private const val ELEMENT_LABEL_OFFSET = 20
private const val ELEMENT_BYTES = 116
private const val ZERO: Byte = 0
private const val TEXT_BYTES = 96

/** Fits a label in the native record without cutting a UTF-8 character in half. */
internal fun win32LabelBytes(label: String): ByteArray {
    val bytes = label.toByteArray(Charsets.UTF_8)
    if (bytes.size < TEXT_BYTES) return bytes
    var length = TEXT_BYTES - 1
    while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) {
        length--
    }
    return bytes.copyOf(length)
}

/**
 * What the swapchain was made with, which Skia has to be told again.
 *
 * `DXGI_FORMAT_R8G8B8A8_UNORM`. Named by its number because the C side holds the header
 * this comes from and nothing on this side can see it, and repeated rather than asked for
 * because a format that disagrees between the two is a window of swapped colour channels
 * rather than a failure anything reports.
 */
const val SWAPCHAIN_FORMAT = 28
