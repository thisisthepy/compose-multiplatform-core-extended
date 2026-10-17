package org.thisisthepy.compose.window

// Code points of a string, which the accessibility server and the input methods count in.

/** Appends one code point, as the pair of surrogates it is when it is past the first plane. */
fun StringBuilder.appendPoint(point: Int) {
    if (point < 0x10000) {
        append(point.toChar())
    } else {
        val offset = point - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}

/** The code points of a string, with a pair of surrogates read as the one character it is. */
fun codePointsOf(text: String): List<Int> {
    val points = ArrayList<Int>(text.length)
    var index = 0
    while (index < text.length) {
        val unit = text[index]
        if (unit.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            points += 0x10000 + ((unit.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
            index += 2
        } else {
            points += unit.code
            index += 1
        }
    }
    return points
}
