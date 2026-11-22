package org.thisisthepy.compose.window.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import org.thisisthepy.compose.window.NativeViewRegion
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.windows.c.dxc_event

/** What the window was asked, in order. */
private class FakeNativeViewsBackend : Win32NativeViewsBackend {
    val calls = ArrayList<String>()
    val placed = ArrayList<Pair<Int, NativeViewRegion>>()
    var refuse = false
    override var parentHandle: Long = 0x1234

    override fun attach(id: Int, handle: Long): Boolean {
        calls += "attach $id $handle"
        return !refuse
    }

    override fun place(id: Int, region: NativeViewRegion) {
        calls += "place $id"
        placed += id to region
    }

    override fun setVisible(id: Int, visible: Boolean) {
        calls += "visible $id $visible"
    }

    override fun focus(id: Int) {
        calls += "focus $id"
    }

    override fun focusScene() {
        calls += "scene"
    }

    override fun detach(id: Int) {
        calls += "detach $id"
    }
}

// The Kotlin half of the Windows slots, over a backend of the test's own: what reaches the C
// window, and when. The window's own half (placing the HWNDs, the region, the focus reports)
// is checked by c/test/win32_shared_test.c on a real window (fr81_native_views_in_a_real_window).
class WindowsNativeViewsTest {

    @Test
    fun fr81_windows_native_views_refuse_what_cannot_be_a_slot() {
        val backend = FakeNativeViewsBackend()
        val views = WindowsNativeViews(backend)
        assertEquals(0x1234L, views.parentHandle)
        assertFalse(views.attach(1, 0L), "no handle")
        assertTrue(views.attach(1, 77L))
        assertFalse(views.attach(1, 78L), "the id is in use")
        backend.refuse = true
        assertFalse(views.attach(2, 79L), "the window refused it")
        assertEquals(listOf(1), views.attached)
        assertEquals(listOf("attach 1 77", "attach 2 79"), backend.calls)
    }

    @Test
    fun fr81_windows_native_views_send_the_whole_region_once_per_change() {
        val backend = FakeNativeViewsBackend()
        val views = WindowsNativeViews(backend)
        views.attach(3, 10L)
        views.setFrame(3, 10, 20, 100, 80)
        views.setFrame(3, 10, 20, 100, 80)
        val hole = PixelRoundRect(30, 70, 20, 20, cornerRadius = 4)
        views.setVisibleRegion(3, PixelRect(0, 60, 300, 300), listOf(hole, PixelRoundRect(0, 0, 0, 5)))
        views.setFrameAndVisibleRegion(3, 10, 30, 100, 80, PixelRect(0, 60, 300, 300), listOf(hole))
        views.setFrameAndVisibleRegion(3, 10, 30, 100, 80, PixelRect(0, 60, 300, 300), listOf(hole))
        // A negative size is no size; an unknown slot is nothing.
        views.setFrame(3, 10, 30, -5, 80)
        views.setFrame(9, 0, 0, 1, 1)
        assertEquals(
            listOf(
                NativeViewRegion(PixelRect(10, 20, 100, 80)),
                NativeViewRegion(PixelRect(10, 20, 100, 80), PixelRect(0, 60, 300, 300), listOf(hole)),
                NativeViewRegion(PixelRect(10, 30, 100, 80), PixelRect(0, 60, 300, 300), listOf(hole)),
                NativeViewRegion(PixelRect(10, 30, 0, 80), PixelRect(0, 60, 300, 300), listOf(hole)),
            ),
            backend.placed.map { it.second },
        )
        assertTrue(backend.placed.all { it.first == 3 })
    }

    @Test
    fun fr81_windows_native_views_focus_only_a_shown_slot_and_detach_once() {
        val backend = FakeNativeViewsBackend()
        val views = WindowsNativeViews(backend)
        views.attach(1, 10L)
        views.attach(2, 20L)
        views.focus(1)
        views.setVisible(1, true)
        views.setVisible(1, true)
        views.focus(1)
        views.focusScene()
        views.setVisible(1, false)
        views.detach(2)
        views.detach(2)
        views.detachAll()
        assertEquals(
            listOf(
                "attach 1 10", "attach 2 20",
                "visible 1 true", "focus 1", "scene", "visible 1 false",
                "detach 2", "detach 1",
            ),
            backend.calls,
        )
        assertEquals(emptyList(), views.attached)
        // A slot detached can be attached again under its id.
        assertTrue(views.attach(1, 10L))
    }

    @Test
    fun fr81_windows_native_view_holes_are_five_words_each() {
        val region = NativeViewRegion(
            PixelRect(0, 0, 100, 100),
            holes = listOf(PixelRoundRect(1, 2, 3, 4, cornerRadius = 9), PixelRoundRect(5, 6, 7, 8)),
        )
        assertContentEquals(intArrayOf(1, 2, 3, 4, 9, 5, 6, 7, 8, 0), nativeViewHoleWords(region))
        assertContentEquals(intArrayOf(), nativeViewHoleWords(NativeViewRegion(PixelRect(0, 0, 1, 1))))
    }

    /**
     * A focus report the window wrote reads as common's event, the slot in nativeViewId. That the
     * C numbers are common's (19 to 22) is checked by c/test/win32_shared_test.c.
     */
    @Test
    fun fr81_a_focus_record_reads_as_commons_event() = memScoped {
        val record = alloc<dxc_event>()
        record.kind = WindowEvent.NATIVE_VIEW_FOCUS_IN
        record.key_code = 7
        val event = Win32Records.readEvent(record)
        assertEquals(WindowEvent.NATIVE_VIEW_FOCUS_IN, event.kind)
        assertEquals(7, event.nativeViewId)
    }
}
