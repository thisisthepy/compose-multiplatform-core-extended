package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import org.thisisthepy.compose.window.EditMenuId
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.FramePresentRecord
import org.thisisthepy.compose.window.SystemTheme
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowPlatform
import org.thisisthepy.compose.window.WindowVisibility

private class FakePlatform : WindowPlatform {
    override var name = "fake"
    val chosen = ArrayList<Int>()
    var width = 200
    var height = 100
    var opened = false
    var closedCalls = 0
    var askClose = false
    val queued = ArrayList<WindowEvent>()
    private var listener: WindowListener? = null

    override fun open(config: WindowConfig, listener: WindowListener): Boolean {
        width = config.width
        height = config.height
        this.listener = listener
        opened = true
        return true
    }

    override fun pump(timeoutMillis: Long) {
        val now = ArrayList(queued)
        queued.clear()
        for (event in now) listener?.onEvent(event)
        for (id in ArrayList(chosen)) listener?.onContextMenuChosen(id)
        chosen.clear()
        if (askClose) listener?.onCloseRequested()
    }

    override fun measure() = WindowMeasurement(width, height, 1f)
    override fun requestFrame() {}
    override fun present(drawnWidth: Int, drawnHeight: Int) =
        FramePresentRecord(drawnWidth, drawnHeight, width, height)

    override fun systemTheme() = SystemTheme.Light
    override fun setTitle(title: String) {}
    override fun setMinimumSize(width: Int, height: Int) {}
    override fun setVisibility(visibility: WindowVisibility) {}
    override fun readClipboardText(): String? = null
    override fun writeClipboardText(text: String) {}
    override fun setImeSpot(x: Int, y: Int) {}
    override fun showContextMenu(items: List<ContextMenuItem>) {}
    override fun close() {
        closedCalls++
    }
}

private class FakeSurface : WindowSurface {
    var painter: (() -> Boolean)? = null
    var begun = 0
    var closed = false

    override fun begin(width: Int, height: Int): FrameTarget {
        begun++
        val surface = Surface.makeRasterN32Premul(width, height)
        return object : FrameTarget {
            override val surface = surface
            override fun close() = surface.close()
        }
    }

    override fun setResizePainter(paint: (() -> Boolean)?) {
        painter = paint
    }

    override fun close() {
        closed = true
    }
}

class ComposeWindowHostTest {

    private fun host(platform: FakePlatform, surface: FakeSurface, content: @androidx.compose.runtime.Composable () -> Unit) =
        ComposeWindowHost(platform, WindowConfig("test", 200, 100), { surface }, content)

    @Test
    fun resize_every_presented_frame_is_drawn_at_the_current_window_size() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        val host = host(platform, surface) { Box(Modifier.fillMaxSize().background(Color.Red)) }
        assertTrue(host.open())
        host.turn(0)
        // A drag: the window changes size, and the frame is drawn from inside the resize
        // handling as well as from the loop.
        for (step in 1..30) {
            platform.width = 200 + step * 7
            platform.height = 100 + step * 3
            platform.queued.add(WindowEvent(WindowEvent.RESIZE, platform.width.toFloat(), platform.height.toFloat(), 0, 0, 0, 0, ""))
            assertTrue(surface.painter!!.invoke())
            if (step % 2 == 0) host.turn(0)
        }
        assertTrue(host.presentLog.frames >= 31, "frames: ${host.presentLog.frames}")
        assertEquals(0, host.presentLog.mismatched)
        val last = host.presentLog.lastFrame!!
        assertEquals(platform.width, last.drawnWidth)
        assertEquals(platform.height, last.drawnHeight)
        host.close()
        assertTrue(surface.closed)
        assertEquals(null, surface.painter)
    }

    @Test
    fun a_refused_window_opens_nothing() {
        val platform = object : WindowPlatform by FakePlatform() {
            override fun open(config: WindowConfig, listener: WindowListener) = false
        }
        val host = ComposeWindowHost(platform, WindowConfig("x", 10, 10), { error("no surface for a refused window") }) {}
        assertTrue(!host.open())
    }

    @Test
    fun a_close_request_ends_the_loop_and_releases_the_window() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        val host = host(platform, surface) { Box(Modifier.fillMaxSize()) }
        assertTrue(host.open())
        host.turn(0)
        platform.askClose = true
        host.run(0)
        assertTrue(host.closed)
        assertEquals(1, platform.closedCalls)
        assertTrue(surface.closed)
    }

    @Test
    fun text_from_the_input_method_reaches_the_focused_field() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        var typed by mutableStateOf("")
        val host = host(platform, surface) {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.size(180.dp, 40.dp),
            )
        }
        assertTrue(host.open())
        host.turn(0)
        platform.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 10f, 10f, 1, 0, 0, 0, ""))
        platform.queued.add(WindowEvent(WindowEvent.POINTER_UP, 10f, 10f, 0, 0, 0, 0, ""))
        repeat(6) { host.turn(0) }
        platform.queued.add(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "가"))
        repeat(6) { host.turn(0) }
        assertEquals("가", typed)
        host.close()
    }

    @Test
    fun fr33_6_every_edit_entry_stands_for_its_own_key_with_the_platforms_shortcut_modifier() {
        val keys = mapOf(
            EditMenuId.CUT to Key.X,
            EditMenuId.COPY to Key.C,
            EditMenuId.PASTE to Key.V,
            EditMenuId.SELECT_ALL to Key.A,
        )
        for ((id, key) in keys) {
            for (command in listOf(true, false)) {
                val sent = editChord(id, command)!!
                assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), sent.map { it.type })
                assertTrue(sent.all { it.key == key && it.isMetaPressed == command && it.isCtrlPressed != command })
            }
        }
        assertEquals(null, editChord(-1, true), "a dismissed menu presses nothing")
        assertEquals(null, editChord(99, true), "a number nobody sent presses nothing")
        assertTrue(usesCommandKey("appkit-graalvm") && usesCommandKey("macos-native"))
        assertTrue(!usesCommandKey("graalvm-linux-x11"))
    }

    @Test
    fun fr33_6_a_chosen_menu_entry_reaches_the_focused_field_as_the_shortcut() {
        val platform = FakePlatform().also { it.name = "appkit-graalvm" }
        val surface = FakeSurface()
        val heard = ArrayList<KeyEvent>()
        val host = host(platform, surface) {
            Box(Modifier.size(180.dp, 40.dp).onPreviewKeyEvent { heard += it; true }) {
                BasicTextField(value = "x", onValueChange = {}, modifier = Modifier.size(180.dp, 40.dp))
            }
        }
        assertTrue(host.open())
        host.turn(0)
        platform.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 10f, 10f, 1, 0, 0, 0, ""))
        platform.queued.add(WindowEvent(WindowEvent.POINTER_UP, 10f, 10f, 0, 0, 0, 0, ""))
        repeat(6) { host.turn(0) }
        heard.clear()
        platform.chosen.add(EditMenuId.COPY)
        repeat(3) { host.turn(0) }
        assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), heard.map { it.type })
        assertTrue(heard.all { it.key == Key.C && it.isMetaPressed })
        host.close()
    }
}
