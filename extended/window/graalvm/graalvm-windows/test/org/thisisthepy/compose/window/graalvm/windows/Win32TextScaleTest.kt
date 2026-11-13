package org.thisisthepy.compose.window.graalvm.windows

import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The text size change the C side writes as a percent reaches the window as a multiplier. */
class Win32TextScaleTest {

    private fun raw(kind: Int, x: Float) = WindowEvent(
        kind = kind, x = x, y = 0f, buttons = 0, modifiers = 0, keyCode = 0, codePoint = 0, text = "",
    )

    @Test
    fun a_text_scale_event_turns_the_percent_into_a_multiplier() {
        assertEquals(WindowEvent.TextScaleChanged(1.5f), win32Event(raw(WindowEvent.TEXT_SCALE_CHANGED, 150f)))
    }

    @Test
    fun a_slider_never_moved_reads_as_the_default() {
        assertEquals(WindowEvent.TextScaleChanged(1f), win32Event(raw(WindowEvent.TEXT_SCALE_CHANGED, 0f)))
    }

    @Test
    fun every_other_event_passes_through_unchanged() {
        val move = raw(WindowEvent.TEXT_SCALE_CHANGED - 1, 150f)
        assertSame(move, win32Event(move))
    }
}
