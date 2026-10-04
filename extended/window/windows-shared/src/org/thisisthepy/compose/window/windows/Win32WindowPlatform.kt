package org.thisisthepy.compose.window.windows

import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shared Win32 window as a [WindowPlatform], for the GraalVM native-image path.
 *
 * A thin layer: it opens the window, pumps Windows and hands what the window wrote down to
 * the listener, and answers the platform's questions from the C side. Which events mean
 * what, the minimum size policy and the coalescing of frame requests are decided in
 * `common`; this class only reads and writes the OS.
 *
 * Frame requests may come from a worker thread, so they are a flag that the frame loop
 * reads with [takeFrameRequest]. A frame asked for from inside a message (a drag of the
 * window's edge) reaches [Win32UpcallSlots.frame], which the owner sets to run its frame.
 */
class Win32WindowPlatform : WindowPlatform {
    override val name: String = "win32-shared-graalvm"

    private var window: Win32NativeWindow? = null
    private var listener: WindowListener? = null
    private var lastTheme: SystemTheme = SystemTheme.Light
    private var lastScale: Float = 0f
    private val frameRequested = AtomicBoolean(false)
    private var closeAnswered = false
    private var decorated = true
    private var transparent = false

    /** The window the platform opened, for the surface that draws into it. */
    val nativeWindow: Win32NativeWindow? get() = window

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        decorated = config.decorated
        transparent = config.transparent
        configureWin32Window(
            resizable = true,
            minWidth = config.minWidth,
            minHeight = config.minHeight,
            systemChrome = config.decorated,
            backdrop = config.transparent,
        )
        val opened = openWin32Window(config.title, config.width, config.height) ?: return false
        window = opened
        this.listener = listener
        lastTheme = systemTheme()
        lastScale = opened.measure().scale
        registerWin32FrameCallback(true)
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val current = window ?: return
        val sink = listener ?: return
        pumpWin32(timeoutMillis / 1000.0)
        for (event in drainWin32Events()) {
            sink.onEvent(event)
        }
        val theme = systemTheme()
        if (theme != lastTheme) {
            lastTheme = theme
            sink.onThemeChanged(theme)
        }
        val scale = current.measure().scale
        if (scale != lastScale) {
            lastScale = scale
            sink.onScaleChanged(scale)
        }
        if (isWin32WindowClosed() && !closeAnswered) {
            closeAnswered = true
            sink.onCloseRequested()
        }
    }

    override fun measure(): WindowMeasurement =
        checkNotNull(window) { "the window is not open" }.measure()

    override fun requestFrame() {
        frameRequested.set(true)
    }

    /** True once if a frame was asked for since the last call. */
    fun takeFrameRequest(): Boolean = frameRequested.getAndSet(false)

    /**
     * Puts the frame the surface just painted on the screen and answers with the size it
     * was drawn at beside the size the window has at this moment.
     */
    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        val current = checkNotNull(window) { "the window is not open" }
        current.endFrame()
        val now = current.measure()
        return FramePresentRecord(drawnWidth, drawnHeight, now.width, now.height)
    }

    override fun systemTheme(): SystemTheme =
        if (isWin32SystemDark()) SystemTheme.Dark else SystemTheme.Light

    override fun setTitle(title: String) {
        window?.setTitle(title)
    }

    override fun setMinimumSize(width: Int, height: Int) {
        // The window reads its minimum from what it was configured with, so the new
        // minimum is configured again; the next size query applies it.
        configureWin32Window(
            resizable = true,
            minWidth = width,
            minHeight = height,
            systemChrome = decorated,
            backdrop = transparent,
        )
    }

    override fun setVisibility(visibility: WindowVisibility) {
        window?.setVisibilityCode(
            when (visibility) {
                WindowVisibility.Hidden -> 0
                WindowVisibility.Visible -> 1
                WindowVisibility.Minimized -> 2
                WindowVisibility.Fullscreen -> 3
            },
        )
    }

    override fun readClipboardText(): String? = readWin32Clipboard().ifEmpty { null }

    override fun writeClipboardText(text: String) = writeWin32Clipboard(text)

    override fun setImeSpot(x: Int, y: Int) = setWin32ImeSpot(x.toFloat(), y.toFloat())

    override fun showContextMenu(items: List<ContextMenuItem>) {
        val current = window ?: return
        listener?.onContextMenuChosen(current.showContextMenu(packMenu(items)))
    }

    override fun close() {
        registerWin32FrameCallback(false)
        window = null
        listener = null
    }

    companion object {
        /** The wire form the C side parses: a tab-separated line per item. */
        fun packMenu(items: List<ContextMenuItem>): String =
            items.joinToString("\n") {
                "${it.id}\t${if (it.enabled) 1 else 0}\t${if (it.separatorAfter) 1 else 0}\t" +
                    it.label.replace('\t', ' ').replace('\n', ' ')
            }
    }
}
