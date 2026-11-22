package org.thisisthepy.compose.window.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.windows.c.dxc_element
import org.thisisthepy.compose.window.windows.c.dxc_event

// Only the records: nothing here calls into the window, so the test executable needs none of
// the C object, which is linked by MSVC into applications and never by this toolchain.
class Win32RecordsTest {

    @Test
    fun recordsHaveTheSizesTheCSideWrites() {
        assertEquals(136L, sizeOf<dxc_event>())
        assertEquals(500L, sizeOf<dxc_element>())
        assertEquals(96, Win32Records.TEXT_BYTES)
        assertEquals(256, Win32Records.VALUE_BYTES)
    }

    /** Where each field of an element sits, which the GraalVM writer and the C both assume. */
    @Test
    fun nfr8_element_fields_sit_where_the_c_reads_them() = memScoped {
        val record = alloc<dxc_element>()
        val base = record.ptr.toLong()
        assertEquals(20L, record.label.toLong() - base)
        assertEquals(148L, record.value.toLong() - base)
        assertEquals(404L, record.hint.toLong() - base)
    }

    /** A reader's request is an event like any other, read field for field. */
    @Test
    fun nfr8_an_accessibility_action_is_read_with_its_element_and_action() = memScoped {
        val record = alloc<dxc_event>()
        record.kind = WindowEvent.ACCESSIBILITY_ACTION
        record.key_code = 42
        record.code_point = AccessibleAction.PRESS
        val event = Win32Records.readEvent(record)
        assertEquals(WindowEvent.ACCESSIBILITY_ACTION, event.kind)
        assertEquals(42, event.keyCode)
        assertEquals(AccessibleAction.PRESS, event.codePoint)
    }

    /** Everything the scene says about an element reaches the record UI Automation answers from. */
    @Test
    fun nfr8_an_element_is_written_with_its_id_parent_states_range_value_and_hint() = memScoped {
        val record = alloc<dxc_element>()
        Win32Records.writeElement(
            AccessibleElement(
                role = ElementRole.SLIDER, x = 1f, y = 2f, width = 3f, height = 4f, label = "Volume",
                id = 9, parentId = 3, value = "50%", hint = "Changes the volume",
                states = AccessibleState.ENABLED or AccessibleState.FOCUSED,
                actions = AccessibleAction.SET_VALUE or AccessibleAction.INCREMENT,
                rangeMin = 0f, rangeMax = 100f, rangeValue = 50f,
            ),
            record,
        )
        assertEquals(9, record.id)
        assertEquals(3, record.parent_id)
        assertEquals(AccessibleState.ENABLED or AccessibleState.FOCUSED, record.states)
        assertEquals(AccessibleAction.SET_VALUE or AccessibleAction.INCREMENT, record.actions)
        assertEquals(Win32Records.HAS_VALUE or Win32Records.HAS_RANGE or Win32Records.HAS_HINT, record.flags)
        assertEquals(100f, record.range_max)
        assertEquals(50f, record.range_value)
        assertEquals('5'.code.toByte(), record.value[0])
        assertEquals(0.toByte(), record.value[3])
        assertEquals('C'.code.toByte(), record.hint[0])
    }

    /** A plain element says it has no value, range or hint, rather than an empty one. */
    @Test
    fun nfr8_an_element_without_a_value_range_or_hint_says_so() = memScoped {
        val record = alloc<dxc_element>()
        record.flags = -1
        Win32Records.writeElement(AccessibleElement(ElementRole.BUTTON, 0f, 0f, 1f, 1f, "OK", id = 4), record)
        assertEquals(0, record.flags)
        assertEquals(-1, record.parent_id)
        assertEquals(0.toByte(), record.value[0])
        // A range missing one of its numbers is no range.
        assertEquals(
            0,
            Win32Records.elementFlags(AccessibleElement(ElementRole.PROGRESS, 0f, 0f, 1f, 1f, "", rangeMax = 1f, rangeValue = 0f)),
        )
    }

    @Test
    fun nfr8_a_long_value_is_cut_before_a_character() {
        val bytes = Win32Records.fittedBytes("가".repeat(100), Win32Records.VALUE_BYTES)
        // 85 three-byte characters are 255 bytes, which leaves exactly the zero.
        assertEquals(255, bytes.size)
        assertEquals("가".repeat(85), bytes.decodeToString())
    }

    @Test
    fun anEventIsReadFieldForField() = memScoped {
        val record = alloc<dxc_event>()
        record.kind = WindowEvent.POINTER_MOVE
        record.x = 12.5f
        record.y = 3f
        record.buttons = 1
        record.modifiers = 2
        record.key_code = 65
        record.code_point = 0x61
        val text = "éa".encodeToByteArray()
        for (index in text.indices) record.text[index] = text[index]
        record.text[text.size] = 0
        assertEquals(
            WindowEvent(WindowEvent.POINTER_MOVE, 12.5f, 3f, 1, 2, 65, 0x61, "éa"),
            Win32Records.readEvent(record),
        )
    }

    /**
     * What win32_window.c writes for WM_RBUTTONDOWN is the right button held and the
     * secondary button's own flag. The scene reads it as a secondary press.
     */
    @Test
    fun aRightButtonPressTheWindowRecordedIsASecondaryPress() = memScoped {
        val record = alloc<dxc_event>()
        record.kind = WindowEvent.POINTER_DOWN
        record.buttons = WindowEvent.pointerButtons(WindowEvent.SECONDARY_HELD, secondaryEvent = true)
        assertTrue(Win32Records.readEvent(record).isSecondaryPress)
        record.buttons = WindowEvent.PRIMARY_HELD
        assertFalse(Win32Records.readEvent(record).isSecondaryPress)
        record.buttons = WindowEvent.pointerButtons(WindowEvent.MIDDLE_HELD, WindowEvent.MIDDLE_BUTTON)
        val middle = Win32Records.readEvent(record)
        assertFalse(middle.isSecondaryPress)
        assertEquals(WindowEvent.MIDDLE_HELD, middle.changedHeldBit)
    }

    /** The message time the window wrote down reaches the scene as the event's own time. */
    @Test
    fun aPointerEventCarriesTheMessageTime() = memScoped {
        val record = alloc<dxc_event>()
        record.kind = WindowEvent.POINTER_DOWN
        record.buttons = WindowEvent.PRIMARY_HELD
        record.time_millis = 0xfffffff0L
        assertEquals(0xfffffff0L, Win32Records.readEvent(record).timeMillis)
        record.time_millis = 0L
        assertEquals(WindowEvent.TIME_UNKNOWN, Win32Records.readEvent(record).timeMillis)
    }

    @Test
    fun textScaleEventTurnsThePercentIntoAMultiplier() = memScoped {
        val record = alloc<dxc_event>()
        record.kind = WindowEvent.TEXT_SCALE_CHANGED
        record.x = 150f
        assertEquals(WindowEvent.TextScaleChanged(1.5f), Win32Records.readEvent(record))
    }

    @Test
    fun textWithNoZeroStopsAtTheEndOfTheField() = memScoped {
        val record = alloc<dxc_event>()
        for (index in 0 until Win32Records.TEXT_BYTES) record.text[index] = 'x'.code.toByte()
        assertEquals("x".repeat(Win32Records.TEXT_BYTES), Win32Records.readEvent(record).text)
    }

    @Test
    fun anElementIsWrittenWithItsLabelEndedByZero() = memScoped {
        val record = alloc<dxc_element>()
        Win32Records.writeElement(AccessibleElement(1, 1f, 2f, 3f, 4f, "OK"), record)
        assertEquals(1, record.role)
        assertEquals(3f, record.width)
        assertEquals('O'.code.toByte(), record.label[0])
        assertEquals(0.toByte(), record.label[2])
    }

    @Test
    fun aLongLabelIsCutBeforeACharacterNotThroughIt() {
        // 47 two-byte characters fill 94 bytes, and a 48th would leave no room for the zero.
        val label = "é".repeat(60)
        val bytes = Win32Records.labelBytes(label)
        assertEquals(94, bytes.size)
        assertEquals("é".repeat(47), bytes.decodeToString())
    }
}
