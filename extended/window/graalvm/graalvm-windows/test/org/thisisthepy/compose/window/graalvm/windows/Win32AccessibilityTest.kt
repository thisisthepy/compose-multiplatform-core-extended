package org.thisisthepy.compose.window.graalvm.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole

class Win32AccessibilityTest {
    @Test
    fun label_fits_the_native_record_without_losing_a_utf8_character() {
        val label = "a".repeat(94) + "한" + "b"
        val encoded = win32LabelBytes(label)

        assertEquals("a".repeat(94), encoded.toString(Charsets.UTF_8))
        assertTrue(encoded.size < 96)
        assertEquals("한b", win32LabelBytes("한b").toString(Charsets.UTF_8))
    }

    @Test
    fun label_uses_every_available_byte_when_the_boundary_is_valid() {
        val encoded = win32LabelBytes("a".repeat(95) + "한")

        assertEquals(95, encoded.size)
        assertEquals("a".repeat(95), encoded.toString(Charsets.UTF_8))
    }

    private fun text(bytes: ByteArray, at: Int, capacity: Int): String {
        var length = 0
        while (length < capacity && bytes[at + length] != 0.toByte()) length++
        return String(bytes, at, length, Charsets.UTF_8)
    }

    /** Every field lands where windows-shared's `struct dxc_element` has it. */
    @Test
    fun nfr8_elements_are_written_where_the_window_reads_them() {
        val bytes = encodeWin32Elements(
            listOf(
                AccessibleElement(ElementRole.BUTTON, 1f, 2f, 3f, 4f, "OK", id = 7, hint = "Saves"),
                AccessibleElement(
                    role = ElementRole.SLIDER, x = 5f, y = 6f, width = 7f, height = 8f, label = "Volume",
                    id = 9, parentId = 7, value = "50%",
                    states = AccessibleState.ENABLED or AccessibleState.FOCUSED,
                    actions = AccessibleAction.SET_VALUE,
                    rangeMin = 0f, rangeMax = 100f, rangeValue = 50f,
                ),
            ),
        )
        assertEquals(2 * 500, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(ElementRole.BUTTON, buffer.getInt(0))
        assertEquals(3f, buffer.getFloat(12))
        assertEquals("OK", text(bytes, 20, 96))
        assertEquals(7, buffer.getInt(116))
        assertEquals(-1, buffer.getInt(120))
        assertEquals(ELEMENT_HAS_HINT, buffer.getInt(132))
        assertEquals("Saves", text(bytes, 404, 96))
        val at = 500
        assertEquals(ElementRole.SLIDER, buffer.getInt(at))
        assertEquals(9, buffer.getInt(at + 116))
        assertEquals(7, buffer.getInt(at + 120))
        assertEquals(AccessibleState.ENABLED or AccessibleState.FOCUSED, buffer.getInt(at + 124))
        assertEquals(AccessibleAction.SET_VALUE, buffer.getInt(at + 128))
        assertEquals(ELEMENT_HAS_VALUE or ELEMENT_HAS_RANGE, buffer.getInt(at + 132))
        assertEquals(0f, buffer.getFloat(at + 136))
        assertEquals(100f, buffer.getFloat(at + 140))
        assertEquals(50f, buffer.getFloat(at + 144))
        assertEquals("50%", text(bytes, at + 148, 256))
        assertEquals("", text(bytes, at + 404, 96))
    }

    @Test
    fun nfr8_at_most_256_elements_cross() {
        val many = List(300) { AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "t$it", id = it) }
        assertEquals(256 * 500, encodeWin32Elements(many).size)
    }

    @Test
    fun nfr8_a_long_value_is_cut_before_a_character() {
        val bytes = win32FittedBytes("가".repeat(100), VALUE_BYTES)
        assertEquals(255, bytes.size)
        assertEquals("가".repeat(85), bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun nfr8_a_range_needs_all_three_numbers() {
        assertEquals(0, win32ElementFlags(AccessibleElement(ElementRole.PROGRESS, 0f, 0f, 1f, 1f, "", rangeMax = 1f, rangeValue = 0f)))
        assertEquals(
            ELEMENT_HAS_RANGE,
            win32ElementFlags(AccessibleElement(ElementRole.PROGRESS, 0f, 0f, 1f, 1f, "", rangeMin = 0f, rangeMax = 1f, rangeValue = 0f)),
        )
    }
}
