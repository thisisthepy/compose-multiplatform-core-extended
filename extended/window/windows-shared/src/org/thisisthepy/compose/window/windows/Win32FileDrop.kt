package org.thisisthepy.compose.window.windows

/**
 * Splits the text of a [org.thisisthepy.compose.window.WindowEvent.FILES_DROPPED] event into
 * the paths: NUL is the one character a Windows path cannot hold, so it is the separator and
 * a path with spaces stays whole. Empty pieces are dropped.
 */
fun splitDroppedPaths(joined: String): List<String> =
    if (joined.isEmpty()) emptyList() else joined.split('\u0000').filter { it.isNotEmpty() }
