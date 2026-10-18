package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.WindowCaption
import org.thisisthepy.compose.window.WindowConfig

class SceneAccessibilityTest {
    private var clicks = 0

    @Composable
    private fun Controls() {
        var checked by remember { mutableStateOf(true) }
        var typed by remember { mutableStateOf("hello") }
        Column {
            Box(
                Modifier.offset(10.dp, 20.dp).size(80.dp, 30.dp)
                    .semantics { contentDescription = "Send" }
                    .clickable(role = Role.Button) { clicks++ },
            )
            Box(Modifier.size(40.dp).toggleable(value = checked, role = Role.Checkbox) { checked = it }) {
                BasicText("Remember me")
            }
            BasicTextField(typed, { typed = it }, Modifier.size(120.dp, 30.dp))
        }
    }

    private fun opened(platform: FakePlatform, content: @Composable () -> Unit): ComposeWindowHost {
        val host = ComposeWindowHost(platform, WindowConfig("a11y", 300, 200), { FakeSurface() }, content = content)
        assertTrue(host.open())
        return host
    }

    private fun FakePlatform.last(): List<AccessibleElement> = assertNotNull(pushed.lastOrNull(), "nothing was pushed")

    @Test
    fun nfr8_nothing_is_described_while_no_reader_is_listening() {
        val platform = FakePlatform()
        val host = opened(platform) { Controls() }
        repeat(3) { host.turn(0) }
        assertEquals(0, platform.pushed.size)
        host.close()
    }

    @Test
    fun nfr8_controls_reach_the_platform_with_role_label_value_states_actions_and_points() {
        val platform = FakePlatform().apply { scale = 2f; listening = true }
        val host = opened(platform) { Controls() }
        repeat(3) { host.turn(0) }
        val elements = platform.last()

        val send = elements.single { it.label == "Send" }
        assertEquals(ElementRole.BUTTON, send.role)
        assertTrue(send.actions and AccessibleAction.PRESS != 0)
        assertTrue(send.states and AccessibleState.ENABLED != 0)
        // Window points: 10 by 20 points in at a scale of 2 is 20 by 40 pixels in the scene.
        assertEquals(10f, send.x, 0.5f)
        assertEquals(20f, send.y, 0.5f)
        assertEquals(80f, send.width, 0.5f)

        val box = elements.single { it.role == ElementRole.CHECKBOX }
        assertEquals("Remember me", box.label)
        assertTrue(box.states and AccessibleState.CHECKABLE != 0)
        assertTrue(box.states and AccessibleState.CHECKED != 0)

        val field = elements.single { it.role == ElementRole.FIELD }
        assertEquals("hello", field.value)
        assertTrue(field.states and AccessibleState.EDITABLE != 0)
        assertTrue(field.actions and AccessibleAction.SET_VALUE != 0)
        assertTrue(elements.all { it.id >= 0 }, "every element has the scene's id")
        host.close()
    }

    @Test
    fun nfr8_a_reader_pressing_a_control_runs_its_click_and_a_new_value_reaches_a_field() {
        val platform = FakePlatform().apply { listening = true }
        val host = opened(platform) { Controls() }
        repeat(3) { host.turn(0) }
        val send = platform.last().single { it.label == "Send" }
        val field = platform.last().single { it.role == ElementRole.FIELD }

        platform.readerAsks += Triple(send.id, AccessibleAction.PRESS, null)
        platform.readerAsks += Triple(field.id, AccessibleAction.SET_VALUE, "typed by a reader")
        repeat(3) { host.turn(0) }

        assertEquals(listOf(true, true), platform.readerAnswers)
        assertEquals(1, clicks)
        assertEquals("typed by a reader", platform.last().single { it.role == ElementRole.FIELD }.value)
        host.close()
    }

    @Test
    fun fr19_4_buttons_the_scene_draws_are_in_the_tree_and_act_on_the_window() {
        val platform = FakePlatform().apply {
            listening = true
            captionNow = WindowCaption(height = 32f, buttonsWidth = 138f, buttonsAtStart = false, buttonsDrawnByScene = true)
        }
        val host = opened(platform) { BasicText("content") }
        repeat(3) { host.turn(0) }
        val close = platform.last().single { it.label == "Close" }
        assertEquals(ElementRole.BUTTON, close.role)
        // At the trailing end, inside the caption.
        assertTrue(close.x > 150f, "close is at ${close.x}")
        assertTrue(close.y < 32f)

        platform.readerAsks += Triple(close.id, AccessibleAction.PRESS, null)
        host.turn(0)
        assertEquals(listOf(CaptionAction.Close), platform.captionActions)

        // The content starts below the caption.
        val content = platform.last().single { it.label == "content" }
        assertTrue(content.y >= 32f, "content is at ${content.y}")
        host.close()
    }

    @Test
    fun fr19_4_content_that_lays_itself_out_against_the_caption_reads_it() {
        val platform = FakePlatform().apply { captionNow = WindowCaption(height = 28f, buttonsWidth = 70f) }
        var seen: WindowCaption? = null
        val host = ComposeWindowHost(platform, WindowConfig("bar", 300, 200), { FakeSurface() }, padContentForCaption = false) {
            seen = LocalWindowCaption.current
        }
        assertTrue(host.open())
        host.turn(0)
        assertEquals(platform.captionNow, seen)
        host.close()
    }
}
