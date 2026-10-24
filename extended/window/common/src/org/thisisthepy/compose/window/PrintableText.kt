package org.thisisthepy.compose.window

/**
 * What of an input method's text goes into a field.
 *
 * Everything but the control characters. A Control letter has a character of its own,
 * Control A is U+0001, and an input method that has no binding for it can hand that over as
 * text. It is never text anybody meant to type: a field that took it drew a box where the
 * caret was. Line breaks and tabs are keys, and arrive as keys, so nothing is lost.
 */
fun printableText(text: String): String {
    if (text.none { it.isControlCharacter() }) return text
    return text.filterNot { it.isControlCharacter() }
}

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7F
