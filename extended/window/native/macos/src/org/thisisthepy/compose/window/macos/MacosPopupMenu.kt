@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.thisisthepy.compose.window.PopupMenuAnchor
import org.thisisthepy.compose.window.PopupMenuEntry
import org.thisisthepy.compose.window.PopupMenuIcon
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSDeviceRGBColorSpace
import platform.AppKit.NSFont
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSImage
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSMutableParagraphStyle
import platform.AppKit.NSParagraphStyleAttributeName
import platform.AppKit.NSTextAlignmentRight
import platform.AppKit.NSTextTab
import platform.AppKit.NSUserInterfaceLayoutDirectionRightToLeft
import platform.AppKit.NSView
import platform.AppKit.size
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeSize
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.sel_registerName
import platform.posix.memcpy

/**
 * An entry menu as the operating system's own (compose-rust FR-33.2): an `NSMenu` built from the
 * entries and popped up under the anchor, on the view [view] gives.
 *
 * The keyboard, VoiceOver, the language of the standard items, the placement at the screen's
 * edge and the ways a menu is dismissed are `NSMenu`'s. A shortcut is shown as text at the
 * trailing edge and never registered as a key equivalent: the application handles the key
 * already. Popping up blocks until the menu closes, so [show] answers with what was chosen.
 * Call it and [close] on the main thread.
 */
class MacosPopupMenu(private val view: () -> NSView?) {
    private var open: NSMenu? = null

    /**
     * Puts the menu up under [anchor] and answers the path of the item chosen (its index at each
     * level, separators counted), or null when it closed with nothing chosen.
     */
    fun show(entries: List<PopupMenuEntry>, anchor: PopupMenuAnchor): List<Int>? {
        val host = view() ?: return null
        if (entries.isEmpty()) return null
        var chosen: List<Int>? = null
        val scale = host.window?.backingScaleFactor ?: 1.0
        val menu = buildPopupMenu(entries, scale) { path -> chosen = path }
        menu.minimumWidth = anchor.width / scale
        if (anchor.rightToLeft) {
            menu.userInterfaceLayoutDirection = NSUserInterfaceLayoutDirectionRightToLeft
        }
        // The anchor is in window pixels from the top of the content; the view counts points up
        // from its bottom unless it is flipped. The menu's top left goes on the anchor's bottom
        // start corner.
        val x = (if (anchor.rightToLeft) anchor.x + anchor.width else anchor.x) / scale
        val bottom = (anchor.y + anchor.height) / scale
        val y = if (host.isFlipped()) bottom.toDouble() else host.frame.useContents { size.height } - bottom
        open = menu
        try {
            menu.popUpMenuPositioningItem(null, CGPointMake(x.toDouble(), y), host)
        } finally {
            open = null
        }
        return chosen
    }

    /** Takes an open menu down from the application's side; [show] then answers null. */
    fun close() {
        open?.cancelTracking()
    }
}

/**
 * The `NSMenu` for [entries], icons drawn at [scale] pixels a point. [onChosen] hears the path of
 * the item chosen. Apart from [MacosPopupMenu.show] so it can be checked without popping up.
 */
internal fun buildPopupMenu(entries: List<PopupMenuEntry>, scale: Double, onChosen: (List<Int>) -> Unit): NSMenu {
    val font = NSFont.menuFontOfSize(0.0)
    // One right-aligned tab stop past the widest label, a gap and the widest shortcut, so every
    // shortcut ends at the same trailing edge, as a menu bar's do, and none runs into its label.
    var widest = 0.0
    var widestShortcut = 0.0
    for (entry in entries) {
        val (label, shortcut) = when (entry) {
            is PopupMenuEntry.Item -> entry.label to entry.shortcut
            is PopupMenuEntry.Submenu -> entry.label to ""
            PopupMenuEntry.Separator -> continue
        }
        widest = maxOf(widest, textWidth(label, font))
        widestShortcut = maxOf(widestShortcut, textWidth(shortcut, font))
    }
    return menuOf(entries, emptyList(), scale, font, widest + 32.0 + widestShortcut, onChosen)
}

private fun textWidth(text: String, font: NSFont): Double =
    NSAttributedString.create(text, mapOf<Any?, Any?>(NSFontAttributeName to font)).size().useContents { width }

private fun menuOf(
    entries: List<PopupMenuEntry>,
    path: List<Int>,
    scale: Double,
    font: NSFont,
    tabStop: Double,
    onChosen: (List<Int>) -> Unit,
): NSMenu {
    val menu = NSMenu("")
    menu.autoenablesItems = false
    entries.forEachIndexed { index, entry ->
        val here = path + index
        when (entry) {
            PopupMenuEntry.Separator -> menu.addItem(NSMenuItem.separatorItem())
            is PopupMenuEntry.Item -> {
                val item = NSMenuItem()
                item.title = entry.label
                if (entry.shortcut.isNotEmpty()) item.attributedTitle = titleWithShortcut(entry.label, entry.shortcut, font, tabStop)
                item.keyEquivalent = ""
                item.enabled = entry.enabled
                item.state = if (entry.checked) NSControlStateValueOn else NSControlStateValueOff
                entry.icon?.let { item.image = popupMenuImage(it, scale) }
                val target = PopupMenuTarget { onChosen(here) }
                item.target = target
                item.setAction(sel_registerName("choose"))
                // The target lives as long as its item: a menu holds its items' targets weakly.
                item.representedObject = target
                menu.addItem(item)
            }
            is PopupMenuEntry.Submenu -> {
                val item = NSMenuItem()
                item.title = entry.label
                item.enabled = entry.enabled
                entry.icon?.let { item.image = popupMenuImage(it, scale) }
                if (entry.enabled) {
                    val children = menuOf(entry.children, here, scale, font, tabStop, onChosen)
                    children.setTitle(entry.label)
                    item.submenu = children
                }
                menu.addItem(item)
            }
        }
    }
    return menu
}

/** The label, a tab, and the shortcut at a right-aligned tab stop, in the menu's font. */
private fun titleWithShortcut(label: String, shortcut: String, font: NSFont, tabStop: Double): NSAttributedString {
    val style = NSMutableParagraphStyle()
    style.setTabStops(listOf(NSTextTab(NSTextAlignmentRight, tabStop, emptyMap<Any?, Any?>())))
    return NSAttributedString.create(
        "$label\t$shortcut",
        mapOf<Any?, Any?>(NSFontAttributeName to font, NSParagraphStyleAttributeName to style),
    )
}

/** An entry's picture as an `NSImage` of its size in points. */
internal fun popupMenuImage(icon: PopupMenuIcon, scale: Double): NSImage? {
    val representation = NSBitmapImageRep(
        bitmapDataPlanes = null,
        pixelsWide = icon.width.toLong(),
        pixelsHigh = icon.height.toLong(),
        bitsPerSample = 8,
        samplesPerPixel = 4,
        hasAlpha = true,
        isPlanar = false,
        colorSpaceName = NSDeviceRGBColorSpace,
        bytesPerRow = icon.width.toLong() * 4,
        bitsPerPixel = 32,
    )
    val target = representation.bitmapData ?: return null
    icon.rgba.usePinned { memcpy(target, it.addressOf(0), icon.rgba.size.toULong()) }
    val size = NSMakeSize(icon.width / scale, icon.height / scale)
    representation.setSize(size)
    val image = NSImage(size = size)
    image.addRepresentation(representation)
    return image
}

/** What a menu item calls: a selector on a target, which runs [run]. */
private class PopupMenuTarget(private val run: () -> Unit) : NSObject() {
    @ObjCAction
    fun choose() = run()
}

/**
 * [menu] as common's describePopupMenu writes entries, read back from the NSMenu: the check that
 * this window and the GraalVM window build the same menu from the same entries (FR-33.2).
 */
internal fun describeNSMenu(menu: NSMenu, depth: Int = 0): String {
    val out = StringBuilder()
    val indent = "  ".repeat(depth)
    for (index in 0 until menu.numberOfItems.toInt()) {
        val item = menu.itemAtIndex(index.toLong()) ?: continue
        if (item.separatorItem) {
            out.append("${indent}separator\n")
            continue
        }
        val text = item.attributedTitle?.string ?: item.title
        val label = text.substringBefore('\t')
        val shortcut = if ('\t' in text) text.substringAfter('\t') else ""
        if (item.hasSubmenu || item.action == null) {
            out.append("${indent}submenu \"$label\" enabled=${item.enabled} icon=${item.image != null}\n")
            item.submenu?.let { out.append(describeNSMenu(it, depth + 1)) }
        } else {
            out.append(
                "${indent}item \"$label\" shortcut=\"$shortcut\" enabled=${item.enabled} " +
                    "checked=${item.state == NSControlStateValueOn} icon=${item.image != null}\n",
            )
        }
    }
    return out.toString()
}
