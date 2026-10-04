package org.thisisthepy.compose.window.graalvm.macos

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
 * The AppKit window as a [WindowPlatform], for the GraalVM native-image path.
 *
 * A thin layer: it opens the window, pumps AppKit and hands what the window wrote down to
 * the listener, and answers the platform's questions from the C side. Which events mean
 * what, the minimum size policy and the coalescing of frame requests are decided in
 * `common`; this class only reads and writes the OS.
 *
 * Frame requests may come from a worker thread, so they are a flag that [pump] callers
 * read with [takeFrameRequest]. The draw callback registered with
 * [registerFrameCallback] reaches [AppKitUpcallSlots.frame], which the owner sets to run
 * its own frame.
 */
class AppKitWindowPlatform : WindowPlatform {
    override val name: String = "appkit-graalvm"

    private var window: NativeWindow? = null
    private var listener: WindowListener? = null
    private var lastTheme: SystemTheme = SystemTheme.Light
    private var lastScale: Float = 0f
    private val frameRequested = AtomicBoolean(false)
    private var closeAnswered = false

    /** The window the platform opened, for the surface that draws into it. */
    val nativeWindow: NativeWindow? get() = window

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        configureNativeWindow(
            resizable = config.resizable,
            minWidth = config.minWidth,
            minHeight = config.minHeight,
            systemChrome = config.decorated,
            backdrop = config.transparent,
        )
        val opened = openNativeWindow(config.title, config.width, config.height) ?: return false
        window = opened
        this.listener = listener
        lastTheme = systemTheme()
        lastScale = opened.measure().scale
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val current = window ?: return
        val sink = listener ?: return
        pumpWindowEvents(timeoutMillis / 1000.0)
        for (event in drainWindowEvents()) {
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
        if (isWindowClosed() && !closeAnswered) {
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

    override fun systemTheme(): SystemTheme = if (isSystemDark()) SystemTheme.Dark else SystemTheme.Light

    override fun setTitle(title: String) {
        window?.setTitle(title)
    }

    override fun setMinimumSize(width: Int, height: Int) {
        window?.setMinimumSize(width, height)
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

    override fun readClipboardText(): String? = readClipboard().ifEmpty { null }

    override fun writeClipboardText(text: String) = writeClipboard(text)

    override fun setImeSpot(x: Int, y: Int) = setNativeImeSpot(x.toFloat(), y.toFloat())

    override fun showContextMenu(items: List<ContextMenuItem>) {
        val current = window ?: return
        val chosen = current.showContextMenu(packMenu(items))
        listener?.onContextMenuChosen(chosen)
    }

    override fun close() {
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
