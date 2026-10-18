package org.thisisthepy.compose.window.graalvm.macos

import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.MacosAxValue
import org.thisisthepy.compose.window.macosAxElement
import org.thisisthepy.compose.window.macosAxKey
import org.thisisthepy.compose.window.macosAxParents
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The records [elements] cross to `appkit_accessibility.h` as, one `struct dxc_ax_element`
 * each, in the machine's byte order.
 *
 * What each control is to AppKit (role, subrole, value, whether it may be set) is decided by
 * the common module's [macosAxElement], the rule the Kotlin/Native window applies too, and
 * written here as strings and flags, so the Objective-C side only builds what it is told.
 * Plain bytes rather than words, so the layout is checked on a JVM by the tests and the
 * native image only copies the block.
 *
 * At most [limit] elements cross; text longer than a field holds is cut at a character
 * boundary.
 */
fun encodeAccessibilityRecords(elements: List<AccessibleElement>, limit: Int = MAX_AX_ELEMENTS): ByteArray {
    val taken = if (elements.size > limit) elements.subList(0, limit) else elements
    val parents = macosAxParents(taken)
    val buffer = ByteBuffer.allocate(taken.size * AX_RECORD_BYTES).order(ByteOrder.nativeOrder())
    for ((index, element) in taken.withIndex()) {
        val at = index * AX_RECORD_BYTES
        val ax = macosAxElement(element)
        var flags = 0
        var number = 0.0
        var text: String? = null
        when (val value = ax.value) {
            is MacosAxValue.Number -> {
                flags = flags or AX_HAS_VALUE or AX_VALUE_IS_NUMBER
                number = value.number
            }
            is MacosAxValue.Text -> {
                flags = flags or AX_HAS_VALUE
                text = value.text
            }
            null -> {}
        }
        if (element.rangeMin != null) flags = flags or AX_HAS_MIN
        if (element.rangeMax != null) flags = flags or AX_HAS_MAX
        if (ax.valueSettable) flags = flags or AX_VALUE_SETTABLE
        if (ax.focusSettable) flags = flags or AX_FOCUS_SETTABLE
        if (element.hint != null) flags = flags or AX_HAS_HINT
        buffer.putInt(at + 0, macosAxKey(element, index))
        buffer.putInt(at + 4, parents[index])
        buffer.putInt(at + 8, element.states)
        buffer.putInt(at + 12, element.actions)
        buffer.putInt(at + 16, flags)
        buffer.putFloat(at + 20, element.x)
        buffer.putFloat(at + 24, element.y)
        buffer.putFloat(at + 28, element.width)
        buffer.putFloat(at + 32, element.height)
        buffer.putDouble(at + 40, number)
        buffer.putFloat(at + 48, element.rangeMin ?: 0f)
        buffer.putFloat(at + 52, element.rangeMax ?: 0f)
        putText(buffer, at + AX_ROLE_OFFSET, AX_NAME_BYTES, ax.role)
        putText(buffer, at + AX_SUBROLE_OFFSET, AX_NAME_BYTES, ax.subrole ?: "")
        putText(buffer, at + AX_LABEL_OFFSET, AX_TEXT_BYTES, element.label)
        putText(buffer, at + AX_VALUE_OFFSET, AX_TEXT_BYTES, text ?: "")
        putText(buffer, at + AX_HINT_OFFSET, AX_TEXT_BYTES, element.hint ?: "")
    }
    return buffer.array()
}

/** Writes [text] as UTF-8 ending in a zero, in at most [capacity] bytes, cut where a character starts. */
private fun putText(buffer: ByteBuffer, at: Int, capacity: Int, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    var length = minOf(bytes.size, capacity - 1)
    // A continuation byte is 10xxxxxx: step back to the start of the character it belongs to.
    while (length > 0 && length < bytes.size && (bytes[length].toInt() and 0xC0) == 0x80) length--
    for (i in 0 until length) buffer.put(at + i, bytes[i])
    buffer.put(at + length, 0)
}

/** The most elements one push hands over. */
const val MAX_AX_ELEMENTS = 1024

/** `sizeof(struct dxc_ax_element)`; the header asserts the same numbers. */
const val AX_RECORD_BYTES = 888
const val AX_NAME_BYTES = 32
const val AX_TEXT_BYTES = 256
const val AX_ROLE_OFFSET = 56
const val AX_SUBROLE_OFFSET = 88
const val AX_LABEL_OFFSET = 120
const val AX_VALUE_OFFSET = 376
const val AX_HINT_OFFSET = 632

const val AX_HAS_VALUE = 1 shl 0
const val AX_VALUE_IS_NUMBER = 1 shl 1
const val AX_HAS_MIN = 1 shl 2
const val AX_HAS_MAX = 1 shl 3
const val AX_VALUE_SETTABLE = 1 shl 4
const val AX_FOCUS_SETTABLE = 1 shl 5
const val AX_HAS_HINT = 1 shl 6

/** The event kind a reader's request is written down as on the C side. */
const val ACCESSIBILITY_ACTION_EVENT = 18
