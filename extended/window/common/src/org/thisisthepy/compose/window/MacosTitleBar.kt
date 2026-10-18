package org.thisisthepy.compose.window

/**
 * How a macOS window's title bar is built, decided once for both macOS windows.
 *
 * The Kotlin/Native window and the GraalVM one each turn a [WindowConfig] into one of these
 * and apply it as it is, so the same configuration gives the same corners and starts the
 * content at the same height on both paths. They used to write down their own style mask and
 * title bar, and on macOS 26 that showed: one had a unified toolbar and the other did not, so
 * one came up rounder and with its content lower.
 *
 * The window's corner is the system's in every case. AppKit has no public way to set a
 * window's radius, and a radius drawn by clipping the content stands inside the system's own
 * outline and shadow. The larger radius and the buttons set further in are what a unified
 * toolbar gives a window, so [TitleBarStyle.Normal] asks for one and [TitleBarStyle.Simple]
 * does not.
 */
data class MacosTitleBar(
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
        /** Content under a transparent bar, with the unified toolbar's height, inset and radius. */
        val Normal = MacosTitleBar(
            fullSizeContentView = true,
            titlebarAppearsTransparent = true,
            titleHidden = true,
            unifiedToolbar = true,
        )

        /** Content under a transparent bar of the plain height, with no toolbar. */
        val Simple = Normal.copy(unifiedToolbar = false)

        /** The system's own title bar, with its title drawn and the content below it. */
        val System = MacosTitleBar(
            fullSizeContentView = false,
            titlebarAppearsTransparent = false,
            titleHidden = false,
            unifiedToolbar = false,
        )

        /**
         * The title bar [chrome] and [titleBar] ask for. Under [WindowChrome.System] the
         * title bar style has nothing to choose between, so it is ignored.
         */
        fun of(chrome: WindowChrome, titleBar: TitleBarStyle): MacosTitleBar = when (chrome) {
            WindowChrome.System -> System
            WindowChrome.Modern -> when (titleBar) {
                TitleBarStyle.Normal -> Normal
                TitleBarStyle.Simple -> Simple
            }
        }

        /** The title bar [config] asks for. */
        fun of(config: WindowConfig): MacosTitleBar = of(config.chrome, config.titleBar)
    }
}

/**
 * The caption of a macOS window built as [bar], from what the window reports, in points.
 *
 * [windowHeight] is the window's frame, [contentLayoutHeight] the part of it below the bar,
 * [closeMinX] where the close button starts and [zoomMaxX] where the zoom button ends, or null
 * for a window with no buttons. The gap in front of the first button is mirrored after the
 * last, so [WindowCaption.buttonsWidth] is the room the three buttons take with their margin
 * on both sides. [cornerRadius] is the radius the system draws, from [macosCornerRadius], or
 * null where that table has none.
 *
 * The first four are read from the window rather than written down here. A window with a
 * unified toolbar has its buttons further in, a taller bar and rounder corners than one
 * without, and the numbers move between releases, so a constant would be right for one style
 * on one release only.
 *
 * The buttons are the system's on macOS, so [WindowCaption.buttonsDrawnByScene] is false and
 * they sit at the leading edge. A window that kept the system's title bar has nothing running
 * under it, so its caption is [WindowCaption.None].
 *
 * Null while the window is changing size: the frame and the layout rect are updated at
 * different moments and the difference can be negative for an instant. The caller keeps the
 * last reading until there is a real one.
 */
fun macosCaption(
    bar: MacosTitleBar,
    windowHeight: Double,
    contentLayoutHeight: Double,
    closeMinX: Double?,
    zoomMaxX: Double?,
    cornerRadius: Double?,
): WindowCaption? {
    if (!bar.fullSizeContentView) return WindowCaption.None
    val height = windowHeight - contentLayoutHeight
    if (height < 0.0) return null
    val width = if (closeMinX == null || zoomMaxX == null) 0.0 else zoomMaxX + closeMinX
    if (width < 0.0) return null
    return WindowCaption(
        height = height.toFloat(),
        buttonsWidth = width.toFloat(),
        buttonsAtStart = true,
        cornerRadius = (cornerRadius?.takeIf { it > 0.0 } ?: 0.0).toFloat(),
        buttonsDrawnByScene = false,
    )
}
