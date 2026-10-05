@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import org.thisisthepy.compose.window.WindowEvent

// The bits a modifier flag word carries, as the window layers write them (NSEvent.h).
private const val MODIFIER_SHIFT = 1 shl 17
private const val MODIFIER_CONTROL = 1 shl 18
private const val MODIFIER_OPTION = 1 shl 19
private const val MODIFIER_COMMAND = 1 shl 20

/**
 * Hands one thing the window heard to the scene.
 *
 * Built from parts rather than from a platform event: every window layer records the same
 * [WindowEvent] fields, so nothing of AppKit or Xlib reaches the scene. The pointer's place
 * arrives from the top left of the content, in the pixels the scene measures in.
 *
 * Text is not handled here. It reaches a field through [SceneTextInput], never as keys,
 * because a key that types a character and the input method that also commits it would
 * type it twice.
 */
internal fun ComposeScene.receive(event: WindowEvent, keyOf: (Int) -> Key = ::composeKey) {
    when (event.kind) {
        WindowEvent.KEY_DOWN, WindowEvent.KEY_UP -> sendKeyEvent(
            KeyEvent(
                key = keyOf(event.keyCode),
                type = if (event.kind == WindowEvent.KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                codePoint = event.codePoint,
                isAltPressed = event.modifiers and MODIFIER_OPTION != 0,
                isCtrlPressed = event.modifiers and MODIFIER_CONTROL != 0,
                isMetaPressed = event.modifiers and MODIFIER_COMMAND != 0,
                isShiftPressed = event.modifiers and MODIFIER_SHIFT != 0,
            ),
        )

        WindowEvent.POINTER_MOVE -> sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(event.x, event.y),
            buttons = PointerButtons(isPrimaryPressed = event.buttons and 1 != 0),
        )

        WindowEvent.POINTER_DOWN -> sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(event.x, event.y),
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
        )

        WindowEvent.POINTER_UP -> sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(event.x, event.y),
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = false),
        )

        // The wheel's travel arrives where a position usually is, because a scroll happens
        // wherever the pointer already was.
        WindowEvent.SCROLL -> sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset.Zero,
            scrollDelta = Offset(event.x, event.y),
        )
    }
}
