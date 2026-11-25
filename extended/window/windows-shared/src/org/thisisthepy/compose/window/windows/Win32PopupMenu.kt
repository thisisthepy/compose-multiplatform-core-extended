@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.PopupMenuAnchor
import org.thisisthepy.compose.window.PopupMenuEntry
import org.thisisthepy.compose.window.decodePopupMenuPath
import org.thisisthepy.compose.window.encodePopupMenu
import org.thisisthepy.compose.window.windows.c.dxc_native_popup_menu
import org.thisisthepy.compose.window.windows.c.dxc_native_popup_menu_close

/**
 * Entry menus as Windows' own (compose-rust FR-33.2): a Win32 popup menu put up with
 * TrackPopupMenuEx under the anchor (c/win32_popup_menu.h, dxc_native_popup_menu). The keyboard,
 * Narrator, the placement at the screen's edge and dismissal are Windows'. Call it on the window's
 * thread; [show] returns when the menu closes.
 */
object Win32PopupMenu {
    /**
     * Puts [entries] up under [anchor] (the window's client pixels) on [window] (its HWND) and
     * answers the chosen item's path, or null for a menu closed with nothing chosen.
     */
    fun show(window: COpaquePointer?, entries: List<PopupMenuEntry>, anchor: PopupMenuAnchor): List<Int>? = memScoped {
        if (entries.isEmpty()) return@memScoped null
        val lines = encodePopupMenu(entries)
        val total = lines.icons.sumOf { it.rgba.size }
        val icons = allocArray<UByteVar>(total.coerceAtLeast(1))
        var at = 0
        for (icon in lines.icons) {
            for (byte in icon.rgba) icons[at++] = byte.toUByte()
        }
        val sizes = allocArray<IntVar>((lines.icons.size * 2).coerceAtLeast(1))
        lines.icons.forEachIndexed { index, icon ->
            sizes[index * 2] = icon.width
            sizes[index * 2 + 1] = icon.height
        }
        val out = allocArray<ByteVar>(PATH_BYTES)
        val written = dxc_native_popup_menu(
            window, lines.text, icons, sizes, lines.icons.size,
            anchor.x, anchor.y, anchor.width, anchor.height, if (anchor.rightToLeft) 1 else 0, out, PATH_BYTES,
        )
        if (written <= 0) null else decodePopupMenuPath(out.toKString())
    }

    /** Takes an open menu down from the application's side; [show] then answers null. */
    fun close() = dxc_native_popup_menu_close()

    private const val PATH_BYTES = 1024
}
