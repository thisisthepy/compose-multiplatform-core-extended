@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

package org.thisisthepy.compose.window.linux

import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import platform.posix.getenv
import platform.posix.system

/**
 * The tree a reader walks, read back by libatspi from the registry on a real accessibility bus.
 *
 * The window is handed records the way a scene hands them (a text field, its label, a button)
 * and joins the bus; scripts/atspi-readback.c, the reader the GraalVM window's test and the
 * plugin's checks run too, finds the window's frame among the desktop's applications, requires
 * each control by role and name, reads the field's text, replaces it through EditableText and
 * presses the button. What it asked for has to arrive at the listener.
 *
 * Needs ATSPI_READBACK naming the built reader, and a session bus with at-spi2 on it (CI runs
 * the tests under dbus-run-session). A CI run without them fails rather than skipping.
 */
class AtspiReadbackTest {
    private class Read(val command: String) {
        val done = AtomicInt(0)
        val status = AtomicInt(-1)
    }

    @Test
    fun nfr8_linux_a_reader_finds_the_field_the_button_and_their_labels_and_acts_on_them() {
        val reader = getenv("ATSPI_READBACK")?.toKString()
        val bus = getenv("DBUS_SESSION_BUS_ADDRESS")?.toKString()
        if (reader.isNullOrEmpty() || bus.isNullOrEmpty() || getenv("DISPLAY") == null) {
            assertTrue(getenv("CI") == null, "no ATSPI_READBACK, session bus or display under CI, so AT-SPI was not read back")
            return
        }
        val window = X11Window()
        val asked = mutableListOf<Triple<Int, Int, String?>>()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {
                if (event.kind == WindowEvent.RESIZE) window.present(event.x.toInt(), event.y.toInt())
            }

            override fun onAccessibilityAction(id: Int, action: Int, argument: String?): Boolean {
                asked += Triple(id, action, argument)
                return true
            }
        }
        assertTrue(window.open(WindowConfig("atspi readback", 400, 300), listener), "no window on a display that is there")
        val worker = Worker.start()
        try {
            window.setAccessibility(
                listOf(
                    AccessibleElement(ElementRole.GROUP, 0f, 0f, 400f, 300f, "Profile", id = 1),
                    AccessibleElement(ElementRole.TEXT, 20f, 20f, 100f, 20f, "Your name", id = 2, parentId = 1),
                    AccessibleElement(
                        ElementRole.FIELD, 20f, 50f, 200f, 30f, "Name", id = 3, parentId = 1, value = "Ada",
                        states = AccessibleState.ENABLED or AccessibleState.FOCUSABLE or AccessibleState.EDITABLE,
                        actions = AccessibleAction.FOCUS or AccessibleAction.SET_VALUE,
                    ),
                    AccessibleElement(
                        ElementRole.BUTTON, 20f, 100f, 80f, 32f, "Save", id = 4, parentId = 1,
                        states = AccessibleState.ENABLED or AccessibleState.FOCUSABLE,
                        actions = AccessibleAction.PRESS or AccessibleAction.FOCUS,
                    ),
                ),
            )
            repeat(10) { window.pump(10) }
            assertTrue(window.joinAccessibility(), "the window did not join the accessibility bus")
            println("kotlin/native x11 joined the accessibility bus as ${window.accessibilityBusName}")
            val read = Read(
                "'$reader' 'atspi readback' --expect 'panel:Profile' --expect 'label:Your name' " +
                    "--expect 'entry:Name' --expect 'push button:Save' --text 'Name=Ada' " +
                    "--set 'Name=Grace' --press 'Save'",
            )
            worker.execute(TransferMode.SAFE, { read }) { state ->
                state.status.store(system(state.command))
                state.done.store(1)
            }
            val started = TimeSource.Monotonic.markNow()
            while (read.done.load() == 0 && started.elapsedNow().inWholeSeconds < 60) window.pump(16)
            assertEquals(0, read.status.load(), "the reader did not find what the window published; its output says what")
            assertTrue(Triple(3, AccessibleAction.SET_VALUE, "Grace") in asked, "the new text did not reach the listener: $asked")
            assertTrue(Triple(4, AccessibleAction.PRESS, null) in asked, "the press did not reach the listener: $asked")
            assertTrue(window.accessibilityActive, "a reader asked, so one is listening")
        } finally {
            worker.requestTermination().result
            window.close()
        }
    }
}
