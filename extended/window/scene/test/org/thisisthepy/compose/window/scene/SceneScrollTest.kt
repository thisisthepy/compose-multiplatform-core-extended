@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scroll reaches the scene where the pointer is.
 *
 * The window records a scroll's travel in the fields a pointer event keeps its place in, so the
 * scene has to remember the place. It used to send every scroll to the top left corner, and the
 * scene hit-tests a scroll at its position: only what lay under the corner could scroll, which
 * in most windows is nothing that scrolls at all.
 */
class SceneScrollTest {

    private class Heard(val position: Offset, val travel: Offset)

    /** One target in the top left corner and one at (60, 60), each 30 pixels square. */
    private fun withTargets(body: (send: (WindowEvent) -> Unit, corner: List<Heard>, far: List<Heard>) -> Unit) {
        val corner = ArrayList<Heard>()
        val far = ArrayList<Heard>()
        val pointer = ScenePointer()
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        fun Modifier.scrolls(into: MutableList<Heard>) = pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        val change = event.changes.first()
                        into += Heard(change.position, change.scrollDelta)
                    }
                }
            }
        }
        try {
            scene.setContent {
                Box(Modifier.size(30.dp).scrolls(corner))
                Box(Modifier.offset { IntOffset(60, 60) }.size(30.dp).scrolls(far))
            }
            scene.render(Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas(), 0L)
            body({ scene.receive(it, pointer) }, corner, far)
        } finally {
            scene.close()
        }
    }

    private fun event(kind: Int, x: Float, y: Float) = WindowEvent(kind, x, y, 0, 0, 0, 0, "")

    @Test
    fun a_scroll_after_a_move_reaches_what_is_under_the_pointer() = withTargets { send, corner, far ->
        send(event(WindowEvent.POINTER_MOVE, 70f, 75f))
        send(event(WindowEvent.SCROLL, 0f, 3f))
        assertEquals(1, far.size, "the target under the pointer heard the scroll")
        assertEquals(Offset(10f, 15f), far[0].position, "at the pointer, in the target's own space")
        assertEquals(Offset(0f, 3f), far[0].travel, "with the travel the window sent")
        assertEquals(0, corner.size, "the target in the corner heard nothing")
    }

    @Test
    fun a_scroll_follows_the_last_press_release_or_enter_too() = withTargets { send, corner, far ->
        send(event(WindowEvent.POINTER_DOWN, 65f, 65f))
        send(event(WindowEvent.POINTER_UP, 66f, 66f))
        send(event(WindowEvent.SCROLL, 0f, -1f))
        assertEquals(listOf(Offset(6f, 6f)), far.map { it.position }, "at the release")
        send(event(WindowEvent.POINTER_EXIT, 10f, 10f))
        send(event(WindowEvent.POINTER_ENTER, 10f, 10f))
        send(event(WindowEvent.SCROLL, 0f, -1f))
        assertEquals(listOf(Offset(10f, 10f)), corner.map { it.position }, "at the enter")
        assertEquals(1, far.size, "and not at the far target again")
    }

    @Test
    fun a_scroll_before_the_window_said_where_the_pointer_is_goes_to_the_corner() = withTargets { send, corner, far ->
        send(event(WindowEvent.SCROLL, 0f, 1f))
        assertEquals(listOf(Offset.Zero), corner.map { it.position })
        assertEquals(0, far.size)
    }

    /**
     * The anchor of the direction on every platform the scene runs on: WindowEvent.SCROLL with
     * a positive y asks for what is below, so a verticalScroll column's position grows, and a
     * negative y brings it back. Each platform's scroll config in foundation answers it (the
     * desktop ones under the JVM, the macOS, Linux and Windows ones under Kotlin/Native), so a
     * config with the wrong sign fails here on that platform.
     */
    @Test
    fun a_scroll_asking_for_what_is_below_moves_a_column_forward_and_back() {
        val state = ScrollState(0)
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        val canvas = Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas()
        var time = 0L
        fun frames() = repeat(60) { scene.render(canvas, time); time += 16_000_000L }
        val pointer = ScenePointer()
        try {
            scene.setContent {
                Column(Modifier.size(100.dp).verticalScroll(state)) {
                    Box(Modifier.size(100.dp, 4000.dp))
                }
            }
            frames()
            scene.receive(event(WindowEvent.POINTER_MOVE, 50f, 50f), pointer)
            scene.receive(event(WindowEvent.SCROLL, 0f, 3f), pointer)
            frames()
            val down = state.value
            assertTrue(down > 0, "three lines asking for what is below move the column forward, it is at $down")
            scene.receive(event(WindowEvent.SCROLL, 0f, -1f), pointer)
            frames()
            assertTrue(state.value in 1 until down, "a line asking for what is above moves it back, from $down to ${state.value}")
        } finally {
            scene.close()
        }
    }
}
