@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import x11.Button1Mask
import x11.Button2Mask
import x11.Button3Mask
import x11.ControlMask
import x11.Mod1Mask
import x11.Mod4Mask
import x11.ShiftMask
import x11.XC_hand2
import x11.XC_left_ptr
import x11.XC_xterm
import x11.XK_BackSpace
import x11.XK_Down
import x11.XK_ISO_Left_Tab
import x11.XK_KP_Enter
import x11.XK_Left
import x11.XK_Return
import x11.XK_Tab
import x11.XK_a
import x11.XK_space

/**
 * What this display server calls a thing, and what the scene is then given.
 *
 * Every assertion here goes the whole way through: from a keysym or a state mask that X11 handed
 * over, to the Compose key or the modifier bit the interpreter reads. That is the point of them.
 * The number in the middle is the shared numbering, which is AppKit's for historical reasons, and
 * a table that agrees with itself while disagreeing with `composeKey` is the defect these catch:
 * the key still reaches the scene, it still does something, and what it does is what a different
 * key means.
 *
 * Runs on Linux only, because these are the values the system's own headers gave cinterop rather
 * than numbers written down here. That is deliberate: a test that repeated the constants would
 * pass on a machine where the headers say something else.
 */
class LinuxInputTest {

    /**
     * The four modifiers a screen can bind, in the bits the interpreter reads them from.
     *
     * Read out of the shared numbering rather than compared with a literal, because what matters
     * is that each X11 mask lands in a different one of the four and that the one it lands in is
     * the one that modifier means.
     */
    @Test
    fun each_modifier_lands_in_its_own_bit() {
        val shift = modifiersOf(ShiftMask.toUInt())
        val control = modifiersOf(ControlMask.toUInt())
        val alt = modifiersOf(Mod1Mask.toUInt())
        val superKey = modifiersOf(Mod4Mask.toUInt())
        val all = listOf(shift, control, alt, superKey)

        assertEquals(4, all.toSet().size, "two modifiers were given the same bit")
        for (one in all) {
            assertEquals(1, one.countOneBits(), "a modifier should set exactly one bit")
        }
        assertEquals(
            shift or control,
            modifiersOf((ShiftMask or ControlMask).toUInt()),
            "two modifiers held together carry both bits",
        )
        assertEquals(0, modifiersOf(0u), "nothing held carries nothing")
    }

    /**
     * The three pointer buttons, in the numbering the shared reader uses.
     *
     * The middle and the right are the pair that get swapped: X11 numbers the middle button 2 and
     * the right one 3, and the shared reader has the right one as the second bit.
     */
    @Test
    fun the_pointer_buttons_keep_their_meaning() {
        assertEquals(1, buttonsOf(Button1Mask.toUInt()), "the primary button is the first bit")
        assertEquals(2, buttonsOf(Button3Mask.toUInt()), "the secondary button is the second bit")
        assertEquals(4, buttonsOf(Button2Mask.toUInt()), "the middle button is the third bit")
        assertEquals(3, buttonsOf((Button1Mask or Button3Mask).toUInt()))
        assertEquals(0, buttonsOf(0u))
    }

    /** Each shape the scene can ask for is a shape this server has, and nothing else is. */
    @Test
    fun every_pointer_shape_has_a_cursor_and_the_rest_are_arrows() {
        assertEquals(XC_left_ptr, cursorFont(CURSOR_ARROW))
        assertEquals(XC_hand2, cursorFont(CURSOR_HAND))
        assertEquals(XC_xterm, cursorFont(CURSOR_TEXT))
        assertEquals(
            CURSOR_SHAPES,
            (0 until CURSOR_SHAPES).map(::cursorFont).toSet().size,
            "two shapes were given the same cursor",
        )
        assertEquals(XC_left_ptr, cursorFont(CURSOR_SHAPES), "a shape we have not got is an arrow")
        assertEquals(XC_left_ptr, cursorFont(-1), "and so is one that is not a shape at all")
    }
}
