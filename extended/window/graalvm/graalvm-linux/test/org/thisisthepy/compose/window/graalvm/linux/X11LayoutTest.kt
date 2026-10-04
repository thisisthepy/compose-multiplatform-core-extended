package org.thisisthepy.compose.window.graalvm.linux

import kotlin.test.Test
import kotlin.test.assertEquals

class X11LayoutTest {
    @Test
    fun eventRecordHoldsSevenWordsThenText() {
        assertEquals(124, X11Layout.EVENT_BYTES)
    }

    @Test
    fun windowRecordIsFivePointers() {
        assertEquals(5 * 8, X11Layout.WINDOW_BYTES)
    }

    @Test
    fun accessibilityElementIsFiveWordsThenLabel() {
        assertEquals(116, X11Layout.ELEMENT_BYTES)
    }
}
