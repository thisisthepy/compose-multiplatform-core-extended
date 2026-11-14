@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

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

/**
 * A clipboard of the test's own, so a test never reads or replaces the one of the machine it
 * runs on. Its native side is the text itself, which is how an embedder without AWT tells
 * Compose's desktop menu whether there is anything to paste.
 */
private class TestClipboard(var text: String = "") : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = if (text.isEmpty()) null else ClipEntry(text)

    // The field hands over its text as itself, a String or an AnnotatedString, and an
    // AWT application hands over a Transferable.
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
 * The menu acting on the field through each entry, on the JVM, where the tests can give the
 * field a clipboard of their own. Compose's desktop menu shows an entry that cannot run greyed,
 * so all four are there, in the order every desktop puts them.
 */
class SceneTextMenuClipboardTest {

    private fun window(text: String, selection: TextRange, clipboard: TestClipboard) =
        FieldWindow(text, selection) { content ->
            CompositionLocalProvider(LocalClipboard provides clipboard) { content() }
        }

    private fun FieldWindow.enabled(): List<Boolean> =
        items().map { it.states and org.thisisthepy.compose.window.AccessibleState.ENABLED != 0 }

    @Test
    fun the_entries_are_enabled_by_the_selection_and_the_clipboard() {
        val empty = TestClipboard()
        val selected = window("hello world", TextRange(0, 5), empty)
        selected.rightClick(10f, 10f)
        assertEquals(listOf(true, true, false, true), selected.enabled(), "cut, copy, paste, select all")
        selected.close()

        val full = TestClipboard("XY")
        val caretOnly = window("hello world", TextRange(11), full)
        caretOnly.key(KEY_MENU)
        assertEquals(listOf(false, false, true, true), caretOnly.enabled(), "nothing selected, text to paste")
        caretOnly.close()
    }

    @Test
    fun copy_puts_the_selection_on_the_clipboard() {
        val clipboard = TestClipboard()
        val window = window("hello world", TextRange(0, 5), clipboard)
        window.rightClick(10f, 10f)
        window.click(window.items()[1])
        assertEquals("hello", clipboard.text)
        assertEquals("hello world", window.value.value.text)
        assertTrue(window.menus().isEmpty())
        window.close()
    }

    @Test
    fun cut_moves_the_selection_to_the_clipboard() {
        val clipboard = TestClipboard()
        val window = window("hello world", TextRange(0, 5), clipboard)
        window.rightClick(10f, 10f)
        window.click(window.items()[0])
        assertEquals("hello", clipboard.text)
        assertEquals(" world", window.value.value.text)
        window.close()
    }

    @Test
    fun paste_puts_the_clipboard_in_at_the_caret() {
        val clipboard = TestClipboard("XY")
        val window = window("hello world", TextRange(11), clipboard)
        window.key(KEY_MENU)
        window.click(window.items()[2])
        assertEquals("hello worldXY", window.value.value.text)
        window.close()
    }

    @Test
    fun copy_chosen_from_the_keyboard_copies_what_was_selected() {
        val clipboard = TestClipboard()
        val window = window("hello world", TextRange(6, 11), clipboard)
        window.key(KEY_F10, MODIFIER_SHIFT)
        assertEquals(TextRange(6, 11), window.value.value.selection, "the selection is kept")
        window.click(window.items()[1])
        assertEquals("world", clipboard.text)
        window.close()
    }
}
