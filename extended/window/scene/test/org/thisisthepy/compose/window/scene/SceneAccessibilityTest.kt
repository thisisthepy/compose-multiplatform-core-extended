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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.popup
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

    @Composable
    private fun Containers(expanded: Boolean, onExpanded: (Boolean) -> Unit) {
        Column {
            Row(Modifier.selectableGroup()) {
                for ((index, title) in listOf("Home", "Settings").withIndex()) {
                    Box(Modifier.size(40.dp).selectable(selected = index == 0, role = Role.Tab) {}) { BasicText(title) }
                }
            }
            Column(Modifier.semantics { collectionInfo = CollectionInfo(rowCount = 2, columnCount = 1) }) {
                for (row in 0 until 2) {
                    Box(Modifier.semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(row, 1, 0, 1) }) { BasicText("Row $row") }
                }
            }
            Box(Modifier.semantics { popup() }) {
                Column {
                    Box(Modifier.clickable {}) { BasicText("Copy") }
                    Box(Modifier.clickable {}) { BasicText("Paste") }
                }
            }
            Box(
                Modifier.size(40.dp).semantics {
                    contentDescription = "Details"
                    if (expanded) collapse { onExpanded(false); true } else expand { onExpanded(true); true }
                },
            )
        }
    }

    @Test
    fun a_tab_row_a_list_a_menu_and_an_expandable_node_take_their_roles() {
        val platform = FakePlatform().apply { listening = true }
        var expanded by mutableStateOf(false)
        val host = opened(platform) { Containers(expanded) { expanded = it } }
        repeat(3) { host.turn(0) }
        val elements = platform.last()
        fun byId(id: Int) = elements.single { it.id == id }

        val tabs = elements.filter { it.role == ElementRole.TAB }
        assertEquals(listOf("Home", "Settings"), tabs.map { it.label })
        assertEquals(ElementRole.TAB_GROUP, byId(tabs.first().parentId).role)

        val items = elements.filter { it.role == ElementRole.LIST_ITEM }
        assertEquals(listOf("Row 0", "Row 1"), items.map { it.label })
        assertEquals(ElementRole.LIST, byId(items.first().parentId).role)

        val menuItems = elements.filter { it.role == ElementRole.MENU_ITEM }
        assertEquals(listOf("Copy", "Paste"), menuItems.map { it.label })
        assertEquals(1, elements.count { it.role == ElementRole.MENU })

        val details = elements.single { it.label == "Details" }
        assertTrue(details.states and AccessibleState.EXPANDABLE != 0)
        assertTrue(details.states and AccessibleState.EXPANDED == 0)
        assertEquals(AccessibleAction.EXPAND, details.actions and (AccessibleAction.EXPAND or AccessibleAction.COLLAPSE))

        platform.readerAsks += Triple(details.id, AccessibleAction.EXPAND, null)
        repeat(3) { host.turn(0) }
        assertEquals(listOf(true), platform.readerAnswers)
        val opened = platform.last().single { it.label == "Details" }
        assertTrue(opened.states and AccessibleState.EXPANDED != 0)
        assertEquals(AccessibleAction.COLLAPSE, opened.actions and (AccessibleAction.EXPAND or AccessibleAction.COLLAPSE))
        host.close()
    }
}
