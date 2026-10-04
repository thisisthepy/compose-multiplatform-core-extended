package org.thisisthepy.compose.window.macos

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The strip at the top of a window that belongs to the window rather than to the
 * application: the transparent title bar the close, minimise and zoom buttons sit in.
 *
 * Content may run underneath it, but a widget placed where the buttons are would leave
 * both unusable, so whatever owns the top of the window steps its own content clear of the
 * strip while still painting across it.
 */
@Immutable
data class WindowCaption(
    /** How tall the strip is. */
    val height: Dp = 0.dp,
    /** How much room the window buttons take at the end they sit at. */
    val buttonsWidth: Dp = 0.dp,
    /** Whether that end is the leading one, which is where this platform puts them. */
    val buttonsAtStart: Boolean = true,
    /** A strip directly above the bar that the bar's surface covers and its content starts below. */
    val insetTop: Dp = 0.dp,
) {
    companion object {
        /** No strip to avoid. */
        val None = WindowCaption()
    }
}
