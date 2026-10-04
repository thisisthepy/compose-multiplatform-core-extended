package org.thisisthepy.compose.window.graalvm.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
