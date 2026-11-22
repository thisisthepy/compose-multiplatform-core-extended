package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.TextScale
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.windows.c.DXC_TEXT_BYTES
import org.thisisthepy.compose.window.windows.c.DXC_VALUE_BYTES
import org.thisisthepy.compose.window.windows.c.dxc_element
import org.thisisthepy.compose.window.windows.c.dxc_event

/**
 * The records the C window and Kotlin exchange, `struct dxc_event` and `struct dxc_element`,
 * read and written through the layouts cinterop took from the header.
 *
 * No calls into the window happen here, which is what lets the tests run these without the
 * window's object linked in.
 */
object Win32Records {

    /** Room for text in either record, the terminating zero included. */
    const val TEXT_BYTES: Int = DXC_TEXT_BYTES

    /** Room for an element's value, the terminating zero included. */
    const val VALUE_BYTES: Int = DXC_VALUE_BYTES

    /** The bits of `struct dxc_element`'s `flags`, as the header numbers them. */
    const val HAS_VALUE: Int = 1 shl 0
    const val HAS_RANGE: Int = 1 shl 1
    const val HAS_HINT: Int = 1 shl 2

    /** At most this many elements are handed to UI Automation in one push. */
    const val MAX_ELEMENTS: Int = 256

    /** One event as the window wrote it down. */
    fun readEvent(record: dxc_event): WindowEvent =
        if (record.kind == WindowEvent.TEXT_SCALE_CHANGED) {
            WindowEvent.TextScaleChanged(TextScale.fromWindowsPercent(record.x.toInt()))
        } else {
            readPlainEvent(record)
        }

    private fun readPlainEvent(record: dxc_event): WindowEvent = WindowEvent(
        kind = record.kind,
        x = record.x,
        y = record.y,
        buttons = record.buttons,
        modifiers = record.modifiers,
        keyCode = record.key_code,
        codePoint = record.code_point,
        text = readText(record.text),
        timeMillis = record.time_millis,
    )

    /**
     * One element of the accessibility tree, written into the record UI Automation reads:
     * what it is and where, its id and its parent's, its states and actions, its range, its
     * value and its hint. A value and a hint that do not fit are cut before a character.
     */
    fun writeElement(element: AccessibleElement, record: dxc_element) {
        record.role = element.role
        record.x = element.x
        record.y = element.y
        record.width = element.width
        record.height = element.height
        writeText(labelBytes(element.label), record.label)
        record.id = element.id
        record.parent_id = element.parentId
        record.states = element.states
        record.actions = element.actions
        record.flags = elementFlags(element)
        record.range_min = element.rangeMin ?: 0f
        record.range_max = element.rangeMax ?: 0f
        record.range_value = element.rangeValue ?: 0f
        writeText(fittedBytes(element.value.orEmpty(), VALUE_BYTES), record.value)
        writeText(labelBytes(element.hint.orEmpty()), record.hint)
    }

    /**
     * Which optional parts of [element] the record carries. A range needs all three of its
     * numbers: a reader told a value without its ends has nothing to read it against.
     */
    fun elementFlags(element: AccessibleElement): Int {
        var flags = 0
        if (element.value != null) flags = flags or HAS_VALUE
        if (element.rangeMin != null && element.rangeMax != null && element.rangeValue != null) {
            flags = flags or HAS_RANGE
        }
        if (element.hint != null) flags = flags or HAS_HINT
        return flags
    }

    /**
     * A label as UTF-8 that fits a record with its terminating zero, cut before a character
     * rather than through one.
     */
    fun labelBytes(label: String): ByteArray = fittedBytes(label, TEXT_BYTES)

    /** [text] as UTF-8 that fits [capacity] bytes with its terminating zero, cut before a character. */
    fun fittedBytes(text: String, capacity: Int): ByteArray {
        val bytes = text.encodeToByteArray()
        if (bytes.size < capacity) return bytes
        var length = capacity - 1
        while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) length--
        return bytes.copyOf(length)
    }

    private fun writeText(bytes: ByteArray, field: CArrayPointer<ByteVar>) {
        for (offset in bytes.indices) field[offset] = bytes[offset]
        field[bytes.size] = 0
    }

    /** UTF-8 up to the first zero, and never past the end of the field. */
    private fun readText(text: CArrayPointer<ByteVar>): String {
        var length = 0
        while (length < TEXT_BYTES && text[length] != 0.toByte()) length++
        return if (length == 0) "" else text.readBytes(length).decodeToString()
    }
}
