package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The resize accounting, fed the sequences `appkit_resize.h` is fed in C, because the Linux
 * windows' report has to mean what the AppKit window's does.
 */
class ResizeStatsTest {
    private fun counted(run: (ResizeStats) -> Unit): List<Long> {
        val stats = ResizeStats()
        run(stats)
        return listOf(stats.steps, stats.presented, stats.stale, stats.stretched)
    }

    @Test
    fun parity_a_drag_with_a_frame_at_every_size_counts_nothing_stretched_or_stale() {
        val result = counted { stats ->
            for (step in 1..60) {
                stats.step(480 + step * 2, 640 + step * 2)
                stats.present(480 + step * 2, 640 + step * 2)
            }
        }
        assertEquals(listOf(60L, 60L, 0L, 0L), result)
    }

    @Test
    fun parity_a_drag_with_no_frames_counts_every_size_stretched() {
        val result = counted { stats ->
            for (step in 1..60) stats.step(480 + step * 2, 640 + step * 2)
        }
        assertEquals(listOf(60L, 0L, 0L, 60L), result)
    }

    @Test
    fun parity_a_frame_at_the_previous_size_counts_stale_and_leaves_the_size_stretched() {
        val result = counted { stats ->
            stats.step(500, 700)
            stats.present(480, 640)
        }
        assertEquals(listOf(1L, 1L, 1L, 1L), result)
    }

    @Test
    fun parity_a_frame_for_a_size_the_window_already_left_is_stale() {
        val stats = ResizeStats()
        stats.step(500, 700)
        // The server already says 520 by 720, though no event has said so yet.
        stats.presentAgainst(500, 700, 520, 720)
        stats.presentAgainst(520, 720, 520, 720)
        assertEquals(2L, stats.presented)
        assertEquals(1L, stats.stale)
        assertEquals(0.5, stats.staleRatio)
    }

    @Test
    fun parity_the_report_line_is_the_one_the_parity_script_reads() {
        val stats = ResizeStats()
        assertEquals("dxc resize: steps=0 presented=0 stale=0 stretched=0 stale_ratio=0.0000", stats.report())
        stats.step(10, 10)
        stats.present(10, 10)
        stats.step(20, 20)
        stats.present(10, 10)
        stats.present(20, 20)
        assertEquals("dxc resize: steps=2 presented=3 stale=1 stretched=0 stale_ratio=0.3333", stats.report())
    }

    @Test
    fun parity_reset_starts_the_count_again() {
        val stats = ResizeStats()
        stats.step(10, 10)
        stats.reset()
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(stats.steps, stats.presented, stats.stale, stats.stretched))
    }
}
