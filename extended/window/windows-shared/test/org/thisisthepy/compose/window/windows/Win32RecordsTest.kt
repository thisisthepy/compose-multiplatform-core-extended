package org.thisisthepy.compose.window.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.windows.c.dxc_element
import org.thisisthepy.compose.window.windows.c.dxc_event

// Only the records: nothing here calls into the window, so the test executable needs none of
// the C object, which is linked by MSVC into applications and never by this toolchain.
class Win32RecordsTest {

    @Test
    fun recordsHaveTheSizesTheCSideWrites() {
        assertEquals(124L, sizeOf<dxc_event>())
        assertEquals(116L, sizeOf<dxc_element>())
        assertEquals(96, Win32Records.TEXT_BYTES)
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
