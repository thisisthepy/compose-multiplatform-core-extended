package org.thisisthepy.compose.window.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The numbers and lines the metrics run prints for its drag made of mouse events. */
class DragMetricsTest {
    @Test
    fun metrics_event_drag_widened_by_is_the_width_difference() {
        assertEquals(450, DragMetrics.widenedBy(1200.0, 1650.0))
        assertEquals(0, DragMetrics.widenedBy(1200.0, 1200.0))
        assertEquals(-20, DragMetrics.widenedBy(1200.0, 1180.0))
    }

    @Test
    fun metrics_event_drag_line_has_the_format_the_check_reads() {
        assertEquals(
            "compose-rust: metrics event-drag widened_by=450 events=300",
            DragMetrics.eventDragLine(450, 300),
        )
    }

    @Test
    fun metrics_capture_match_is_the_share_of_pixels_that_agreed() {
        assertEquals(0.99, DragMetrics.matchFraction(99, 100))
        assertEquals(0.0, DragMetrics.matchFraction(0, 0))
    }

    @Test
    fun metrics_capture_line_carries_a_match_the_check_can_parse() {
        val line = DragMetrics.captureLine(100, 2400, 1800, 2.0, 2.0, 0.99876)
        assertEquals(
            "compose-rust: metrics capture at=100 window=2400x1800 image_scale=2.0 backing_scale=2.0 match=0.9988",
            line,
        )
    }

    @Test
    fun metrics_check_pattern_has_both_colours() {
        val whites = (0 until 64).flatMap { by -> (0 until 64).map { bx -> DragMetrics.blockIsWhite(bx, by) } }
        assertTrue(whites.any { it } && whites.any { !it })
    }
}
