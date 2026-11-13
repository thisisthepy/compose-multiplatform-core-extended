package org.thisisthepy.compose.window.graalvm.linux

import kotlin.test.Test
import kotlin.test.assertEquals

class X11LayoutTest {
    @Test
    fun eventRecordHoldsSevenWordsThenTextThenTheEventTime() {
        assertEquals(128, X11Layout.EVENT_TIME_OFFSET)
        assertEquals(136, X11Layout.EVENT_BYTES)
    }

    @Test
    fun windowRecordIsFivePointers() {
        assertEquals(5 * 8, X11Layout.WINDOW_BYTES)
    }
}
