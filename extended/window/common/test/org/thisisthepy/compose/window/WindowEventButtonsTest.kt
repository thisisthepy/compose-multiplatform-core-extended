package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `buttons` field every platform layer fills in and the scene reads. The C layers
 * (x11_buttons.h, win32_buttons.h, appkit_window.m) mirror these values and pin them in their
 * own tests, so a number changed here fails there.
 */
class WindowEventButtonsTest {

    private fun event(kind: Int, buttons: Int) = WindowEvent(kind, 1f, 2f, buttons, 0, 0, 0, "")

    @Test
    fun the_encoding_is_the_one_the_c_layers_mirror() {
        assertEquals(1, WindowEvent.PRIMARY_HELD)
        assertEquals(2, WindowEvent.SECONDARY_HELD)
        assertEquals(4, WindowEvent.MIDDLE_HELD)
        assertEquals(1 shl 16, WindowEvent.SECONDARY_BUTTON)
        assertEquals(
            0,
            WindowEvent.SECONDARY_BUTTON and (WindowEvent.PRIMARY_HELD or WindowEvent.SECONDARY_HELD or WindowEvent.MIDDLE_HELD),
            "the event's own button must not overlap the held bits",
        )
    }

    @Test
    fun a_secondary_press_is_seen_whatever_is_held() {
        for (held in listOf(0, WindowEvent.SECONDARY_HELD, WindowEvent.PRIMARY_HELD, WindowEvent.PRIMARY_HELD or WindowEvent.SECONDARY_HELD)) {
            val buttons = WindowEvent.pointerButtons(held, secondaryEvent = true)
            assertTrue(event(WindowEvent.POINTER_DOWN, buttons).isSecondaryPress, "held $held")
        }
    }

    @Test
    fun only_the_press_of_the_secondary_button_is_a_secondary_press() {
        val secondary = WindowEvent.pointerButtons(WindowEvent.SECONDARY_HELD, secondaryEvent = true)
        assertFalse(event(WindowEvent.POINTER_UP, secondary).isSecondaryPress)
        assertFalse(event(WindowEvent.POINTER_MOVE, secondary).isSecondaryPress)
        val primary = WindowEvent.pointerButtons(WindowEvent.PRIMARY_HELD, secondaryEvent = false)
        assertFalse(event(WindowEvent.POINTER_DOWN, primary).isSecondaryPress)
        assertEquals(WindowEvent.PRIMARY_HELD, primary, "a primary press adds nothing to what is held")
    }

    @Test
    fun a_control_click_is_a_secondary_press_with_only_the_primary_held() {
        val buttons = WindowEvent.pointerButtons(WindowEvent.PRIMARY_HELD, secondaryEvent = true)
        assertEquals(0, buttons and WindowEvent.SECONDARY_HELD)
        assertTrue(event(WindowEvent.POINTER_DOWN, buttons).isSecondaryPress)
    }
}
