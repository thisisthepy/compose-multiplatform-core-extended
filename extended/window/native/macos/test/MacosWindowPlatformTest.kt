package org.thisisthepy.compose.window.macos.test

import org.thisisthepy.compose.window.macos.MacosWindowPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class MacosWindowPlatformTest {
    @Test
    fun presentBeforeOpenRecordsTheWindowSizeReadAtThatTime() {
        val platform = MacosWindowPlatform {}
        val record = platform.present(0, 0)
        assertEquals(0, record.windowWidth)
        assertEquals(0, platform.presentLog.mismatched)
        assertEquals(1, platform.presentLog.frames)
    }
}
