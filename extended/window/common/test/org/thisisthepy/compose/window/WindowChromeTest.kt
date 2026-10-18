package org.thisisthepy.compose.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowChromeTest {
    @Test
    fun fr19_4_a_window_that_says_nothing_gets_the_modern_normal_caption() {
        val config = WindowConfig("app", 400, 300)
        assertEquals(WindowChrome.Modern, config.chrome)
        assertEquals(TitleBarStyle.Normal, config.titleBar)
    }

    @Test
    fun a_platform_that_has_no_caption_or_accessibility_bridge_yet_answers_none() {
        val platform = object : WindowPlatform {
            override val name = "bare"
            override fun open(config: WindowConfig, listener: WindowListener) = true
            override fun pump(timeoutMillis: Long) {}
            override fun measure() = WindowMeasurement(1, 1, 1f)
            override fun requestFrame() {}
            override fun present(drawnWidth: Int, drawnHeight: Int) =
                FramePresentRecord(drawnWidth, drawnHeight, drawnWidth, drawnHeight)
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
        assertEquals(WindowCaption.None, platform.caption())
        assertFalse(platform.startMove())
        assertFalse(platform.startResize(WindowEdge.BottomRight))
        platform.setAccessibility(listOf(AccessibleElement(ElementRole.BUTTON, 0f, 0f, 10f, 10f, "OK")))
        platform.performCaptionAction(CaptionAction.Close)
    }
}
