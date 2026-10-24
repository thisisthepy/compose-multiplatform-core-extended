package org.thisisthepy.compose.window

/**
 * Who draws the strip at the top of a window.
 *
 * [Modern] is what a window gets when nothing is said: the content runs to the top edge and the
 * window keeps its buttons. On macOS the system's buttons stay where they are, over a
 * transparent title bar whose title is carried for the switcher but not drawn. On Linux there
 * is no system title bar: the window has no decorations, the scene draws the caption and its
 * buttons, a press in an empty part of the caption moves the window ([WindowPlatform.startMove])
 * and a press at an edge resizes it ([WindowPlatform.startResize]).
 *
 * [System] is the platform's own title bar, with the content below it. [TitleBarStyle] has no
 * effect there.
 */
enum class WindowChrome { Modern, System }

/**
 * Which window a [WindowChrome.Modern] caption belongs to.
 *
 * [Normal] is the default: what an application on that platform is today. On macOS its buttons
 * sit further in from the corner and its corner radius is larger (the unified toolbar's), and
 * [Simple] is the plain title bar's place and radius. On Linux the two differ only in the
 * caption's height; the buttons stay at the end the platform puts them.
 */
enum class TitleBarStyle { Normal, Simple }

/**
 * The strip at the top of a window that belongs to the window rather than to the application,
 * as the platform measured it, in points.
 *
 * Content may paint under it, but a control placed where the buttons are would leave both
 * unusable, so the scene steps its content clear of [height] and of [buttonsWidth] at the end
 * the buttons are at, while still painting across the strip.
 */
data class WindowCaption(
    /** How tall the strip is. Zero where the window has none, as under [WindowChrome.System]. */
    val height: Float = 0f,
    /** How much room the window buttons take at the end they sit at. */
    val buttonsWidth: Float = 0f,
    /** Whether that end is the leading one (macOS) rather than the trailing one (Linux). */
    val buttonsAtStart: Boolean = true,
    /** A strip directly above the bar that the bar's surface covers and its content starts below. */
    val insetTop: Float = 0f,
    /** The radius the system gave the window's corners, or zero where it does not say. */
    val cornerRadius: Float = 0f,
    /**
     * Whether the scene draws the window buttons itself, because the platform draws none
     * (Linux under [WindowChrome.Modern]). They then act through [WindowPlatform.performCaptionAction].
     */
    val buttonsDrawnByScene: Boolean = false,
) {
    companion object {
        /** No strip to avoid. */
        val None = WindowCaption()
    }
}

/** What a caption button asks of its window. */
enum class CaptionAction { Minimize, ToggleMaximize, Close }

/** The edge or corner a resize starts from. */
enum class WindowEdge { Top, Bottom, Left, Right, TopLeft, TopRight, BottomLeft, BottomRight }
