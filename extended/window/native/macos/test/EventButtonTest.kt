package org.thisisthepy.compose.window.macos

import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** The buttons AppKit numbers in its other-mouse events reach the scene as the buttons they are. */
class EventButtonTest {
    @Test
    fun the_other_mouse_buttons_are_middle_back_and_forward() {
        assertEquals(WindowEvent.MIDDLE_BUTTON, eventButtonOfMacButton(2))
        assertEquals(WindowEvent.BACK_BUTTON, eventButtonOfMacButton(3))
        assertEquals(WindowEvent.FORWARD_BUTTON, eventButtonOfMacButton(4))
        assertEquals(0, eventButtonOfMacButton(5))
    }

    @Test
    fun the_held_bits_of_the_systems_mask_are_the_window_events() {
        // NSEvent.pressedMouseButtons sets bit n for button n: left, right, middle, back, forward.
        assertEquals(1 shl 0, WindowEvent.PRIMARY_HELD)
        assertEquals(1 shl 1, WindowEvent.SECONDARY_HELD)
        assertEquals(1 shl 2, WindowEvent.MIDDLE_HELD)
        assertEquals(1 shl 3, WindowEvent.BACK_HELD)
        assertEquals(1 shl 4, WindowEvent.FORWARD_HELD)
    }
}
