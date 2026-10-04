@file:JvmName("Win32Natives")

package org.thisisthepy.compose.window.windows

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
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement

// The Kotlin face of `c/win32_window.c` and `c/win32_platform.c`, for the GraalVM
// native-image path. The C side owns the window, the Direct3D device, the queue and the
// swapchain, and answers with pointers. Nothing there draws. This file declares the entry
// points with `@CFunction` and reads the event queue the window fills.
//
// A word value is made where it is used and nowhere else. Native-image accepts one in
// straight-line code inside a single method, so no helper here returns one and none is
// held across a call.

@CFunction("dxc_native_window_configure")
private external fun configureWindow(
    resizable: Int,
    minWidth: Int,
    minHeight: Int,
    systemChrome: Int,
    backdrop: Int,
)

@CFunction("dxc_native_window_open")
private external fun openWindow(title: CCharPointer?, width: Int, height: Int, out: Pointer?): Int

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

@CFunction("dxc_native_set_draw_callback")
private external fun setDrawCallback(callback: CFunctionPointer?, isolateThread: IsolateThread?)

@CFunction("dxc_native_pump")
private external fun pump(seconds: Double)

@CFunction("dxc_native_window_closed")
private external fun windowClosed(): Int

@CFunction("dxc_native_poll_event")
private external fun pollEvent(out: Pointer?): Int

@CFunction("dxc_native_clipboard_read")
private external fun clipboardRead(out: CCharPointer?, capacity: Int): Int

@CFunction("dxc_native_clipboard_write")
private external fun clipboardWrite(text: CCharPointer?)

@CFunction("dxc_native_set_ime_spot")
private external fun setImeSpot(x: Float, y: Float)

@CFunction("dxc_native_set_title")
private external fun setTitle(window: Pointer?, title: CCharPointer?)

@CFunction("dxc_native_system_dark")
private external fun systemDark(): Int

@CFunction("dxc_native_set_visibility")
private external fun setVisibility(window: Pointer?, code: Int)

@CFunction("dxc_native_show_context_menu")
private external fun showContextMenu(window: Pointer?, packed: CCharPointer?): Int

/**
 * The five pointers a window is, once Win32 and DXGI have made one, carried as plain
 * `Long`s because that is what may cross a method boundary.
 */
class Win32NativeWindow internal constructor(
    val window: Long,
    val device: Long,
    val queue: Long,
    val adapter: Long,
    val swapchain: Long,
) {
    /** The swapchain's size in pixels, and how many of them go to a point. */
    fun measure(): WindowMeasurement {
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        val scale = StackValue.get<CFloatPointer>(4)
        windowSize(WordFactory.pointer(window), width, height, scale)
        return WindowMeasurement(width.read(), height.read(), scale.read())
    }

    /**
     * The buffer this frame paints into, or zero where the swapchain had none to give.
     * Zero is not a failure: the frame is skipped rather than waited for.
     */
    fun beginFrame(): Long {
        val resource = StackValue.get<Pointer>(8)
        if (beginFrame(WordFactory.pointer(swapchain), resource) != 0) return 0
        return resource.readWord<Pointer>(0).rawValue()
    }

    /** Puts the painted frame on the screen. */
    fun endFrame() = endFrame(WordFactory.pointer(queue))

    fun setTitle(title: String) {
        val holder = CTypeConversion.toCString(title)
        try {
            setTitle(WordFactory.pointer(window), holder.get())
        } finally {
            holder.close()
        }
    }

    fun setVisibilityCode(code: Int) = setVisibility(WordFactory.pointer(window), code)

    /** Shows a menu at the pointer and answers with the id chosen, or -1. */
    fun showContextMenu(packed: String): Int {
        val holder = CTypeConversion.toCString(packed)
        try {
            return showContextMenu(WordFactory.pointer(window), holder.get())
        } finally {
            holder.close()
        }
    }
}

/** Says how the next window is made. Called before [openWin32Window]. */
fun configureWin32Window(
    resizable: Boolean,
    minWidth: Int,
    minHeight: Int,
    systemChrome: Boolean,
    backdrop: Boolean,
) = configureWindow(
    if (resizable) 1 else 0,
    minWidth,
    minHeight,
    if (systemChrome) 1 else 0,
    if (backdrop) 1 else 0,
)

/**
 * Opens a window, or null where this machine has no Direct3D 12 adapter. Null rather than
 * an exception: a machine without one is not a mistake in this code.
 */
fun openWin32Window(title: String, width: Int, height: Int): Win32NativeWindow? {
    val holder = CTypeConversion.toCString(title)
    try {
        // Five pointers, in the order the C struct declares them.
        val out = StackValue.get<Pointer>(WINDOW_STRUCT_BYTES)
        if (openWindow(holder.get(), width, height, out) != 0) return null
        return Win32NativeWindow(
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
private const val EVENT_STRUCT_BYTES = 124
private const val TEXT_OFFSET = 28
private const val TEXT_BYTES = 96
private const val CLIPBOARD_BYTES = 1 shl 20
private const val ZERO: Byte = 0

/** Gives the window the frame upcall's address, and takes it away again with `false`. */
fun registerWin32FrameCallback(register: Boolean) {
    if (register) {
        setDrawCallback(Win32Upcalls.DRAW_FRAME.functionPointer, CurrentIsolate.getCurrentThread())
    } else {
        setDrawCallback(
            WordFactory.nullPointer<CFunctionPointer>(),
            WordFactory.nullPointer<IsolateThread>(),
        )
    }
}

/** Gives the window its turn. This thread is the one Windows delivers messages to. */
fun pumpWin32(seconds: Double) = pump(seconds)

/** True once the reader has closed the window. */
fun isWin32WindowClosed(): Boolean = windowClosed() != 0

/** Everything the window wrote down since the last call, in the order it arrived. */
fun drainWin32Events(): List<WindowEvent> {
    val record = StackValue.get<Pointer>(EVENT_STRUCT_BYTES)
    val events = ArrayList<WindowEvent>()
    val bytes = ByteArray(TEXT_BYTES)
    while (pollEvent(record) != 0) {
        var length = 0
        while (length < TEXT_BYTES) {
            val byte = record.readByte(TEXT_OFFSET + length)
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

/** What is on the clipboard, or empty where it holds something that is not text. */
fun readWin32Clipboard(): String {
    val buffer = StackValue.get<Pointer>(CLIPBOARD_BYTES)
    val length = clipboardRead(buffer, CLIPBOARD_BYTES)
    if (length <= 0) return ""
    val bytes = ByteArray(length)
    for (index in 0 until length) {
        bytes[index] = buffer.readByte(index)
    }
    return String(bytes, Charsets.UTF_8)
}

fun writeWin32Clipboard(text: String) {
    val holder = CTypeConversion.toCString(text)
    try {
        clipboardWrite(holder.get())
    } finally {
        holder.close()
    }
}

fun setWin32ImeSpot(x: Float, y: Float) = setImeSpot(x, y)

fun isWin32SystemDark(): Boolean = systemDark() != 0
