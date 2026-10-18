package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosTitleBarTest {
    @Test
    fun fr19_4_a_window_that_says_nothing_runs_its_content_under_a_bar_that_draws_no_title() {
        val bar = MacosTitleBar.of(WindowConfig("app", 400, 300))
        assertTrue(bar.fullSizeContentView, "the content runs under the title bar")
        assertTrue(bar.titlebarAppearsTransparent, "the bar draws nothing over the content")
        assertTrue(bar.titleHidden, "the title is carried but not drawn")
        assertTrue(bar.unifiedToolbar, "the ordinary window has the unified toolbar's place and radius")
    }

    @Test
    fun fr19_7_simple_differs_from_normal_only_in_the_toolbar() {
        val normal = MacosTitleBar.of(WindowChrome.Modern, TitleBarStyle.Normal)
        val simple = MacosTitleBar.of(WindowChrome.Modern, TitleBarStyle.Simple)
        assertEquals(normal.copy(unifiedToolbar = false), simple)
        assertFalse(simple.unifiedToolbar)
    }

    @Test
    fun fr19_4_the_system_title_bar_ignores_the_style_and_draws_its_title() {
        for (style in TitleBarStyle.entries) {
            val bar = MacosTitleBar.of(WindowChrome.System, style)
            assertEquals(MacosTitleBar.System, bar, "under $style")
            assertFalse(bar.fullSizeContentView)
            assertFalse(bar.titleHidden)
        }
    }

    @Test
    fun fr19_7_the_caption_is_what_the_window_measured_with_the_tables_radius() {
        val toolbar = macosCaption(MacosTitleBar.Normal, 700.0, 648.0, 20.0, 88.0, cornerRadius = 26.75)
        val plain = macosCaption(MacosTitleBar.Simple, 700.0, 672.0, 7.0, 75.0, cornerRadius = 16.75)
        assertEquals(WindowCaption(52f, 108f, true, cornerRadius = 26.75f), toolbar)
        assertEquals(WindowCaption(28f, 82f, true, cornerRadius = 16.75f), plain)
    }

    @Test
    fun fr19_4_the_system_draws_the_buttons_so_the_scene_does_not() {
        val caption = macosCaption(MacosTitleBar.Normal, 700.0, 648.0, 20.0, 88.0, cornerRadius = 10.25)
        assertEquals(false, caption?.buttonsDrawnByScene)
        assertEquals(true, caption?.buttonsAtStart)
    }

    @Test
    fun fr19_7_the_radius_comes_from_the_measured_table_for_each_release() {
        for ((major, normal, simple) in listOf(Triple(14, 10.25, 10.25), Triple(15, 10.25, 10.25), Triple(26, 26.75, 16.75))) {
            val n = macosCaption(MacosTitleBar.Normal, 700.0, 648.0, 20.0, 88.0, macosCornerRadius(true, major))
            val s = macosCaption(MacosTitleBar.Simple, 700.0, 672.0, 7.0, 75.0, macosCornerRadius(false, major))
            assertEquals(normal.toFloat(), n?.cornerRadius, "Normal on macOS $major")
            assertEquals(simple.toFloat(), s?.cornerRadius, "Simple on macOS $major")
        }
    }

    @Test
    fun a_window_under_the_system_title_bar_has_no_caption() {
        assertEquals(WindowCaption.None, macosCaption(MacosTitleBar.System, 700.0, 672.0, 7.0, 75.0, 10.25))
    }

    @Test
    fun a_window_between_sizes_keeps_its_last_caption() {
        assertNull(macosCaption(MacosTitleBar.Normal, 600.0, 648.0, 20.0, 88.0, 26.75))
    }

    @Test
    fun a_window_with_no_buttons_reserves_no_room_for_them_and_no_radius_where_the_table_has_none() {
        val caption = macosCaption(MacosTitleBar.Simple, 700.0, 672.0, null, null, cornerRadius = null)
        assertEquals(WindowCaption(28f, 0f, true, cornerRadius = 0f), caption)
    }
}
