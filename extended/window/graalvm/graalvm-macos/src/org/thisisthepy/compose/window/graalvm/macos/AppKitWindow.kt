@file:JvmName("AppKitWindow")

package org.thisisthepy.compose.window.graalvm.macos

import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.UnmanagedMemory
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement

// The Kotlin face of `native/appkit_window.m`, for the GraalVM native-image path.
//
// The C side owns the window, the view, the layer, the Metal device and the queue, and
// answers with pointers. Nothing there draws. This file declares the entry points with
// `@CFunction` and reads the event queue the window fills; it names no toolkit type and
// no Compose type, so it compiles on a JVM with no AWT on the path.

@CFunction("dxc_native_window_open")
private external fun openWindow(
    title: CCharPointer?,
    width: Int,
    height: Int,
    out: Pointer?,
): Int

@CFunction("dxc_native_window_size")
private external fun windowSize(
    view: Pointer?,
    width: CIntPointer?,
    height: CIntPointer?,
    scale: CFloatPointer?,
)

@CFunction("dxc_native_frame_begin")
private external fun beginFrame(layer: Pointer?, textureOut: Pointer?): Int

@CFunction("dxc_native_frame_end")
private external fun endFrame(queue: Pointer?)

@CFunction("dxc_native_poll_event")
private external fun pollEvent(out: Pointer?): Int

@CFunction("dxc_native_set_accessibility")
private external fun setAccessibility(elements: Pointer?, count: Int, view: Pointer?)

@CFunction("dxc_native_set_cursor")
private external fun setCursorShape(shape: Int)

@CFunction("dxc_native_pump")
private external fun pumpEvents(seconds: Double)

@CFunction("dxc_native_set_draw_callback")
private external fun setAppKitDrawCallback(callback: CFunctionPointer?, isolateThread: IsolateThread?)

@CFunction("dxc_native_debug_resize")
private external fun debugResize(
    window: Pointer?,
    view: Pointer?,
    fromWidth: Int,
    fromHeight: Int,
    toWidth: Int,
    toHeight: Int,
    steps: Int,
    pauseMicros: Int,
)

@CFunction("dxc_native_debug_key")
private external fun debugKey(window: Pointer?, keyCode: Int, characters: CCharPointer?)

@CFunction("dxc_native_clipboard_read")
private external fun clipboardRead(out: Pointer?, capacity: Int): Int

@CFunction("dxc_native_clipboard_write")
private external fun clipboardWrite(text: CCharPointer?)

@CFunction("dxc_native_install_menu")
private external fun installMenu(name: CCharPointer?)

@CFunction("dxc_native_window_configure")
private external fun configureWindow(
    resizable: Int,
    minWidth: Int,
    minHeight: Int,
    systemChrome: Int,
    backdrop: Int,
)

@CFunction("dxc_native_window_chrome")
private external fun windowChrome(
    fullSizeContent: Int,
    transparentTitleBar: Int,
    titleHidden: Int,
    unifiedToolbar: Int,
)

@CFunction("dxc_native_window_title_bar")
private external fun windowTitleBar(view: Pointer?, out: CFloatPointer?)

@CFunction("dxc_native_set_icon")
private external fun setIcon(rgba: CCharPointer?, width: Int, height: Int)

@CFunction("dxc_native_dropped_paths")
private external fun droppedPaths(out: Pointer?, capacity: Int): Int

@CFunction("dxc_native_window_closed")
private external fun windowClosed(): Int
/**
 * The four pointers a window is, once AppKit has made one.
 *
 * Words rather than objects, because that is what crosses: native-image accepts a word
 * value in straight-line code inside one method and nowhere else, so each is read out
 * once, here, and carried as a plain `Long` after that.
 */
class NativeWindow internal constructor(
    val window: Long,
    val view: Long,
    val device: Long,
    val queue: Long,
    val layer: Long,
) {

    // A word value is made where it is used and nowhere else. Native-image accepts one
    // in straight-line code inside a single method, so a helper that returned one, or a
    // variable that held one across a call, is rejected: `WordFactory.pointer` is
    // written out at each call rather than wrapped.

    /** The size of the drawable in pixels, and how many of them go to a point. */
    fun measure(): WindowMeasurement {
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        val scale = StackValue.get<CFloatPointer>(4)
        windowSize(WordFactory.pointer(layer), width, height, scale)
        return WindowMeasurement(width.read(), height.read(), scale.read())
    }

    /**
     * The texture this frame paints into, or zero where the system had none to give.
     *
     * Zero is not a failure. It means frames are being produced faster than the screen
     * takes them, and the answer to that is to skip one rather than to wait.
     */
    fun beginFrame(): Long {
        val texture = StackValue.get<Pointer>(8)
        if (beginFrame(WordFactory.pointer(layer), texture) != 0) return 0
        return texture.readWord<Pointer>(0).rawValue()
    }

    /** Puts the painted frame on the screen. */
    fun endFrame() = endFrame(WordFactory.pointer(queue))

    /** Posts a key press for [character] to the window. See `DXC_SYNTH`. */
    fun postKey(keyCode: Int, character: String) {
        val holder = CTypeConversion.toCString(character)
        try {
            debugKey(WordFactory.pointer(window), keyCode, holder.get())
        } finally {
            holder.close()
        }
    }

    /** Takes the window through the sizes a drag would, for measuring. See `DXC_SYNTH_RESIZE`. */
    fun scriptedResize(from: Pair<Int, Int>, to: Pair<Int, Int>, steps: Int, pauseMicros: Int) {
        debugResize(
            WordFactory.pointer(window), WordFactory.pointer(view),
            from.first, from.second, to.first, to.second, steps, pauseMicros,
        )
    }

}

/**
 * Takes everything the window has heard since the last frame.
 *
 * Drained rather than delivered. AppKit answers on its own thread and the Host keeps its
 * state on the one that draws, so an event that arrived as a call would arrive on the
 * wrong thread; the shell writes them down and this reads them where they can be used.
 */
fun drainWindowEvents(): List<WindowEvent> {
    val record = StackValue.get<Pointer>(EVENT_STRUCT_BYTES)
    val events = ArrayList<WindowEvent>()
    // Everything about the record is read here. A word value may not leave the method it
    // was made in, so the text is copied out byte by byte rather than by handing the
    // pointer to something that knows how to read a string.
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

/**
 * Hands the platform what the window would tell a reader who cannot see it.
 *
 * Written into stack storage and copied on the other side. The elements are few, they
 * change when the screen changes rather than when a frame is drawn, and the alternative
 * is the platform asking across threads at a moment nobody chose.
 */
fun NativeWindow.describeTo(elements: List<AccessibleElement>) = describeWindow(view, elements)

/**
 * Writes the records and hands them to whichever window asked.
 *
 * Apart from the extension above because the windows the other desktops open are not this
 * class, and what a tree looks like on the way across does not differ between them: one
 * layout, written once, so a field that moves cannot move in one place only.
 */
fun describeWindow(view: Long, elements: List<AccessibleElement>) {
    val capped = if (elements.size > MAX_ELEMENTS) elements.take(MAX_ELEMENTS) else elements
    val records = StackValue.get<Pointer>(MAX_ELEMENTS * ELEMENT_BYTES)
    for ((index, element) in capped.withIndex()) {
        val at = index * ELEMENT_BYTES
        records.writeInt(at, element.role)
        records.writeFloat(at + 4, element.x)
        records.writeFloat(at + 8, element.y)
        records.writeFloat(at + 12, element.width)
        records.writeFloat(at + 16, element.height)
        val bytes = element.label.toByteArray(Charsets.UTF_8)
        var length = 0
        while (length < bytes.size && length < TEXT_BYTES - 1) {
            records.writeByte(at + ELEMENT_LABEL_OFFSET + length, bytes[length])
            length++
        }
        records.writeByte(at + ELEMENT_LABEL_OFFSET + length, ZERO)
    }
    setAccessibility(records, capped.size, WordFactory.pointer(view))
}

/**
 * Sets the shape of the pointer over the window.
 *
 * The scene decides: a control that is a link asks for a hand, a field asks for a bar.
 * Which platform cursor that is belongs to the shell, so what crosses is a number.
 */
fun setPointerShape(shape: Int) = setCursorShape(shape)

/**
 * What the application asked of its window, handed over before the window is made.
 *
 * Sizes are in points, which is what the window measures its content in.
 */
fun configureNativeWindow(
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
 * How the title bar is built, handed over before the window is made: whether the content
 * runs under the bar, whether the bar is transparent, whether the title is hidden, and
 * whether the window has an empty unified toolbar, which sets the bar's height and the
 * window's corner radius on macOS 26.
 */
fun configureNativeWindowChrome(
    fullSizeContentView: Boolean,
    titlebarAppearsTransparent: Boolean,
    titleHidden: Boolean,
    unifiedToolbar: Boolean,
) = windowChrome(
    if (fullSizeContentView) 1 else 0,
    if (titlebarAppearsTransparent) 1 else 0,
    if (titleHidden) 1 else 0,
    if (unifiedToolbar) 1 else 0,
)

/**
 * What the title bar's size is worked out from, as the window reports it, in points.
 *
 * Measured rather than assumed, because the height follows the platform: it is taller
 * under a toolbar than under the standard bar and has changed between releases.
 */
fun NativeWindow.measureTitleBar(): TitleBarMetrics {
    val out = StackValue.get<CFloatPointer>(20)
    windowTitleBar(WordFactory.pointer(view), out)
    val close = out.read(2)
    val zoom = out.read(3)
    return TitleBarMetrics(
        windowHeight = out.read(0),
        contentLayoutHeight = out.read(1),
        closeMinX = close.takeIf { it >= 0f },
        zoomMaxX = zoom.takeIf { it >= 0f },
        cornerRadius = out.read(4).takeIf { it >= 0f },
    )
}

/**
 * The strip of the window the title bar occupies and the room its three buttons take at the
 * leading edge, in points, or null while the window is between sizes. The gap in front of
 * the first button is mirrored after the last.
 */
fun NativeWindow.measureCaption(): CaptionMetrics? = measureTitleBar().caption()

/** The paths of the files last dragged over the window, one string, NUL between them. */
fun readDroppedPaths(): String {
    val buffer = StackValue.get<Pointer>(DROPPED_PATHS_BYTES)
    val length = droppedPaths(buffer, DROPPED_PATHS_BYTES)
    if (length <= 0) return ""
    val bytes = ByteArray(length)
    for (index in 0 until length) {
        bytes[index] = buffer.readByte(index)
    }
    return String(bytes, Charsets.UTF_8)
}

private const val DROPPED_PATHS_BYTES = 64 * 1024

/**
 * Puts a picture on the application, which is what the Dock and the switcher show.
 *
 * [rgba] is eight bits each of red, green, blue and alpha, the colour already multiplied by
 * the alpha, row after row with no padding.
 */
fun setApplicationIcon(rgba: ByteArray, width: Int, height: Int) {
    val holder = CTypeConversion.toCBytes(rgba)
    try {
        setIcon(holder.get(), width, height)
    } finally {
        holder.close()
    }
}

/**
 * Lets the window answer for itself for a moment.
 *
 * Called once a frame. The thread that draws is the thread the platform delivers on, so a
 * loop that never gave it a turn would be a window that heard nothing.
 */
fun pumpWindowEvents(seconds: Double) = pumpEvents(seconds)

/** True once the reader has closed the window. */
fun isWindowClosed(): Boolean = windowClosed() != 0

/**
 * Gives the application the menu bar every application on this platform has.
 *
 * Without one, the shortcuts a reader expects do nothing: command-Q does not quit and
 * command-C does not copy. The items are the system's own actions and are sent to
 * whatever holds focus, so no window is asked to implement them.
 */
fun installApplicationMenu(name: String) {
    val holder = CTypeConversion.toCString(name)
    try {
        installMenu(holder.get())
    } finally {
        holder.close()
    }
}

/** What is on the clipboard, or empty where it holds something that is not text. */
fun readClipboard(): String {
    val buffer = UnmanagedMemory.malloc<Pointer>(CLIPBOARD_BYTES)
    try {
        val length = clipboardRead(buffer, CLIPBOARD_BYTES)
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        for (index in 0 until length) {
            bytes[index] = buffer.readByte(index)
        }
        return String(bytes, Charsets.UTF_8)
    } finally {
        UnmanagedMemory.free(buffer)
    }
}

/** Puts text on the clipboard, replacing what was there. */
fun writeClipboard(text: String) {
    val holder = CTypeConversion.toCString(text)
    try {
        clipboardWrite(holder.get())
    } finally {
        holder.close()
    }
}

/**
 * How much of the clipboard a paste may carry.
 *
 * A paragraph rather than a book. What crosses is stack storage, and a field that is
 * handed a novel has a different problem from the one this is solving.
 */
private const val CLIPBOARD_BYTES = 4 * 1024 * 1024

/** What a pointer can look like, in the small set both sides agree on. */
object PointerShape {
    const val ARROW = 0
    const val HAND = 1
    const val TEXT = 2
    const val CROSSHAIR = 3
    const val RESIZE_LEFT_RIGHT = 4
    const val RESIZE_UP_DOWN = 5
}

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
private const val TEXT_OFFSET = 28
private const val TEXT_BYTES = 96
private const val EVENT_STRUCT_BYTES = 124

/**
 * Opens a window, or null where this machine has no Metal device.
 *
 * Null rather than an exception: a machine without Metal is not a mistake in this code,
 * and the caller has an older path it can take instead.
 */
fun openNativeWindow(title: String, width: Int, height: Int): NativeWindow? {
    val holder = CTypeConversion.toCString(title)
    try {
        // Four pointers, in the order the C struct declares them.
        val out = StackValue.get<Pointer>(WINDOW_STRUCT_BYTES)
        val status = openWindow(holder.get(), width, height, out)
        if (status == 1 || status == 2) {
            return null
        }
        check(status == 0) { "AppKit could not register the process for mouse input ($status)" }
        return NativeWindow(
            window = out.readWord<Pointer>(0).rawValue(),
            view = out.readWord<Pointer>(8).rawValue(),
            device = out.readWord<Pointer>(16).rawValue(),
            queue = out.readWord<Pointer>(24).rawValue(),
            layer = out.readWord<Pointer>(32).rawValue(),
        )
    } finally {
        holder.close()
    }
}

private const val WINDOW_STRUCT_BYTES = 40

/**
 * Gives the window an address to ask for a frame at, and takes it away again.
 *
 * The thread goes with the address because an entry point of this image cannot be called
 * without being told which thread of which isolate is calling. Written out at the call
 * because native-image accepts a word value in straight-line code inside one method only.
 * The address is [AppKitUpcalls.DRAW_FRAME], resolved while the image is built.
 */
fun registerFrameCallback() =
    setAppKitDrawCallback(AppKitUpcalls.DRAW_FRAME.functionPointer, org.graalvm.nativeimage.CurrentIsolate.getCurrentThread())

fun forgetFrameCallback() = setAppKitDrawCallback(
    WordFactory.nullPointer<CFunctionPointer>(),
    WordFactory.nullPointer<IsolateThread>(),
)

@CFunction("dxc_native_set_title")
private external fun nativeSetTitle(window: Pointer?, title: CCharPointer?)

@CFunction("dxc_native_set_min_size")
private external fun nativeSetMinSize(window: Pointer?, width: Int, height: Int)

@CFunction("dxc_native_set_visibility")
private external fun nativeSetVisibility(window: Pointer?, visibility: Int)

@CFunction("dxc_native_system_dark")
private external fun nativeSystemDark(): Int

@CFunction("dxc_native_set_ime_spot")
private external fun nativeSetImeSpot(x: Float, y: Float)

@CFunction("dxc_native_context_menu")
private external fun nativeContextMenu(view: Pointer?, items: CCharPointer?): Int

/** Puts a title on the window. */
fun NativeWindow.setTitle(title: String) {
    val holder = CTypeConversion.toCString(title)
    try {
        nativeSetTitle(WordFactory.pointer(window), holder.get())
    } finally {
        holder.close()
    }
}

/** Changes the smallest content size, in points. */
fun NativeWindow.setMinimumSize(width: Int, height: Int) =
    nativeSetMinSize(WordFactory.pointer(window), width, height)

/** Zero hides, one shows, two minimizes, three enters full screen. */
fun NativeWindow.setVisibilityCode(code: Int) =
    nativeSetVisibility(WordFactory.pointer(window), code)

/** True when the system is set to dark appearance. */
fun isSystemDark(): Boolean = nativeSystemDark() != 0

/** Tells the input method where the caret is, in points from the top left of the view. */
fun setNativeImeSpot(x: Float, y: Float) = nativeSetImeSpot(x, y)

/**
 * Shows a context menu at the pointer and answers with the id chosen, or -1.
 *
 * [packed] is one line per entry: id, enabled, separator after, label, separated by tabs.
 */
fun NativeWindow.showContextMenu(packed: String): Int {
    val holder = CTypeConversion.toCString(packed)
    try {
        return nativeContextMenu(WordFactory.pointer(view), holder.get())
    } finally {
        holder.close()
    }
}
