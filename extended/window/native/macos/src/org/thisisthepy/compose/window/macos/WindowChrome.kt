package org.thisisthepy.compose.window.macos

import androidx.compose.ui.unit.dp

/**
 * How a macOS window is built: its style mask bit, its title bar and its toolbar.
 *
 * Decided in one place so that every macOS window built from the same description has the
 * same corners and its content at the same height. On macOS 26 a window with a toolbar has
 * a larger corner radius and a taller title bar than one without, so two windows that each
 * wrote down their own style mask and title bar came up looking different.
 *
 * The window's corner is the system's in every case. AppKit has no public way to set a
 * window's radius, and a radius drawn by clipping the content stands inside the system's
 * own outline and shadow. The larger radius and the buttons set further in are what a
 * unified toolbar gives a window, so [Modern] asks for one and [Simple] does not.
 */
data class MacosWindowChrome(
    /** The content runs under the title bar rather than starting below it. */
    val fullSizeContentView: Boolean,
    /** The title bar draws nothing of its own, so the content shows through it. */
    val titlebarAppearsTransparent: Boolean,
    /** The title is carried, for the switcher and Mission Control, but not drawn. */
    val titleHidden: Boolean,
    /** An empty unified toolbar, which sets the bar's height and the window's radius. */
    val unifiedToolbar: Boolean,
) {
    companion object {
        /** Content under a transparent bar, with the unified toolbar's height and radius. */
        val Modern = MacosWindowChrome(
            fullSizeContentView = true,
            titlebarAppearsTransparent = true,
            titleHidden = true,
            unifiedToolbar = true,
        )

        /** Content under a transparent bar of the plain height, with no toolbar. */
        val Simple = Modern.copy(unifiedToolbar = false)

        /** The system's own title bar, with the content below it. */
        val System = MacosWindowChrome(
            fullSizeContentView = false,
            titlebarAppearsTransparent = false,
            titleHidden = false,
            unifiedToolbar = false,
        )
    }
}

/**
 * The strip the title bar takes and the room its buttons take, from what the window
 * reports, in points.
 *
 * Both macOS windows measure the same five numbers and hand them here, so the content
 * starts at the same height in both. [windowHeight] is the window's frame,
 * [contentLayoutHeight] the part of it below the bar, [closeMinX] where the close button
 * starts and [zoomMaxX] where the zoom button ends, or null for a window with no buttons.
 * The gap in front of the first button is mirrored after the last. [cornerRadius] is the
 * radius the system gave the window's corners, or null where it does not say.
 *
 * Every one of them is the system's. A window with a unified toolbar has its buttons
 * further in, a taller bar and rounder corners than one without, and the numbers move
 * between releases, so a constant would be right for one style on one release only.
 *
 * Null while the window is changing size: the frame and the layout rect are updated at
 * different moments and the difference can be negative for an instant. The last reading
 * stands until there is a real one. A window that kept the system's title bar has nothing
 * running under it, so its caption is empty.
 */
fun macosWindowCaption(
    chrome: MacosWindowChrome,
    windowHeight: Double,
    contentLayoutHeight: Double,
    closeMinX: Double?,
    zoomMaxX: Double?,
    cornerRadius: Double? = null,
): WindowCaption? {
    if (!chrome.fullSizeContentView) return WindowCaption.None
    val height = windowHeight - contentLayoutHeight
    if (height < 0.0) return null
    val width = if (closeMinX == null || zoomMaxX == null) 0.0 else zoomMaxX + closeMinX
    if (width < 0.0) return null
    return WindowCaption(
        height = height.toFloat().dp,
        buttonsWidth = width.toFloat().dp,
        // The platform's own, and this platform puts them at the leading edge.
        buttonsAtStart = true,
        cornerRadius = (cornerRadius?.takeIf { it > 0.0 } ?: 0.0).toFloat().dp,
    )
}

/**
 * The key both macOS windows read the system's corner radius under.
 *
 * AppKit has no public property for it. The window answers `_cornerRadius` on every
 * release that draws rounded windows, and both windows ask by key value coding after
 * checking that it answers, so a release that drops it gives no radius rather than an
 * exception. Asked the same way in both so they cannot read different numbers.
 */
const val MACOS_CORNER_RADIUS_KEY: String = "_cornerRadius"
