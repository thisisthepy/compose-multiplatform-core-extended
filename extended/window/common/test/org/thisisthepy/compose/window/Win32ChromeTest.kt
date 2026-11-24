package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Win32ChromeTest {
    private val modern = WindowConfig("w", 320, 240)

    @Test
    fun fr19_4_windows_a_modern_window_gives_its_caption_strip_to_the_scene() {
        assertFalse(Win32Chrome.systemCaption(modern), "Modern is the default and takes the strip")
        assertTrue(Win32Chrome.systemCaption(modern.copy(chrome = WindowChrome.System)))
    }

    @Test
    fun fr19_4_windows_the_scene_draws_three_buttons_at_the_trailing_end_of_a_32_point_strip() {
        val caption = Win32Chrome.caption(modern.copy(titleBar = TitleBarStyle.Simple))
        assertEquals(32f, caption.height)
        assertEquals(46f * 3, caption.buttonsWidth)
        assertTrue(caption.buttonsDrawnByScene)
        assertFalse(caption.buttonsAtStart)
        assertEquals(0f, caption.insetTop)
    }

    @Test
    fun fr19_4_windows_the_frame_stays_the_systems_so_the_scene_draws_no_resize_edges() {
        assertTrue(Win32Chrome.caption(modern).frameBySystem)
        assertFalse(X11Chrome.caption(modern).frameBySystem, "an X11 window without its frame has edges only the scene can give it")
    }

    @Test
    fun fr19_4_windows_a_maximised_window_keeps_its_strip_and_says_so() {
        assertFalse(Win32Chrome.caption(modern).maximized)
        val maximized = Win32Chrome.caption(modern, maximized = true)
        assertTrue(maximized.maximized)
        assertEquals(Win32Chrome.caption(modern), maximized.copy(maximized = false))
    }

    @Test
    fun fr19_4_windows_the_systems_caption_leaves_the_scene_nothing_to_draw() {
        val system = modern.copy(chrome = WindowChrome.System)
        assertEquals(WindowCaption.None, Win32Chrome.caption(system))
        assertEquals(WindowCaption.None, Win32Chrome.caption(system, maximized = true))
    }

    @Test
    fun fr19_7_windows_the_title_bar_style_sets_the_caption_height() {
        val normal = Win32Chrome.caption(modern)
        val simple = Win32Chrome.caption(modern.copy(titleBar = TitleBarStyle.Simple))
        assertEquals(Win32Chrome.NORMAL_CAPTION_HEIGHT, normal.height, "Normal, the default, is the tall caption")
        assertEquals(Win32Chrome.CAPTION_HEIGHT, simple.height, "Simple is the platform's 32 points")
        assertTrue(normal.height != simple.height, "the two modes differ in height (FR-19.7 criterion 2)")
        // The buttons stay where Windows puts them: three of 46 points at the trailing end.
        assertEquals(normal.buttonsWidth, simple.buttonsWidth)
        assertFalse(normal.buttonsAtStart)
        // The hit test is told the same height the scene draws.
        assertEquals(normal.height.toInt(), Win32Chrome.captionHeightDip(modern))
        assertEquals(32, Win32Chrome.captionHeightDip(modern.copy(titleBar = TitleBarStyle.Simple)))
    }

    @Test
    fun fr19_7_windows_the_systems_caption_ignores_the_title_bar_style() {
        val system = modern.copy(chrome = WindowChrome.System)
        assertEquals(Win32Chrome.caption(system), Win32Chrome.caption(system.copy(titleBar = TitleBarStyle.Simple)))
    }
}
