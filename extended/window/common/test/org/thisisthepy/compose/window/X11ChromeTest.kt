package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class X11ChromeTest {
    private val modern = WindowConfig("w", 320, 240)

    @Test
    fun fr19_4_linux_a_modern_window_has_no_frame_of_the_window_managers() {
        assertFalse(X11Chrome.keepsFrame(modern), "Modern is the default and takes the frame off")
        assertTrue(X11Chrome.keepsFrame(modern.copy(chrome = WindowChrome.System)))
        assertFalse(
            X11Chrome.keepsFrame(modern.copy(chrome = WindowChrome.System, decorated = false)),
            "a window asked for no decorations has none under either",
        )
    }

    @Test
    fun fr19_4_linux_the_scene_draws_the_caption_and_its_buttons_at_the_trailing_end() {
        val caption = X11Chrome.caption(modern)
        assertEquals(X11Chrome.NORMAL_CAPTION_HEIGHT, caption.height)
        assertEquals(X11Chrome.NORMAL_CAPTION_HEIGHT * 3, caption.buttonsWidth)
        assertTrue(caption.buttonsDrawnByScene)
        assertFalse(caption.buttonsAtStart)
    }

    @Test
    fun fr19_4_linux_normal_and_simple_differ_only_in_the_captions_height() {
        val normal = X11Chrome.caption(modern)
        val simple = X11Chrome.caption(modern.copy(titleBar = TitleBarStyle.Simple))
        assertEquals(X11Chrome.SIMPLE_CAPTION_HEIGHT, simple.height)
        assertTrue(simple.height < normal.height)
        assertEquals(normal.buttonsAtStart, simple.buttonsAtStart)
        assertEquals(normal.buttonsDrawnByScene, simple.buttonsDrawnByScene)
    }

    @Test
    fun fr19_4_linux_a_system_frame_and_full_screen_have_no_caption() {
        assertEquals(WindowCaption.None, X11Chrome.caption(modern.copy(chrome = WindowChrome.System)))
        assertEquals(WindowCaption.None, X11Chrome.caption(modern, fullscreen = true))
    }

    @Test
    fun fr19_7_linux_each_edge_has_the_ewmh_direction() {
        // _NET_WM_MOVERESIZE_SIZE_* in the EWMH specification, and MOVE after them.
        val expected = mapOf(
            WindowEdge.TopLeft to 0, WindowEdge.Top to 1, WindowEdge.TopRight to 2, WindowEdge.Right to 3,
            WindowEdge.BottomRight to 4, WindowEdge.Bottom to 5, WindowEdge.BottomLeft to 6, WindowEdge.Left to 7,
        )
        for ((edge, direction) in expected) assertEquals(direction, X11Chrome.direction(edge), edge.name)
        assertEquals(8, X11Chrome.MOVE)
    }

    @Test
    fun fr19_7_linux_two_presses_close_in_time_and_place_are_a_double_click() {
        val gesture = CaptionGesture()
        assertFalse(gesture.move(1_000_000_000L, 50f, 10f))
        gesture.released()
        assertTrue(gesture.move(1_300_000_000L, 52f, 11f))
        assertFalse(gesture.waiting, "a double click moves nothing")
        assertFalse(gesture.move(1_400_000_000L, 52f, 11f), "a third press starts again")
    }

    @Test
    fun fr19_7_linux_presses_too_far_apart_are_two_single_ones() {
        val late = CaptionGesture()
        assertFalse(late.move(0L, 50f, 10f))
        assertFalse(late.move(500_000_000L, 50f, 10f), "later than the double click time")
        val moved = CaptionGesture()
        assertFalse(moved.move(0L, 50f, 10f))
        assertFalse(moved.move(100_000_000L, 80f, 10f), "further than the double click distance")
    }

    @Test
    fun fr19_7_linux_the_move_is_handed_over_once_the_pointer_goes() {
        val gesture = CaptionGesture()
        gesture.move(0L, 50f, 10f)
        assertEquals(CaptionGesture.NONE, gesture.moved(52f, 11f), "a hand that shakes is not a drag")
        assertEquals(X11Chrome.MOVE, gesture.moved(60f, 10f))
        assertEquals(CaptionGesture.NONE, gesture.moved(90f, 10f), "handed over once")
    }

    @Test
    fun fr19_7_linux_a_click_let_go_before_moving_hands_nothing_over() {
        val gesture = CaptionGesture()
        gesture.move(0L, 50f, 10f)
        gesture.released()
        assertEquals(CaptionGesture.NONE, gesture.moved(90f, 10f))
    }

    @Test
    fun fr19_7_linux_an_edge_waits_for_the_pointer_too_and_names_its_direction() {
        val gesture = CaptionGesture()
        gesture.resize(X11Chrome.direction(WindowEdge.Left), 2f, 100f)
        assertTrue(gesture.waiting)
        assertEquals(7, gesture.moved(-10f, 100f))
    }
}
