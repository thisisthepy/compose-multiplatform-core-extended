package org.thisisthepy.compose.window.graalvm.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import org.thisisthepy.compose.window.FramePresentRecord

class X11PlatformTest {
    @Test
    fun presentRecordFlagsADrawnSizeThatDiffersFromTheWindow() {
        assertEquals(true, FramePresentRecord(100, 80, 120, 80).mismatched)
        assertEquals(false, FramePresentRecord(100, 80, 100, 80).mismatched)
    }
}
