package org.thisisthepy.compose.window.windows

import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.ScreenPointer
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowBounds
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility

/**
 * One Win32 window as [Win32WindowPlatform] needs it. Every call is for that window alone;
 * a backend that shares a process-wide current window with others (the C one) chooses its
 * window before each call, so the platform never has to.
 *
 * Positions and sizes are screen points at the scale of the monitor the window is on. The
 * `take` calls report each change once and answer null while there is nothing new.
 */
interface Win32WindowBackend {
    /** True once the window has been destroyed. */
    val closed: Boolean

    fun measure(): WindowMeasurement
    fun drainEvents(): List<WindowEvent>
    fun setTitle(title: String)
    fun setMinimumSize(width: Int, height: Int)
    fun setVisibility(visibility: WindowVisibility)
    fun readClipboardText(): String?
    fun writeClipboardText(text: String)
    fun setImeSpot(x: Int, y: Int)

    /** Shows the menu and waits for the answer: the index in [items] chosen, or -1. */
    fun showContextMenu(items: List<ContextMenuItem>): Int
    fun performCaptionAction(action: CaptionAction)
    fun systemDark(): Boolean

    fun setPosition(x: Int, y: Int)
    fun setContentSize(width: Int, height: Int)
    fun bounds(): WindowBounds?

    /** True when the window has the keyboard now; false when the foreground lock refused and the window flashed. */
    fun focus(): Boolean
    val isActive: Boolean
    fun pointerOnScreen(): ScreenPointer?
    fun followStart(anchorX: Float, anchorY: Float): Boolean

    /** The activation change since the last call (true gained, false lost), or null. */
    fun takeActivation(): Boolean?

    /** The outer frame, once, if the window moved or changed size since the last call. */
    fun takeMoved(): WindowBounds?

    /** Whether a follow ended since the last call, and if so whether it was cancelled. */
    fun takeFollowEnd(): Boolean?

    /** True once for each request to close that the system made of this window. */
    fun takeCloseRequest(): Boolean

    /** Destroys the window; every other window and the process carry on. */
    fun destroy()

    /** Gives back what the window held. Nothing may call the backend after this. */
    fun release()
}

/** What the process holds once for all its windows. */
interface Win32ProcessBackend {
    /** Opens a window, or null where the machine cannot make one. */
    fun openWindow(config: WindowConfig): Win32WindowBackend?

    /** Serves the process's message queue for every window, waiting at most [seconds] for the first message. */
    fun pump(seconds: Double)
    fun postQuit(code: Int)

    /** The code of a quit the application posted, once it has come through the queue; null before. */
    fun quitPosted(): Int?
}

/**
 * The windows of one process: the platforms opened from it and the pump they share.
 *
 * The caller pumps once per turn, through [pump] or through [WindowPlatform.pump] on any one
 * window, and every window's events reach that window's listener. A second pump in the same
 * turn finds nothing more to deliver and does not wait again.
 *
 * Closing the last window does not end the process, and neither does closing any other: that
 * is the application's to decide, with [openCount] and [postQuit].
 */
class Win32Windows(private val process: Win32ProcessBackend) {
    private val platforms = ArrayList<Win32WindowPlatform>()

    /** A platform for a window that is not open yet. */
    fun newPlatform(): Win32WindowPlatform = Win32WindowPlatform(this, process)

    /** How many windows are open. */
    val openCount: Int get() = platforms.size

    /** Reads the operating system once and delivers to every window what it heard. */
    fun pump(timeoutMillis: Long) {
        process.pump(timeoutMillis / 1000.0)
        // A listener may open or close windows: deliver to the ones that were open.
        for (platform in platforms.toList()) platform.deliver()
    }

    /** Asks the message loop to end with [code]; read back with [quitPosted]. */
    fun postQuit(code: Int = 0) = process.postQuit(code)

    /** The code of a posted quit once it has come through the queue, otherwise null. */
    fun quitPosted(): Int? = process.quitPosted()

    internal fun register(platform: Win32WindowPlatform) {
        platforms.add(platform)
    }

    internal fun unregister(platform: Win32WindowPlatform) {
        platforms.remove(platform)
    }
}

/**
 * The Win32 [WindowPlatform]: one window, over a [Win32WindowBackend].
 *
 * The close button, Alt+F4 and the system menu ask the window to close; the answer is the
 * listener's. [WindowListener.onCloseRequested] returning false keeps the window and the
 * application closes it later with [close].
 *
 * Until the renderer's own window moves here, a window keeps the system's caption
 * ([caption] answers none and [startMove] and [startResize] leave the move to the system).
 */
class Win32WindowPlatform internal constructor(
    private val owner: Win32Windows,
    private val process: Win32ProcessBackend,
) : WindowPlatform {
    private var window: Win32WindowBackend? = null
    private var listener: WindowListener? = null
    private var reportedKey = false
    private var frameWanted = false

    override val name: String get() = "windows-native"

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        if (window != null) return false
        val opened = process.openWindow(config) ?: return false
        window = opened
        this.listener = listener
        reportedKey = false
        owner.register(this)
        return true
    }

    /**
     * Reads the operating system for every window of the process, not only this one, and
     * delivers to each its own listener. The caller pumps once per turn on any one window.
     */
    override fun pump(timeoutMillis: Long) {
        owner.pump(timeoutMillis)
    }

    /** What this window has to tell its listener since the last turn: events, then focus, move, follow and close. */
    internal fun deliver() {
        val current = window ?: return
        val target = listener ?: return
        for (event in current.drainEvents()) {
            target.onEvent(event)
            if (window !== current) return
        }
        val activation = current.takeActivation()
        if (activation != null && activation != reportedKey) {
            reportedKey = activation
            target.onFocusChanged(activation)
            if (window !== current) return
        }
        val moved = current.takeMoved()
        if (moved != null) {
            target.onMoved(moved)
            if (window !== current) return
        }
        val followEnd = current.takeFollowEnd()
        if (followEnd != null) {
            target.onFollowEnded(followEnd)
            if (window !== current) return
        }
        if (current.takeCloseRequest() && target.onCloseRequested()) {
            close()
        }
    }

    override fun measure(): WindowMeasurement = window?.measure() ?: WindowMeasurement(0, 0, 1f)

    override fun requestFrame() {
        frameWanted = true
    }

    /** True once since the last call if a frame was asked for. The surface that draws reads it. */
    fun takeFrameRequest(): Boolean {
        val wanted = frameWanted
        frameWanted = false
        return wanted
    }

    /** Reads the sizes at present time; the surface hands the frame to the swapchain (Win32NativeWindow.endFrame). */
    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        val size = measure()
        return FramePresentRecord(drawnWidth, drawnHeight, size.width, size.height)
    }

    override fun systemTheme(): SystemTheme =
        if (window?.systemDark() == true) SystemTheme.Dark else SystemTheme.Light

    override fun setTitle(title: String) {
        window?.setTitle(title)
    }

    override fun setMinimumSize(width: Int, height: Int) {
        window?.setMinimumSize(width, height)
    }

    override fun setVisibility(visibility: WindowVisibility) {
        window?.setVisibility(visibility)
    }

    override fun readClipboardText(): String? = window?.readClipboardText()

    override fun writeClipboardText(text: String) {
        window?.writeClipboardText(text)
    }

    override fun setImeSpot(x: Int, y: Int) {
        window?.setImeSpot(x, y)
    }

    override fun showContextMenu(items: List<ContextMenuItem>) {
        val current = window ?: return
        val index = current.showContextMenu(items)
        val id = if (index in items.indices) items[index].id else -1
        listener?.onContextMenuChosen(id)
    }

    override fun performCaptionAction(action: CaptionAction) {
        window?.performCaptionAction(action)
    }

    // ---- several windows in one process ------------------------------------------------------

    override val supportsMultipleWindows: Boolean get() = true

    override fun setPosition(x: Int, y: Int) {
        window?.setPosition(x, y)
    }

    override fun setContentSize(width: Int, height: Int) {
        window?.setContentSize(width, height)
    }

    override fun bounds(): WindowBounds? = window?.bounds()

    override fun focus() {
        // A refusal is a flash and nothing else: the activation, when it comes, is reported by
        // the pump from what the system said, so a refused focus reports no change.
        window?.focus()
    }

    override val isKey: Boolean get() = window?.isActive == true

    override fun pointerOnScreen(): ScreenPointer? = window?.pointerOnScreen()

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean =
        window?.followStart(anchorX, anchorY) == true

    override fun close() {
        val current = window ?: return
        window = null
        owner.unregister(this)
        current.destroy()
        current.release()
    }
}
