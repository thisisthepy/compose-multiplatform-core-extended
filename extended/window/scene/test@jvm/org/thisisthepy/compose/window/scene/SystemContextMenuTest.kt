@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.WindowEvent

/** A clipboard of the test's own, as in SceneTextMenuClipboardTest, so the machine's is never touched. */
private class OwnClipboard(var text: String = "") : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = if (text.isEmpty()) null else ClipEntry(text)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = when (val native = clipEntry?.nativeClipEntry) {
            null -> ""
            is String -> native
            is AnnotatedString -> native.text
            else -> clipEntry.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String ?: ""
        }
    }

    override val nativeClipboard: NativeClipboard get() = text
}

/**
 * The GraalVM AppKit window's text field (DarkPyonix/compose-rust#180): a right click puts up
 * one menu, the system's, holding what Compose's menu holds, and the application's own where it
 * gives one (FR-33.1); the editing shortcuts act on the field as that window writes them down.
 */
class SystemContextMenuTest {

    private fun window(
        selection: TextRange,
        clipboard: OwnClipboard,
        systemMenu: Boolean = true,
        inner: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { it() },
    ) = FieldWindow("hello world", selection, platformName = "appkit-graalvm", systemMenu = systemMenu) { content ->
        CompositionLocalProvider(LocalClipboard provides clipboard) { inner(content) }
    }

    private fun FieldWindow.shown() = platform.menusShown

    /** Chooses the entry at [index] of the menu up now: Cut, Copy, Paste, Select All, in the system's language. */
    private fun FieldWindow.choose(index: Int) {
        platform.chosen += shown().last()[index].id
        turns()
    }

    @Test
    fun fr33_1_a_right_click_puts_up_one_menu_the_system_s_with_compose_s_entries() {
        val window = window(TextRange(0, 5), OwnClipboard())
        window.rightClick(10f, 10f)
        assertEquals(1, window.shown().size, "one menu handed to the platform")
        assertTrue(window.menus().isEmpty(), "and none drawn beside it: ${window.menus()}")
        val items = window.shown().single()
        // Compose's own four, labelled in the platform's language.
        assertEquals(4, items.size, "entries: ${items.map { it.label }}")
        assertTrue(items.all { it.label.isNotBlank() })
        assertEquals(listOf(true, true, false, true), items.map { it.enabled }, "nothing on the clipboard to paste")
        assertEquals(TextRange(0, 5), window.value.value.selection, "a press inside the selection keeps it")
        window.close()
    }

    @Test
    fun fr33_1_the_entry_chosen_acts_on_the_field_and_the_next_click_opens_the_menu_again() {
        val clipboard = OwnClipboard()
        val window = window(TextRange(0, 5), clipboard)
        window.rightClick(10f, 10f)
        window.choose(COPY)
        assertEquals("hello", clipboard.text)
        window.rightClick(10f, 10f)
        assertEquals(2, window.shown().size)
        assertEquals(true, window.shown().last()[PASTE].enabled, "the clipboard has text now")
        window.choose(SELECT_ALL)
        assertEquals(TextRange(0, 11), window.value.value.selection)
        // Dismissed: nothing runs, and the menu can be opened again.
        window.rightClick(10f, 10f)
        window.platform.chosen += -1
        window.turns()
        assertEquals("hello world", window.value.value.text)
        window.rightClick(10f, 10f)
        assertEquals(4, window.shown().size)
        assertTrue(window.menus().isEmpty())
        window.close()
    }

    @Test
    fun fr33_1_the_menu_key_puts_up_the_system_s_menu_too() {
        val window = window(TextRange(0, 5), OwnClipboard())
        window.key(KEY_MENU)
        assertEquals(1, window.shown().size)
        assertTrue(window.menus().isEmpty())
        window.close()
    }

    @Test
    fun fr33_1_an_application_s_own_menu_is_shown_alone() {
        val window = window(TextRange(0, 5), OwnClipboard()) { content ->
            CompositionLocalProvider(LocalContextMenuRepresentation provides LightDefaultContextMenuRepresentation) {
                content()
            }
        }
        window.rightClick(10f, 10f)
        assertEquals(1, window.menus().size, "the application's menu, drawn")
        assertTrue(window.shown().isEmpty(), "and no system menu beside it")
        window.close()
    }

    @Test
    fun a_platform_with_no_menu_of_its_own_keeps_compose_s_drawn_one() {
        val window = window(TextRange(0, 5), OwnClipboard(), systemMenu = false)
        window.rightClick(10f, 10f)
        assertEquals(1, window.menus().size)
        assertTrue(window.shown().isEmpty())
        window.close()
    }

    /** A key as the AppKit window writes it down: its number, its flags, and the letter without them. */
    private fun FieldWindow.press(code: Int, letter: Char, modifiers: Int) = send(
        WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, modifiers, code, letter.code, ""),
        WindowEvent(WindowEvent.KEY_UP, 0f, 0f, 0, modifiers, code, letter.code, ""),
    )

    @Test
    fun fr5_command_a_c_x_v_z_and_shift_command_z_act_on_the_field() {
        // Compose's desktop keys are the macOS ones only on macOS, where this window runs.
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val clipboard = OwnClipboard()
        val window = window(TextRange(0, 5), clipboard)
        window.press(KEY_X, 'x', MODIFIER_COMMAND)
        assertEquals("hello", clipboard.text, "Command X cut the selection")
        assertEquals(" world", window.value.value.text)
        window.press(KEY_V, 'v', MODIFIER_COMMAND)
        assertEquals("hello world", window.value.value.text, "Command V pasted it back")
        window.press(KEY_Z, 'z', MODIFIER_COMMAND)
        assertEquals(" world", window.value.value.text, "Command Z undid the paste")
        window.press(KEY_Z, 'z', MODIFIER_COMMAND or MODIFIER_SHIFT)
        assertEquals("hello world", window.value.value.text, "Shift Command Z did it again")
        window.press(KEY_A, 'a', MODIFIER_COMMAND)
        assertEquals(TextRange(0, 11), window.value.value.selection, "Command A selected everything")
        window.press(KEY_C, 'c', MODIFIER_COMMAND)
        assertEquals("hello world", clipboard.text, "Command C copied it")
        // Control A is the line's start, and its letter is not typed.
        window.press(KEY_A, 'a', MODIFIER_CONTROL)
        assertEquals("hello world", window.value.value.text)
        assertEquals(TextRange(0), window.value.value.selection)
        window.close()
    }

    private companion object {
        const val COPY = 1
        const val PASTE = 2
        const val SELECT_ALL = 3
        const val KEY_A = 0x00
        const val KEY_Z = 0x06
        const val KEY_X = 0x07
        const val KEY_C = 0x08
        const val KEY_V = 0x09
    }
}
