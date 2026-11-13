package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The geometry and the focus rules every platform's native view slots share. */
class NativeViewsTest {

    @Test
    fun the_new_event_kinds_follow_the_existing_ones_and_carry_the_slot_id() {
        assertEquals(19, WindowEvent.NATIVE_VIEW_FOCUS_IN)
        assertEquals(20, WindowEvent.NATIVE_VIEW_FOCUS_OUT)
        assertEquals(21, WindowEvent.POINTER_ENTER)
        assertEquals(22, WindowEvent.POINTER_EXIT)
        assertEquals(7, WindowEvent.NativeViewFocusIn(7).nativeViewId)
        assertEquals(WindowEvent.NATIVE_VIEW_FOCUS_OUT, WindowEvent.NativeViewFocusOut(7).kind)
    }

    @Test
    fun a_platform_has_no_native_views_unless_it_says_so() {
        assertNull(unusedPlatform().nativeViews)
    }

    @Test
    fun a_rect_holds_its_top_left_edge_and_not_its_bottom_right_one() {
        val rect = PixelRect(10, 20, 30, 40)
        assertTrue(rect.contains(10.0, 20.0))
        assertTrue(rect.contains(39.9, 59.9))
        assertFalse(rect.contains(40.0, 30.0))
        assertFalse(rect.contains(20.0, 60.0))
        assertFalse(PixelRect(0, 0, 0, 10).contains(0.0, 0.0))
        assertEquals(PixelRect(20, 30, 20, 30), rect.intersect(PixelRect(20, 30, 100, 100)))
        assertTrue(rect.intersect(PixelRect(100, 100, 5, 5)).isEmpty)
    }

    @Test
    fun a_rounded_rect_leaves_out_the_corner_beyond_its_arc() {
        val round = PixelRoundRect(0, 0, 100, 100, cornerRadius = 20)
        assertFalse(round.contains(1.0, 1.0), "the very corner")
        assertTrue(round.contains(20.0, 1.0), "the top edge past the corner")
        assertTrue(round.contains(6.0, 6.0), "inside the arc: (14, 14) from its centre is under 20")
        assertFalse(round.contains(98.5, 98.5), "the bottom right corner")
        assertTrue(round.contains(50.0, 50.0))
        assertEquals(50, PixelRoundRect(0, 0, 100, 100, cornerRadius = 80).radius, "at most half the shorter side")
    }

    @Test
    fun a_region_shows_inside_its_frame_and_clip_and_outside_its_holes() {
        val region = NativeViewRegion(
            frame = PixelRect(100, 100, 200, 200),
            clip = PixelRect(0, 150, 1000, 1000),
            holes = listOf(PixelRoundRect(250, 250, 100, 100, cornerRadius = 10)),
        )
        assertFalse(region.contains(150.0, 120.0), "above the clip")
        assertTrue(region.contains(150.0, 160.0))
        assertFalse(region.contains(260.0, 260.0), "in the hole")
        assertTrue(region.contains(251.0, 251.0), "in the hole's rounded-off corner, so the view shows")
        assertFalse(region.contains(320.0, 160.0), "outside the frame")
        assertEquals(PixelRect(100, 150, 200, 150), region.bounds)
        assertFalse(region.isWholeFrame)
        assertTrue(NativeViewRegion(PixelRect(0, 0, 10, 10)).isWholeFrame)
        assertTrue(NativeViewRegion(PixelRect(0, 0, 10, 10), holes = listOf(PixelRoundRect(20, 20, 5, 5))).isWholeFrame)
    }

    @Test
    fun overlapping_holes_are_told_apart_from_disjoint_ones() {
        val frame = PixelRect(0, 0, 100, 100)
        assertTrue(NativeViewRegion(frame, holes = listOf(PixelRoundRect(0, 0, 10, 10), PixelRoundRect(10, 0, 10, 10))).holesAreDisjoint)
        val overlapping = NativeViewRegion(frame, holes = listOf(PixelRoundRect(0, 0, 20, 20), PixelRoundRect(10, 10, 20, 20)))
        assertFalse(overlapping.holesAreDisjoint)
        assertFalse(overlapping.contains(15.0, 15.0), "the overlap of two holes is a hole")
    }

    @Test
    fun focus_moving_between_scene_and_slots_says_out_then_in() {
        val focus = NativeViewFocus()
        assertEquals(listOf(WindowEvent.NativeViewFocusIn(1)), focus.responderChanged(1))
        assertEquals(emptyList(), focus.responderChanged(1), "no change, nothing to say")
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(1), WindowEvent.NativeViewFocusIn(2)), focus.responderChanged(2))
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(2)), focus.responderChanged(null))
        assertNull(focus.focused)
    }

    @Test
    fun a_window_that_stops_being_key_says_out_and_says_in_again_when_it_is_key() {
        val focus = NativeViewFocus()
        focus.responderChanged(3)
        assertEquals(listOf(WindowEvent.NativeViewFocusOut(3)), focus.keyChanged(false))
        assertEquals(emptyList(), focus.keyChanged(false))
        assertEquals(listOf(WindowEvent.NativeViewFocusIn(3)), focus.keyChanged(true))
        focus.responderChanged(null)
        assertEquals(emptyList(), focus.keyChanged(false), "the scene had focus: no slot to report")
        assertEquals(emptyList(), focus.keyChanged(true))
    }

    @Test
    fun a_change_while_the_window_is_not_key_is_reported_when_it_is_key_again() {
        val focus = NativeViewFocus(key = false)
        assertEquals(emptyList(), focus.responderChanged(4))
        assertEquals(4, focus.focused)
        assertEquals(listOf(WindowEvent.NativeViewFocusIn(4)), focus.keyChanged(true))
    }

    private fun unusedPlatform(): WindowPlatform = object : WindowPlatform {
        override val name = "none"
        override fun open(config: WindowConfig, listener: WindowListener) = false
        override fun pump(timeoutMillis: Long) {}
        override fun measure() = WindowMeasurement(0, 0, 1f)
        override fun requestFrame() {}
        override fun present(drawnWidth: Int, drawnHeight: Int) = FramePresentRecord(drawnWidth, drawnHeight, 0, 0)
        override fun systemTheme() = SystemTheme.Light
        override fun setTitle(title: String) {}
        override fun setMinimumSize(width: Int, height: Int) {}
        override fun setVisibility(visibility: WindowVisibility) {}
        override fun readClipboardText(): String? = null
        override fun writeClipboardText(text: String) {}
        override fun setImeSpot(x: Int, y: Int) {}
        override fun showContextMenu(items: List<ContextMenuItem>) {}
        override fun close() {}
    }
}
