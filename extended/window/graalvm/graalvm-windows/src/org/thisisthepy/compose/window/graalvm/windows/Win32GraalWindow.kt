package org.thisisthepy.compose.window.graalvm.windows

import org.graalvm.nativeimage.CurrentIsolate
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.UnmanagedMemory
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.word.Pointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.graalvm.word.WordFactory
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.FrameClock
import org.thisisthepy.compose.window.DoubleClick
import org.thisisthepy.compose.window.EditMenuState
import org.thisisthepy.compose.window.TextScale
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.monotonicNanos
import org.thisisthepy.compose.window.win32EditMenuFlags

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

@CFunction("dxc_native_popup_menu")
private external fun nativePopupMenu(
    window: Pointer?,
    lines: CCharPointer?,
    icons: CCharPointer?,
    iconSizes: CIntPointer?,
    iconCount: Int,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    rtl: Int,
    out: CCharPointer?,
    capacity: Int,
): Int

@CFunction("dxc_native_popup_menu_close")
private external fun nativePopupMenuClose()

@CFunction("dxc_native_set_cursor")
private external fun setCursorShape(shape: Int)

@CFunction("dxc_native_text_scale_percent")
private external fun textScalePercent(): Int

@CFunction("dxc_native_double_click_millis")
private external fun doubleClickMillisNative(): Long

@CFunction("dxc_native_set_accessibility")
private external fun setAccessibility(elements: Pointer?, count: Int, window: Pointer?)

@CFunction("dxc_native_take_accessibility_argument")
private external fun takeAccessibilityArgumentNative(out: Pointer?, capacity: Int): Int

@CFunction("dxc_native_set_edit_menu")
private external fun setEditMenuNative(window: Pointer?, flags: Int)

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

    /**
     * The system's text size as a multiplier, 1 when unchanged.
     *
     * The C side reads the Accessibility "Text size" slider as a percent; the conversion is
     * the one windows-shared makes, so both Windows windows answer the same number.
     */
    fun textScale(): Float = TextScale.fromWindowsPercent(textScalePercent())

    /** The double-click interval set in the mouse settings, in milliseconds (GetDoubleClickTime). */
    fun doubleClickMillis(): Long = DoubleClick.clamp(doubleClickMillisNative())

    /** True once the window has been closed. */
    fun closed(): Boolean = windowClosed() != 0

    /**
     * The window's own text edit menu: [state] says a text field has focus and what it
     * allows, null that none has. While one has, a right click, the Menu key or Shift and F10
     * put up the system's popup menu, and the entry chosen arrives among [drainEvents] as the
     * shortcut it is named after, Control and X, C, V or A, as on the Kotlin/Native window
     * (`Win32WindowPlatform.setEditMenu`).
     */

    /**
     * Puts an entry menu up as Windows' own (compose-rust FR-33.2) under [anchor] (client pixels)
     * and answers the chosen item's path, or null for a menu closed with nothing chosen. The same
     * C as the Kotlin/Native window (dxc_native_popup_menu), so both paths put up the same menu.
     */
    fun showPopupMenu(
        entries: List<org.thisisthepy.compose.window.PopupMenuEntry>,
        anchor: org.thisisthepy.compose.window.PopupMenuAnchor,
    ): List<Int>? {
        if (entries.isEmpty()) return null
        val lines = org.thisisthepy.compose.window.encodePopupMenu(entries)
        val pixels = ByteArray(lines.icons.sumOf { it.rgba.size }.coerceAtLeast(1))
        var at = 0
        for (icon in lines.icons) {
            icon.rgba.copyInto(pixels, at)
            at += icon.rgba.size
        }
        val sizes = org.graalvm.nativeimage.UnmanagedMemory.malloc<CIntPointer>(8 * lines.icons.size.coerceAtLeast(1))
        lines.icons.forEachIndexed { index, icon ->
            sizes.write(index * 2, icon.width)
            sizes.write(index * 2 + 1, icon.height)
        }
        val text = CTypeConversion.toCString(lines.text)
        val icons = CTypeConversion.toCBytes(pixels)
        val capacity = 1024
        val out = org.graalvm.nativeimage.UnmanagedMemory.malloc<CCharPointer>(capacity)
        try {
            val length = nativePopupMenu(
                WordFactory.pointer(window), text.get(), icons.get(), sizes, lines.icons.size,
                anchor.x, anchor.y, anchor.width, anchor.height, if (anchor.rightToLeft) 1 else 0, out, capacity,
            )
            if (length <= 0) return null
            val bytes = ByteArray(length)
            CTypeConversion.asByteBuffer(out, length).get(bytes)
            return org.thisisthepy.compose.window.decodePopupMenuPath(String(bytes, Charsets.UTF_8))
        } finally {
            org.graalvm.nativeimage.UnmanagedMemory.free(out)
            org.graalvm.nativeimage.UnmanagedMemory.free(sizes)
            icons.close()
            text.close()
        }
    }

    /** Takes an open entry menu down from the application's side; [showPopupMenu] then answers null. */
    fun closePopupMenu() = nativePopupMenuClose()

    fun setEditMenu(state: EditMenuState?) = setEditMenuNative(WordFactory.pointer(window), win32EditMenuFlags(state))

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
 * Written into memory of our own and copied on the other side. The elements are few, they
 * change when the screen changes rather than when a frame is drawn, and the alternative
 * is the platform asking across threads at a moment nobody chose. On the heap rather than
 * the stack: a full tree is over a hundred kilobytes.
 */
fun Win32GraalWindow.describeTo(elements: List<AccessibleElement>) {
    val bytes = encodeWin32Elements(elements)
    val count = bytes.size / ELEMENT_BYTES
    if (count == 0) {
        setAccessibility(WordFactory.nullPointer(), 0, WordFactory.pointer(window))
        return
    }
    val records = UnmanagedMemory.malloc<Pointer>(bytes.size)
    try {
        CTypeConversion.asByteBuffer(records, bytes.size).put(bytes)
        setAccessibility(records, count, WordFactory.pointer(window))
    } finally {
        UnmanagedMemory.free(records)
    }
}

/**
 * The records `struct dxc_element` lays out (windows-shared's header), at most
 * [MAX_ELEMENTS] of them, in the machine's byte order. Plain bytes, so a test on any JVM
 * reads what the window will.
 */
internal fun encodeWin32Elements(elements: List<AccessibleElement>): ByteArray {
    val capped = if (elements.size > MAX_ELEMENTS) elements.take(MAX_ELEMENTS) else elements
    val buffer = ByteBuffer.allocate(capped.size * ELEMENT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for ((index, element) in capped.withIndex()) {
        val at = index * ELEMENT_BYTES
        buffer.putInt(at, element.role)
        buffer.putFloat(at + 4, element.x)
        buffer.putFloat(at + 8, element.y)
        buffer.putFloat(at + 12, element.width)
        buffer.putFloat(at + 16, element.height)
        putText(buffer, at + ELEMENT_LABEL_OFFSET, win32LabelBytes(element.label))
        buffer.putInt(at + ELEMENT_ID_OFFSET, element.id)
        buffer.putInt(at + ELEMENT_ID_OFFSET + 4, element.parentId)
        buffer.putInt(at + ELEMENT_ID_OFFSET + 8, element.states)
        buffer.putInt(at + ELEMENT_ID_OFFSET + 12, element.actions)
        buffer.putInt(at + ELEMENT_FLAGS_OFFSET, win32ElementFlags(element))
        buffer.putFloat(at + ELEMENT_RANGE_OFFSET, element.rangeMin ?: 0f)
        buffer.putFloat(at + ELEMENT_RANGE_OFFSET + 4, element.rangeMax ?: 0f)
        buffer.putFloat(at + ELEMENT_RANGE_OFFSET + 8, element.rangeValue ?: 0f)
        putText(buffer, at + ELEMENT_VALUE_OFFSET, win32FittedBytes(element.value.orEmpty(), VALUE_BYTES))
        putText(buffer, at + ELEMENT_HINT_OFFSET, win32LabelBytes(element.hint.orEmpty()))
    }
    return buffer.array()
}

/** Which optional parts the record carries: a value, a range (all three numbers), a hint. */
internal fun win32ElementFlags(element: AccessibleElement): Int {
    var flags = 0
    if (element.value != null) flags = flags or ELEMENT_HAS_VALUE
    if (element.rangeMin != null && element.rangeMax != null && element.rangeValue != null) {
        flags = flags or ELEMENT_HAS_RANGE
    }
    if (element.hint != null) flags = flags or ELEMENT_HAS_HINT
    return flags
}

// The buffer starts zeroed, so the byte after the text already ends it.
private fun putText(buffer: ByteBuffer, at: Int, bytes: ByteArray) {
    for (offset in bytes.indices) buffer.put(at + offset, bytes[offset])
}

/**
 * The new value of the oldest SET_VALUE request a reader made that has not been read, or
 * empty when there is none. Asked once with room for most values and again with the room
 * the window says a longer one needs.
 */
private fun takeAccessibilityArgument(): String {
    var capacity = 1024
    while (true) {
        val buffer = UnmanagedMemory.malloc<Pointer>(capacity)
        try {
            val length = takeAccessibilityArgumentNative(buffer, capacity)
            if (length < 0) return ""
            if (length <= capacity) {
                val bytes = ByteArray(length)
                CTypeConversion.asByteBuffer(buffer, length).get(bytes)
                return String(bytes, Charsets.UTF_8)
            }
            capacity = length
        } finally {
            UnmanagedMemory.free(buffer)
        }
    }
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
        val event =
            win32Event(
                WindowEvent(
                    kind = record.readInt(0),
                    x = record.readFloat(4),
                    y = record.readFloat(8),
                    buttons = record.readInt(12),
                    modifiers = record.readInt(16),
                    keyCode = record.readInt(20),
                    codePoint = record.readInt(24),
                    text = if (length == 0) "" else String(bytes, 0, length, Charsets.UTF_8),
                    timeMillis = record.readLong(EVENT_TIME_OFFSET),
                ),
            )
        // A reader's new value is kept beside the event, since it can be longer than the
        // event's text, and is read here in the same order.
        events.add(
            if (event.kind == WindowEvent.ACCESSIBILITY_ACTION && event.codePoint == AccessibleAction.SET_VALUE) {
                event.copy(text = takeAccessibilityArgument())
            } else {
                event
            },
        )
    }
    return events
}

/**
 * The event as the rest of the window reads it.
 *
 * The C side writes a text size change with the slider's percent in `x`; everything past
 * here expects the multiplier, as windows-shared's `Win32Records.readEvent` gives it.
 */
internal fun win32Event(raw: WindowEvent): WindowEvent =
    if (raw.kind == WindowEvent.TEXT_SCALE_CHANGED) {
        WindowEvent.TextScaleChanged(TextScale.fromWindowsPercent(raw.x.toInt()))
    } else {
        raw
    }

private const val EVENT_TEXT_OFFSET = 28
// The int64 after the text, aligned to 8: 28 + 96 is 124, padded to 128.
private const val EVENT_TIME_OFFSET = 128
private const val EVENT_STRUCT_BYTES = 136

/**
 * How many things a screen may say it has.
 *
 * Enough for a screen and not for a document. A list of ten thousand rows is windowed
 * before it reaches the scene, so what is here is what is on screen.
 */
private const val MAX_ELEMENTS = 256
// Where `struct dxc_element`'s fields are; windows-shared's C test asserts the same numbers.
internal const val ELEMENT_LABEL_OFFSET = 20
internal const val ELEMENT_ID_OFFSET = 116
internal const val ELEMENT_FLAGS_OFFSET = 132
internal const val ELEMENT_RANGE_OFFSET = 136
internal const val ELEMENT_VALUE_OFFSET = 148
internal const val ELEMENT_HINT_OFFSET = 404
internal const val ELEMENT_BYTES = 500
internal const val ELEMENT_HAS_VALUE = 1 shl 0
internal const val ELEMENT_HAS_RANGE = 1 shl 1
internal const val ELEMENT_HAS_HINT = 1 shl 2
private const val ZERO: Byte = 0
private const val TEXT_BYTES = 96
internal const val VALUE_BYTES = 256

/** Fits a label in the native record without cutting a UTF-8 character in half. */
internal fun win32LabelBytes(label: String): ByteArray = win32FittedBytes(label, TEXT_BYTES)

/** [text] as UTF-8 that fits [capacity] bytes with its terminating zero, cut before a character. */
internal fun win32FittedBytes(text: String, capacity: Int): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size < capacity) return bytes
    var length = capacity - 1
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
