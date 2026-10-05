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
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility

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

    /** Whether a frame was asked for since the last [present]; [requestFrame] coalesces. */
    val frameRequested: Boolean get() = frameWanted

    /** Whether the window may be resized, set before [open]. */
    var resizable: Boolean = true

    /** Whether the window shows what is behind it, set before [open]. */
    var backdrop: Boolean = false

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        this.listener = listener
        X11Natives.windowConfigure(
            if (resizable) 1 else 0, config.minWidth, config.minHeight, if (config.decorated) 1 else 0,
            if (backdrop) 1 else 0,
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
            listener?.onEvent(read(eventBuffer))
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
     * Makes the window's GL framebuffer current for the frame about to be drawn. False where
     * there is nothing to draw into, which is a closed window. A frame that was begun is
     * ended by [present].
     */
    fun beginFrame(): Boolean = X11Natives.frameBegin(WordFactory.pointer<Pointer>(windowPointer)) == 0

    /**
     * Swaps the frame just drawn onto the screen and reports it. The window size is read
     * after the swap, so a frame drawn for an older size shows up as a mismatch.
     */
    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        frameWanted = false
        X11Natives.frameEnd(WordFactory.pointer<Pointer>(queuePointer))
        val window = measure()
        return FramePresentRecord(drawnWidth, drawnHeight, window.width, window.height)
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
        const val EVENT_TEXT_PASTE = 13
    }
}
