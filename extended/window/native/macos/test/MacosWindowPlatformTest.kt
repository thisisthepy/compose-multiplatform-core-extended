package org.thisisthepy.compose.window.macos.test

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.macos.MacosWindowPlatform
import org.thisisthepy.compose.window.macos.runMacosWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacosWindowPlatformTest {
    @Test
    fun presentBeforeOpenRecordsTheWindowSizeReadAtThatTime() {
        val platform = MacosWindowPlatform {}
        val record = platform.present(0, 0)
        assertEquals(0, record.windowWidth)
        assertEquals(0, platform.presentLog.mismatched)
        assertEquals(1, platform.presentLog.frames)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun runMacosWindowIdleSoakWhenRequested() {
        val seconds = platform.posix.getenv("DXC_IDLE_SOAK_SECONDS")?.toKString()?.toIntOrNull() ?: 0
        if (seconds <= 0) return
        val ran = runMacosWindow(WindowConfig(title = "idle-soak-kn", width = 520, height = 360)) {
            var value by remember { mutableStateOf("hello") }
            Column(modifier = Modifier.padding(16.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.size(320.dp, 64.dp),
                )
                BasicText(text = "Typed: $value")
            }
        }
        assertTrue(ran)
    }
}
