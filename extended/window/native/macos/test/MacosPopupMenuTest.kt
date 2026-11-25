@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import org.thisisthepy.compose.window.PopupMenuEntry
import org.thisisthepy.compose.window.PopupMenuIcon
import kotlinx.cinterop.useContents
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-33.2 (compose-rust) on the Kotlin/Native macOS window: an entry menu is an `NSMenu` with the
 * entries in order and in their state, and choosing an item answers its path.
 */
class MacosPopupMenuTest {

    private val icon = PopupMenuIcon(ByteArray(4 * 4 * 4) { 0x80.toByte() }, 4, 4)

    private val entries = listOf(
        PopupMenuEntry.Item("Open", shortcut = "⌘O", icon = icon),
        PopupMenuEntry.Item("Save", enabled = false),
        PopupMenuEntry.Separator,
        PopupMenuEntry.Item("Wrap lines", checked = true),
        PopupMenuEntry.Submenu("Recent", listOf(PopupMenuEntry.Item("a.txt"), PopupMenuEntry.Item("b.txt"))),
        PopupMenuEntry.Submenu("Empty", emptyList(), enabled = false),
    )

    private fun NSMenu.item(index: Int): NSMenuItem = checkNotNull(itemAtIndex(index.toLong()))

    @Test
    fun fr33_2_macos_the_nsmenu_holds_the_entries_in_order_and_state() {
        val menu = buildPopupMenu(entries, scale = 2.0) {}
        assertEquals(6L, menu.numberOfItems)
        assertFalse(menu.autoenablesItems, "enabled is the entry's, not AppKit's guess")

        val open = menu.item(0)
        assertTrue(open.title.startsWith("Open"), "the label leads the title")
        assertTrue(open.enabled)
        assertEquals("", open.keyEquivalent, "a shortcut is text, never a registered key")
        assertTrue(open.attributedTitle?.string?.endsWith("\t⌘O") == true, "the shortcut is shown after a tab")
        val image = assertNotNull(open.image, "the entry's picture is on the item")
        image.size.useContents { assertEquals(2.0, width, "4 px at 2x is 2 points") }

        assertFalse(menu.item(1).enabled)
        assertTrue(menu.item(2).separatorItem)
        assertEquals(NSControlStateValueOn, menu.item(3).state)

        val recent = menu.item(4)
        val children = assertNotNull(recent.submenu)
        assertEquals(listOf("a.txt", "b.txt"), (0 until children.numberOfItems.toInt()).map { children.item(it).title })

        val empty = menu.item(5)
        assertFalse(empty.enabled)
        assertNull(empty.submenu, "a disabled submenu opens nothing")
    }

    @Test
    fun fr33_2_macos_choosing_an_item_answers_its_path() {
        // An item's action goes through the application, as it does when a person chooses it.
        platform.AppKit.NSApplication.sharedApplication()
        val chosen = ArrayList<List<Int>>()
        val menu = buildPopupMenu(entries, scale = 1.0) { chosen += it }
        menu.performActionForItemAtIndex(3)
        checkNotNull(menu.item(4).submenu).performActionForItemAtIndex(1)
        assertEquals(listOf(listOf(3), listOf(4, 1)), chosen)
    }

    @Test
    fun fr33_2_macos_both_paths_build_the_same_menu() {
        // The fixture common's PopupMenuTest describes and the GraalVM window's test checks its
        // NSMenu against (appkit_window_test.m, check_popup_menu): the same text from this NSMenu.
        val small = PopupMenuIcon(ByteArray(2 * 2 * 4) { 0xFF.toByte() }, 2, 2)
        val fixture = listOf(
            PopupMenuEntry.Item("Open", shortcut = "Ctrl+O", icon = small),
            PopupMenuEntry.Item("Save as", shortcut = "Ctrl+Shift+S", enabled = false),
            PopupMenuEntry.Separator,
            PopupMenuEntry.Item("Wrap lines", checked = true),
            PopupMenuEntry.Submenu(
                "Recent",
                listOf(PopupMenuEntry.Item("a.txt"), PopupMenuEntry.Separator, PopupMenuEntry.Item("b.txt", icon = small)),
            ),
            PopupMenuEntry.Submenu("Empty", emptyList(), enabled = false),
        )
        assertEquals(org.thisisthepy.compose.window.describePopupMenu(fixture), describeNSMenu(buildPopupMenu(fixture, 2.0) {}))
    }
}
