package org.thisisthepy.compose.window

/**
 * What happened in the window, as the window recorded it.
 *
 * Plain numbers rather than a platform event. Every platform layer fills in the same
 * record, so nothing of AppKit, Win32 or Xlib reaches the scene and the scene cannot tell
 * which platform it ran on.
 */
data class WindowEvent(
    val kind: Int,
    val x: Float,
    val y: Float,
    val buttons: Int,
    val modifiers: Int,
    val keyCode: Int,
    val codePoint: Int,
    /** What an input method produced, and empty for everything that is not text. */
    val text: String,
) {

    /**
     * Whether this is the press of the secondary button. Read through [SECONDARY_BUTTON],
     * which every platform layer sets, and not through the held bits, which a control-click
     * on macOS does not carry.
     */
    val isSecondaryPress: Boolean
        get() = kind == POINTER_DOWN && buttons and SECONDARY_BUTTON != 0

    companion object {
        const val POINTER_MOVE = 1
        const val POINTER_DOWN = 2
        const val POINTER_UP = 3
        const val SCROLL = 4
        const val KEY_DOWN = 5
        const val KEY_UP = 6
        const val TEXT_COMMIT = 7
        const val TEXT_COMPOSE = 8
        const val RESIZE = 9
        const val FILES_ENTERED = 10
        const val FILES_DROPPED = 11
        const val FILES_EXITED = 12

        // An input method's preedit callbacks as the X11 window heard them: a composition
        // began, a run of characters was replaced (first in `keyCode`, how many in
        // `codePoint`, the caret in `x`, the new characters in `text`), and the composition
        // ended.
        const val PREEDIT_START = 13
        const val PREEDIT_DRAW = 14
        const val PREEDIT_DONE = 15

        /**
         * An editing action AppKit named by its selector, `selectAll:` or `copy:`, with the
         * selector in [text]. The Edit menu sends these, and so does a key binding the
         * scene has not already been shown as a key.
         */
        const val EDIT_COMMAND = 16

        /**
         * Set in [buttons] on a press or release of the secondary button. The pressed
         * buttons alone cannot say which one was let go.
         */
        const val SECONDARY_BUTTON = 1 shl 16

        /** The buttons held down, in the low bits of [buttons]: primary, secondary and middle. */
        const val PRIMARY_HELD = 1
        const val SECONDARY_HELD = 2
        const val MIDDLE_HELD = 4

        /**
         * The [buttons] of a pointer event: the buttons [held] and, when the event is the
         * press or release of the secondary button, [SECONDARY_BUTTON]. Every platform layer
         * builds the field through this, so the scene reads one encoding.
         */
        fun pointerButtons(held: Int, secondaryEvent: Boolean): Int =
            if (secondaryEvent) held or SECONDARY_BUTTON else held

        // An entry of the text edit menu the window put up itself on a right click was
        // chosen; `keyCode` is its [EditMenuId]. Platform layers hand it to
        // [WindowListener.onContextMenuChosen] and do not pass it on as an event.
        const val MENU_COMMAND = 17
    }
}
