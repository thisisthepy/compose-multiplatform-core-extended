package org.thisisthepy.compose.window.graalvm.linux

import org.graalvm.nativeimage.UnmanagedMemory
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory
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
    private var eventBuffer: Pointer? = null
    private var windowBuffer: Pointer? = null
    private var textBuffer: Pointer? = null
    private var sizeBuffer: Pointer? = null
    private var frameWanted = false
    private var lastScale = 0f
    private var lastTheme: SystemTheme? = null

    /** Whether a frame was asked for since the last [present]; [requestFrame] coalesces. */
    val frameRequested: Boolean get() = frameWanted

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        this.listener = listener
        X11Natives.windowConfigure(1, config.minWidth, config.minHeight, if (config.decorated) 1 else 0, 0)
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
        windowBuffer = out
        windowPointer = out.readWord<Pointer>(0).rawValue()
        queuePointer = out.readWord<Pointer>(24).rawValue()
        eventBuffer = UnmanagedMemory.calloc<Pointer>(X11Layout.EVENT_BYTES)
        textBuffer = UnmanagedMemory.calloc<Pointer>(TEXT_CAPACITY)
        sizeBuffer = UnmanagedMemory.calloc<Pointer>(12)
        lastScale = measure().scale
        lastTheme = systemTheme()
        return true
    }

    override fun pump(timeoutMillis: Long) {
        X11Natives.pump(timeoutMillis.coerceAtLeast(0) / 1000.0)
        val record = eventBuffer ?: return
        while (X11Natives.pollEvent(record) != 0) {
            if (record.readInt(0) == EVENT_TEXT_PASTE) {
                takePaste()?.let { listener?.onEvent(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, it)) }
                continue
            }
            listener?.onEvent(read(record))
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
        val buffer = textBuffer ?: return null
        val length = X11Natives.takePaste(buffer as CCharPointer, TEXT_CAPACITY)
        if (length <= 0) return null
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.readByte(i)
        return bytes.decodeToString()
    }

    private fun read(record: Pointer): WindowEvent {
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
        val size = sizeBuffer ?: return WindowMeasurement(0, 0, 1f)
        X11Natives.windowSize(
            WordFactory.pointer<Pointer>(windowPointer),
            size as CIntPointer,
            size.add(4) as CIntPointer,
            size.add(8) as CFloatPointer,
        )
        return WindowMeasurement(size.readInt(0), size.readInt(4), size.readFloat(8))
    }

    override fun requestFrame() {
        frameWanted = true
    }

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
        val buffer = textBuffer ?: return null
        val length = X11Natives.clipboardRead(buffer as CCharPointer, TEXT_CAPACITY)
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
        listOf(eventBuffer, windowBuffer, textBuffer, sizeBuffer).forEach { it?.let(UnmanagedMemory::free) }
        eventBuffer = null
        windowBuffer = null
        textBuffer = null
        sizeBuffer = null
    }

    private companion object {
        // 4 MiB, the most one X11 property read returns: a longer clipboard answers empty.
        const val TEXT_CAPACITY = 4 * 1024 * 1024
        const val ACTION_CLOSE = 2
        const val EVENT_TEXT_PASTE = 13
    }
}
