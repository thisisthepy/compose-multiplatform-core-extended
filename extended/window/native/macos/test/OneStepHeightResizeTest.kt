@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Growing a macOS window's height in one programmatic step (520x360 -> taller) must lay out
 * the Compose scene at the window's new content height with top-anchored content at y == 0.
 */
class OneStepHeightResizeTest {
    private fun spin(seconds: Double = 0.02) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    @Test
    fun one_step_height_growth_lays_out_at_new_height() {
        NSApplication.sharedApplication()
        var rootSize = IntSize.Zero
        var topY = -1f
        val window = MacosWindow(name = "one-step-resize", width = 520, height = 360)
        window.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootSize = it.size },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .onGloballyPositioned { topY = it.positionInRoot().y },
                )
            }
        }
        try {
            repeat(5) {
                window.pumpHost(0L)
                spin(0.01)
            }
            val scale = window.window.backingScaleFactor.toFloat().coerceAtLeast(1f)
            val initialHeightPx = rootSize.height
            assertTrue(initialHeightPx > 0, "initial layout never ran")

            // Request 420x808 in one step (AppKit may clamp height to the screen's visibleFrame,
            // e.g. 677 pt on a 1024x768 CI runner, which is still well above 360 pt).
            window.window.setFrame(NSMakeRect(100.0, 100.0, 420.0, 808.0), display = true)
            repeat(8) {
                window.pumpHost(0L)
                spin(0.01)
            }
            val (actualWidthPt, actualHeightPt) = window.window.contentView!!.bounds.useContents {
                size.width to size.height
            }
            val expectedWidthPx = (actualWidthPt * scale).toInt()
            val expectedHeightPx = (actualHeightPt * scale).toInt()
            assertTrue(
                expectedHeightPx > initialHeightPx + (150f * scale).toInt(),
                "window did not grow enough to test one-step height growth: initial=$initialHeightPx px, actual=$expectedHeightPx px",
            )
            assertEquals(
                expectedHeightPx,
                rootSize.height,
                "after one-step resize, root height should match contentView ($expectedHeightPx px), got ${rootSize.height} px (width=${rootSize.width})",
            )
            assertEquals(
                expectedWidthPx,
                rootSize.width,
                "after one-step resize, root width should match contentView ($expectedWidthPx px), got ${rootSize.width} px",
            )
            assertEquals(0f, topY, "top-anchored mark should stay at y == 0")
        } finally {
            window.close()
            spin(0.02)
        }
    }
}
