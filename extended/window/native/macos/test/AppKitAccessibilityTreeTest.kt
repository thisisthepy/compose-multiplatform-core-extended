@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.MacosAx
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskTitled
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Kotlin/Native window's reader tree, fed records by hand as the scene would push them
 * and asked as a screen reader asks: through the view's accessibility children and each
 * element's own answers.
 */
class AppKitAccessibilityTreeTest {
    private class Flipped : NSView(NSMakeRect(0.0, 0.0, 400.0, 300.0)) {
        override fun isFlipped() = true
    }

    private val asked = ArrayList<Triple<Int, Int, String?>>()

    private fun tree(): Pair<NSView, AppKitAccessibilityTree> {
        val window = NSWindow(
            contentRect = NSMakeRect(100.0, 100.0, 400.0, 300.0),
            styleMask = NSWindowStyleMaskTitled,
            backing = NSBackingStoreBuffered,
            defer = true,
        )
        val view = Flipped()
        window.contentView = view
        return view to AppKitAccessibilityTree(view) { id, action, argument ->
            asked += Triple(id, action, argument)
            true
        }
    }

    private val form = listOf(
        AccessibleElement(ElementRole.GROUP, 0f, 0f, 400f, 300f, "Form", id = 1),
        AccessibleElement(
            ElementRole.FIELD, 10f, 20f, 200f, 30f, "Name", id = 2, parentId = 1, value = "Ada",
            states = AccessibleState.ENABLED or AccessibleState.EDITABLE or AccessibleState.FOCUSABLE,
            actions = AccessibleAction.SET_VALUE or AccessibleAction.FOCUS,
        ),
        AccessibleElement(
            ElementRole.BUTTON, 10f, 60f, 80f, 30f, "Send", id = 3, parentId = 1,
            actions = AccessibleAction.PRESS,
        ),
        AccessibleElement(
            ElementRole.CHECKBOX, 10f, 100f, 20f, 20f, "Remember me", id = 4,
            states = AccessibleState.ENABLED or AccessibleState.CHECKABLE or AccessibleState.CHECKED,
            actions = AccessibleAction.PRESS,
        ),
        AccessibleElement(ElementRole.TEXT, 10f, 130f, 200f, 20f, "Hello", id = 5),
    )

    @Test
    fun nfr8_the_view_lists_the_text_field_the_button_and_their_labels() {
        val (view, tree) = tree()
        tree.update(form)
        val roots = view.accessibilityChildren().orEmpty().map { it as AxElement }
        assertEquals(listOf("Form", "Remember me", "Hello"), roots.map { it.accessibilityLabel() })
        val inForm = roots[0].accessibilityChildren().orEmpty().map { it as AxElement }
        assertEquals(listOf(MacosAx.TEXT_FIELD, MacosAx.BUTTON), inForm.map { it.accessibilityRole() })
        assertEquals(listOf("Name", "Send"), inForm.map { it.accessibilityLabel() })
        assertEquals("Ada", inForm[0].accessibilityValue())
        assertSame(roots[0], inForm[1].accessibilityParent(), "the button says which group it is in")
        assertSame(view, roots[0].accessibilityParent(), "a top element says it is in the window's view")
    }

    @Test
    fun nfr8_a_checked_checkbox_reports_one() {
        val (_, tree) = tree()
        tree.update(form)
        val box = tree.all.first { it.element.id == 4 }
        assertEquals(MacosAx.CHECK_BOX, box.accessibilityRole())
        assertEquals(1.0, (box.accessibilityValue() as NSNumber).doubleValue)
    }

    @Test
    fun nfr8_press_value_and_focus_reach_the_scene_with_the_elements_id() {
        val (_, tree) = tree()
        tree.update(form)
        val field = tree.all.first { it.element.id == 2 }
        val button = tree.all.first { it.element.id == 3 }
        assertTrue(button.accessibilityPerformPress())
        field.setAccessibilityValue("Grace")
        field.setAccessibilityFocused(true)
        assertEquals(
            listOf(
                Triple(3, AccessibleAction.PRESS, null),
                Triple(2, AccessibleAction.SET_VALUE, "Grace"),
                Triple(2, AccessibleAction.FOCUS, null),
            ),
            asked,
        )
    }

    @Test
    fun nfr8_a_control_offers_only_what_the_scene_said_it_does() {
        val (_, tree) = tree()
        tree.update(form)
        val text = tree.all.first { it.element.id == 5 }
        val field = tree.all.first { it.element.id == 2 }
        val button = tree.all.first { it.element.id == 3 }
        assertFalse(text.accessibilityPerformPress(), "a label is not pressed")
        assertFalse(text.isAccessibilitySelectorAllowed(NSSelectorFromString("accessibilityPerformPress")))
        assertTrue(button.isAccessibilitySelectorAllowed(NSSelectorFromString("accessibilityPerformPress")))
        assertTrue(field.isAccessibilitySelectorAllowed(NSSelectorFromString("setAccessibilityValue:")))
        assertFalse(button.isAccessibilitySelectorAllowed(NSSelectorFromString("setAccessibilityValue:")))
        text.setAccessibilityValue("typed")
        assertTrue(asked.isEmpty(), "nothing was handed on: $asked")
    }

    @Test
    fun an_element_keeps_its_object_while_its_id_lasts() {
        val (_, tree) = tree()
        tree.update(form)
        val before = tree.all.first { it.element.id == 3 }
        tree.update(form.map { if (it.id == 3) it.copy(label = "Sent") else it })
        val after = tree.all.first { it.element.id == 3 }
        assertSame(before, after)
        assertEquals("Sent", after.accessibilityLabel())
        tree.update(form.filter { it.id != 3 })
        assertFalse(tree.all.any { it.element.id == 3 })
    }

    @Test
    fun nfr8_the_frame_is_where_the_control_is_in_points() {
        val (_, tree) = tree()
        tree.update(form)
        val button = tree.all.first { it.element.id == 3 }
        button.accessibilityFrame().useContents {
            assertEquals(80.0, size.width)
            assertEquals(30.0, size.height)
        }
    }

    @Test
    fun the_focused_element_is_the_one_the_scene_marked() {
        val (_, tree) = tree()
        tree.update(form.map { if (it.id == 2) it.copy(states = it.states or AccessibleState.FOCUSED) else it })
        assertEquals(2, tree.focused?.element?.id)
        assertTrue(tree.focused!!.isAccessibilityFocused())
    }
}
