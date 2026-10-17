package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.windows.c.DXC_TEXT_BYTES
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

    /** At most this many elements are handed to UI Automation in one push. */
    const val MAX_ELEMENTS: Int = 256

    /** One event as the window wrote it down. */
    fun readEvent(record: dxc_event): WindowEvent = WindowEvent(
        kind = record.kind,
        x = record.x,
        y = record.y,
        buttons = record.buttons,
        modifiers = record.modifiers,
        keyCode = record.key_code,
        codePoint = record.code_point,
        text = readText(record.text),
    )

    /** One element of the accessibility tree, written into the record UI Automation reads. */
    fun writeElement(element: AccessibleElement, record: dxc_element) {
        record.role = element.role
        record.x = element.x
        record.y = element.y
        record.width = element.width
        record.height = element.height
        val label = labelBytes(element.label)
        for (offset in label.indices) record.label[offset] = label[offset]
        record.label[label.size] = 0
    }

    /**
     * A label as UTF-8 that fits a record with its terminating zero, cut before a character
     * rather than through one.
     */
    fun labelBytes(label: String): ByteArray {
        val bytes = label.encodeToByteArray()
        if (bytes.size < TEXT_BYTES) return bytes
        var length = TEXT_BYTES - 1
        while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) length--
        return bytes.copyOf(length)
    }

    /** UTF-8 up to the first zero, and never past the end of the field. */
    private fun readText(text: CArrayPointer<ByteVar>): String {
        var length = 0
        while (length < TEXT_BYTES && text[length] != 0.toByte()) length++
        return if (length == 0) "" else text.readBytes(length).decodeToString()
    }
}
