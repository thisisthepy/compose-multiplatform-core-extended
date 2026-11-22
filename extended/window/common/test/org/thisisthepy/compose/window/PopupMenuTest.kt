package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * FR-33.2 (compose-rust): the entry menu every desktop window puts up as its own, in the one form
 * the Kotlin and the C/Objective-C windows share.
 */
class PopupMenuTest {

    private val icon = PopupMenuIcon(ByteArray(2 * 2 * 4) { 0xFF.toByte() }, 2, 2)

    private val menu = listOf(
        PopupMenuEntry.Item("Open", shortcut = "Ctrl+O", icon = icon),
        PopupMenuEntry.Item("Save\tas", shortcut = "Ctrl+Shift+S", enabled = false),
        PopupMenuEntry.Separator,
        PopupMenuEntry.Item("Wrap lines", checked = true),
        PopupMenuEntry.Submenu(
            "Recent",
            listOf(PopupMenuEntry.Item("a.txt"), PopupMenuEntry.Separator, PopupMenuEntry.Item("b.txt", icon = icon)),
        ),
        PopupMenuEntry.Submenu("Empty", emptyList(), enabled = false),
    )

    @Test
    fun fr33_2_the_menu_is_one_line_per_entry_depth_first() {
        val lines = encodePopupMenu(menu)
        assertEquals(
            "I\t0\t1\t0\t0\tOpen\tCtrl+O\n" +
                "I\t0\t0\t0\t-1\tSave as\tCtrl+Shift+S\n" +
                "S\t0\t0\t0\t-1\t\t\n" +
                "I\t0\t1\t1\t-1\tWrap lines\t\n" +
                "M\t0\t1\t0\t-1\tRecent\t\n" +
                "I\t1\t1\t0\t-1\ta.txt\t\n" +
                "S\t1\t0\t0\t-1\t\t\n" +
                "I\t1\t1\t0\t1\tb.txt\t\n" +
                "M\t0\t0\t0\t-1\tEmpty\t\n",
            lines.text,
        )
        assertEquals(2, lines.icons.size, "the icons travel apart from the text, by index")
    }

    @Test
    fun fr33_2_a_chosen_path_reads_back_and_nothing_chosen_is_null() {
        assertEquals(listOf(4, 2), decodePopupMenuPath("4/2"))
        assertEquals(listOf(0), decodePopupMenuPath(encodePopupMenuPath(listOf(0))))
        assertNull(decodePopupMenuPath(""))
        assertNull(decodePopupMenuPath(null))
        assertNull(decodePopupMenuPath("1/x"))
        assertNull(decodePopupMenuPath("-1"))
    }

    @Test
    fun fr33_2_the_description_both_macos_paths_are_compared_by() {
        assertEquals(
            "item \"Open\" shortcut=\"Ctrl+O\" enabled=true checked=false icon=true\n" +
                "item \"Save\tas\" shortcut=\"Ctrl+Shift+S\" enabled=false checked=false icon=false\n" +
                "separator\n" +
                "item \"Wrap lines\" shortcut=\"\" enabled=true checked=true icon=false\n" +
                "submenu \"Recent\" enabled=true icon=false\n" +
                "  item \"a.txt\" shortcut=\"\" enabled=true checked=false icon=false\n" +
                "  separator\n" +
                "  item \"b.txt\" shortcut=\"\" enabled=true checked=false icon=true\n" +
                "submenu \"Empty\" enabled=false icon=false\n",
            describePopupMenu(menu),
        )
    }

    @Test
    fun fr33_2_an_icon_is_width_by_height_by_four_bytes() {
        assertFailsWith<IllegalArgumentException> { PopupMenuIcon(ByteArray(3), 1, 1) }
    }
}
