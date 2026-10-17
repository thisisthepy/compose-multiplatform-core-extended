package org.thisisthepy.compose.window.scene

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneKeysTest {
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
    fun nfr14_a_key_with_no_number_is_not_a_letter() {
        assertEquals(Key.Unknown, composeKey(-1))
    }
}
