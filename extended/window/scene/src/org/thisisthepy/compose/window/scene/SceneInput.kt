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
import org.thisisthepy.compose.window.monotonicNanos

// The bits a modifier flag word carries, as the window layers write them (NSEvent.h).
internal const val MODIFIER_SHIFT = 1 shl 17
internal const val MODIFIER_CONTROL = 1 shl 18
internal const val MODIFIER_OPTION = 1 shl 19
internal const val MODIFIER_COMMAND = 1 shl 20

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
 *
 * A pointer event reaches Compose at [timeMillis], which the host takes from when the
 * platform says it happened (see [org.thisisthepy.compose.window.EventClock]); Compose counts
 * a double click by the time between two presses, and the time the scene read the second
 * one is late by however long the frame before it took.
 */
internal fun ComposeScene.receive(
    event: WindowEvent,
    keyOf: (Int) -> Key = ::composeKey,
    timeMillis: Long = monotonicNanos() / 1_000_000,
    /** Hears a key the scene did not use, and answers whether it used it. */
    unused: (KeyEvent) -> Boolean = { false },
) {
    when (event.kind) {
        WindowEvent.KEY_DOWN, WindowEvent.KEY_UP -> keyEventOf(event, keyOf).let { key ->
            if (!sendKeyEvent(key)) unused(key)
        }

        WindowEvent.POINTER_MOVE -> sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(event.x, event.y),
            timeMillis = timeMillis,
            buttons = pointerButtonsOf(event.buttons and WindowEvent.ALL_HELD),
        )

        // The button that changed is the one the event names, and it counts as held for its
        // own press and as let go for its own release, whatever the platform had in the held
        // bits at that instant. A control-click on macOS, for one, carries only the primary.
        WindowEvent.POINTER_DOWN -> sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(event.x, event.y),
            timeMillis = timeMillis,
            button = pointerButtonOf(event.changedHeldBit),
            buttons = pointerButtonsOf((event.buttons and WindowEvent.ALL_HELD) or event.changedHeldBit),
        )

        WindowEvent.POINTER_UP -> sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(event.x, event.y),
            timeMillis = timeMillis,
            button = pointerButtonOf(event.changedHeldBit),
            buttons = pointerButtonsOf((event.buttons and WindowEvent.ALL_HELD) and event.changedHeldBit.inv()),
        )

        // The pointer went into or came back from a place the scene does not get it, a native
        // view slot: what it hovered stops being hovered, and starts again on the way back.
        WindowEvent.POINTER_EXIT -> sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = Offset(event.x, event.y),
            timeMillis = timeMillis,
            buttons = pointerButtonsOf(event.buttons and WindowEvent.ALL_HELD),
        )

        WindowEvent.POINTER_ENTER -> sendPointerEvent(
            eventType = PointerEventType.Enter,
            position = Offset(event.x, event.y),
            timeMillis = timeMillis,
            buttons = pointerButtonsOf(event.buttons and WindowEvent.ALL_HELD),
        )

        // A native view took the keyboard: the scene lets go of its focus, so no field keeps a
        // caret or takes the input method while keys go elsewhere.
        WindowEvent.NATIVE_VIEW_FOCUS_IN -> focusManager.releaseFocus()

        // The wheel's travel arrives where a position usually is, because a scroll happens
        // wherever the pointer already was.
        WindowEvent.SCROLL -> sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset.Zero,
            scrollDelta = Offset(event.x, event.y),
            timeMillis = timeMillis,
        )
    }
}

/** The Compose key event for a key press or release a window heard. */
internal fun keyEventOf(event: WindowEvent, keyOf: (Int) -> Key = ::composeKey): KeyEvent = KeyEvent(
    key = keyOf(event.keyCode),
    type = if (event.kind == WindowEvent.KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
    codePoint = event.codePoint,
    isAltPressed = event.modifiers and MODIFIER_OPTION != 0,
    isCtrlPressed = event.modifiers and MODIFIER_CONTROL != 0,
    isMetaPressed = event.modifiers and MODIFIER_COMMAND != 0,
    isShiftPressed = event.modifiers and MODIFIER_SHIFT != 0,
)

/** The Compose button a held bit stands for. */
internal fun pointerButtonOf(heldBit: Int): PointerButton = when (heldBit) {
    WindowEvent.SECONDARY_HELD -> PointerButton.Secondary
    WindowEvent.MIDDLE_HELD -> PointerButton.Tertiary
    WindowEvent.BACK_HELD -> PointerButton.Back
    WindowEvent.FORWARD_HELD -> PointerButton.Forward
    else -> PointerButton.Primary
}

/** The buttons Compose sees held, from the held bits of a window event. */
internal fun pointerButtonsOf(held: Int): PointerButtons = PointerButtons(
    isPrimaryPressed = held and WindowEvent.PRIMARY_HELD != 0,
    isSecondaryPressed = held and WindowEvent.SECONDARY_HELD != 0,
    isTertiaryPressed = held and WindowEvent.MIDDLE_HELD != 0,
    isBackPressed = held and WindowEvent.BACK_HELD != 0,
    isForwardPressed = held and WindowEvent.FORWARD_HELD != 0,
)
