package org.thisisthepy.compose.window.linux

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset

/**
 * Files let go over the window, and where.
 *
 * The same surface the macOS window offers: the window takes the drop itself and reports
 * paths, because Compose's own drag and drop does not reach this platform. A drag of
 * anything that is not a list of files is refused at the edge of the window, so the reader
 * sees the no-entry cursor rather than a drop that quietly does nothing.
 */
object FileDrop {

    /** Where the pointer is while files are being dragged over the window. */
    val hovering = mutableStateOf<Offset?>(null)

    /** The paths of the last drop, in the order the source listed them. */
    val dropped = mutableStateOf<List<String>>(emptyList())
}

/**
 * Turns the `text/uri-list` a drag source hands over into paths.
 *
 * One URI per line, `#` starts a comment line. Only `file:` URIs are kept; a host name
 * between the slashes is skipped and percent escapes are decoded as UTF-8. A line that cannot
 * be read is dropped and the rest are kept: one unreadable name must not lose the other nine.
 */
fun pathsFromUriList(list: String): List<String> =
    list.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("file://") }
        .mapNotNull { line ->
            val afterScheme = line.removePrefix("file://")
            val slash = afterScheme.indexOf('/')
            if (slash < 0) null else percentDecode(afterScheme.substring(slash)).takeIf { it.isNotEmpty() }
        }
        .toList()

private fun percentDecode(text: String): String {
    val out = ArrayList<Byte>(text.length)
    var at = 0
    val source = text.encodeToByteArray()
    while (at < source.size) {
        val b = source[at]
        if (b == '%'.code.toByte() && at + 2 < source.size) {
            val hi = hexValue(source[at + 1])
            val lo = hexValue(source[at + 2])
            if (hi >= 0 && lo >= 0) {
                out.add(((hi shl 4) or lo).toByte())
                at += 3
                continue
            }
        }
        out.add(b)
        at++
    }
    return out.toByteArray().decodeToString()
}

private fun hexValue(b: Byte): Int = when (b.toInt().toChar()) {
    in '0'..'9' -> b - '0'.code.toByte()
    in 'a'..'f' -> b - 'a'.code.toByte() + 10
    in 'A'..'F' -> b - 'A'.code.toByte() + 10
    else -> -1
}
