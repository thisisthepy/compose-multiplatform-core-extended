@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.window.WindowEvent
import platform.AppKit.NSApplication
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeKeyDown
import platform.AppKit.NSEventTypeKeyUp
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSTextInputClientProtocol
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNotFound
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typing in the Kotlin/Native window: every key goes through the view's real `keyDown`,
 * and an input method stands in for the system's one, answering each key the way the
 * system's does (`insertText`, `setMarkedText`, or a command for an editing key).
 *
 * The report (DarkPyonix/compose-rust#563): `abc` typed `aabbcc`, because the key carried
 * its letter and Compose on this platform types a key that does, and the input method
 * committed the same letter.
 */
class TypeOnceTest {
    private var value by mutableStateOf(TextFieldValue(""))
    private lateinit var window: MacosWindow
    private val keys = mutableListOf<WindowEvent>()

    private val client get() = window.view as NSTextInputClientProtocol
    private val noRange get() = NSMakeRange(NSNotFound.toULong(), 0u)

    private fun open(inputMethod: (NSEvent) -> Boolean) {
        NSApplication.sharedApplication()
        window = MacosWindow(name = "type-once", width = 320, height = 200)
        window.inputMethod = inputMethod
        window.setContent {
            val focus = remember { FocusRequester() }
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.size(300.dp, 160.dp).focusRequester(focus),
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        val forward = window.onEvent
        window.onEvent = { event ->
            if (event.kind == WindowEvent.KEY_DOWN || event.kind == WindowEvent.KEY_UP) keys += event
            forward?.invoke(event)
        }
        settle()
    }

    @AfterTest
    fun close() {
        if (::window.isInitialized) {
            window.host?.close()
            window.window.orderOut(null)
        }
    }

    private fun settle() {
        repeat(10) { window.host?.turn(10) }
    }

    private fun nsKey(down: Boolean, keyCode: Int, text: String, modifiers: Long) = NSEvent.keyEventWithType(
        type = if (down) NSEventTypeKeyDown else NSEventTypeKeyUp,
        location = NSMakePoint(0.0, 0.0),
        modifierFlags = modifiers.toULong(),
        timestamp = NSProcessInfo.processInfo.systemUptime,
        windowNumber = window.window.windowNumber,
        context = null,
        characters = text,
        charactersIgnoringModifiers = text,
        isARepeat = false,
        keyCode = keyCode.toUShort(),
    )!!

    /** One press and release, as AppKit hands them to the view. */
    private fun press(keyCode: Int, text: String, modifiers: Long = 0L) {
        window.view.keyDown(nsKey(true, keyCode, text, modifiers))
        window.view.keyUp(nsKey(false, keyCode, text, modifiers))
        settle()
    }

    private fun command(name: String) {
        client.doCommandBySelector(NSSelectorFromString(name))
    }

    /** What the system's input method does with a US layout: text for letters, commands for the rest. */
    private val latin: (NSEvent) -> Boolean = { event ->
        val control = event.modifierFlags.toLong() and MacModifiers.CONTROL != 0L
        when {
            control && event.keyCode.toInt() == KEY_A -> command("moveToBeginningOfParagraph:")
            event.keyCode.toInt() == KEY_BACKSPACE -> command("deleteBackward:")
            event.keyCode.toInt() == KEY_LEFT -> command("moveLeft:")
            event.keyCode.toInt() == KEY_RIGHT -> command("moveRight:")
            event.keyCode.toInt() == KEY_RETURN -> command("insertNewline:")
            else -> client.insertText(event.characters ?: "", noRange)
        }
        true
    }

    @Test
    fun a_letter_is_typed_once() {
        open(latin)
        press(KEY_A, "a")
        assertEquals("a", value.text)
        val down = keys.first { it.kind == WindowEvent.KEY_DOWN }
        assertEquals(0, down.codePoint, "the key that also goes to the input method carries no character")
    }

    @Test
    fun abc_is_typed_as_abc() {
        open(latin)
        press(KEY_A, "a")
        press(KEY_B, "b")
        press(KEY_C, "c")
        assertEquals(TextFieldValue("abc", TextRange(3)), value)
    }

    @Test
    fun a_korean_syllable_is_composed_and_committed_once() {
        // Korean 2-Set: g k s is ㅎ ㅏ ㄴ, and Space finishes the syllable.
        var marked = ""
        open { event ->
            when (event.keyCode.toInt()) {
                KEY_G -> { marked = "ㅎ"; client.setMarkedText(marked, NSMakeRange(1u, 0u), noRange) }
                KEY_K -> { marked = "하"; client.setMarkedText(marked, NSMakeRange(1u, 0u), noRange) }
                KEY_S -> { marked = "한"; client.setMarkedText(marked, NSMakeRange(1u, 0u), noRange) }
                KEY_SPACE -> {
                    if (marked.isNotEmpty()) client.insertText(marked, noRange)
                    marked = ""
                    client.insertText(" ", noRange)
                }
            }
            true
        }
        press(KEY_G, "g")
        assertEquals("ㅎ", value.text)
        press(KEY_K, "k")
        press(KEY_S, "s")
        assertEquals(TextFieldValue("한", TextRange(1), TextRange(0, 1)), value)
        press(KEY_SPACE, " ")
        assertEquals(TextFieldValue("한 ", TextRange(2)), value)
    }

    @Test
    fun backspace_arrows_enter_and_control_a_still_edit() {
        open(latin)
        press(KEY_A, "a")
        press(KEY_B, "b")
        press(KEY_BACKSPACE, "\u007f")
        assertEquals("a", value.text)
        press(KEY_C, "c")
        press(KEY_LEFT, "")
        press(KEY_X, "x")
        assertEquals(TextFieldValue("axc", TextRange(2)), value)
        press(KEY_RIGHT, "")
        press(KEY_RETURN, "\r")
        press(KEY_B, "b")
        assertEquals(TextFieldValue("axc\nb", TextRange(5)), value)
        press(KEY_A, "\u0001", MacModifiers.CONTROL)
        assertEquals(TextFieldValue("axc\nb", TextRange(4)), value, "Control A goes to the line start and types nothing")
    }

    @Test
    fun command_a_and_command_c_are_shortcuts_and_type_nothing() {
        val pasteboard = NSPasteboard.generalPasteboard
        val saved = pasteboard.stringForType(NSPasteboardTypeString)
        try {
            open(latin)
            press(KEY_A, "a")
            press(KEY_B, "b")
            press(KEY_A, "a", MacModifiers.COMMAND)
            assertEquals(TextFieldValue("ab", TextRange(0, 2)), value, "Command A selects everything")
            press(KEY_C, "c", MacModifiers.COMMAND)
            assertEquals("ab", value.text)
            assertEquals("ab", pasteboard.stringForType(NSPasteboardTypeString), "Command C copied the selection")
            assertTrue(
                keys.filter { it.kind == WindowEvent.KEY_DOWN && it.modifiers.toLong() and MacModifiers.COMMAND != 0L }
                    .all { it.codePoint != 0 },
                "a Command key keeps its character",
            )
        } finally {
            pasteboard.clearContents()
            if (saved != null) pasteboard.setString(saved, NSPasteboardTypeString)
        }
    }

    @Test
    fun only_a_key_kept_from_the_input_method_carries_its_character() {
        assertEquals(0, keyCodePoint("a", toInputMethod = true))
        assertEquals('a'.code, keyCodePoint("a", toInputMethod = false))
        assertEquals(0, keyCodePoint(null, toInputMethod = false))
        assertEquals(0, keyCodePoint("", toInputMethod = false))
        assertTrue(reachesInputMethod(MacModifiers.CONTROL), "Control chords are shown to the input method")
        assertTrue(!reachesInputMethod(MacModifiers.COMMAND))
    }

    private companion object {
        const val KEY_A = 0x00
        const val KEY_S = 0x01
        const val KEY_G = 0x05
        const val KEY_X = 0x07
        const val KEY_C = 0x08
        const val KEY_B = 0x0B
        const val KEY_K = 0x28
        const val KEY_SPACE = 0x31
        const val KEY_RETURN = 0x24
        const val KEY_BACKSPACE = 0x33
        const val KEY_LEFT = 0x7B
        const val KEY_RIGHT = 0x7C
    }
}
