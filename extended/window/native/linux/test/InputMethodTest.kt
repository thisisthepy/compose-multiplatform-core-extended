package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The input style this window asks the input method for, and which locales can carry Hangul.
 * The composition and key arithmetic is tested in the common module with the code it covers.
 */
class InputMethodTest {

    private val callbacks = 0x0002L
    private val nothing = 0x0008L
    private val none = 0x0010L
    private val statusNothing = 0x0400L
    private val statusNone = 0x0800L
    private val area = 0x0001L

    private fun choose(vararg offered: Long) =
        chooseInputStyle(offered.toList(), callbacks, nothing, none, statusNothing, statusNone)

    /** The style that puts the composition in the field is asked for whenever it is offered. */
    @Test
    fun fr5_preedit_callbacks_are_preferred_over_the_input_method_drawing_its_own_window() {
        assertEquals(
            callbacks or statusNothing,
            choose(nothing or statusNothing, callbacks or statusNothing, area or statusNothing),
        )
        assertEquals(callbacks or statusNone, choose(callbacks or statusNone, nothing or statusNothing))
    }

    @Test
    fun fr5_an_input_method_without_callbacks_is_still_typed_through() {
        assertEquals(nothing or statusNothing, choose(nothing or statusNothing, area or statusNothing))
        assertEquals(none or statusNone, choose(none or statusNone))
    }

    @Test
    fun fr5_an_input_method_that_offers_no_style_this_window_can_give_is_refused() {
        assertNull(choose(area or statusNothing))
        assertNull(choose())
    }

    @Test
    fun fr5_only_a_utf8_locale_can_carry_hangul() {
        assertTrue(isUtf8Locale("ko_KR.UTF-8"))
        assertTrue(isUtf8Locale("C.utf8"))
        assertTrue(isUtf8Locale("en_US.UTF-8@euro"))
        assertFalse(isUtf8Locale("C"))
        assertFalse(isUtf8Locale("ko_KR.eucKR"))
        assertFalse(isUtf8Locale(null))
    }
}
