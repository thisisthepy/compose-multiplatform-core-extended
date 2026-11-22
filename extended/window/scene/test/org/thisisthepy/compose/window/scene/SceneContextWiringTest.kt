@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.EditMenuId
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowPlatform

class SceneContextWiringTest {

    @Test
    fun scene_window_info_reflects_live_focus_updates() {
        var focused = true
        val info = SceneWindowInfo(size = { IntSize(200, 100) }, focused = { focused })
        assertTrue(info.isWindowFocused)
        focused = false
        assertFalse(info.isWindowFocused)
        focused = true
        assertTrue(info.isWindowFocused)
    }

    @Test
    fun host_updates_local_window_info_focus_from_listener() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        val observed = ArrayList<Boolean>()
        val host = ComposeWindowHost(platform, WindowConfig("focus", 200, 100), { surface }) {
            observed += LocalWindowInfo.current.isWindowFocused
            Box(Modifier.size(200.dp, 100.dp))
        }
        assertTrue(host.open())
        host.turn(0)
        assertEquals(true, observed.last())

        platform.focusChanges += false
        repeat(3) { host.turn(0) }
        assertEquals(false, observed.last())

        platform.focusChanges += true
        repeat(3) { host.turn(0) }
        assertEquals(true, observed.last())
        host.close()
    }

    @Test
    @Suppress("DEPRECATION")
    fun host_wires_local_clipboard_and_clipboard_manager_to_window_platform() {
        val platform = FakePlatform().also { it.clipboardText = "initial" }
        val surface = FakeSurface()
        var readFromClipboard: String? = null
        var managerRef: ClipboardManager? = null
        var clipboardRef: Clipboard? = null
        val host = ComposeWindowHost(platform, WindowConfig("clip", 200, 100), { surface }) {
            val clipboard = LocalClipboard.current
            val manager = LocalClipboardManager.current
            clipboardRef = clipboard
            managerRef = manager
            LaunchedEffect(clipboard) {
                readFromClipboard = clipEntryPlainText(clipboard.getClipEntry())
                clipboard.setClipEntry(plainTextClipEntry("written-via-clipboard"))
            }
            Box(Modifier.size(200.dp, 100.dp))
        }
        assertTrue(host.open())
        repeat(4) { host.turn(0) }

        assertEquals("initial", readFromClipboard)
        assertEquals("written-via-clipboard", platform.clipboardText)
        assertNotNull(clipboardRef)

        val manager = managerRef!!
        assertTrue(manager.hasText())
        assertEquals(AnnotatedString("written-via-clipboard"), manager.getText())
        manager.setText(AnnotatedString("written-via-manager"))
        assertEquals("written-via-manager", platform.clipboardText)

        platform.clipboardText = null
        assertFalse(manager.hasText())
        assertNull(manager.getText())
        host.close()
    }

    @Test
    fun pointer_icon_changes_forward_to_platform_and_host_callback() {
        val base = FakePlatform()
        val platformIcons = ArrayList<PointerIcon>()
        val platform = object : WindowPlatform by base, PlatformPointerIcon {
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                platformIcons += pointerIcon
            }
        }
        val hostIcons = ArrayList<PointerIcon>()
        val surface = FakeSurface()
        val host = ComposeWindowHost(platform, WindowConfig("cursor", 200, 100), { surface }) {
            Box(Modifier.size(100.dp).pointerHoverIcon(PointerIcon.Hand))
        }
        host.onPointerIconChanged = { hostIcons += it }
        assertTrue(host.open())
        host.turn(0)

        base.queued.add(WindowEvent(WindowEvent.POINTER_MOVE, 20f, 20f, 0, 0, 0, 0, ""))
        repeat(3) { host.turn(0) }

        assertEquals(PointerIcon.Hand, host.currentPointerIcon)
        assertTrue(PointerIcon.Hand in platformIcons, "platform received Hand icon: $platformIcons")
        assertTrue(PointerIcon.Hand in hostIcons, "host callback received Hand icon: $hostIcons")

        base.queued.add(WindowEvent(WindowEvent.POINTER_MOVE, 150f, 50f, 0, 0, 0, 0, ""))
        repeat(3) { host.turn(0) }
        assertEquals(PointerIcon.Default, host.currentPointerIcon)
        host.close()
    }

    @Test
    fun scene_text_toolbar_shows_menu_and_invokes_selected_action() {
        val platform = FakePlatform()
        val surface = FakeSurface()
        var toolbarRef: TextToolbar? = null
        val host = ComposeWindowHost(platform, WindowConfig("toolbar", 200, 100), { surface }) {
            toolbarRef = LocalTextToolbar.current
            Box(Modifier.size(200.dp, 100.dp))
        }
        assertTrue(host.open())
        host.turn(0)

        val toolbar = toolbarRef!!
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)

        var cutCalled = 0
        var copyCalled = 0
        var selectAllCalled = 0
        toolbar.showMenu(
            rect = Rect(10f, 10f, 50f, 30f),
            onCopyRequested = { copyCalled++ },
            onPasteRequested = null,
            onCutRequested = { cutCalled++ },
            onSelectAllRequested = { selectAllCalled++ },
        )
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        host.turn(0)

        assertEquals(1, platform.menusShown.size)
        assertEquals(
            listOf(EditMenuId.CUT, EditMenuId.COPY, EditMenuId.SELECT_ALL),
            platform.menusShown.single().map { it.id },
        )

        platform.chosen += EditMenuId.COPY
        host.turn(0)
        assertEquals(1, copyCalled)
        assertEquals(0, cutCalled)
        assertEquals(0, selectAllCalled)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)

        // Show and hide without choosing.
        toolbar.showMenu(
            rect = Rect(10f, 10f, 50f, 30f),
            onCopyRequested = { copyCalled++ },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
        )
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        toolbar.hide()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        host.close()
    }
}
