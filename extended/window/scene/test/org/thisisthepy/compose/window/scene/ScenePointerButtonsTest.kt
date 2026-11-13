@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A press or release reaches Compose as the button the window heard, with the buttons held
 * at that moment, and not as the primary button whatever was pressed.
 */
class ScenePointerButtonsTest {

    private class Heard(val type: PointerEventType, val button: PointerButton?, val buttons: PointerButtons)

    /** The held state as the five flags Compose reads, in the order primary, secondary, tertiary, back, forward. */
    private fun PointerButtons.flags() =
        listOf(isPrimaryPressed, isSecondaryPressed, isTertiaryPressed, isBackPressed, isForwardPressed)

    private fun held(vararg pressed: Int) = (0 until 5).map { (1 shl it) in pressed }

    private fun withScene(body: (send: (Int, Int) -> Unit, heard: List<Heard>) -> Unit) {
        val heard = ArrayList<Heard>()
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        try {
            scene.setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                                    heard += Heard(event.type, event.button, event.buttons)
                                }
                            }
                        }
                    },
                )
            }
            scene.render(Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas(), 0L)
            body({ kind, buttons -> scene.receive(WindowEvent(kind, 50f, 50f, buttons, 0, 0, 0, "")) }, heard)
        } finally {
            scene.close()
        }
    }

    private val perButton = listOf(
        Triple(WindowEvent.PRIMARY_HELD, 0, PointerButton.Primary),
        Triple(WindowEvent.SECONDARY_HELD, WindowEvent.SECONDARY_BUTTON, PointerButton.Secondary),
        Triple(WindowEvent.MIDDLE_HELD, WindowEvent.MIDDLE_BUTTON, PointerButton.Tertiary),
        Triple(WindowEvent.BACK_HELD, WindowEvent.BACK_BUTTON, PointerButton.Back),
        Triple(WindowEvent.FORWARD_HELD, WindowEvent.FORWARD_BUTTON, PointerButton.Forward),
    )

    @Test
    fun a_press_and_release_of_each_button_reach_compose_as_that_button() {
        for ((bit, own, expected) in perButton) {
            withScene { send, heard ->
                send(WindowEvent.POINTER_DOWN, WindowEvent.pointerButtons(bit, own))
                send(WindowEvent.POINTER_UP, WindowEvent.pointerButtons(0, own))
                assertEquals(2, heard.size, "button $expected")
                assertEquals(PointerEventType.Press, heard[0].type)
                assertEquals(expected, heard[0].button)
                assertEquals(held(bit), heard[0].buttons.flags(), "held during the press of $expected")
                assertEquals(PointerEventType.Release, heard[1].type)
                assertEquals(expected, heard[1].button)
                assertEquals(held(), heard[1].buttons.flags(), "held after the release of $expected")
            }
        }
    }

    @Test
    fun a_window_that_leaves_the_pressed_button_out_of_the_held_bits_still_presses_it() {
        // A control-click on macOS: the primary is physically down and the event names the secondary.
        withScene { send, heard ->
            send(WindowEvent.POINTER_DOWN, WindowEvent.pointerButtons(WindowEvent.PRIMARY_HELD, WindowEvent.SECONDARY_BUTTON))
            assertEquals(PointerButton.Secondary, heard[0].button)
            assertEquals(true, heard[0].buttons.isSecondaryPressed)
        }
    }

    @Test
    fun a_press_of_one_button_while_another_is_held_keeps_both_held_until_each_is_released() {
        withScene { send, heard ->
            send(WindowEvent.POINTER_DOWN, WindowEvent.PRIMARY_HELD)
            send(
                WindowEvent.POINTER_DOWN,
                WindowEvent.pointerButtons(WindowEvent.PRIMARY_HELD or WindowEvent.SECONDARY_HELD, WindowEvent.SECONDARY_BUTTON),
            )
            send(
                WindowEvent.POINTER_UP,
                WindowEvent.pointerButtons(WindowEvent.PRIMARY_HELD or WindowEvent.SECONDARY_HELD, WindowEvent.SECONDARY_BUTTON),
            )
            send(WindowEvent.POINTER_UP, WindowEvent.PRIMARY_HELD)
            assertEquals(listOf(PointerButton.Primary, PointerButton.Secondary, PointerButton.Secondary, PointerButton.Primary), heard.map { it.button })
            assertEquals(held(1), heard[0].buttons.flags())
            assertEquals(held(1, 2), heard[1].buttons.flags(), "the secondary press with the primary still down")
            assertEquals(held(1), heard[2].buttons.flags(), "the secondary release leaves the primary down")
            assertEquals(held(), heard[3].buttons.flags())
        }
    }
}
