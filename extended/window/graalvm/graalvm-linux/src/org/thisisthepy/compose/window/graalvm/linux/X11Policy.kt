package org.thisisthepy.compose.window.graalvm.linux

import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowVisibility

/** The codes `dxc_native_set_visibility` takes. */
internal object X11Visibility {
    fun code(visibility: WindowVisibility): Int = when (visibility) {
        WindowVisibility.Hidden -> 0
        WindowVisibility.Visible -> 1
        WindowVisibility.Minimized -> 2
        WindowVisibility.Fullscreen -> 3
    }
}

/** How the window decides between light and dark, matching the Kotlin/Native X11 window. */
internal object X11Theme {
    fun fromGtkTheme(value: String?): SystemTheme {
        val gtk = value.orEmpty().lowercase()
        return if (gtk.endsWith(":dark") || gtk.endsWith("-dark")) SystemTheme.Dark else SystemTheme.Light
    }
}

/** Splits what `readDroppedPaths` returns into the paths: NUL is the one byte no path holds. */
fun splitDroppedPaths(joined: String): List<String> =
    if (joined.isEmpty()) emptyList() else joined.split('\u0000').filter { it.isNotEmpty() }
