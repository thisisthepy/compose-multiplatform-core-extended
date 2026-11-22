@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSView
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake

/**
 * What a selection offers when it is asked.
 *
 * The system draws this, rather than Compose drawing a strip of its own. A menu that came
 * from the platform is the one the reader already knows: it takes the same keys, it reads
 * to a screen reader, and it looks like every other menu on the machine.
 *
 * Which items appear is Compose's decision and is made afresh each time, because what a
 * selection can do changes: there is nothing to copy without a selection, and nothing to
 * paste while the pasteboard holds no text, which Compose reads from the clipboard the
 * window provides.
 */
class MacosTextToolbar(private val view: () -> NSView) : TextToolbar {

    private var shown = false

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val menu = buildMenu(onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
        if (menu.numberOfItems.toInt() == 0) return

        val host = view()
        val scale = host.window?.backingScaleFactor ?: 1.0
        val where = host.frame.useContents {
            menuLocation(rect, size.height, host.isFlipped(), scale)
        }
        shown = true
        status = TextToolbarStatus.Shown
        menu.popUpMenuPositioningItem(null, where, host)
        shown = false
        status = TextToolbarStatus.Hidden
    }

    internal fun buildMenu(
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ): NSMenu {
        val menu = NSMenu()
        // In the order every other application on this platform puts them.
        add(menu, TextCommand.Cut.title, onCutRequested)
        add(menu, TextCommand.Copy.title, onCopyRequested)
        add(menu, TextCommand.Paste.title, onPasteRequested)
        if (onSelectAllRequested != null) {
            if (menu.numberOfItems.toInt() > 0) menu.addItem(NSMenuItem.separatorItem())
            add(menu, TextCommand.SelectAll.title, onSelectAllRequested)
        }
        return menu
    }

    override fun hide() {
        // The system takes the menu down itself, on the click that chose an item or the
        // one that went elsewhere. There is nothing left to take down by the time anything
        // here would ask.
        status = TextToolbarStatus.Hidden
    }

    private fun add(menu: NSMenu, title: String, action: (() -> Unit)?) {
        if (action == null) return
        val item = NSMenuItem()
        item.setTitle(title)
        item.setTarget(MenuAction(action))
        item.setAction(platform.darwin.sel_registerName("perform"))
        menu.addItem(item)
    }
}

/**
 * Converts a selection rectangle in scene pixels to the bottom-left anchor point in the host
 * view's point coordinates, accounting for backing scale and whether the view is flipped.
 */
internal fun menuLocation(
    rect: Rect,
    viewHeight: Double,
    flipped: Boolean,
    scale: Double,
): CValue<CGPoint> {
    val factor = if (scale > 0.0) scale else 1.0
    val x = rect.left.toDouble() / factor
    val y = if (flipped) rect.bottom.toDouble() / factor else viewHeight - rect.bottom.toDouble() / factor
    return CGPointMake(x, y)
}

/** Holds the closure a menu item runs, because a menu item calls a selector on a target. */
private class MenuAction(private val run: () -> Unit) : platform.darwin.NSObject() {
    @kotlinx.cinterop.ObjCAction
    fun perform() = run()
}
