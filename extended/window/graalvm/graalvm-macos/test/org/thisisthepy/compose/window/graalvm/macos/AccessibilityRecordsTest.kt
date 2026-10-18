package org.thisisthepy.compose.window.graalvm.macos

import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The records the GraalVM window hands its Objective-C side, read back at the offsets
 * `appkit_accessibility.h` asserts, and the way a reader's request comes back as an event.
 */
class AccessibilityRecordsTest {
    private val form = listOf(
        AccessibleElement(ElementRole.GROUP, 0f, 0f, 400f, 300f, "Form", id = 1),
        AccessibleElement(
            ElementRole.FIELD, 10f, 20f, 200f, 30f, "Name", id = 2, parentId = 1, value = "Ada",
            states = AccessibleState.ENABLED or AccessibleState.EDITABLE,
            actions = AccessibleAction.SET_VALUE or AccessibleAction.FOCUS,
        ),
        AccessibleElement(ElementRole.BUTTON, 10f, 60f, 80f, 30f, "Send", id = 3, parentId = 1, actions = AccessibleAction.PRESS),
        AccessibleElement(
            ElementRole.CHECKBOX, 10f, 100f, 20f, 20f, "Remember me",
            states = AccessibleState.ENABLED or AccessibleState.CHECKABLE or AccessibleState.CHECKED,
        ),
    )

    private fun read(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

    private fun text(buffer: ByteBuffer, at: Int, capacity: Int): String {
        var length = 0
        while (length < capacity && buffer.get(at + length) != 0.toByte()) length++
        return String(ByteArray(length) { buffer.get(at + it) }, Charsets.UTF_8)
    }

    @Test
    fun nfr8_each_element_crosses_with_its_ax_role_label_value_and_place() {
        val bytes = encodeAccessibilityRecords(form)
        assertEquals(form.size * AX_RECORD_BYTES, bytes.size)
        val buffer = read(bytes)
        val field = AX_RECORD_BYTES
        assertEquals(2, buffer.getInt(field + 0), "the key is the scene's id")
        assertEquals(0, buffer.getInt(field + 4), "the field is inside the form, the first record")
        assertEquals(MacosAx.TEXT_FIELD, text(buffer, field + AX_ROLE_OFFSET, AX_NAME_BYTES))
        assertEquals("Name", text(buffer, field + AX_LABEL_OFFSET, AX_TEXT_BYTES))
        assertEquals("Ada", text(buffer, field + AX_VALUE_OFFSET, AX_TEXT_BYTES))
        val flags = buffer.getInt(field + 16)
        assertEquals(AX_HAS_VALUE or AX_VALUE_SETTABLE or AX_FOCUS_SETTABLE, flags)
        assertEquals(10f, buffer.getFloat(field + 20))
        assertEquals(20f, buffer.getFloat(field + 24))
        assertEquals(200f, buffer.getFloat(field + 28))
        assertEquals(30f, buffer.getFloat(field + 32))
        val button = 2 * AX_RECORD_BYTES
        assertEquals(MacosAx.BUTTON, text(buffer, button + AX_ROLE_OFFSET, AX_NAME_BYTES))
        assertEquals(AccessibleAction.PRESS, buffer.getInt(button + 12))
        assertEquals(-1, buffer.getInt(0 + 4), "the form is directly in the view")
    }

    @Test
    fun nfr8_a_checked_checkbox_crosses_as_the_number_one_under_a_key_below_every_id() {
        val buffer = read(encodeAccessibilityRecords(form))
        val box = 3 * AX_RECORD_BYTES
        assertEquals(-5, buffer.getInt(box), "no id, so keyed by its place")
        assertEquals(AX_HAS_VALUE or AX_VALUE_IS_NUMBER, buffer.getInt(box + 16))
        assertEquals(1.0, buffer.getDouble(box + 40))
    }

    @Test
    fun long_text_is_cut_where_a_character_starts() {
        val long = "가".repeat(200)
        val buffer = read(encodeAccessibilityRecords(listOf(AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, long))))
        val label = text(buffer, AX_LABEL_OFFSET, AX_TEXT_BYTES)
        assertEquals("가".repeat(85), label, "255 bytes hold 85 three-byte characters")
    }

    @Test
    fun no_more_than_the_limit_crosses() {
        val many = List(5) { AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "t$it", id = it) }
        assertEquals(3 * AX_RECORD_BYTES, encodeAccessibilityRecords(many, limit = 3).size)
    }

    @Test
    fun nfr8_a_readers_request_reaches_the_listener_with_the_new_value() {
        val heard = ArrayList<Triple<Int, Int, String?>>()
        val sink = object : WindowListener {
            override fun onEvent(event: WindowEvent) = error("not an input event: $event")
            override fun onAccessibilityAction(id: Int, action: Int, argument: String?): Boolean {
                heard += Triple(id, action, argument)
                return true
            }
        }
        fun request(id: Int, action: Int) = WindowEvent(ACCESSIBILITY_ACTION_EVENT, 0f, 0f, 0, 0, id, action, "")
        AppKitWindowPlatform.deliver(request(3, AccessibleAction.PRESS), sink) { error("a press carries no value") }
        AppKitWindowPlatform.deliver(request(2, AccessibleAction.SET_VALUE), sink) { "Grace" }
        assertEquals(listOf(Triple(3, AccessibleAction.PRESS, null), Triple(2, AccessibleAction.SET_VALUE, "Grace")), heard)
    }
}
