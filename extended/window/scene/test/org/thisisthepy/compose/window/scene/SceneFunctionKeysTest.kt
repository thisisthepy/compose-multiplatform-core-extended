@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shift with F10 and the menu key, which ask for a context menu from the keyboard, reach the
 * node that has focus as the keys they are. Before the function keys were in the table they
 * arrived as Unknown and nothing could tell them apart.
 */
class SceneFunctionKeysTest {
    private val shift = 1 shl 17

    private fun key(kind: Int, keyCode: Int, modifiers: Int) =
        WindowEvent(kind, 0f, 0f, 0, modifiers, keyCode, 0, "")

    @Test
    fun shift_f10_and_the_menu_key_reach_the_focused_node() {
        val heard = ArrayList<KeyEvent>()
        val requester = FocusRequester()
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        val canvas = Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas()
        var time = 0L
        val frame = { scene.render(canvas, time); time += 16_000_000L }
        try {
            scene.setContent {
                Box(
                    Modifier.fillMaxSize()
                        .focusRequester(requester)
                        .onKeyEvent { heard += it; true }
                        .focusable(),
                )
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
            frame()
            frame()
            scene.receive(key(WindowEvent.KEY_DOWN, 0x6D, shift))
            scene.receive(key(WindowEvent.KEY_UP, 0x6D, shift))
            scene.receive(key(WindowEvent.KEY_DOWN, 0x6E, 0))
            frame()
        } finally {
            scene.close()
        }
        assertEquals(3, heard.size, "every key reached the focused node: $heard")
        assertEquals(Key.F10, heard[0].key)
        assertEquals(KeyEventType.KeyDown, heard[0].type)
        assertTrue(heard[0].isShiftPressed, "Shift is held with F10")
        assertEquals(Key.F10, heard[1].key)
        assertEquals(KeyEventType.KeyUp, heard[1].type)
        assertEquals(Key.Menu, heard[2].key)
    }
}
