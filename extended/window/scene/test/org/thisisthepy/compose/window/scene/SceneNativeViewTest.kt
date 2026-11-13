@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the scene does when the window says the pointer or the keyboard went to a native view. */
class SceneNativeViewTest {

    private fun withScene(content: @androidx.compose.runtime.Composable () -> Unit, body: (ComposeScene, () -> Unit) -> Unit) {
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        val canvas = Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas()
        var time = 0L
        val frame = { scene.render(canvas, time); time += 16_000_000L }
        try {
            scene.setContent(content)
            frame()
            body(scene, frame)
        } finally {
            scene.close()
        }
    }

    private fun event(kind: Int, x: Float = 50f, y: Float = 50f, keyCode: Int = 0) =
        WindowEvent(kind, x, y, 0, 0, keyCode, 0, "")

    @Test
    fun the_pointer_leaving_for_a_native_view_ends_the_hover_and_coming_back_starts_it() {
        var hovered = false
        val source = MutableInteractionSource()
        withScene({
            val isHovered by source.collectIsHoveredAsState()
            hovered = isHovered
            Box(Modifier.fillMaxSize().hoverable(source))
        }) { scene, frame ->
            scene.receive(event(WindowEvent.POINTER_MOVE))
            frame()
            assertTrue(hovered, "hovered after a move over it")
            scene.receive(event(WindowEvent.POINTER_EXIT))
            frame()
            assertFalse(hovered, "not hovered once the pointer went into the native view")
            scene.receive(event(WindowEvent.POINTER_ENTER))
            frame()
            assertTrue(hovered, "hovered again when it came back")
        }
    }

    @Test
    fun a_native_view_taking_focus_makes_the_scene_let_go_of_its_own() {
        var focused = false
        val requester = FocusRequester()
        withScene({
            Box(Modifier.fillMaxSize().focusRequester(requester).onFocusChanged { focused = it.isFocused }.focusable())
            LaunchedEffect(Unit) { requester.requestFocus() }
        }) { scene, frame ->
            frame()
            assertTrue(focused, "the box has focus to begin with")
            scene.receive(WindowEvent.NativeViewFocusIn(1))
            frame()
            assertFalse(focused, "the box lost focus to the native view")
            scene.receive(WindowEvent.NativeViewFocusOut(1))
            frame()
            assertFalse(focused, "focus out of the slot gives nothing in the scene focus by itself")
        }
    }
}
