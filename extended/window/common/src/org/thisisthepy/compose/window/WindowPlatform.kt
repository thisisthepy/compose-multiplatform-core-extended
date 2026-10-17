package org.thisisthepy.compose.window

/**
 * Where a platform layer sends what it hears.
 *
 * Everything arrives on the thread that runs [WindowPlatform.pump], which is the Renderer
 * UI thread. Nothing is queued across threads and nothing needs a lock.
 */
interface WindowListener {
    /** One input or lifecycle event, in the order the operating system produced it. */
    fun onEvent(event: WindowEvent)

    /** The system appearance changed. */
    fun onThemeChanged(theme: SystemTheme) {}

    /** The window moved to a display with another scale, or the scale changed. */
    fun onScaleChanged(scale: Float) {}

    /** The user chose [id] from a menu opened by [WindowPlatform.showContextMenu]; -1 if dismissed. */
    fun onContextMenuChosen(id: Int) {}

    /** The window manager asked the window to close. Return true to let it close. */
    fun onCloseRequested(): Boolean = true
}

/**
 * The operating system layer under one window.
 *
 * Both paths implement it: `native/<os>` through Kotlin/Native interop and `graalvm/<os>`
 * through C and `@CFunction` wrappers. The platform does the OS work (create, pump, present,
 * clipboard, input method position); the common module decides policy. A platform never
 * calls the policy rules the other way round: it reports and applies.
 *
 * The interface is pull-friendly for the GraalVM path and push-friendly for Kotlin/Native.
 * A GraalVM layer reads its C event queue inside [pump] and forwards each record to the
 * listener; a Kotlin/Native layer forwards from its own callbacks inside [pump]. In both
 * cases [pump] is the one place the operating system is read, and [WindowEventLog.read]
 * guards it against being re-entered from a frame drawn inside a resize.
 */
interface WindowPlatform {
    /** A short name for logs and parity tables, such as `macos-native` or `x11-graalvm`. */
    val name: String

    /** Creates and shows the window. Returns false if the operating system refused. */
    fun open(config: WindowConfig, listener: WindowListener): Boolean

    /** Reads whatever the operating system has, waiting at most [timeoutMillis]. */
    fun pump(timeoutMillis: Long)

    /** The drawable's size and scale right now. */
    fun measure(): WindowMeasurement

    /** Asks for one frame. Any number of calls before the frame coalesce into one. */
    fun requestFrame()

    /**
     * Hands the frame just drawn to the display. The result is what the parity resize check
     * counts: the drawn size and the window size at this moment.
     */
    fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord

    /** The system's current appearance. */
    fun systemTheme(): SystemTheme

    fun setTitle(title: String)

    /** Applies a minimum size, in points, to the operating system. */
    fun setMinimumSize(width: Int, height: Int)

    fun setVisibility(visibility: WindowVisibility)

    /** The text on the system clipboard, or null where there is none. */
    fun readClipboardText(): String?

    fun writeClipboardText(text: String)

    /** Where the input method puts its candidate window, in window pixels. */
    fun setImeSpot(x: Int, y: Int)

    /** Shows a context menu at the pointer. The answer arrives at [WindowListener.onContextMenuChosen]. */
    fun showContextMenu(items: List<ContextMenuItem>)

    /** Destroys the window and everything the layer holds for it. */
    fun close()
}
