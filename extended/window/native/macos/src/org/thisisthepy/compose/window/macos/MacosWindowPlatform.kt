@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.runtime.Composable
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.contentMinimum
import org.thisisthepy.compose.window.FramePresentLog
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility
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

/**
 * The [WindowPlatform] of the Kotlin/Native macOS window.
 *
 * An operating system layer around [MacosWindow]: it opens the window, pumps the run loop,
 * reads the size and scale, and presents. The decisions (smallest size, what a menu offers,
 * what text on the clipboard means) are the common module's and this file's neighbours that
 * hold nothing of AppKit.
 *
 * Input events reach the Compose scene directly inside [MacosWindow], which is the path
 * that keeps the input method's composition where it is, so [WindowListener.onEvent] is not
 * called by this implementation. Theme changes, the close request and context menu choices
 * are reported through the listener.
 *
 * [content] is what the window draws, which is the only thing the common module does not
 * know.
 */
class MacosWindowPlatform(
    private val content: @Composable () -> Unit,
) : WindowPlatform {
    override val name: String = "macos-kotlin-native"

    /** One record per [present], so a test can ask whether any frame was drawn at the wrong size. */
    val presentLog = FramePresentLog()

    private var window: MacosWindow? = null
    private var listener: WindowListener? = null
    private val pasteboard = GeneralPasteboard()

    /** Where the input method was last told the caret is, in points from the content's top left. */
    val imeSpot: Pair<Int, Int>? get() = window?.imeSpot

    /** The AppKit window, for a caller that needs to move or size it. Null before [open]. */
    val nativeWindow: platform.AppKit.NSWindow? get() = window?.window

    /** The size of the last frame drawn, in points, which is what [present] is given. */
    fun drawnSize(): Pair<Int, Int> {
        val w = window ?: return 0 to 0
        val scale = w.window.backingScaleFactor
        val px = w.drawnSizeInPixels
        return (px.width / scale).toInt() to (px.height / scale).toInt()
    }

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        this.listener = listener
        val minimum = contentMinimum(config.minWidth, config.minHeight)
        val created = MacosWindow(
            name = config.title,
            width = config.width,
            height = config.height,
            minimumSize = minimum,
            clipboardHasText = { pasteboard.hasText() },
        )
        created.setContent(content)
        // The one event path: every event leaves the window as a record, goes to the
        // listener, and reaches the scene through the adapter until the scene layer
        // consumes the listener itself.
        created.eventSink = { event ->
            listener.onEvent(event)
            created.feed(event)
        }
        created.menuChosen = { id -> listener.onContextMenuChosen(id) }
        window = created
        observeSystemAppearance { listener.onThemeChanged(systemTheme()) }
        return true
    }

    override fun pump(timeoutMillis: Long) {
        NSRunLoop.currentRunLoop.runMode(
            NSDefaultRunLoopMode,
            beforeDate = NSDate.dateWithTimeIntervalSinceNow(timeoutMillis / 1000.0),
        )
    }

    override fun measure(): WindowMeasurement {
        val w = window?.window ?: return WindowMeasurement(0, 0, 1f)
        val size = w.contentView?.frame?.useContents { size.width to size.height } ?: (0.0 to 0.0)
        return WindowMeasurement(size.first.toInt(), size.second.toInt(), w.backingScaleFactor.toFloat())
    }

    override fun requestFrame() {
        window?.requestFrame()
    }

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
        window?.setImeSpot(x, y)
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
        // Returns once the menu closes, so a dismissal is told apart from a choice here.
        menu.popUpMenuPositioningItem(null, atLocation = view.convertPoint(at, fromView = null), inView = view)
        listener?.onContextMenuChosen(chosen)
    }

    override fun close() {
        window?.window?.close()
        window = null
    }
}
