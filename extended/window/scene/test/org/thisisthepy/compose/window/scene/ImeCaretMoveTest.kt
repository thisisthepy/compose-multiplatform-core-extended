package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent

/**
 * A key that moves the caret while an input method is composing, and what is typed after it.
 *
 * The report (thisisthepy/Gemstone#41, upstream CMP-8200): with a Korean syllable still
 * marked, the arrow keys moved the caret, and the next text went in at the old caret or the
 * syllable came out twice. Each test drives the events a window would send for that and
 * checks where the text lands.
 */
class ImeCaretMoveTest {
    private val platform = FakePlatform()
    private var value by mutableStateOf(TextFieldValue("abc", TextRange(3)))
    private lateinit var host: ComposeWindowHost

    private fun open(clickY: Float = 70f, content: (@Composable () -> Unit)? = null) {
        host = ComposeWindowHost(platform, WindowConfig("test", 200, 100), { FakeSurface() }) {
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                if (content != null) {
                    content()
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.size(180.dp, 80.dp),
                    )
                }
            }
        }
        assertTrue(host.open())
        host.turn(0)
        // A click past the end of the text, which focuses the field with the caret at the
        // end of the line under the pointer.
        send(
            WindowEvent(WindowEvent.POINTER_DOWN, 170f, clickY, 1, 0, 0, 0, ""),
            WindowEvent(WindowEvent.POINTER_UP, 170f, clickY, 0, 0, 0, 0, ""),
        )
    }

    /** Delivers [events] in one read of the window, then lets the scene settle. */
    private fun send(vararg events: WindowEvent) {
        platform.queued.addAll(events)
        repeat(4) { host.turn(0) }
    }

    private fun key(code: Int, modifiers: Int = 0) = arrayOf(
        WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, modifiers, code, 0, ""),
        WindowEvent(WindowEvent.KEY_UP, 0f, 0f, 0, modifiers, code, 0, ""),
    )

    private fun compose(text: String) = WindowEvent(WindowEvent.TEXT_COMPOSE, 0f, 0f, 0, 0, 0, 0, text)
    private fun commit(text: String) = WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, text)

    @Test
    fun an_arrow_key_during_a_composition_keeps_the_syllable_and_moves_the_caret() {
        open()
        send(compose("ㅎ"))
        send(compose("하"))
        send(compose("한"))
        assertEquals(TextFieldValue("abc한", TextRange(4), TextRange(3, 4)), value)
        send(*key(LEFT))
        assertEquals(TextFieldValue("abc한", TextRange(3)), value, "the syllable is kept, unmarked, and the caret moved")
        assertEquals(1, platform.endedCompositions, "the input method is told to let go")
        send(*key(LEFT))
        assertEquals(TextFieldValue("abc한", TextRange(2)), value)
        send(commit("x"))
        assertEquals(TextFieldValue("abxc한", TextRange(3)), value)
        assertEquals(1, platform.endedCompositions)
        host.close()
    }

    @Test
    fun a_new_composition_after_the_move_starts_at_the_new_caret() {
        open()
        send(compose("한"))
        send(*key(LEFT), *key(LEFT), *key(LEFT))
        assertEquals(TextFieldValue("abc한", TextRange(1)), value)
        send(compose("ㅌ"))
        assertEquals(TextFieldValue("aㅌbc한", TextRange(2), TextRange(1, 2)), value)
        send(compose("터"))
        assertEquals(TextFieldValue("a터bc한", TextRange(2), TextRange(1, 2)), value)
        send(commit("터"))
        assertEquals(TextFieldValue("a터bc한", TextRange(2)), value)
        host.close()
    }

    @Test
    fun the_composition_and_the_key_in_one_read_of_the_window_land_the_same() {
        open()
        send(compose("한"), *key(LEFT), *key(LEFT), commit("x"))
        assertEquals(TextFieldValue("abxc한", TextRange(3)), value)
        assertEquals(1, platform.endedCompositions)
        host.close()
    }

    @Test
    fun an_input_method_that_commits_before_the_key_is_not_asked_to_let_go() {
        open()
        send(compose("한"))
        // What macOS and Windows send for an arrow key with a Korean syllable marked: the
        // syllable as finished text, then the key.
        send(commit("한"), *key(LEFT))
        send(*key(LEFT))
        send(commit("x"))
        assertEquals(TextFieldValue("abxc한", TextRange(3)), value)
        assertEquals(0, platform.endedCompositions)
        host.close()
    }

    @Test
    fun with_no_composition_the_arrow_keys_move_the_caret_and_text_follows() {
        open()
        send(commit("한"))
        send(*key(LEFT), *key(LEFT))
        send(commit("x"))
        assertEquals(TextFieldValue("abxc한", TextRange(3)), value)
        assertEquals(0, platform.endedCompositions)
        host.close()
    }

    @Test
    fun arrow_keys_and_text_in_one_read_of_the_window_are_taken_in_order() {
        open()
        send(commit("한"))
        // Two presses and the text after them, all waiting when the window is read: each
        // has to see what the one before it did.
        send(*key(LEFT), *key(LEFT), commit("x"))
        assertEquals(TextFieldValue("abxc한", TextRange(3)), value)
        send(commit("y"), *key(LEFT), *key(LEFT), commit("z"))
        assertEquals(TextFieldValue("abzxyc한", TextRange(3)), value)
        host.close()
    }

    @Test
    fun a_letter_key_and_the_text_it_typed_cost_one_frame() {
        open()
        val before = host.presentLog.frames
        // A letter as the scene is meant to get it: the key, carrying no character and
        // changing nothing, and the text beside it.
        platform.queued.add(WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, 0, 0x0F, 0, ""))
        platform.queued.add(commit("r"))
        host.turn(0)
        val drawn = host.presentLog.frames - before
        repeat(4) { host.turn(0) }
        assertEquals(TextFieldValue("abcr", TextRange(4)), value)
        assertEquals(1, drawn, "no frame is drawn between a key that changed nothing and its text")
        host.close()
    }

    @Test
    fun only_changes_that_a_key_has_to_see_or_that_follow_a_key_are_waited_for() {
        assertTrue(needsSettling(Edit.KEY, Edit.KEY))
        assertTrue(needsSettling(Edit.KEY, Edit.TEXT))
        assertTrue(needsSettling(Edit.TEXT, Edit.KEY))
        assertTrue(needsSettling(Edit.POINTER, Edit.TEXT))
        assertTrue(needsSettling(Edit.POINTER, Edit.KEY))
        assertTrue(!needsSettling(Edit.TEXT, Edit.TEXT))
        assertTrue(!needsSettling(Edit.POINTER, Edit.POINTER))
        assertTrue(!needsSettling(Edit.NONE, Edit.KEY))
        assertTrue(!needsSettling(Edit.KEY, Edit.NONE))
    }

    @Test
    fun up_during_a_composition_types_on_the_line_above() {
        value = TextFieldValue("abc\ndef", TextRange(7))
        open()
        assertEquals(TextRange(7), value.selection, "the click put the caret at the end of the last line")
        send(compose("한"))
        assertEquals("abc\ndef한", value.text)
        send(*key(UP))
        assertEquals(null, value.composition)
        assertTrue(value.selection.collapsed && value.selection.start <= 3, "caret on the first line: ${value.selection}")
        send(commit("x"))
        val lines = value.text.split("\n")
        assertEquals("def한", lines[1], "the syllable stays where it was composed, once")
        assertEquals("abcx".toList().sorted(), lines[0].toList().sorted(), "the text went in on the first line: ${value.text}")
        assertEquals(1, platform.endedCompositions)
        host.close()
    }

    @Test
    fun down_during_a_composition_types_on_the_line_below() {
        value = TextFieldValue("abc\ndef", TextRange(3))
        open(clickY = 4f)
        assertEquals(TextRange(3), value.selection, "the click put the caret at the end of the first line")
        send(compose("한"))
        assertEquals("abc한\ndef", value.text)
        send(*key(DOWN))
        assertEquals(null, value.composition)
        assertTrue(value.selection.collapsed && value.selection.start >= 5, "caret on the second line: ${value.selection}")
        send(compose("ㅌ"))
        send(commit("터"))
        val lines = value.text.split("\n")
        assertEquals("abc한", lines[0], "the syllable stays where it was composed, once")
        assertEquals("def터".toList().sorted(), lines[1].toList().sorted(), "the text went in on the second line: ${value.text}")
        host.close()
    }

    @Test
    fun an_empty_composition_arriving_after_the_move_deletes_nothing() {
        open()
        send(compose("한"))
        // Shift and Left: the caret leaves the syllable and selects it.
        send(*key(LEFT, modifiers = SHIFT))
        assertEquals(TextFieldValue("abc한", TextRange(4, 3)), value)
        // Some input methods answer a cancel with an empty composition.
        send(compose(""))
        assertEquals(TextFieldValue("abc한", TextRange(4, 3)), value)
        host.close()
    }

    @Test
    fun a_key_that_does_not_move_the_caret_leaves_the_composition_open() {
        open()
        send(compose("한"))
        // The key under ㄱ: part of the composition, and the input method says what it made.
        send(*key(0x0F), compose("핡"))
        assertEquals(TextFieldValue("abc핡", TextRange(4), TextRange(3, 4)), value)
        assertEquals(0, platform.endedCompositions)
        host.close()
    }

    @Test
    fun a_field_holding_its_own_state_behaves_the_same() {
        val state = TextFieldState("abc")
        open { BasicTextField(state = state, modifier = Modifier.size(180.dp, 80.dp)) }
        send(compose("한"))
        assertEquals("abc한", state.text.toString())
        assertEquals(TextRange(3, 4), state.composition)
        send(*key(LEFT), *key(LEFT))
        assertEquals(null, state.composition)
        assertEquals(TextRange(2), state.selection)
        send(compose("ㅌ"))
        assertEquals("abㅌc한", state.text.toString())
        send(commit("터"))
        send(commit("x"))
        assertEquals("ab터xc한", state.text.toString())
        assertEquals(1, platform.endedCompositions)
        host.close()
    }

    private companion object {
        // Key numbers as the windows send them, the ones `composeKey` reads.
        const val LEFT = 0x7B
        const val DOWN = 0x7D
        const val UP = 0x7E
        const val SHIFT = 1 shl 17
    }
}
