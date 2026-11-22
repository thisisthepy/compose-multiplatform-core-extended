package org.thisisthepy.compose.window

/**
 * Encodes window title text for the EWMH `_NET_WM_NAME` property (`UTF8_STRING`, format 8).
 *
 * Embedded NUL bytes are removed and no trailing NUL is appended, matching the EWMH
 * specification for `UTF8_STRING` properties.
 */
object X11Title {
    fun netWmNameBytes(title: String): ByteArray =
        if ('\u0000' in title) title.replace("\u0000", "").encodeToByteArray()
        else title.encodeToByteArray()
}
