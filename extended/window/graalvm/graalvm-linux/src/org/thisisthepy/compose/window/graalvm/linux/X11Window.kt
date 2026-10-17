package org.thisisthepy.compose.window.graalvm.linux

import org.graalvm.nativeimage.UnmanagedMemory
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.ResizeStats
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility
import org.thisisthepy.compose.window.X11Events

/**
 * The X11 window of the GraalVM path: [WindowPlatform] over `x11_window.c`.
 *
 * Every decision (minimum size, event meaning, frame coalescing) belongs to the common
 * module. This class opens the window, reads what C queued, and hands it to the listener.
 * Calls go down through [X11Natives]. The one call that comes back up is the frame painter
 * in [X11Upcalls], used while a resize is being handled.
 */
class X11Window : WindowPlatform {
    override val name: String = "graalvm-linux-x11"

    private var listener: WindowListener? = null
    private var windowPointer: Long = 0
    private var queuePointer: Long = 0
    // Addresses, with zero for none. A word value is never compared with null or put in a
    // collection here, because native-image rejects both: a word is not an object.
    private var eventBuffer: Long = 0
    private var windowBuffer: Long = 0
    private var textBuffer: Long = 0
    private var sizeBuffer: Long = 0
    private var frameWanted = false
    private var lastScale = 0f
    private var lastTheme: SystemTheme? = null

    // What the C window records, a key as a keysym, a state word and the text it typed and the
    // preedit callbacks as they came, turned into events by the same code the Kotlin/Native X11
    // window uses. A click while composing ends the composition with what the method kept.
    private val x11Events = X11Events(::resetInputMethod)

    /**
     * What a resize showed: every size the window took and every frame presented, judged
     * against the window's size at that moment. Printed by the host as [ResizeStats.report]
     * when `DXC_REPORT_RESIZE` is set, which is also when the size is asked of the server at
     * each present; without it the last size event answers, which costs nothing.
     */
    val resizeStats = ResizeStats()
    private val strictResizeAccounting = System.getenv("DXC_REPORT_RESIZE") != null || resizeDetailWanted()
    private val resizeDetail = resizeDetailWanted()

    private fun resizeDetailWanted() = System.getenv("DXC_REPORT_RESIZE_DETAIL") != null
    private var countedSerial = 0L

    /** Whether a frame was asked for since the last [present]; [requestFrame] coalesces. */
    val frameRequested: Boolean get() = frameWanted

    /** Whether the window may be resized, set before [open]. */
    var resizable: Boolean = true

    /** Whether the window shows what is behind it, set before [open]. */
    var backdrop: Boolean = false

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        this.listener = listener
        // Either side may say no: the property set before open, or the config.
        X11Natives.windowConfigure(
            if (resizable && config.resizable) 1 else 0, config.minWidth, config.minHeight,
            if (config.decorated) 1 else 0, if (backdrop) 1 else 0,
        )
        val title = CTypeConversion.toCString(config.title)
        val out = UnmanagedMemory.calloc<Pointer>(X11Layout.WINDOW_BYTES)
        try {
            if (X11Natives.windowOpen(title.get(), config.width, config.height, out) != 0) {
                UnmanagedMemory.free(out)
                return false
            }
        } finally {
            title.close()
        }
        windowBuffer = out.rawValue()
        windowPointer = out.readWord<Pointer>(0).rawValue()
        queuePointer = out.readWord<Pointer>(24).rawValue()
        eventBuffer = UnmanagedMemory.calloc<Pointer>(X11Layout.EVENT_BYTES).rawValue()
        textBuffer = UnmanagedMemory.calloc<Pointer>(TEXT_CAPACITY).rawValue()
        sizeBuffer = UnmanagedMemory.calloc<Pointer>(12).rawValue()
        lastScale = measure().scale
        lastTheme = systemTheme()
        return true
    }

    override fun pump(timeoutMillis: Long) {
        X11Natives.pump(timeoutMillis.coerceAtLeast(0) / 1000.0)
        if (eventBuffer == 0L) return
        while (X11Natives.pollEvent(WordFactory.pointer<Pointer>(eventBuffer)) != 0) {
            if (WordFactory.pointer<Pointer>(eventBuffer).readInt(0) == EVENT_TEXT_PASTE) {
                takePaste()?.let { listener?.onEvent(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, it)) }
                continue
            }
            for (event in x11Events.heard(read(eventBuffer))) listener?.onEvent(event)
        }
        val scale = measure().scale
        if (scale != lastScale) {
            lastScale = scale
            listener?.onScaleChanged(scale)
        }
        val theme = systemTheme()
        if (theme != lastTheme) {
            lastTheme = theme
            listener?.onThemeChanged(theme)
        }
        if (X11Natives.windowClosed() != 0 && listener?.onCloseRequested() != false) {
            close()
        }
    }

    /**
     * Ends the wait in [pump] early, so the loop turns now rather than at the end of the
     * frame's wait. Any thread may call it, before the window opens and after it closes too:
     * it is how work sent to the window's thread from another one is noticed at once.
     */
    override fun wake() = X11Natives.wake()

    /** Whether the window has the keyboard, as the server last said. */
    val isFocused: Boolean get() = X11Natives.windowFocused() != 0

    /** Where the window's top left corner is on the screen, in pixels. */
    fun originOnScreen(): Pair<Int, Int> {
        if (sizeBuffer == 0L) return 0 to 0
        val origin = WordFactory.pointer<Pointer>(sizeBuffer)
        X11Natives.windowOrigin(
            WordFactory.pointer<CIntPointer>(sizeBuffer),
            WordFactory.pointer<CIntPointer>(sizeBuffer + 4),
        )
        return origin.readInt(0) to origin.readInt(4)
    }

    /** Ends the input method's composition where it stands and answers what it kept. */
    private fun resetInputMethod(): String {
        if (textBuffer == 0L) return ""
        val buffer = WordFactory.pointer<Pointer>(textBuffer)
        val length = X11Natives.imeReset(WordFactory.pointer<CCharPointer>(textBuffer), IME_RESET_BYTES)
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.readByte(i)
        return bytes.decodeToString()
    }

    /** The whole of a paste the window is holding, so that it lands as one edit. */
    private fun takePaste(): String? {
        if (textBuffer == 0L) return null
        val buffer = WordFactory.pointer<Pointer>(textBuffer)
        val length = X11Natives.takePaste(WordFactory.pointer<CCharPointer>(textBuffer), TEXT_CAPACITY)
        if (length <= 0) return null
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.readByte(i)
        return bytes.decodeToString()
    }

    // Takes the address, not a word: native-image rejects a word passed as an argument to a
    // method that is not inlined, so each read makes the word where it uses it.
    private fun read(address: Long): WindowEvent {
        val record = WordFactory.pointer<Pointer>(address)
        val bytes = ByteArray(X11Layout.EVENT_TEXT_BYTES)
        var length = 0
        while (length < bytes.size) {
            val b = record.readByte(28 + length)
            if (b.toInt() == 0) break
            bytes[length++] = b
        }
        return WindowEvent(
            kind = record.readInt(0),
            x = record.readFloat(4),
            y = record.readFloat(8),
            buttons = record.readInt(12),
            modifiers = record.readInt(16),
            keyCode = record.readInt(20),
            codePoint = record.readInt(24),
            text = bytes.decodeToString(0, length),
        )
    }

    override fun measure(): WindowMeasurement {
        if (sizeBuffer == 0L) return WindowMeasurement(0, 0, 1f)
        val size = WordFactory.pointer<Pointer>(sizeBuffer)
        X11Natives.windowSize(
            WordFactory.pointer<Pointer>(windowPointer),
            WordFactory.pointer<CIntPointer>(sizeBuffer),
            WordFactory.pointer<CIntPointer>(sizeBuffer + 4),
            WordFactory.pointer<CFloatPointer>(sizeBuffer + 8),
        )
        return WindowMeasurement(size.readInt(0), size.readInt(4), size.readFloat(8))
    }

    override fun requestFrame() {
        frameWanted = true
    }

    /**
     * The monotonic clock when the frame is started.
     *
     * Xlib says nothing about when the server will show a frame: a swap is queued and the
     * compositor, if there is one, presents it on its own schedule. The time a frame is
     * started is the closest answer this window has, and it moves at the speed of the wall
     * clock whatever the refresh rate. `System.nanoTime` reads `CLOCK_MONOTONIC` on Linux,
     * the clock Rust's `Instant` reads there too.
     */
    override fun frameTimeNanos(): Long = System.nanoTime()

    /**
     * Swaps the frame just drawn onto the screen and reports it. The window size is read
     * after the swap, so a frame drawn for an older size shows up as a mismatch.
     */
    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        frameWanted = false
        X11Natives.frameEnd(WordFactory.pointer<Pointer>(queuePointer))
        val window = measure()
        countSizes(window)
        val (width, height) = if (strictResizeAccounting) serverSize() else window.width to window.height
        resizeStats.presentAgainst(drawnWidth, drawnHeight, width, height)
        if (resizeDetail) {
            System.err.println(
                "dxc resize detail: t=${System.nanoTime() / 1000}us present drawn=${drawnWidth}x$drawnHeight " +
                    "configured=${window.width}x${window.height} server=${width}x$height",
            )
        }
        return FramePresentRecord(drawnWidth, drawnHeight, width, height)
    }

    /**
     * Takes the window through the sizes a drag would, from its size now to [toWidth] by
     * [toHeight] in [steps], pausing [pauseMicros] on each, for measuring. The frame for each
     * size is drawn inside its handling, through the frame painter. The count starts again.
     */
    fun scriptedResize(toWidth: Int, toHeight: Int, steps: Int, pauseMicros: Int) {
        if (windowPointer == 0L) return
        val from = measure()
        resizeStats.reset()
        countedSerial = X11Natives.resizeSerial()
        X11Natives.debugResize(
            WordFactory.pointer<Pointer>(windowPointer), WordFactory.nullPointer<Pointer>(),
            from.width, from.height, toWidth, toHeight, steps, pauseMicros,
        )
        countSizes(measure())
    }

    /** The resize report line, with any size the window took since the last frame counted. */
    fun resizeReport(): String {
        countSizes(measure())
        return resizeStats.report()
    }

    // Every size C saw since the last count is a step, the latest at the size the window has.
    // Several between two frames means all but the last were left without one.
    private fun countSizes(window: WindowMeasurement) {
        val serial = X11Natives.resizeSerial()
        while (countedSerial < serial) {
            resizeStats.step(window.width, window.height)
            countedSerial++
        }
    }

    private fun serverSize(): Pair<Int, Int> {
        if (sizeBuffer == 0L) return 0 to 0
        val size = WordFactory.pointer<Pointer>(sizeBuffer)
        X11Natives.windowServerSize(
            WordFactory.pointer<CIntPointer>(sizeBuffer),
            WordFactory.pointer<CIntPointer>(sizeBuffer + 4),
        )
        return size.readInt(0) to size.readInt(4)
    }

    /** Read the way the Kotlin/Native X11 window reads it: from `GTK_THEME`. */
    override fun systemTheme(): SystemTheme = X11Theme.fromGtkTheme(System.getenv("GTK_THEME"))

    /**
     * Makes the window's GL context current for the frame about to be drawn. False where
     * there is nothing to draw into, which is a closed window or one with no pixels; the
     * frame is then skipped rather than waited for.
     */
    fun beginFrame(): Boolean = X11Natives.frameBegin(WordFactory.pointer<Pointer>(windowPointer)) == 0

    /**
     * Sets the shape of the pointer over the window. [shape] is one of the small numbers the
     * renderer and `x11_window.c` agree on: 0 arrow, 1 hand, 2 text, 3 crosshair, 4 resize
     * left and right, 5 resize up and down.
     */
    fun setCursor(shape: Int) = X11Natives.setCursor(shape)

    /**
     * Hands the window what it would tell a reader who cannot see it, as the records
     * `x11_window.c` holds for an AT-SPI bridge to read. Capped at [X11Layout.MAX_ELEMENTS].
     */
    fun setAccessibility(elements: List<AccessibleElement>) {
        val capped = if (elements.size > X11Layout.MAX_ELEMENTS) elements.take(X11Layout.MAX_ELEMENTS) else elements
        val records = UnmanagedMemory.calloc<Pointer>(X11Layout.MAX_ELEMENTS * X11Layout.ELEMENT_BYTES)
        try {
            for ((index, element) in capped.withIndex()) {
                val at = index * X11Layout.ELEMENT_BYTES
                records.writeInt(at, element.role)
                records.writeFloat(at + 4, element.x)
                records.writeFloat(at + 8, element.y)
                records.writeFloat(at + 12, element.width)
                records.writeFloat(at + 16, element.height)
                val bytes = element.label.encodeToByteArray()
                var length = 0
                while (length < bytes.size && length < X11Layout.EVENT_TEXT_BYTES - 1) {
                    records.writeByte(at + X11Layout.ELEMENT_LABEL_OFFSET + length, bytes[length])
                    length++
                }
                records.writeByte(at + X11Layout.ELEMENT_LABEL_OFFSET + length, 0)
            }
            X11Natives.setAccessibility(records, capped.size, WordFactory.pointer<Pointer>(windowPointer))
        } finally {
            UnmanagedMemory.free(records)
        }
    }

    override fun setTitle(title: String) {
        val holder = CTypeConversion.toCString(title)
        try {
            X11Natives.setTitle(holder.get())
        } finally {
            holder.close()
        }
    }

    override fun setMinimumSize(width: Int, height: Int) = X11Natives.setMinSize(width, height)

    override fun setVisibility(visibility: WindowVisibility) =
        X11Natives.setVisibility(X11Visibility.code(visibility))

    override fun readClipboardText(): String? {
        if (textBuffer == 0L) return null
        val buffer = WordFactory.pointer<Pointer>(textBuffer)
        val length = X11Natives.clipboardRead(WordFactory.pointer<CCharPointer>(textBuffer), TEXT_CAPACITY)
        if (length <= 0) return null
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.readByte(i)
        return bytes.decodeToString()
    }

    /** The paths of the files last dropped on the window, one string, NUL between them. */
    fun readDroppedPaths(): String {
        if (textBuffer == 0L) return ""
        val buffer = WordFactory.pointer<Pointer>(textBuffer)
        val length = X11Natives.droppedPaths(WordFactory.pointer<CCharPointer>(textBuffer), DROPPED_PATHS_BYTES)
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.readByte(i)
        return bytes.decodeToString()
    }

    override fun writeClipboardText(text: String) {
        val holder = CTypeConversion.toCString(text)
        try {
            X11Natives.clipboardWrite(holder.get())
        } finally {
            holder.close()
        }
    }

    override fun setImeSpot(x: Int, y: Int) = X11Natives.setImeSpot(x.toFloat(), y.toFloat())

    /**
     * Bare X11 has no native context menu, and the Kotlin/Native X11 window does not draw
     * one either: the items are not shown and the listener hears the menu was dismissed, so
     * a host that wants a menu draws it in its own scene.
     */
    override fun showContextMenu(items: List<ContextMenuItem>) {
        listener?.onContextMenuChosen(-1)
    }

    override fun close() {
        X11Upcalls.clearFramePainter()
        X11Natives.windowAction(ACTION_CLOSE)
        X11Natives.pump(0.0)
        freeBuffer(eventBuffer)
        freeBuffer(windowBuffer)
        freeBuffer(textBuffer)
        freeBuffer(sizeBuffer)
        eventBuffer = 0
        windowBuffer = 0
        textBuffer = 0
        sizeBuffer = 0
    }

    private fun freeBuffer(address: Long) {
        if (address != 0L) UnmanagedMemory.free(WordFactory.pointer<Pointer>(address))
    }

    private companion object {
        // 4 MiB, the most one X11 property read returns: a longer clipboard answers empty.
        const val TEXT_CAPACITY = 4 * 1024 * 1024
        const val ACTION_CLOSE = 2
        const val DROPPED_PATHS_BYTES = 64 * 1024
        /** `DXC_EVENT_TEXT_PASTE`: not a shared event kind, so outside their numbers. */
        const val EVENT_TEXT_PASTE = 64
        const val IME_RESET_BYTES = 4096
    }
}
