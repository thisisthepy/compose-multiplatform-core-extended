package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicTest {
    @Test
    fun pr3_requests_between_two_turns_coalesce_into_one() {
        val requests = FrameRequestCoalescer()
        assertFalse(requests.take())
        requests.request()
        requests.request()
        requests.request()
        assertTrue(requests.take())
        assertFalse(requests.take(), "three requests are one frame")
        requests.request()
        assertTrue(requests.take())
    }

    @Test
    fun a_request_made_before_the_first_frame_can_be_marked_served() {
        val requests = FrameRequestCoalescer()
        requests.request()
        requests.markServed()
        assertFalse(requests.take())
    }

    @Test
    fun preedit_replaces_a_run_counted_in_code_points() {
        val buffer = PreeditBuffer()
        buffer.replace(0, 0, "a😀b", 3)
        assertEquals("a😀b", buffer.text)
        buffer.replace(1, 1, "X", 2)
        assertEquals("aXb", buffer.text)
        buffer.replace(99, 5, "c", 99)
        assertEquals("aXbc", buffer.text, "a start past the end appends")
        assertEquals(4, buffer.caret)
    }

    @Test
    fun a_commit_takes_out_what_was_being_composed_first() {
        val heard = ArrayList<WindowEvent>()
        val session = ImeSession { heard.add(it) }
        session.preeditDraw(0, 0, "하", 1)
        session.commit("하")
        assertEquals(
            listOf(WindowEvent.TEXT_COMPOSE to "하", WindowEvent.TEXT_COMPOSE to "", WindowEvent.TEXT_COMMIT to "하"),
            heard.map { it.kind to it.text },
        )
        assertFalse(session.composing)
    }

    @Test
    fun key_events_for_a_shortcut_carry_no_text() {
        val events = keyEventsFor(true, X11_CONTROL_MASK, 'c'.code.toLong(), "\u0003")
        assertEquals(1, events.size)
        assertEquals("", events[0].text)
        assertEquals(0, events[0].codePoint)
    }

    @Test
    fun the_candidate_window_goes_under_the_caret_or_nowhere() {
        assertEquals(10 to 30, candidateSpot(CaretRect(10f, 20f, 12f, 30f), 1f))
        assertNull(candidateSpot(CaretRect(0f, 0f, 0f, 0f), 1f))
        assertNull(candidateSpot(null, 1f))
    }

    @Test
    fun the_accessibility_tree_is_pushed_only_when_it_changed() {
        var tree = listOf(AccessibleElement(ElementRole.BUTTON, 0f, 0f, 10f, 10f, "OK"))
        val pushed = ArrayList<List<AccessibleElement>>()
        val cache = AccessibilityCache({ tree }, { pushed.add(it) })
        assertFalse(cache.pushIfChanged(), "nothing was noted")
        cache.noteChanged()
        assertTrue(cache.pushIfChanged())
        assertEquals(1, pushed.size)
        cache.noteChanged()
        assertFalse(cache.pushIfChanged(), "the same tree costs no crossing")
        tree = tree.map { it.copy(x = 5f) }
        assertTrue(cache.pushIfChanged(afterDrawing = true), "a control that moved is read again after a frame")
        assertEquals(2, pushed.size)
        assertEquals(tree, cache.current)
    }

    @Test
    fun the_edit_menu_offers_only_what_the_field_can_do() {
        assertEquals(emptyList(), editMenuItems(false, false, false, false))
        val full = editMenuItems(true, true, true, true)
        assertEquals(listOf("Cut", "Copy", "Paste", "Select All"), full.map { it.label })
        assertTrue(full[2].separatorAfter)
        assertEquals(listOf("Select All"), editMenuItems(false, false, false, true).map { it.label })
    }

    @Test
    fun a_platform_clipboard_reads_as_a_pasteboard() {
        var stored: String? = null
        val platform = object : WindowPlatform by unsupported() {
            override fun readClipboardText() = stored
            override fun writeClipboardText(text: String) { stored = text }
        }
        val board = platform.pasteboard()
        board.copyText("hi")
        assertEquals("hi", board.pasteText())
    }

    private fun unsupported(): WindowPlatform = object : WindowPlatform {
        override val name = "fake"
        override fun open(config: WindowConfig, listener: WindowListener) = true
        override fun pump(timeoutMillis: Long) {}
        override fun measure() = WindowMeasurement(1, 1, 1f)
        override fun requestFrame() {}
        override fun present(drawnWidth: Int, drawnHeight: Int) = FramePresentRecord(drawnWidth, drawnHeight, drawnWidth, drawnHeight)
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
