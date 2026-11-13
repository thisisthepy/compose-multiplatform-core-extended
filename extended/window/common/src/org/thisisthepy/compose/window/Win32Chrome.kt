package org.thisisthepy.compose.window

/**
 * How a Win32 window is framed, decided once so that the Kotlin/Native window and the GraalVM
 * one come up the same.
 *
 * Under [WindowChrome.Modern] the window keeps the system's frame (the shadow, the resize
 * borders, Snap, the animations) and gives up only the caption strip, which becomes part of
 * the content: the scene draws the minimise, maximise or restore and close buttons there, at
 * the trailing end, Windows 11's 46 by 32 points each. The window answers the system's hit test
 * for the strip itself (`windows-shared/c/win32_caption.h`): an empty part of it moves the
 * window, its top edge resizes, and the maximise button is the system's maximise button to
 * Windows, which is what shows Snap Layouts over it. So the scene draws the buttons and
 * nothing else of the frame ([WindowCaption.frameBySystem]).
 *
 * [WindowChrome.System] keeps the system's caption with the content below it. A window nobody
 * configured has that one too: taking the strip is only safe for a caller that draws the
 * buttons.
 */
object Win32Chrome {
    /** The caption strip's height, in points. `DXC_CAPTION_HEIGHT_DIP` in win32_caption.h. */
    const val CAPTION_HEIGHT = 32f

    /** One button's width, in points. `DXC_CAPTION_BUTTON_WIDTH_DIP` in win32_caption.h. */
    const val CAPTION_BUTTON_WIDTH = 46f

    /** How many buttons the scene draws. `DXC_CAPTION_BUTTON_COUNT` in win32_caption.h. */
    const val CAPTION_BUTTONS = 3

    /**
     * Whether the window keeps the system's caption: the `system_chrome` the window is
     * configured with before it opens (`dxc_native_window_configure`).
     */
    fun systemCaption(config: WindowConfig): Boolean = config.chrome != WindowChrome.Modern

    /**
     * The strip the scene draws its buttons in, or [WindowCaption.None] where the caption is
     * the system's. [maximized] is whether the window is maximised now
     * (`dxc_native_window_is_zoomed`), which turns the maximise button into restore.
     *
     * A maximised window, which is also what full screen is on this platform, keeps the strip
     * and its buttons. [TitleBarStyle] changes nothing here: the hit test knows one height.
     */
    fun caption(config: WindowConfig, maximized: Boolean = false): WindowCaption {
        if (systemCaption(config)) return WindowCaption.None
        return WindowCaption(
            height = CAPTION_HEIGHT,
            buttonsWidth = CAPTION_BUTTON_WIDTH * CAPTION_BUTTONS,
            buttonsAtStart = false,
            buttonsDrawnByScene = true,
            frameBySystem = true,
            maximized = maximized,
        )
    }
}
