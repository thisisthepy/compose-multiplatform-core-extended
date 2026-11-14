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
import platform.AppKit.NSApplication
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeKeyDown
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSTextInputClientProtocol
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNotFound
import platform.Foundation.NSProcessInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editing shortcuts and the Control letters on a focused field of the Kotlin/Native
 * window, as AppKit hands them over (DarkPyonix/compose-rust#180, FR-5).
 *
 * A Command key reaches the view through `performKeyEquivalent`, which AppKit offers the
 * view before the menu bar: Command A, C, X, V, Z and Shift Command Z each do to the field
 * what they mean. A Control letter goes to the input method, which may hand it back as the
 * control character itself; nothing of that is typed.
 */
class EditingShortcutsTest {
    private var value by mutableStateOf(TextFieldValue(""))
    private lateinit var window: MacosWindow

    private val client get() = window.view as NSTextInputClientProtocol
    private val noRange get() = NSMakeRange(NSNotFound.toULong(), 0u)

    /** An input method with no binding for anything: whatever the key's characters are is committed. */
    private val bare: (NSEvent) -> Boolean = { event ->
        client.insertText(event.characters ?: "", noRange)
        true
    }

    private fun open(initial: TextFieldValue) {
        value = initial
        NSApplication.sharedApplication()
        window = MacosWindow(name = "shortcuts", width = 320, height = 200)
        window.inputMethod = bare
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

    private fun keyDown(keyCode: Int, text: String, modifiers: Long) = NSEvent.keyEventWithType(
        type = NSEventTypeKeyDown,
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

    /** A Command key as AppKit offers it: to the view's key equivalents, with no key up after. */
    private fun shortcut(keyCode: Int, text: String, shift: Boolean = false): Boolean {
        val flags = MacModifiers.COMMAND or (if (shift) MacModifiers.SHIFT else 0L)
        val claimed = window.view.performKeyEquivalent(keyDown(keyCode, text, flags))
        settle()
        return claimed
    }

    @Test
    fun fr5_command_a_c_x_v_z_and_shift_command_z_act_on_the_focused_field() {
        val pasteboard = NSPasteboard.generalPasteboard
        val saved = pasteboard.stringForType(NSPasteboardTypeString)
        try {
            open(TextFieldValue("hello world", TextRange(0, 5)))
            assertTrue(shortcut(KEY_X, "x"), "the view claims Command X before the menu bar")
            assertEquals("hello", pasteboard.stringForType(NSPasteboardTypeString), "Command X put the selection on the pasteboard")
            assertEquals(TextFieldValue(" world", TextRange(0)), value, "and took it out of the field")

            assertTrue(shortcut(KEY_V, "v"))
            assertEquals(TextFieldValue("hello world", TextRange(5)), value, "Command V pasted it back at the caret")

            assertTrue(shortcut(KEY_Z, "z"))
            assertEquals(" world", value.text, "Command Z undid the paste")
            assertTrue(shortcut(KEY_Z, "Z", shift = true))
            assertEquals("hello world", value.text, "Shift Command Z did it again")

            assertTrue(shortcut(KEY_A, "a"))
            assertEquals(TextRange(0, 11), value.selection, "Command A selected everything")
            assertTrue(shortcut(KEY_C, "c"))
            assertEquals("hello world", pasteboard.stringForType(NSPasteboardTypeString), "Command C copied it")
            assertEquals("hello world", value.text, "and left the field as it was")

            assertFalse(window.view.performKeyEquivalent(keyDown(KEY_Q, "q", MacModifiers.COMMAND)), "Command Q stays the menu's")
        } finally {
            pasteboard.clearContents()
            if (saved != null) pasteboard.setString(saved, NSPasteboardTypeString)
        }
    }

    @Test
    fun fr5_a_control_letter_the_input_method_hands_back_as_a_character_types_nothing() {
        open(TextFieldValue("ab", TextRange(2)))
        // Control A to Control Z, and the input method commits U+0001 to U+001A for them.
        for (index in CONTROL_LETTERS.indices) {
            val code = CONTROL_LETTERS[index]
            val character = (index + 1).toChar().toString()
            window.view.keyDown(keyDown(code, character, MacModifiers.CONTROL))
            settle()
        }
        // What a Control letter means as a key (Control D deletes, Control O opens a line) may
        // change the text; the character it also has never goes in.
        val typed = value.text.filter { it.code < 0x20 && it != '\n' && it != '\t' }
        assertEquals("", typed, "control characters typed: ${typed.map { it.code }}")
    }

    private companion object {
        const val KEY_A = 0x00
        const val KEY_Z = 0x06
        const val KEY_X = 0x07
        const val KEY_C = 0x08
        const val KEY_V = 0x09
        const val KEY_Q = 0x0C

        // kVK_ANSI_A to kVK_ANSI_Z, in the alphabet's order.
        val CONTROL_LETTERS = intArrayOf(
            0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
            0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06,
        )
    }
}
