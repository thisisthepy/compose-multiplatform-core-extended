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
        assertFalse(platform.accessibilityActive)
        assertFalse(platform.startMove())
        assertFalse(platform.startResize(WindowEdge.BottomRight))
        platform.setAccessibility(listOf(AccessibleElement(ElementRole.BUTTON, 0f, 0f, 10f, 10f, "OK")))
        platform.performCaptionAction(CaptionAction.Close)
    }

    @Test
    fun nfr8_an_element_described_by_place_and_label_alone_is_an_enabled_root_child_with_nothing_more() {
        val element = AccessibleElement(ElementRole.TEXT, 1f, 2f, 3f, 4f, "Hello")
        assertEquals(-1, element.id)
        assertEquals(-1, element.parentId)
        assertEquals(null, element.value)
        assertEquals(AccessibleState.ENABLED, element.states)
        assertEquals(0, element.actions)
        assertEquals(null, element.rangeValue)
    }

    @Test
    fun nfr8_a_listener_that_does_not_handle_accessibility_actions_says_so() {
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        assertFalse(listener.onAccessibilityAction(7, AccessibleAction.PRESS))
        assertFalse(listener.onAccessibilityAction(7, AccessibleAction.SET_VALUE, "text"))
    }

    @Test
    fun nfr8_state_and_action_bits_do_not_overlap() {
        val states = listOf(
            AccessibleState.ENABLED, AccessibleState.FOCUSABLE, AccessibleState.FOCUSED, AccessibleState.CHECKABLE,
            AccessibleState.CHECKED, AccessibleState.MIXED, AccessibleState.SELECTED, AccessibleState.EDITABLE,
            AccessibleState.READ_ONLY, AccessibleState.PASSWORD, AccessibleState.MULTI_LINE,
        )
        assertEquals(states.size, states.fold(0) { all, bit -> all or bit }.countOneBits())
        val actions = listOf(
            AccessibleAction.PRESS, AccessibleAction.FOCUS, AccessibleAction.SET_VALUE, AccessibleAction.INCREMENT,
            AccessibleAction.DECREMENT, AccessibleAction.SCROLL_FORWARD, AccessibleAction.SCROLL_BACKWARD, AccessibleAction.SHOW_MENU,
        )
        assertEquals(actions.size, actions.fold(0) { all, bit -> all or bit }.countOneBits())
    }
}
