package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.window.EventClock
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two presses count as a double click by when the platform says they happened, against the
 * platform's double-click interval, and not by when the scene read them against Compose's
 * 300 ms. A frame that took long between them changes nothing.
 */
class SceneDoubleClickTest {

    private class Clicked(val selection: TextRange, val timeout: Long)

    /**
     * Clicks the start of "hello world" twice, [gapMillis] apart by event time, on a platform
     * whose interval is [interval]. [slowFrameMillis] is how much later than that the scene's
     * own clock reads the second press.
     */
    private fun twoClicks(interval: Long, gapMillis: Long, slowFrameMillis: Long = 0): Clicked {
        val platform = FakePlatform().apply { doubleClick = interval }
        val state = TextFieldState("hello world")
        var timeout = -1L
        val host = ComposeWindowHost(platform, WindowConfig("test", 200, 100), { FakeSurface() }) {
            timeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                BasicTextField(state, Modifier.size(180.dp, 40.dp))
            }
        }
        var now = 50_000L
        host.eventClock = EventClock { now }
        assertTrue(host.open())
        host.turn(0)
        fun click(at: Long) {
            platform.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 10f, 10f, WindowEvent.PRIMARY_HELD, 0, 0, 0, "", at))
            platform.queued.add(WindowEvent(WindowEvent.POINTER_UP, 10f, 10f, 0, 0, 0, 0, "", at + 20))
            repeat(4) { host.turn(0) }
        }
        val first = 3_000_000L
        click(first)
        now += gapMillis + slowFrameMillis
        click(first + gapMillis)
        val selection = state.selection
        host.close()
        return Clicked(selection, timeout)
    }

    @Test
    fun the_platforms_interval_is_compose_double_tap_timeout() {
        assertEquals(500L, twoClicks(interval = 500, gapMillis = 400).timeout)
        assertEquals(300L, twoClicks(interval = 300, gapMillis = 400).timeout)
    }

    @Test
    fun two_presses_400_ms_apart_are_a_double_click_with_a_500_ms_interval() {
        assertEquals(TextRange(0, 5), twoClicks(interval = 500, gapMillis = 400).selection, "the word is selected")
    }

    @Test
    fun two_presses_400_ms_apart_are_two_single_clicks_with_a_300_ms_interval() {
        val selection = twoClicks(interval = 300, gapMillis = 400).selection
        assertTrue(selection.collapsed, "a caret, not a word: $selection")
    }

    @Test
    fun a_slow_frame_between_the_presses_does_not_change_the_count() {
        assertEquals(TextRange(0, 5), twoClicks(interval = 500, gapMillis = 400, slowFrameMillis = 3_000).selection)
        val single = twoClicks(interval = 300, gapMillis = 400, slowFrameMillis = 3_000).selection
        assertTrue(single.collapsed, "a caret, not a word: $single")
    }

    @Test
    fun presses_the_platform_did_not_time_are_timed_by_the_scene_clock() {
        val platform = FakePlatform()
        val state = TextFieldState("hello world")
        val host = ComposeWindowHost(platform, WindowConfig("test", 200, 100), { FakeSurface() }) {
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                BasicTextField(state, Modifier.size(180.dp, 40.dp))
            }
        }
        var now = 50_000L
        host.eventClock = EventClock { now }
        assertTrue(host.open())
        host.turn(0)
        repeat(2) {
            platform.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 10f, 10f, WindowEvent.PRIMARY_HELD, 0, 0, 0, ""))
            platform.queued.add(WindowEvent(WindowEvent.POINTER_UP, 10f, 10f, 0, 0, 0, 0, ""))
            repeat(4) { host.turn(0) }
            now += 1_000
        }
        // A second apart on the scene's clock, which is longer than the 500 ms interval.
        assertTrue(state.selection.collapsed, "a caret, not a word: ${state.selection}")
        host.close()
    }
}
