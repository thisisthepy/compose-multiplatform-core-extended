package org.thisisthepy.compose.window.graalvm.linux

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.atspi.AtspiWindow

/**
 * The tree a reader walks, read back by libatspi from the registry on a real accessibility bus,
 * through the same [AtspiWindow] and [JvmBusConnection] the GraalVM X11 window publishes with.
 *
 * The window itself is C reached through `@CFunction`, which only a native image can call, so
 * the test publishes without one: the records, the bus connection and the answers are the
 * window's, and only where the window is on the screen is made up. scripts/atspi-readback.c,
 * the reader the Kotlin/Native window's test and the plugin's checks run too, requires each
 * control by role and name, reads the field's text, replaces it and presses the button, and
 * what it asked for has to arrive at the listener.
 *
 * Needs ATSPI_READBACK naming the built reader, and a session bus with at-spi2 on it (CI runs
 * the tests under dbus-run-session). A CI run without them fails rather than skipping.
 */
class AtspiReadbackTest {
    @Test
    fun nfr8_linux_a_reader_finds_the_field_the_button_and_their_labels_and_acts_on_them() {
        val reader = System.getenv("ATSPI_READBACK")
        if (reader.isNullOrEmpty() || System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrEmpty()) {
            assertTrue(System.getenv("CI") == null, "no ATSPI_READBACK or session bus under CI, so AT-SPI was not read back")
            return
        }
        val asked = mutableListOf<Triple<Int, Int, String?>>()
        val accessibility = AtspiWindow(
            openSession = { JvmBusConnection.open() },
            openAccessibility = { address -> JvmBusConnection.open(address) },
            userId = { JvmBusConnection.userId() },
            applicationName = { "graalvm-atspi-readback" },
            windowOrigin = { 100 to 80 },
            requestFrame = {},
            disabled = false,
        )
        accessibility.listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}

            override fun onAccessibilityAction(id: Int, action: Int, argument: String?): Boolean {
                asked += Triple(id, action, argument)
                return true
            }
        }
        accessibility.setTitle("graalvm atspi readback")
        accessibility.setGeometry(400, 300, 1f)
        accessibility.setElements(
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
        try {
            assertTrue(accessibility.join(), "did not join the accessibility bus")
            println("graalvm x11 joined the accessibility bus as ${accessibility.busName}")
            val process = ProcessBuilder(
                reader, "graalvm atspi readback",
                "--expect", "panel:Profile", "--expect", "label:Your name",
                "--expect", "entry:Name", "--expect", "push button:Save",
                "--text", "Name=Ada", "--set", "Name=Grace", "--press", "Save",
            ).inheritIO().start()
            val started = System.nanoTime()
            while (process.isAlive && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(60)) {
                accessibility.pump(focused = true)
                Thread.sleep(16)
            }
            if (process.isAlive) process.destroyForcibly()
            assertEquals(0, process.waitFor(), "the reader did not find what was published; its output says what")
            assertTrue(Triple(3, AccessibleAction.SET_VALUE, "Grace") in asked, "the new text did not reach the listener: $asked")
            assertTrue(Triple(4, AccessibleAction.PRESS, null) in asked, "the press did not reach the listener: $asked")
            assertTrue(accessibility.readerListening, "a reader asked, so one is listening")
        } finally {
            accessibility.close()
        }
    }
}
