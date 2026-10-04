package org.thisisthepy.compose.window.macos

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.thisisthepy.compose.window.EditMenuId

/**
 * The key presses that stand for an entry of the edit menu: the key down and up with
 * Command held. An entry acts by pressing the shortcut it is named after, so the editor
 * stays Compose's and an input method's composition is left where it was.
 */
fun editChord(id: Int): List<KeyEvent>? {
    val key = when (id) {
        EditMenuId.CUT -> Key.X
        EditMenuId.COPY -> Key.C
        EditMenuId.PASTE -> Key.V
        EditMenuId.SELECT_ALL -> Key.A
        else -> return null
    }
    return listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).map { type ->
        KeyEvent(
            key = key, type = type, codePoint = 0,
            isAltPressed = false, isCtrlPressed = false, isMetaPressed = true, isShiftPressed = false,
        )
    }
}
