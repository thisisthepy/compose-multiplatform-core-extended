@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.runtime.Composable
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentLog
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility
import org.thisisthepy.compose.window.scene.ComposeWindowHost
import org.thisisthepy.compose.window.scene.runComposeWindow
import platform.AppKit.NSApplication
import platform.AppKit.currentEvent
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakeSize
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode

private val EMPTY_CONTENT: @Composable () -> Unit = {}

/**
 * The [WindowPlatform] of the Kotlin/Native macOS window.
 *
 * An operating system layer around [MacosWindow]: it opens the window, pumps the run loop,
 * reads the size and scale, and presents.
 *
 * Input events reach the Compose scene via [ComposeWindowHost] through [WindowListener.onEvent].
 * Theme changes, the close request and context menu choices are reported through the listener.
 */
class MacosWindowPlatform(
    private val content: @Composable () -> Unit = EMPTY_CONTENT,
) : WindowPlatform {
    override val name: String = "macos-kotlin-native"

    /** One record per [present], so a test can ask whether any frame was drawn at the wrong size. */
    val presentLog = FramePresentLog()

    var window: MacosWindow? = null
        private set
    private var listener: WindowListener? = null
    private var internalHost: ComposeWindowHost? = null
    private var inPump = false
    private val pasteboard = GeneralPasteboard()

    /** Where the input method was last told the caret is, in points from the content's top left. */
    var imeSpot: Pair<Int, Int> = 0 to 0
        private set

    internal fun attachWindow(win: MacosWindow) {
        window = win
    }

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        this.listener = listener
        var created = window
        if (created == null) {
            val minimum = if (config.minWidth > 0 || config.minHeight > 0) {
                config.minWidth.coerceAtLeast(0).toDouble() to config.minHeight.coerceAtLeast(0).toDouble()
            } else {
                null
            }
            created = MacosWindow(
                name = config.title,
                width = config.width,
                height = config.height,
                minimumSize = minimum,
                chrome = MacosWindowChrome.of(config.chrome, config.titleBar).also { macosTitleBar = it.toTitleBar() },
            )
            created.initWindow()
            window = created
            observeSystemAppearance { listener.onThemeChanged(systemTheme()) }
        }
        created.onEvent = { event ->
            this.listener?.onEvent(event)
        }
        created.onClose = {
            this.listener?.onCloseRequested()
        }

        if (content !== EMPTY_CONTENT && internalHost == null) {
            val host = ComposeWindowHost(this, config, ::macosWindowSurface, content = content)
            internalHost = host
            host.open()
        }
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val host = internalHost
        if (host != null && !inPump) {
            inPump = true
            try {
                host.turn(timeoutMillis)
            } finally {
                inPump = false
            }
        } else {
            NSRunLoop.currentRunLoop.runMode(
                NSDefaultRunLoopMode,
                beforeDate = NSDate.dateWithTimeIntervalSinceNow(timeoutMillis / 1000.0),
            )
        }
    }

    override fun measure(): WindowMeasurement {
        val w = window?.window ?: return WindowMeasurement(0, 0, 1f)
        val size = w.contentView?.frame?.useContents { size.width to size.height } ?: (0.0 to 0.0)
        return WindowMeasurement(size.first.toInt(), size.second.toInt(), w.backingScaleFactor.toFloat())
    }

    override fun requestFrame() {
        internalHost?.requestFrame()
        window?.requestFrame()
    }

    /** The time now: the window draws inside AppKit's display, which runs on the refresh. */
    override fun frameTimeNanos(): Long = window?.frameTime?.invoke() ?: uptimeNanos()

    /** Reads the window's size now, not when the frame was started, so a resize in between shows. */
    override fun present(drawnWidth: Int, drawnHeight: Int): FramePresentRecord {
        val now = measure()
        val record = FramePresentRecord(drawnWidth, drawnHeight, now.width, now.height)
        presentLog.record(record)
        return record
    }

    override fun systemTheme(): SystemTheme = if (systemIsDark()) SystemTheme.Dark else SystemTheme.Light

    override fun setTitle(title: String) {
        window?.window?.setTitle(title)
    }

    override fun setMinimumSize(width: Int, height: Int) {
        window?.window?.contentMinSize = NSMakeSize(width.toDouble(), height.toDouble())
    }

    override fun setVisibility(visibility: WindowVisibility) {
        val w = window?.window ?: return
        when (visibility) {
            WindowVisibility.Hidden -> w.orderOut(null)
            WindowVisibility.Visible -> {
                if (w.miniaturized) w.deminiaturize(null)
                w.makeKeyAndOrderFront(null)
            }
            WindowVisibility.Minimized -> w.miniaturize(null)
            WindowVisibility.Fullscreen -> w.toggleFullScreen(null)
        }
    }

    override fun readClipboardText(): String? = pasteboard.pasteText()

    override fun writeClipboardText(text: String) = pasteboard.copyText(text)

    override fun setImeSpot(x: Int, y: Int) {
        imeSpot = x to y
    }

    override fun showContextMenu(items: List<ContextMenuItem>) {
        val w = window?.window
        val view = w?.contentView
        if (w == null || view == null) {
            listener?.onContextMenuChosen(-1)
            return
        }
        var chosen = -1
        val menu = NSMenu()
        menu.autoenablesItems = false
        for (entry in items) {
            val item = NSMenuItem()
            item.setTitle(entry.label)
            item.setEnabled(entry.enabled)
            item.setTarget(MenuShortcut { chosen = entry.id })
            item.setAction(platform.darwin.sel_registerName("perform"))
            menu.addItem(item)
            if (entry.separatorAfter) menu.addItem(NSMenuItem.separatorItem())
        }
        val event = NSApplication.sharedApplication().currentEvent
        val at = if (event != null) event.locationInWindow else CGPointMake(0.0, 0.0)
        menu.popUpMenuPositioningItem(null, atLocation = view.convertPoint(at, fromView = null), inView = view)
        listener?.onContextMenuChosen(chosen)
    }

    /**
     * The strip the system's buttons sit in and the radius of the window's corners, as the
     * window last measured them.
     */
    override fun caption(): org.thisisthepy.compose.window.WindowCaption =
        window?.caption?.value?.toCommon() ?: org.thisisthepy.compose.window.WindowCaption.None

    /**
     * Gives the reader [elements] in place of what the window's own scene says, and hands
     * what the reader asks of them to [WindowListener.onAccessibilityAction].
     */
    override fun setAccessibility(elements: List<AccessibleElement>) {
        val current = window ?: return
        val sink = listener
        current.onAccessibilityAction = { id, action, argument ->
            internalHost?.onAccessibilityAction(id, action, argument)
                ?: sink?.onAccessibilityAction(id, action, argument)
                ?: false
        }
        current.describeFrom(elements)
    }

    override val accessibilityActive: Boolean get() = window?.accessibilityActive == true

    /** The title bar this window was built with, for `macosChromeFindings`. */
    var macosTitleBar: org.thisisthepy.compose.window.MacosTitleBar = org.thisisthepy.compose.window.MacosTitleBar.Normal
        private set

    /** What a reader is told about this application's windows, walked in process. */
    fun accessibilitySnapshot(): org.thisisthepy.compose.window.MacosAxSnapshot =
        org.thisisthepy.compose.window.macos.accessibilitySnapshot()

    /**
     * How the window checked each frame it presented against its size: the `dxc resize:` line
     * and the count of frames drawn at another size.
     */
    val resizeStats: org.thisisthepy.compose.window.ResizeStats? get() = window?.resizeStats

    /** The AppKit window, once [open] has made it. */
    val nativeWindow: platform.AppKit.NSWindow? get() = window?.window

    /** The elements the reader is given now, for a test that asks as a reader would. */
    val accessibilityTree: AppKitAccessibilityTree? get() = window?.reader

    override fun close() {
        internalHost?.close()
        internalHost = null
        window?.window?.close()
        window = null
        listener = null
    }
}

/**
 * Opens a macOS window, hosts [content] via [ComposeWindowHost], and runs it until it closes.
 */
fun runMacosWindow(
    config: WindowConfig,
    padContentForCaption: Boolean = true,
    content: @Composable () -> Unit,
): Boolean {
    val platform = MacosWindowPlatform()
    return runComposeWindow(platform, config, ::macosWindowSurface, padContentForCaption, content)
}
