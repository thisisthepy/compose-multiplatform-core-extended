package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent

class SceneWindowEventsTest {

    @Test
    fun edit_command_selectors_map_to_platform_shortcut_chords() {
        val commands = listOf(
            Triple("cut:", Key.X, false),
            Triple("copy:", Key.C, false),
            Triple("paste:", Key.V, false),
            Triple("selectAll:", Key.A, false),
            Triple("undo:", Key.Z, false),
            Triple("redo:", Key.Z, true),
            Triple("copy", Key.C, false),
        )
        for ((selector, expectedKey, expectedShift) in commands) {
            for (commandKey in listOf(true, false)) {
                val chord = editCommandChord(selector, commandKey)!!
                assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), chord.map { it.type })
                assertTrue(
                    chord.all {
                        it.key == expectedKey &&
                            it.isMetaPressed == commandKey &&
                            it.isCtrlPressed != commandKey &&
                            it.isShiftPressed == expectedShift
                    },
                    "selector $selector with commandKey=$commandKey",
                )
            }
        }
        assertNull(editCommandChord("unknownSelector:", true))
        assertNull(editCommandChord("", false))
    }

    @Test
    fun edit_command_events_reach_focused_field_as_shortcuts() {
        for ((platformName, expectMeta) in listOf("appkit-graalvm" to true, "graalvm-linux-x11" to false)) {
            val platform = FakePlatform().also { it.name = platformName }
            val surface = FakeSurface()
            val heard = ArrayList<KeyEvent>()
            val host = ComposeWindowHost(platform, WindowConfig("edit", 200, 100), { surface }) {
                Box(Modifier.size(180.dp, 40.dp).onPreviewKeyEvent { heard += it; true }) {
                    BasicTextField(value = "hello", onValueChange = {}, modifier = Modifier.size(180.dp, 40.dp))
                }
            }
            assertTrue(host.open())
            host.turn(0)
            platform.queued.add(WindowEvent(WindowEvent.POINTER_DOWN, 10f, 10f, WindowEvent.PRIMARY_HELD, 0, 0, 0, ""))
            platform.queued.add(WindowEvent(WindowEvent.POINTER_UP, 10f, 10f, 0, 0, 0, 0, ""))
            repeat(6) { host.turn(0) }
            heard.clear()

            platform.queued.add(WindowEvent(WindowEvent.EDIT_COMMAND, 0f, 0f, 0, 0, 0, 0, "selectAll:"))
            platform.queued.add(WindowEvent(WindowEvent.EDIT_COMMAND, 0f, 0f, 0, 0, 0, 0, "copy:"))
            platform.queued.add(WindowEvent(WindowEvent.EDIT_COMMAND, 0f, 0f, 0, 0, 0, 0, "paste:"))
            repeat(4) { host.turn(0) }

            assertEquals(
                listOf(Key.A, Key.A, Key.C, Key.C, Key.V, Key.V),
                heard.map { it.key },
                "platform $platformName",
            )
            assertTrue(heard.all { it.isMetaPressed == expectMeta && it.isCtrlPressed != expectMeta })
            host.close()
        }
    }

    @Test
    fun file_drop_events_dispatch_entered_dropped_and_exited_with_paths() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        val received = ArrayList<FileDropEvent>()
        val host = ComposeWindowHost(platform, WindowConfig("drop", 200, 100), { surface }) {
            Box(Modifier.size(200.dp, 100.dp))
        }
        host.onFileDrop = { received += it }
        assertTrue(host.open())
        host.turn(0)

        platform.queued.add(
            WindowEvent(WindowEvent.FILES_ENTERED, 25f, 35f, 0, 0, 0, 0, "/tmp/a.txt\u0000/tmp/b c.txt"),
        )
        platform.queued.add(
            WindowEvent(WindowEvent.FILES_DROPPED, 40f, 50f, 0, 0, 0, 0, "/tmp/a.txt\u0000/tmp/b c.txt"),
        )
        platform.queued.add(
            WindowEvent(WindowEvent.FILES_EXITED, 0f, 0f, 0, 0, 0, 0, ""),
        )
        repeat(3) { host.turn(0) }

        assertEquals(
            listOf(
                FileDropEvent.Entered(25f, 35f, listOf("/tmp/a.txt", "/tmp/b c.txt")),
                FileDropEvent.Dropped(40f, 50f, listOf("/tmp/a.txt", "/tmp/b c.txt")),
                FileDropEvent.Exited,
            ),
            received,
        )
        host.close()
    }

    @Test
    fun file_drop_events_without_callback_are_safely_ignored() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        val host = ComposeWindowHost(platform, WindowConfig("drop", 200, 100), { surface }) {
            Box(Modifier.size(200.dp, 100.dp))
        }
        assertTrue(host.open())
        host.turn(0)
        platform.queued.add(WindowEvent(WindowEvent.FILES_DROPPED, 10f, 10f, 0, 0, 0, 0, "/tmp/a.txt"))
        repeat(2) { host.turn(0) }
        host.close()
    }
}
