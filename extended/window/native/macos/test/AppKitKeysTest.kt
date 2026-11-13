package org.thisisthepy.compose.window.macos.test

import androidx.compose.ui.input.key.Key
import org.thisisthepy.compose.window.macos.composeKey
import kotlin.test.Test
import kotlin.test.assertEquals

class AppKitKeysTest {
    @Test
    fun nfr14_digits_are_digits_on_the_shared_board() {
        assertEquals(Key.One, composeKey(0x12))
        assertEquals(Key.Five, composeKey(0x17))
        assertEquals(Key.Zero, composeKey(0x1D))
    }

    @Test
    fun nfr14_the_clipboard_shortcuts_are_their_own_keys_on_the_shared_board() {
        assertEquals(Key.C, composeKey(0x08))
        assertEquals(Key.V, composeKey(0x09))
        assertEquals(Key.X, composeKey(0x07))
        assertEquals(Key.Z, composeKey(0x06))
        assertEquals(Key.A, composeKey(0x00))
    }

    @Test
    fun every_function_key_and_the_menu_key_on_the_shared_board() {
        // kVK_F1 to kVK_F12 and kVK_ContextualMenu, from Carbon's Events.h.
        val board = listOf(0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F)
        val keys = listOf(
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
            Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
        )
        assertEquals(keys, board.map { composeKey(it) })
        assertEquals(Key.Menu, composeKey(0x6E))
    }

    @Test
    fun nfr14_a_key_with_no_number_is_not_a_letter() {
        assertEquals(Key.Unknown, composeKey(-1))
    }
}
