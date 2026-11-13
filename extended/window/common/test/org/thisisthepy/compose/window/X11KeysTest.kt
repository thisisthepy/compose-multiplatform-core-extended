package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The X11 key table both X11 windows read, checked as key numbers (the shared board's). */
class X11KeysTest {
    @Test
    fun the_editing_shortcut_letters_arrive_as_themselves() {
        assertEquals(0x08, x11KeyNumber('c'.code.toLong()))
        assertEquals(0x08, x11KeyNumber('C'.code.toLong()))
        assertEquals(0x09, x11KeyNumber('v'.code.toLong()))
        assertEquals(0x07, x11KeyNumber('x'.code.toLong()))
        assertEquals(0x06, x11KeyNumber('z'.code.toLong()))
        assertEquals(0x00, x11KeyNumber('a'.code.toLong()))
    }

    @Test
    fun every_letter_and_digit_has_its_own_key() {
        assertEquals(26, ('a'..'z').map { x11KeyNumber(it.code.toLong()) }.toSet().size)
        assertEquals(10, ('0'..'9').map { x11KeyNumber(it.code.toLong()) }.toSet().size)
        assertEquals(0x17, x11KeyNumber('5'.code.toLong()))
        assertEquals(0x1D, x11KeyNumber('0'.code.toLong()))
    }

    @Test
    fun named_keys_and_their_second_keysyms() {
        assertEquals(0x24, x11KeyNumber(0xFF0DL))
        assertEquals(0x24, x11KeyNumber(0xFF8DL))
        assertEquals(0x30, x11KeyNumber(0xFF09L))
        assertEquals(0x30, x11KeyNumber(0xFE20L))
        assertEquals(0x31, x11KeyNumber(0x20L))
        assertEquals(0x33, x11KeyNumber(0xFF08L))
        assertEquals(0x35, x11KeyNumber(0xFF1BL))
        assertEquals(0x75, x11KeyNumber(0xFFFFL))
        assertEquals(0x72, x11KeyNumber(0xFF63L))
        assertEquals(0x7B, x11KeyNumber(0xFF51L))
        assertEquals(0x7E, x11KeyNumber(0xFF52L))
    }

    @Test
    fun every_function_key_and_the_menu_key_has_its_number_on_the_board() {
        // XK_F1 to XK_F12 from keysymdef.h, against the board's F1 to F12.
        val keysyms = (0xFFBEL..0xFFC9L).toList()
        val board = listOf(0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F)
        assertEquals(board, keysyms.map { x11KeyNumber(it) })
        assertEquals(0x6E, x11KeyNumber(0xFF67L))
    }

    @Test
    fun shift_f10_arrives_as_f10_with_shift_held() {
        val down = keyEventsFor(press = true, state = X11_SHIFT_MASK, keysym = 0xFFC7L, text = "").single()
        assertEquals(WindowEvent.KEY_DOWN, down.kind)
        assertEquals(0x6D, down.keyCode)
        assertEquals(x11Modifiers(X11_SHIFT_MASK), down.modifiers)
        val up = keyEventsFor(press = false, state = X11_SHIFT_MASK, keysym = 0xFFC7L, text = "").single()
        assertEquals(0x6D, up.keyCode)
        val menu = keyEventsFor(press = true, state = 0, keysym = 0xFF67L, text = "").single()
        assertEquals(0x6E, menu.keyCode)
    }

    @Test
    fun a_key_with_no_meaning_is_minus_one_never_zero() {
        assertEquals(NO_KEY, x11KeyNumber(0xFD00L))
        assertNotEquals(0, x11KeyNumber(0xFFBEL))
    }

    @Test
    fun each_modifier_lands_in_its_own_bit() {
        assertEquals(1 shl 17, x11Modifiers(X11_SHIFT_MASK))
        assertEquals(1 shl 18, x11Modifiers(X11_CONTROL_MASK))
        assertEquals(1 shl 19, x11Modifiers(X11_MOD1_MASK))
        assertEquals(1 shl 20, x11Modifiers(X11_MOD4_MASK))
        assertEquals(0, x11Modifiers(0))
    }

    @Test
    fun control_alt_and_super_make_a_shortcut_and_shift_does_not() {
        assertEquals(true, x11IsShortcut(X11_CONTROL_MASK))
        assertEquals(true, x11IsShortcut(X11_MOD1_MASK))
        assertEquals(true, x11IsShortcut(X11_MOD4_MASK))
        assertEquals(false, x11IsShortcut(X11_SHIFT_MASK))
    }

    @Test
    fun shift_delete_cuts_as_control_x_and_shift_insert_stays_insert() {
        val down = keyEventsFor(press = true, state = X11_SHIFT_MASK, keysym = 0xFFFFL, text = "").single()
        assertEquals(0x07, down.keyCode)
        assertEquals(x11Modifiers(X11_CONTROL_MASK), down.modifiers)
        val plain = keyEventsFor(press = true, state = 0, keysym = 0xFFFFL, text = "").single()
        assertEquals(0x75, plain.keyCode)
        val insert = keyEventsFor(press = true, state = X11_SHIFT_MASK, keysym = 0xFF63L, text = "").single()
        assertEquals(0x72, insert.keyCode)
    }
}
