package org.thisisthepy.compose.window.graalvm.macos

import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.EditMenuId
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpcallTableTest {

    @Test
    fun upcalls_deliver_through_slots_not_reflection() {
        var count = 0
        AppKitUpcallSlots.frame = Runnable { count++ }
        AppKitUpcallSlots.frame!!.run()
        assertEquals(1, count)
        AppKitUpcallSlots.frame = null
        assertNull(AppKitUpcallSlots.frame)
    }

    @Test
    fun event_kinds_match_the_record_the_window_writes() {
        assertEquals(1, WindowEvent.POINTER_MOVE)
        assertEquals(12, WindowEvent.FILES_EXITED)
    }

    @Test
    fun the_appkit_window_is_a_window_platform() {
        val platform: WindowPlatform = AppKitWindowPlatform()
        assertEquals("appkit-graalvm", platform.name)
        // Frame requests coalesce: any number of asks is one frame.
        (platform as AppKitWindowPlatform).requestFrame()
        platform.requestFrame()
        assertTrue(platform.takeFrameRequest())
        assertTrue(!platform.takeFrameRequest())
    }

    @Test
    fun context_menu_items_are_packed_one_line_each() {
        val packed = AppKitWindowPlatform.packMenu(
            listOf(
                ContextMenuItem(1, "Copy"),
                ContextMenuItem(2, "Paste\tnow", enabled = false, separatorAfter = true),
            ),
        )
        assertEquals("1\t1\t0\tCopy\n2\t0\t1\tPaste now", packed)
    }

    private class Recorder : WindowListener {
        val events = ArrayList<WindowEvent>()
        val chosen = ArrayList<Int>()
        override fun onEvent(event: WindowEvent) { events += event }
        override fun onContextMenuChosen(id: Int) { chosen += id }
    }

    @Test
    fun fr33_6_the_text_menu_a_right_click_puts_up_is_cut_copy_paste_and_select_all() {
        val items = AppKitWindowPlatform.textEditMenu()
        assertEquals(
            listOf(EditMenuId.CUT, EditMenuId.COPY, EditMenuId.PASTE, EditMenuId.SELECT_ALL),
            items.map { it.id },
        )
        assertEquals(
            "1\t1\t0\tCut\n2\t1\t0\tCopy\n3\t1\t1\tPaste\n4\t1\t0\tSelect All",
            AppKitWindowPlatform.packMenu(items),
        )
    }

    @Test
    fun fr33_6_a_chosen_menu_entry_reaches_the_listener_as_a_choice_and_not_as_an_event() {
        val recorder = Recorder()
        val chosen = WindowEvent(WindowEvent.MENU_COMMAND, 0f, 0f, 0, 0, EditMenuId.COPY, 0, "")
        AppKitWindowPlatform.deliver(chosen, recorder)
        assertEquals(listOf(EditMenuId.COPY), recorder.chosen)
        assertTrue(recorder.events.isEmpty())

        val key = WindowEvent(WindowEvent.KEY_DOWN, 0f, 0f, 0, 0, 8, 0, "")
        AppKitWindowPlatform.deliver(key, recorder)
        assertEquals(listOf(key), recorder.events)
        assertEquals(listOf(EditMenuId.COPY), recorder.chosen)
    }

    @Test
    fun fr5_a_control_character_the_input_context_hands_over_is_not_committed_as_text() {
        val recorder = Recorder()
        // Control A, when the input method has no binding for it, comes back as U+0001.
        val control = WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "\u0001")
        AppKitWindowPlatform.deliver(control, recorder)
        assertTrue(recorder.events.isEmpty(), "nothing is typed into the field")

        val mixed = WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "a\u0001b")
        AppKitWindowPlatform.deliver(mixed, recorder)
        assertEquals(listOf("ab"), recorder.events.map { it.text })

        val composed = WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "한글")
        AppKitWindowPlatform.deliver(composed, recorder)
        assertEquals("한글", recorder.events.last().text)
    }

    @Test
    fun skia_resource_cache_limit_defaults_to_16_mb_and_respects_override() {
        assertEquals(16L * 1048576L, defaultSkiaCacheLimitBytes(null))
        assertEquals(16L * 1048576L, defaultSkiaCacheLimitBytes(""))
        assertEquals(32L * 1048576L, defaultSkiaCacheLimitBytes("32"))
    }
}
