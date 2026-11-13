package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.CaretRect
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent

/** A window holding one focused field, and what a reader would be told is in it. */
internal class FieldWindow(
    text: String,
    selection: TextRange,
    platformName: String = "graalvm-linux-x11",
    wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    val platform = FakePlatform().also {
        it.name = platformName
        // Described only while a reader listens; the tests read the menu from that description.
        it.listening = true
    }
    val value: MutableState<TextFieldValue> = mutableStateOf(TextFieldValue(text, selection))
    val host = ComposeWindowHost(platform, WindowConfig("menu", 400, 300), { FakeSurface() }) {
        wrap {
            val focus = remember { FocusRequester() }
            BasicTextField(
                value = value.value,
                onValueChange = { value.value = it },
                modifier = Modifier.size(180.dp, 40.dp).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    init {
        assertTrue(host.open())
        turns(6)
    }

    fun turns(count: Int = 6) = repeat(count) { host.turn(0) }

    fun send(vararg events: WindowEvent) {
        platform.queued.addAll(events)
        turns()
    }

    private fun described(): List<AccessibleElement> = platform.pushed.lastOrNull() ?: emptyList()

    fun menus(): List<AccessibleElement> = described().filter { it.role == ElementRole.MENU }

    fun items(): List<AccessibleElement> = described().filter { it.role == ElementRole.MENU_ITEM }

    fun enabledItems(): List<AccessibleElement> = items().filter { it.states and AccessibleState.ENABLED != 0 }

    fun click(element: AccessibleElement) {
        val x = element.x + element.width / 2f
        val y = element.y + element.height / 2f
        send(
            WindowEvent(WindowEvent.POINTER_DOWN, x, y, WindowEvent.PRIMARY_HELD, 0, 0, 0, ""),
            WindowEvent(WindowEvent.POINTER_UP, x, y, 0, 0, 0, 0, ""),
        )
    }

    fun rightClick(x: Float, y: Float) = send(*secondaryClickAt(Offset(x, y), WindowEvent.TIME_UNKNOWN).toTypedArray())

    fun key(code: Int, modifiers: Int = 0) = send(
        WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, modifiers, code, 0, ""),
        WindowEvent(WindowEvent.KEY_UP, 0f, 0f, 0, modifiers, code, 0, ""),
    )

    fun close() = host.close()
}

/** A point on the "e" of "hello", inside a selection of the first word. */
private const val ON_HELLO_X = 10f
private const val ON_HELLO_Y = 10f
private const val KEY_UP_ARROW = 0x7E
private const val KEY_RETURN = 0x24
private const val KEY_ESCAPE = 0x35

class SceneTextMenuTest {

    @Test
    fun a_secondary_press_on_a_field_opens_one_menu_with_what_the_field_allows() {
        val window = FieldWindow("hello world", TextRange(0, 5))
        assertTrue(window.menus().isEmpty())
        window.rightClick(ON_HELLO_X, ON_HELLO_Y)
        // Compose's own menu, and only that one: the scene draws none beside it.
        assertEquals(1, window.menus().size, "menus: ${window.menus()}")
        // Cut, Copy and Select All can run on a selected word in an editable field. Paste is
        // there and enabled only when the clipboard holds text, which the tests that own a
        // clipboard check (SceneTextMenuClipboardTest).
        assertTrue(window.items().size in 3..4, "items: ${window.items().map { it.label }}")
        assertTrue(window.enabledItems().size >= 3, "enabled: ${window.enabledItems().map { it.label }}")
        assertEquals(TextRange(0, 5), window.value.value.selection, "a press inside the selection keeps it")
        window.close()
    }

    @Test
    fun choosing_select_all_selects_the_whole_field_and_closes_the_menu() {
        val window = FieldWindow("hello world", TextRange(0, 5))
        window.rightClick(ON_HELLO_X, ON_HELLO_Y)
        // Select All is the last entry whichever entries the field left out.
        window.click(window.items().last())
        assertEquals(TextRange(0, 11), window.value.value.selection)
        assertTrue(window.menus().isEmpty(), "the menu closes on a choice")
        window.close()
    }

    @Test
    fun the_menu_key_opens_the_menu_at_the_caret_and_keeps_the_selection() {
        val window = FieldWindow("hello world", TextRange(0, 5))
        window.key(KEY_MENU)
        assertEquals(1, window.menus().size)
        assertEquals(TextRange(0, 5), window.value.value.selection)
        // The menu's own node fills the popup layer; its first entry is where the menu is drawn.
        val menu = window.items().first()
        val caret = window.host.focusedCaret()
        assertNotNull(caret)
        // Opened where the keyboard is, not where the pointer last was.
        assertTrue(menu.x in caret.left - 24f..caret.left + 24f, "menu at ${menu.x}, caret at ${caret.left}")
        window.close()
    }

    @Test
    fun shift_f10_opens_the_menu_and_f10_alone_does_not() {
        val window = FieldWindow("hello world", TextRange(0, 5))
        window.key(KEY_F10)
        assertTrue(window.menus().isEmpty(), "F10 alone is not the menu")
        window.key(KEY_F10, MODIFIER_SHIFT)
        assertEquals(1, window.menus().size)
        window.close()
    }

    @Test
    fun the_arrow_keys_move_through_the_menu_and_return_chooses() {
        val window = FieldWindow("hello world", TextRange(0, 5))
        window.key(KEY_MENU)
        assertEquals(1, window.menus().size)
        // Up from the top goes round to the last entry, Select All.
        window.key(KEY_UP_ARROW)
        window.key(KEY_RETURN)
        assertEquals(TextRange(0, 11), window.value.value.selection)
        assertTrue(window.menus().isEmpty())
        window.close()
    }

    @Test
    fun escape_closes_the_menu_and_leaves_the_field_as_it_was() {
        val window = FieldWindow("hello world", TextRange(0, 5))
        window.key(KEY_MENU)
        assertEquals(1, window.menus().size)
        window.key(KEY_ESCAPE)
        assertTrue(window.menus().isEmpty())
        assertEquals("hello world", window.value.value.text)
        assertEquals(TextRange(0, 5), window.value.value.selection)
        window.close()
    }

    @Test
    fun the_menu_key_with_no_field_focused_opens_nothing() {
        val platform = FakePlatform().also { it.listening = true }
        val host = ComposeWindowHost(platform, WindowConfig("menu", 200, 100), { FakeSurface() }) {}
        assertTrue(host.open())
        platform.queued.add(WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, 0, KEY_MENU, 0, ""))
        repeat(4) { host.turn(0) }
        assertTrue((platform.pushed.lastOrNull() ?: emptyList()).none { it.role == ElementRole.MENU })
        host.close()
    }

    @Test
    fun the_keys_that_ask_for_the_menu() {
        fun down(code: Int, modifiers: Int = 0) = WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, modifiers, code, 0, "")
        assertTrue(opensTextMenu(down(KEY_MENU)))
        assertTrue(opensTextMenu(down(KEY_MENU, MODIFIER_SHIFT)))
        assertTrue(opensTextMenu(down(KEY_F10, MODIFIER_SHIFT)))
        assertFalse(opensTextMenu(down(KEY_F10)))
        assertFalse(opensTextMenu(down(KEY_F10, MODIFIER_SHIFT or MODIFIER_CONTROL)))
        assertFalse(opensTextMenu(down(KEY_MENU, MODIFIER_CONTROL)))
        assertFalse(opensTextMenu(down(KEY_MENU).copy(kind = WindowEvent.KEY_UP)))
    }

    @Test
    fun the_keyboard_menu_is_asked_for_on_the_caret_or_inside_the_selection() {
        val caret = CaretRect(40f, 4f, 41f, 20f)
        assertEquals(Offset(40f, 12f), textMenuPoint(caret, TextRange(3)))
        assertEquals(Offset(39f, 12f), textMenuPoint(caret, TextRange(0, 5)))
        assertEquals(Offset(39f, 12f), textMenuPoint(caret, TextRange(5, 0)), "the caret is at the far end either way")
        assertNull(textMenuPoint(null, TextRange(0)))
        assertNull(textMenuPoint(CaretRect(0f, 0f, 0f, 0f), TextRange(0)))
    }
}
