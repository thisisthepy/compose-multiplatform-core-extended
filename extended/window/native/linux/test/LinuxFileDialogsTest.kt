@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.toKString
import org.thisisthepy.compose.window.FileDialogFilter
import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.atspi.AtspiWire
import org.thisisthepy.compose.window.atspi.DBusMessage
import org.thisisthepy.compose.window.atspi.DBusWriter
import org.thisisthepy.compose.window.atspi.Destination
import org.thisisthepy.compose.window.atspi.IncomingCall
import org.thisisthepy.compose.window.atspi.entry
import org.thisisthepy.compose.window.portal.FileChooserPortal
import platform.posix.getenv

/**
 * A portal the test plays itself: a second connection to the real session bus that owns
 * `org.freedesktop.portal.Desktop` and answers the file chooser the way the desktop's does.
 */
private class PlayedPortal(private val bus: PosixBusConnection) {
    private var serial = 0
    private val held = ArrayDeque<ByteArray>()

    /** One request as the portal received it. */
    class Asked(val call: IncomingCall, val parent: String, val title: String, val token: String, val handle: String)

    fun own(): Boolean {
        if (ask("Hello", null) {} == null) return false
        val reply = ask("RequestName", "su") {
            string(FileChooserPortal.SERVICE)
            u32(DO_NOT_QUEUE)
        } ?: return false
        return reply.body().u32() == PRIMARY_OWNER
    }

    /** Asks the bus something and waits for the answer, which also means it has routed all sent before. */
    private fun ask(member: String, signature: String?, body: DBusWriter.() -> Unit): DBusMessage? {
        val sent = ++serial
        bus.send(DBusMessage.methodCall(sent, BUS, member, signature, DBusWriter().apply(body).bytes()))
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < 5_000) {
            val bytes = bus.receive(50) ?: continue
            val message = DBusMessage.parse(bytes)
            if (message != null && message.replySerial == sent.toLong()) {
                return if (message.type == DBusMessage.METHOD_RETURN) message else null
            }
            held.addLast(bytes)
        }
        return null
    }

    fun settle() {
        assertNotNull(ask("GetId", null) {}, "the bus stopped answering")
    }

    /** The next method call made to the portal, waiting at most [timeoutMillis]. */
    fun nextCall(timeoutMillis: Int): IncomingCall? {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val bytes = held.removeFirstOrNull() ?: bus.receive(20)
            val call = bytes?.let { AtspiWire.parseCall(it) }
            if (call != null) return call
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMillis) return null
        }
    }

    /** Takes the next file chooser call and answers it with the path of its request. */
    fun accept(method: String): Asked {
        val call = assertNotNull(nextCall(5_000), "no call reached the portal")
        assertEquals(FileChooserPortal.INTERFACE, call.interfaceName)
        assertEquals(FileChooserPortal.OBJECT_PATH, call.path)
        assertEquals(method, call.member)
        assertEquals(FileChooserPortal.CALL_SIGNATURE, call.signature)
        val arguments = call.arguments()
        val parent = arguments.string()
        val title = arguments.string()
        var token = ""
        arguments.array(8) {
            arguments.align(8)
            val key = arguments.string()
            val signature = arguments.signature()
            if (key == "handle_token") token = arguments.string() else arguments.skip(signature)
        }
        val sender = assertNotNull(call.sender).removePrefix(":").replace('.', '_')
        val handle = "${FileChooserPortal.OBJECT_PATH}/request/$sender/$token"
        bus.send(AtspiWire.methodReturn(++serial, call, "o") { string(handle) })
        return Asked(call, parent, title, token, handle)
    }

    fun respond(asked: Asked, code: Long, vararg uris: String) {
        bus.send(
            AtspiWire.signal(++serial, asked.handle, FileChooserPortal.REQUEST_INTERFACE, "Response", "ua{sv}") {
                u32(code)
                array(8) {
                    if (uris.isNotEmpty()) entry("uris", "as") { array(4) { uris.forEach { string(it) } } }
                }
            },
        )
        settle()
    }

    fun close() = bus.close()

    private companion object {
        const val DO_NOT_QUEUE = 4L
        const val PRIMARY_OWNER = 1L
        val BUS = Destination("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")
    }
}

/**
 * The portal file dialogs of the X11 window against a real session bus: the request leaves
 * through the window's own socket code, and the answer is read on the window's loop.
 *
 * The desktop's portal is played by the test, so nothing has to click through a real dialog.
 * Needs a display and a session bus (CI runs the tests under xvfb-run and dbus-run-session, a
 * bus of the tests' own where nothing else owns the portal's name); skipped without them. What
 * is sent and how answers are read is covered without a bus by FileChooserPortalTest.
 */
class LinuxFileDialogsTest {
    private val unavailable =
        getenv("DISPLAY")?.toKString().isNullOrEmpty() || getenv("DBUS_SESSION_BUS_ADDRESS")?.toKString().isNullOrEmpty()

    private fun openWindow(): X11Window {
        val window = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        assertTrue(window.open(WindowConfig("dialogs", 320, 240), listener), "no window on a display that is there")
        // Mapping, exposure and focus, all read and answered, so the waits below are idle ones.
        repeat(30) { window.pump(10) }
        return window
    }

    private fun playPortal(): PlayedPortal {
        val portal = PlayedPortal(assertNotNull(PosixBusConnection.open(), "no session bus at the address given"))
        assertTrue(portal.own(), "the test could not own the portal's name on the session bus")
        return portal
    }

    private fun millisOf(block: () -> Unit): Long {
        val started = TimeSource.Monotonic.markNow()
        block()
        return started.elapsedNow().inWholeMilliseconds
    }

    private fun pumpUntil(window: X11Window, waitMillis: Long = 10, done: () -> Boolean) {
        val started = TimeSource.Monotonic.markNow()
        while (!done() && started.elapsedNow().inWholeSeconds < 10) window.pump(waitMillis)
    }

    @Test
    fun fr72_linux_the_chosen_paths_reach_the_caller_from_inside_the_pump_and_end_its_wait() {
        if (unavailable) return
        val window = openWindow()
        val portal = playPortal()
        try {
            val dialogs = LinuxFileDialogs { window }
            val results = ArrayList<FileDialogOutcome>()
            var pumping = false
            var deliveredInPump = false
            val request = FileDialogRequest(
                FileDialogKind.OpenFiles,
                title = "Pick two",
                directory = "/tmp",
                filters = listOf(FileDialogFilter("Text", listOf("txt")), FileDialogFilter("All", emptyList())),
            )
            assertTrue(millisOf { dialogs.show(request) { deliveredInPump = pumping; results.add(it) } } < 1_000, "show waited")
            assertTrue(results.isEmpty(), "a result before the portal answered")

            val asked = portal.accept("OpenFile")
            assertEquals("x11:" + window.windowId.toString(16), asked.parent)
            assertEquals("Pick two", asked.title)
            assertTrue(asked.token.startsWith("compose_"), asked.token)
            portal.respond(asked, 0, "file:///tmp/a%20b.txt", "file:///tmp/%EB%AC%B8%EC%84%9C.txt")

            // The answer is on the bus socket and nothing is on the display's: the wait ends for it.
            pumping = true
            val waited = millisOf { pumpUntil(window, waitMillis = 5_000) { results.isNotEmpty() } }
            pumping = false
            assertTrue(waited < 2_000, "the answer did not end the wait of the window's loop ($waited ms)")
            val chosen = assertIs<FileDialogOutcome.Chosen>(results.single())
            assertEquals(listOf("/tmp/a b.txt", "/tmp/문서.txt"), chosen.paths)
            assertEquals(0, chosen.filterIndex)
            assertTrue(deliveredInPump, "the result did not arrive from inside pump")
            repeat(10) { window.pump(10) }
            assertEquals(1, results.size)
        } finally {
            portal.close()
            window.close()
        }
    }

    @Test
    fun fr72_linux_an_idle_pump_with_a_dialog_open_still_waits() {
        if (unavailable) return
        val window = openWindow()
        val portal = playPortal()
        try {
            val dialogs = LinuxFileDialogs { window }
            val results = ArrayList<FileDialogOutcome>()
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
            val asked = portal.accept("OpenFile")
            portal.settle()
            // The bus's answers to the request are read first; after that there is nothing to wake for.
            repeat(20) { window.pump(10) }
            assertTrue(millisOf { window.pump(300) } >= 250, "the open dialog's socket made the loop spin")
            portal.respond(asked, 1)
            pumpUntil(window) { results.isNotEmpty() }
            assertIs<FileDialogOutcome.Cancelled>(results.single())
        } finally {
            portal.close()
            window.close()
        }
    }

    @Test
    fun fr72_linux_requests_reach_the_portal_one_at_a_time_in_order() {
        if (unavailable) return
        val window = openWindow()
        val portal = playPortal()
        try {
            val dialogs = LinuxFileDialogs { window }
            val results = ArrayList<Pair<String, FileDialogOutcome>>()
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFolder, title = "first")) { results.add("first" to it) }
            dialogs.show(FileDialogRequest(FileDialogKind.Save, title = "second", name = "a.txt")) { results.add("second" to it) }
            val first = portal.accept("OpenFile")
            assertEquals("first", first.title)
            repeat(20) { window.pump(10) }
            assertNull(portal.nextCall(200), "the second request was sent while the first dialog was open")
            portal.respond(first, 0, "file:///tmp")
            pumpUntil(window) { results.size == 1 }
            val second = portal.accept("SaveFile")
            assertEquals("second", second.title)
            portal.respond(second, 0, "file:///tmp/a.txt")
            pumpUntil(window) { results.size == 2 }
            assertEquals(listOf("first", "second"), results.map { it.first })
            assertEquals(listOf("/tmp"), assertIs<FileDialogOutcome.Chosen>(results[0].second).paths)
            assertEquals(listOf("/tmp/a.txt"), assertIs<FileDialogOutcome.Chosen>(results[1].second).paths)
        } finally {
            portal.close()
            window.close()
        }
    }

    @Test
    fun fr72_linux_cancel_all_asks_the_portal_to_close_the_dialog_and_answers_cancelled_once() {
        if (unavailable) return
        val window = openWindow()
        val portal = playPortal()
        try {
            val dialogs = LinuxFileDialogs { window }
            val results = ArrayList<FileDialogOutcome>()
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
            val asked = portal.accept("OpenFile")
            portal.settle()
            repeat(20) { window.pump(10) }
            dialogs.cancelAll()
            assertEquals(2, results.size)
            assertTrue(results.all { it is FileDialogOutcome.Cancelled })
            val close = assertNotNull(portal.nextCall(5_000), "the portal was not asked to close the dialog")
            assertEquals("Close", close.member)
            assertEquals(asked.handle, close.path)
            assertEquals(FileChooserPortal.REQUEST_INTERFACE, close.interfaceName)
            assertNull(portal.nextCall(200), "the waiting request reached the portal after cancelAll")
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
            assertIs<FileDialogOutcome.Unavailable>(results.last())
            repeat(10) { window.pump(10) }
            assertEquals(3, results.size)
        } finally {
            portal.close()
            window.close()
        }
    }

    @Test
    fun fr72_linux_closing_the_window_cancels_its_dialog() {
        if (unavailable) return
        val window = openWindow()
        val portal = playPortal()
        try {
            val dialogs = LinuxFileDialogs { window }
            val results = ArrayList<FileDialogOutcome>()
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
            portal.accept("OpenFile")
            window.close()
            assertIs<FileDialogOutcome.Cancelled>(results.single())
            dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
            assertIs<FileDialogOutcome.Unavailable>(results.last())
            assertEquals(2, results.size)
        } finally {
            portal.close()
            window.close()
        }
    }

    @Test
    fun fr72_linux_no_session_bus_is_unavailable_at_once() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val window = openWindow()
        try {
            val dialogs = LinuxFileDialogs({ window }, openBus = { null })
            val results = ArrayList<FileDialogOutcome>()
            dialogs.show(FileDialogRequest(FileDialogKind.Save)) { results.add(it) }
            assertIs<FileDialogOutcome.Unavailable>(results.single())
            window.pump(10)
            assertEquals(1, results.size)
        } finally {
            window.close()
        }
    }

    @Test
    fun fr72_linux_a_window_that_never_opened_has_no_dialogs() {
        val window = X11Window()
        val results = ArrayList<FileDialogOutcome>()
        LinuxFileDialogs { window }.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
        assertIs<FileDialogOutcome.Unavailable>(results.single())
        val gone = LinuxFileDialogs { null }
        gone.show(FileDialogRequest(FileDialogKind.OpenFile)) { results.add(it) }
        assertEquals(2, results.size)
        assertIs<FileDialogOutcome.Unavailable>(results.last())
    }
}
