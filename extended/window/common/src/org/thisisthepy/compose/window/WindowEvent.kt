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
    }
}
