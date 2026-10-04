package org.thisisthepy.compose.window.graalvm.macos

import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.WindowEvent
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
}
