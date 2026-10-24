package org.thisisthepy.compose.window.graalvm.macos

import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.MacosAxSnapshot
import org.thisisthepy.compose.window.MacosTitleBar
import org.thisisthepy.compose.window.macosAccessibleContentFindings
import org.thisisthepy.compose.window.macosChromeFindings
import org.thisisthepy.compose.window.parseMacosAxSnapshot
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.macosCaption
import org.thisisthepy.compose.window.editMenuItems
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
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
    private var titleBar: MacosTitleBar = MacosTitleBar.Normal
    private var lastCaption: WindowCaption = WindowCaption.None
    private var readerWasListening = false
    private val idleSoakSeconds: Int =
        System.getenv("DXC_IDLE_SOAK_SECONDS")?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    private var openedAtMillis: Long = 0L
    private var idleStartedAtMillis: Long = 0L
    private var lastIdleSampleElapsed: Int = -1
    private var idleTyped = false
    private var framesPresented: Long = 0L

    /** The window the platform opened, for the surface that draws into it. */
    val nativeWindow: NativeWindow? get() = window

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        val bar = MacosTitleBar.of(config)
        titleBar = bar
        configureNativeWindow(
            resizable = config.resizable,
            minWidth = config.minWidth,
            minHeight = config.minHeight,
            systemChrome = config.chrome == WindowChrome.System,
            backdrop = config.transparent,
        )
        // The rule both macOS windows share, so this one has the Kotlin/Native one's
        // corners, buttons and content top for the same configuration.
        configureNativeWindowChrome(
            fullSizeContentView = bar.fullSizeContentView,
            titlebarAppearsTransparent = bar.titlebarAppearsTransparent,
            titleHidden = bar.titleHidden,
            unifiedToolbar = bar.unifiedToolbar,
        )
        setTextMenu(packMenu(textEditMenu()))
        val opened = openNativeWindow(config.title, config.width, config.height) ?: return false
        window = opened
        this.listener = listener
        lastTheme = systemTheme()
        lastScale = opened.measure().scale
        openedAtMillis = System.currentTimeMillis()
        idleStartedAtMillis = 0L
        lastIdleSampleElapsed = -1
        idleTyped = false
        framesPresented = 0L
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val current = window ?: return
        val sink = listener ?: return
        pumpWindowEvents(timeoutMillis / 1000.0)
        for (event in drainWindowEvents()) {
            deliver(event, sink)
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
        // A reader started listening: the scene describes after a frame, so it is given one.
        val listening = isAccessibilityActive()
        if (listening && !readerWasListening) requestFrame()
        readerWasListening = listening
        if (isWindowClosed() && !closeAnswered) {
            closeAnswered = true
            sink.onCloseRequested()
        }
        if (idleSoakSeconds > 0 && !closeAnswered) {
            stepIdleSoak(current, sink)
        }
    }

    override fun measure(): WindowMeasurement =
        checkNotNull(window) { "the window is not open" }.measure()

    override fun requestFrame() {
        frameRequested.set(true)
    }

    /** The display link's next presentation, from the C side. */
    override fun frameTimeNanos(): Long = org.thisisthepy.compose.window.graalvm.macos.frameTimeNanos()

    /** True once if a frame was asked for since the last call. */
    fun takeFrameRequest(): Boolean = frameRequested.getAndSet(false)

    /**
     * Puts the frame the surface just painted on the screen and answers with the size it
     * was drawn at beside the size the window has at this moment.
     */
    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        val current = checkNotNull(window) { "the window is not open" }
        current.endFrame()
        framesPresented++
        val now = current.measure()
        return FramePresentRecord(drawnWidth, drawnHeight, now.width, now.height)
    }

    private fun stepIdleSoak(current: NativeWindow, sink: WindowListener) {
        val now = System.currentTimeMillis()
        val sinceOpen = now - openedAtMillis
        if (!idleTyped && sinceOpen >= 1_500L && System.getenv("DXC_SYNTH") != "none") {
            idleTyped = true
            val scale = current.measure().scale.coerceAtLeast(1f)
            val x = 80f * scale
            val y = 80f * scale
            sink.onEvent(WindowEvent(WindowEvent.POINTER_DOWN, x, y, 1, 0, 0, 0, ""))
            sink.onEvent(WindowEvent(WindowEvent.POINTER_UP, x, y, 0, 0, 0, 0, ""))
            sink.onEvent(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "abcde"))
        }
        if (sinceOpen < 8_000L) return
        if (idleStartedAtMillis == 0L) {
            System.gc()
            idleStartedAtMillis = now
            lastIdleSampleElapsed = 0
            logIdleSoak("start", 0, current)
            idleSoakBreakdown(0, isEnd = false)
            return
        }
        val elapsed = ((now - idleStartedAtMillis) / 1000L).toInt()
        val step = (elapsed / 10) * 10
        if (step > lastIdleSampleElapsed && elapsed < idleSoakSeconds) {
            lastIdleSampleElapsed = step
            logIdleSoak("sample", step, current)
        }
        if (elapsed >= idleSoakSeconds) {
            System.gc()
            logIdleSoak("end", idleSoakSeconds, current)
            idleSoakBreakdown(idleSoakSeconds, isEnd = true)
            System.err.println("compose-rust: idle-soak done seconds=$idleSoakSeconds")
            closeAnswered = true
            sink.onCloseRequested()
        }
    }

    private fun logIdleSoak(phase: String, elapsedS: Int, current: NativeWindow) {
        val runtime = Runtime.getRuntime()
        val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) + 524_288L) / 1_048_576L
        val maxMb = (runtime.maxMemory() + 524_288L) / 1_048_576L
        val gcs = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf {
            it.collectionCount.coerceAtLeast(0L)
        }
        val skiaLimitMb = (defaultSkiaCacheLimitBytes() + 524_288L) / 1_048_576L
        val skiaCpuMb = (org.jetbrains.skia.Graphics.resourceCacheTotalUsed.toLong() + 524_288L) / 1_048_576L
        val skiaFontMb = (org.jetbrains.skia.Graphics.fontCacheUsed.toLong() + 524_288L) / 1_048_576L
        System.err.println(
            "compose-rust: idle-soak phase $phase elapsed_s=$elapsedS frames=$framesPresented " +
                "footprint_mb=${processFootprintMb()} resident_mb=${processResidentMb()} " +
                "metal_allocated_mb=${current.metalAllocatedMb()} " +
                "skia_cache_limit_mb=$skiaLimitMb skia_cpu_cache_mb=$skiaCpuMb " +
                "skia_font_cache_mb=$skiaFontMb heap_used_mb=$usedMb heap_max_mb=$maxMb gcs=$gcs",
        )
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

    /**
     * The strip the system's buttons sit in and the radius of the window's corners, measured
     * from the window now. While the window is between sizes the last reading stands.
     */
    override fun caption(): WindowCaption {
        val current = window ?: return WindowCaption.None
        val bar = current.measureTitleBar()
        val measured = macosCaption(
            titleBar,
            windowHeight = bar.windowHeight.toDouble(),
            contentLayoutHeight = bar.contentLayoutHeight.toDouble(),
            closeMinX = bar.closeMinX?.toDouble(),
            zoomMaxX = bar.zoomMaxX?.toDouble(),
            cornerRadius = bar.cornerRadius?.toDouble(),
        )
        if (measured != null) lastCaption = measured
        return lastCaption
    }

    /** Gives the reader [elements]; what it asks of them arrives at [WindowListener.onAccessibilityAction]. */
    override fun setAccessibility(elements: List<AccessibleElement>) {
        window?.describeTo(elements)
    }

    override val accessibilityActive: Boolean get() = window != null && isAccessibilityActive()

    /**
     * What a reader is told about this application's windows, walked in process, for a check
     * on a machine where no outside accessibility client is trusted. See [macosChromeFindings]
     * and [macosAccessibleContentFindings].
     */
    fun accessibilitySnapshot(): MacosAxSnapshot = parseMacosAxSnapshot(accessibilitySnapshotText())

    /** What the system's accessibility API answers an outside client about this application. */
    fun accessibilityApiProbe(): org.thisisthepy.compose.window.MacosAxApiAnswer = axApiProbe()

    /** The title bar this window was built with, for [macosChromeFindings]. */
    val macosTitleBar: MacosTitleBar get() = titleBar

    override fun close() {
        window = null
        listener = null
    }

    companion object {
        /**
         * The entries of the menu a right click puts up. Cut and Copy cannot be judged from
         * outside the field, which owns the selection; Paste is greyed out by the window
         * while the clipboard holds no text.
         */
        fun textEditMenu(): List<ContextMenuItem> =
            editMenuItems(canCut = true, canCopy = true, canPaste = true, canSelectAll = true)

        /**
         * Hands one event the window wrote down to [sink]. A chosen menu entry goes to
         * [WindowListener.onContextMenuChosen] and a reader's request to
         * [WindowListener.onAccessibilityAction], not to [WindowListener.onEvent], as they do
         * on the other platform layers. [argument] reads the new value of a set-value request.
         */
        fun deliver(event: WindowEvent, sink: WindowListener, argument: () -> String? = ::takeAccessibilityArgument) {
            when (event.kind) {
                WindowEvent.MENU_COMMAND -> sink.onContextMenuChosen(event.keyCode)
                // A reader asked control [keyCode] to do [codePoint]; a new value is kept
                // apart, because it can be longer than an event's text.
                ACCESSIBILITY_ACTION_EVENT -> {
                    val value = if (event.codePoint == AccessibleAction.SET_VALUE) argument() else null
                    sink.onAccessibilityAction(event.keyCode, event.codePoint, value)
                }
                else -> sink.onEvent(event)
            }
        }

        /** The wire form the C side parses: a tab-separated line per item. */
        fun packMenu(items: List<ContextMenuItem>): String =
            items.joinToString("\n") {
                "${it.id}\t${if (it.enabled) 1 else 0}\t${if (it.separatorAfter) 1 else 0}\t" +
                    it.label.replace('\t', ' ').replace('\n', ' ')
            }
    }
}
