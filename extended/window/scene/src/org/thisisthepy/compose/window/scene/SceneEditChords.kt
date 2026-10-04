package org.thisisthepy.compose.window.scene

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.thisisthepy.compose.window.EditMenuId

/**
 * The key presses that stand for an entry of the text edit menu: the key down and the key up
 * with the shortcut modifier held, which is Command on macOS and Control elsewhere. Null for
 * an id that is not an edit entry, such as a dismissed menu.
 *
 * An entry acts by pressing the shortcut it is named after. The editor stays Compose's and
 * the key goes to the field that has focus exactly as it would from the keyboard, so an
 * input method's composition is left where it was and no text is moved by the menu.
 */
internal fun editChord(id: Int, commandKey: Boolean): List<KeyEvent>? {
    val key = when (id) {
        EditMenuId.CUT -> Key.X
        EditMenuId.COPY -> Key.C
        EditMenuId.PASTE -> Key.V
        EditMenuId.SELECT_ALL -> Key.A
        else -> return null
    }
    return listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).map { type ->
        KeyEvent(
            key = key,
            type = type,
            codePoint = 0,
            isAltPressed = false,
            isCtrlPressed = !commandKey,
            isMetaPressed = commandKey,
            isShiftPressed = false,
        )
    }
}

/** True for the platform layers that use Command as the shortcut modifier. */
internal fun usesCommandKey(platformName: String): Boolean =
    platformName.startsWith("appkit") || platformName.startsWith("macos")
