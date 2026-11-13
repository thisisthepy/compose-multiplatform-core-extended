@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import org.thisisthepy.compose.window.CaretRect
import org.thisisthepy.compose.window.WindowEvent

// The text context menu in a scene is Compose's own: a text field and a selection answer a
// secondary press with the menu they build (Cut, Copy, Paste, Select All, each enabled by
// what the field allows and labelled in the platform's language), drawn as a popup of the
// scene on the platforms whose window has no menu of its own, X11 among them. The host adds
// nothing to it for a press. It adds the keyboard's way to open it, which Compose has not got.

/** The key number of F10 in the shared reader's numbering (AppKit's kVK_F10). */
internal const val KEY_F10 = 0x6D

/** The key number of the Menu key in the shared reader's numbering (AppKit's kVK_ContextualMenu). */
internal const val KEY_MENU = 0x6E

/**
 * Whether [event] asks for the focused field's menu from the keyboard: the Menu key alone,
 * or Shift and F10, the same request on a keyboard that has no Menu key. Windows and the
 * Linux desktops both take these two.
 */
internal fun opensTextMenu(event: WindowEvent): Boolean {
    if (event.kind != WindowEvent.KEY_DOWN) return false
    val held = event.modifiers and (MODIFIER_SHIFT or MODIFIER_CONTROL or MODIFIER_OPTION or MODIFIER_COMMAND)
    return when (event.keyCode) {
        KEY_MENU -> held == 0 || held == MODIFIER_SHIFT
        KEY_F10 -> held == MODIFIER_SHIFT
        else -> false
    }
}

/**
 * Where the menu opened from the keyboard is asked for, in window pixels: on the caret, at
 * the middle of its height. With text selected the point is moved one pixel into the
 * selection, onto the last selected character. The caret stands at the selection's far end
 * ([TextRange.max]), so that character is the one just before it. A press inside the
 * selection keeps it, where a press outside it would put the caret there, and a Copy chosen
 * from the menu would then have nothing to copy.
 *
 * Null where the field has no caret laid out yet.
 */
internal fun textMenuPoint(caret: CaretRect?, selection: TextRange?): Offset? {
    if (caret == null || caret.bottom <= caret.top) return null
    val y = (caret.top + caret.bottom) / 2f
    val x = if (selection != null && !selection.collapsed) caret.left - 1f else caret.left
    return Offset(x, y)
}

/**
 * The press and release of the secondary button at [at], which is how the menu is asked for:
 * the field that has the caret there answers it as it answers the mouse, with the same menu
 * and the same items. [timeMillis] is the key's own time, so the two are dated as it was.
 */
internal fun secondaryClickAt(at: Offset, timeMillis: Long): List<WindowEvent> = listOf(
    WindowEvent(
        WindowEvent.POINTER_DOWN, at.x, at.y,
        WindowEvent.SECONDARY_HELD or WindowEvent.SECONDARY_BUTTON, 0, 0, 0, "", timeMillis,
    ),
    WindowEvent(WindowEvent.POINTER_UP, at.x, at.y, WindowEvent.SECONDARY_BUTTON, 0, 0, 0, "", timeMillis),
)

/**
 * The Tab presses that step through an open menu as the arrow [key] asks: Down to the next
 * entry, Up to the one before. Null for any other key.
 *
 * Compose's menu on the JVM takes the arrows itself. Its common menu, the one Kotlin/Native
 * draws, only moves its focus with Tab, so an arrow it left alone is turned into the Tab that
 * does what the arrow means. The host asks only while a menu is open, so an arrow nobody took
 * anywhere else stays an arrow.
 */
internal fun menuStepFor(key: KeyEvent): List<KeyEvent>? {
    if (key.type != KeyEventType.KeyDown) return null
    val backwards = when (key.key) {
        Key.DirectionDown -> false
        Key.DirectionUp -> true
        else -> return null
    }
    return listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).map { type ->
        KeyEvent(
            key = Key.Tab,
            type = type,
            codePoint = 0,
            isAltPressed = false,
            isCtrlPressed = false,
            isMetaPressed = false,
            isShiftPressed = backwards,
        )
    }
}

/**
 * Opens the focused field's menu at a point without pressing for it, where the platform's
 * Compose gives the scene a way to: the JVM's does, Kotlin/Native's does not.
 */
internal expect class FieldMenuOpener() {
    /** Wraps the content, so the fields in it can be found. */
    @Composable
    fun Provide(content: @Composable () -> Unit)

    /**
     * Opens the menu of the field that has focus at [at], in window pixels. False where
     * there is no such field or no way to, and the scene presses the secondary button there.
     */
    fun open(at: Offset): Boolean
}
