package org.thisisthepy.compose.window.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.FileDialogFilter
import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.atspi.AtspiWire
import org.thisisthepy.compose.window.atspi.BusConnection
import org.thisisthepy.compose.window.atspi.DBusMessage
import org.thisisthepy.compose.window.atspi.DBusReader
import org.thisisthepy.compose.window.atspi.DBusWriter
import org.thisisthepy.compose.window.atspi.IncomingCall
import org.thisisthepy.compose.window.atspi.double
import org.thisisthepy.compose.window.atspi.entry
import org.thisisthepy.compose.window.atspi.i16
import org.thisisthepy.compose.window.normalizeFileDialogRequest

private const val PORTAL_SENDER = ":1.7"

/** A bus that records what is sent and hands back what a test queued. */
private class FakeBus : BusConnection {
    val sent = ArrayList<ByteArray>()
    val incoming = ArrayDeque<ByteArray>()
    var closed = false
    var refuseSends = false

    override fun send(message: ByteArray): Boolean {
        if (closed || refuseSends) return false
        sent.add(message)
        return true
    }

    override fun receive(timeoutMillis: Int): ByteArray? = if (closed) null else incoming.removeFirstOrNull()

    override fun close() {
        closed = true
    }

    override val isOpen: Boolean get() = !closed

    fun calls(): List<IncomingCall> = sent.map { AtspiWire.parseCall(it) ?: error("not a method call") }

    /** The call to the file chooser, which is the fourth message of a request. */
    fun chooserCall(): IncomingCall = calls().first { it.interfaceName == FileChooserPortal.INTERFACE }
}

/** The options of a call, by key: the variant's signature and a reader placed at its value. */
private class Options(call: IncomingCall) {
    val parent: String
    val title: String
    val signatures = LinkedHashMap<String, String>()
    private val values = HashMap<String, Int>()
    private val reader = call.arguments()

    init {
        parent = reader.string()
        title = reader.string()
        reader.array(8) {
            reader.align(8)
            val key = reader.string()
            val signature = reader.signature()
            signatures[key] = signature
            // Every read below aligns itself, so the value is found from here.
            values[key] = reader.position
            reader.skip(signature)
        }
    }

    private fun at(key: String): DBusReader {
        reader.position = values.getValue(key)
        return reader
    }

    fun text(key: String): String = at(key).string()

    fun flag(key: String): Boolean = at(key).u32() == 1L

    fun bytes(key: String): List<Int> {
        val value = at(key)
        val out = ArrayList<Int>()
        value.array(1) { out.add(value.byte()) }
        return out
    }

    fun filter(key: String): PortalFilter = readFilter(at(key))

    fun filters(key: String): List<PortalFilter> {
        val value = at(key)
        val out = ArrayList<PortalFilter>()
        value.array(8) { out.add(readFilter(value)) }
        return out
    }

    private fun readFilter(value: DBusReader): PortalFilter {
        value.align(8)
        val label = value.string()
        val globs = ArrayList<String>()
        value.array(8) {
            value.align(8)
            assertEquals(0L, value.u32(), "every pattern is a glob")
            globs.add(value.string())
        }
        return PortalFilter(label, globs)
    }
}

private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) = struct {
    byte(code)
    signature(signature)
    value()
}

/** A message as the bus delivers one, with the sender it stamps on it. */
private fun delivered(type: Int, signature: String?, body: DBusWriter.() -> Unit, fields: DBusWriter.() -> Unit): ByteArray {
    val content = DBusWriter().apply(body).bytes()
    val message = DBusWriter()
    message.byte('l'.code)
    message.byte(type)
    message.byte(1)
    message.byte(1)
    message.u32(content.size.toLong())
    message.u32(99)
    message.array(8) {
        fields()
        field(7, "s") { string(PORTAL_SENDER) }
        if (signature != null) field(8, "g") { signature(signature) }
    }
    message.align(8)
    message.raw(content)
    return message.bytes()
}

private fun returnTo(serial: Int, signature: String?, body: DBusWriter.() -> Unit = {}): ByteArray =
    delivered(DBusMessage.METHOD_RETURN, signature, body) { field(5, "u") { u32(serial.toLong()) } }

private fun errorTo(serial: Int, name: String): ByteArray =
    delivered(DBusMessage.ERROR, "s", { string("refused") }) {
        field(4, "s") { string(name) }
        field(5, "u") { u32(serial.toLong()) }
    }

private fun response(path: String, code: Long, results: DBusWriter.() -> Unit = {}): ByteArray =
    AtspiWire.signal(7, path, FileChooserPortal.REQUEST_INTERFACE, "Response", "ua{sv}") {
        u32(code)
        array(8) { results() }
    }

private fun DBusWriter.uris(vararg uris: String) = entry("uris", "as") { array(4) { uris.forEach { string(it) } } }

private fun DBusWriter.currentFilter(label: String, vararg globs: String) = entry("current_filter", "(sa(us))") {
    struct {
        string(label)
        array(8) {
            globs.forEach { glob ->
                struct {
                    u32(0)
                    string(glob)
                }
            }
        }
    }
}

private fun ownerChanged(name: String, old: String, new: String): ByteArray =
    AtspiWire.signal(8, "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameOwnerChanged", "sss") {
        string(name)
        string(old)
        string(new)
    }

private fun handleOf(token: String) = "/org/freedesktop/portal/desktop/request/1_42/$token"

private val IMAGES = FileDialogFilter("Images", listOf("png", "jpg"))
private val TEXT = FileDialogFilter("Text", listOf("txt"))
private val ALL = FileDialogFilter("All files", emptyList())

private fun request(
    kind: FileDialogKind = FileDialogKind.OpenFile,
    title: String = "",
    directory: String = "",
    name: String = "",
    filters: List<FileDialogFilter> = emptyList(),
    defaultFilter: Int = 0,
) = normalizeFileDialogRequest(FileDialogRequest(kind, title, directory, name, filters, defaultFilter))

class FileChooserPortalTest {
    private fun optionsOf(request: FileDialogRequest): Options {
        val bus = FakeBus()
        assertTrue(PortalFileRequest(bus, request, "x11:2a", "compose_1").start(0))
        return Options(bus.chooserCall())
    }

    private fun outcomeOf(request: FileDialogRequest, code: Long, results: DBusWriter.() -> Unit = {}): FileDialogOutcome {
        val body = DBusWriter().apply {
            u32(code)
            array(8) { results() }
        }.bytes()
        return FileChooserPortal.outcome(request, DBusReader(body, 0))
    }

    // What is sent.

    @Test
    fun fr72_linux_an_x11_parent_is_the_window_id_in_lower_case_hex() {
        assertEquals("x11:2a", FileChooserPortal.x11ParentWindow(42))
        assertEquals("x11:3c0000b", FileChooserPortal.x11ParentWindow(0x3c0000b))
    }

    @Test
    fun fr72_linux_a_request_is_the_greeting_two_subscriptions_and_one_call_in_that_order() {
        val bus = FakeBus()
        assertTrue(PortalFileRequest(bus, request(title = "Pick"), "x11:2a", "compose_1").start(0))
        val calls = bus.calls()
        assertEquals(listOf("Hello", "AddMatch", "AddMatch", "OpenFile"), calls.map { it.member })
        assertEquals(listOf(1L, 2L, 3L, 4L), calls.map { it.serial })
        assertTrue("member='Response'" in calls[1].arguments().string())
        assertTrue("arg0='org.freedesktop.portal.Desktop'" in calls[2].arguments().string())
        val call = calls[3]
        assertEquals("/org/freedesktop/portal/desktop", call.path)
        assertEquals("org.freedesktop.portal.FileChooser", call.interfaceName)
        assertEquals("ssa{sv}", call.signature)
        val options = Options(call)
        assertEquals("x11:2a", options.parent)
        assertEquals("Pick", options.title)
        assertEquals("compose_1", options.text("handle_token"))
        assertEquals(setOf("handle_token", "modal"), options.signatures.keys)
        assertTrue(options.flag("modal"))
    }

    @Test
    fun fr72_linux_each_kind_sets_the_method_and_the_multiple_and_directory_options() {
        fun keys(kind: FileDialogKind) = optionsOf(request(kind)).signatures.keys - setOf("handle_token", "modal")
        assertEquals(emptySet(), keys(FileDialogKind.OpenFile))
        assertEquals(setOf("multiple"), keys(FileDialogKind.OpenFiles))
        assertEquals(setOf("directory"), keys(FileDialogKind.OpenFolder))
        assertEquals(setOf("directory", "multiple"), keys(FileDialogKind.OpenFolders))
        assertEquals(emptySet(), keys(FileDialogKind.Save))
        assertTrue(optionsOf(request(FileDialogKind.OpenFolders)).flag("directory"))
        assertTrue(optionsOf(request(FileDialogKind.OpenFolders)).flag("multiple"))
        assertEquals("b", optionsOf(request(FileDialogKind.OpenFiles)).signatures["multiple"])
        assertEquals("SaveFile", FileChooserPortal.method(FileDialogKind.Save))
        for (kind in FileDialogKind.entries - FileDialogKind.Save) assertEquals("OpenFile", FileChooserPortal.method(kind))
    }

    @Test
    fun fr72_linux_a_request_without_a_title_gets_a_plain_one() {
        assertEquals("Open File", optionsOf(request()).title)
        assertEquals("Save File", optionsOf(request(FileDialogKind.Save)).title)
        assertEquals("Select Folder", optionsOf(request(FileDialogKind.OpenFolder)).title)
    }

    @Test
    fun fr72_linux_filters_are_globs_in_both_cases_and_no_extensions_is_every_file() {
        val filters = FileChooserPortal.filters(request(filters = listOf(IMAGES, ALL, FileDialogFilter("", listOf("tar.gz", "7z")))))
        assertEquals(
            listOf(
                PortalFilter("Images", listOf("*.png", "*.PNG", "*.jpg", "*.JPG")),
                PortalFilter("All files", listOf("*")),
                // No label: the globs are the label. Digits have one case.
                PortalFilter("*.tar.gz *.TAR.GZ *.7z *.7Z", listOf("*.tar.gz", "*.TAR.GZ", "*.7z", "*.7Z")),
            ),
            filters,
        )
        assertEquals(listOf("*.123"), FileChooserPortal.filters(request(filters = listOf(FileDialogFilter("n", listOf("123")))))[0].globs)
    }

    @Test
    fun fr72_linux_filters_and_the_default_one_are_written_as_the_portal_reads_them() {
        val options = optionsOf(request(filters = listOf(IMAGES, TEXT, ALL), defaultFilter = 1))
        assertEquals("a(sa(us))", options.signatures["filters"])
        assertEquals("(sa(us))", options.signatures["current_filter"])
        val expected = listOf(
            PortalFilter("Images", listOf("*.png", "*.PNG", "*.jpg", "*.JPG")),
            PortalFilter("Text", listOf("*.txt", "*.TXT")),
            PortalFilter("All files", listOf("*")),
        )
        assertEquals(expected, options.filters("filters"))
        assertEquals(expected[1], options.filter("current_filter"))
    }

    @Test
    fun fr72_linux_a_folder_picker_sends_no_filters() {
        val options = optionsOf(request(FileDialogKind.OpenFolder, filters = listOf(IMAGES)))
        assertTrue("filters" !in options.signatures && "current_filter" !in options.signatures)
    }

    @Test
    fun fr72_linux_a_save_carries_the_suggested_name_and_the_folder_as_bytes_ending_in_zero() {
        val options = optionsOf(request(FileDialogKind.Save, directory = "/home/a/문서", name = "보고서.txt"))
        assertEquals("s", options.signatures["current_name"])
        assertEquals("보고서.txt", options.text("current_name"))
        assertEquals("ay", options.signatures["current_folder"])
        val expected = "/home/a/문서".encodeToByteArray().map { it.toInt() and 0xff } + 0
        assertEquals(expected, options.bytes("current_folder"))
    }

    @Test
    fun fr72_linux_an_open_carries_the_folder_and_never_a_name() {
        val options = optionsOf(request(FileDialogKind.OpenFile, directory = "/tmp", name = "ignored.txt"))
        assertEquals("/tmp".encodeToByteArray().map { it.toInt() } + 0, options.bytes("current_folder"))
        assertTrue("current_name" !in options.signatures)
    }

    @Test
    fun fr72_linux_a_folder_that_is_not_an_absolute_path_is_left_to_the_portal() {
        assertTrue("current_folder" !in optionsOf(request(directory = "relative/dir")).signatures)
        assertTrue("current_folder" !in optionsOf(request(directory = "")).signatures)
        assertTrue("current_folder" !in optionsOf(request(directory = "/a\u0000b")).signatures)
        assertTrue("current_name" !in optionsOf(request(FileDialogKind.Save)).signatures)
    }

    // file:// URIs.

    @Test
    fun fr72_linux_a_file_uri_is_its_absolute_path_with_escapes_decoded_as_utf8() {
        assertEquals("/home/a/b.txt", FileChooserPortal.pathOfFileUri("file:///home/a/b.txt"))
        assertEquals("/tmp/a b#c?d%.txt", FileChooserPortal.pathOfFileUri("file:///tmp/a%20b%23c%3Fd%25.txt"))
        assertEquals("/tmp/문서/보고서.txt", FileChooserPortal.pathOfFileUri("file:///tmp/%EB%AC%B8%EC%84%9C/%eb%b3%b4%ea%b3%a0%ec%84%9c.txt"))
        assertEquals("/tmp/문서.txt", FileChooserPortal.pathOfFileUri("file:///tmp/문서.txt"))
        assertEquals("/tmp/a+b", FileChooserPortal.pathOfFileUri("file:///tmp/a+b"))
        assertEquals("/", FileChooserPortal.pathOfFileUri("file:///"))
        assertEquals("/etc/hosts", FileChooserPortal.pathOfFileUri("file://localhost/etc/hosts"))
        assertEquals("/etc/hosts", FileChooserPortal.pathOfFileUri("FILE:/etc/hosts"))
    }

    @Test
    fun fr72_linux_anything_that_is_not_a_local_file_uri_has_no_path() {
        assertNull(FileChooserPortal.pathOfFileUri("https://example.com/a.txt"))
        assertNull(FileChooserPortal.pathOfFileUri("file://server/share/a.txt"))
        assertNull(FileChooserPortal.pathOfFileUri("file://"))
        assertNull(FileChooserPortal.pathOfFileUri("file:relative"))
        assertNull(FileChooserPortal.pathOfFileUri("/plain/path"))
        assertNull(FileChooserPortal.pathOfFileUri(""))
        assertNull(FileChooserPortal.pathOfFileUri("file:///a%2"))
        assertNull(FileChooserPortal.pathOfFileUri("file:///a%zz"))
        assertNull(FileChooserPortal.pathOfFileUri("file:///a%00b"))
        assertNull(FileChooserPortal.pathOfFileUri("file:///a#fragment"))
        // Bytes that are not UTF-8 cannot reach the caller as text.
        assertNull(FileChooserPortal.pathOfFileUri("file:///a%FF%FE"))
    }

    // What comes back.

    @Test
    fun fr72_linux_a_successful_response_is_the_paths_in_order() {
        val outcome = outcomeOf(request(FileDialogKind.OpenFiles), 0) {
            uris("file:///tmp/a%20b.txt", "file:///tmp/c.txt")
        }
        assertIs<FileDialogOutcome.Chosen>(outcome)
        assertEquals(listOf("/tmp/a b.txt", "/tmp/c.txt"), outcome.paths)
        assertEquals(-1, outcome.filterIndex)
    }

    @Test
    fun fr72_linux_the_selected_filter_is_found_among_the_ones_sent() {
        val asked = request(filters = listOf(IMAGES, TEXT, ALL), defaultFilter = 2)
        val exact = outcomeOf(asked, 0) {
            currentFilter("Text", "*.txt", "*.TXT")
            uris("file:///a.txt")
        }
        assertEquals(1, assertIs<FileDialogOutcome.Chosen>(exact).filterIndex)
        // A portal that rewrote the globs still names the filter by its label.
        val byLabel = outcomeOf(asked, 0) {
            uris("file:///a.png")
            currentFilter("Images", "*.png")
        }
        assertEquals(0, assertIs<FileDialogOutcome.Chosen>(byLabel).filterIndex)
        // Unknown, or not said: the default that was asked for.
        val unknown = outcomeOf(asked, 0) {
            uris("file:///a")
            currentFilter("Other", "*.x")
        }
        assertEquals(2, assertIs<FileDialogOutcome.Chosen>(unknown).filterIndex)
        assertEquals(2, assertIs<FileDialogOutcome.Chosen>(outcomeOf(asked, 0) { uris("file:///a") }).filterIndex)
        // A folder picker has no filter, whatever the portal says.
        val folder = outcomeOf(request(FileDialogKind.OpenFolder, filters = listOf(IMAGES)), 0) {
            uris("file:///d")
            currentFilter("Images", "*.png", "*.PNG", "*.jpg", "*.JPG")
        }
        assertEquals(-1, assertIs<FileDialogOutcome.Chosen>(folder).filterIndex)
    }

    @Test
    fun fr72_linux_results_this_side_does_not_use_are_stepped_over() {
        val outcome = outcomeOf(request(), 0) {
            entry("choices", "a(ss)") {
                array(8) {
                    struct {
                        string("encoding")
                        string("utf8")
                    }
                }
            }
            entry("writable", "b") { u32(1) }
            entry("odd", "(yqxdvay)") {
                struct {
                    byte(1)
                    i16(2)
                    double(3.0)
                    double(4.0)
                    signature("as")
                    array(4) { string("in a variant") }
                    array(1) { byte(9) }
                }
            }
            entry("map", "a{sv}") { array(8) { entry("k", "u") { u32(5) } } }
            uris("file:///tmp/x")
            entry("after", "s") { string("tail") }
        }
        assertEquals(listOf("/tmp/x"), assertIs<FileDialogOutcome.Chosen>(outcome).paths)
    }

    @Test
    fun fr72_linux_cancel_other_endings_and_unusable_results() {
        assertIs<FileDialogOutcome.Cancelled>(outcomeOf(request(), 1))
        // What the GTK portal answers to Escape and to closing the dialog's window.
        assertIs<FileDialogOutcome.Cancelled>(outcomeOf(request(), 2))
        assertIs<FileDialogOutcome.Cancelled>(outcomeOf(request(), 2) { uris("file:///ignored") })
        // Success with no paths is not a choice.
        assertIs<FileDialogOutcome.Unavailable>(outcomeOf(request(), 0))
        assertIs<FileDialogOutcome.Unavailable>(outcomeOf(request(), 0) { uris() })
        // One path that is not a local file fails the whole result.
        assertIs<FileDialogOutcome.Unavailable>(outcomeOf(request(), 0) { uris("file:///a", "sftp://host/b") })
        // A body that ends early, and no body at all.
        val whole = DBusWriter().apply {
            u32(0)
            array(8) { uris("file:///tmp/a.txt") }
        }.bytes()
        // Every cut but the last byte, the final string's closing zero, which nothing reads.
        for (cut in 0 until whole.size - 1) {
            assertIs<FileDialogOutcome.Unavailable>(FileChooserPortal.outcome(request(), DBusReader(whole.copyOf(cut), 0)), "cut at $cut")
        }
    }

    @Test
    fun fr72_linux_skipping_rejects_a_signature_that_is_not_one() {
        val bytes = ByteArray(64)
        assertFailsWith<IllegalArgumentException> { DBusReader(bytes, 0).skip("(s") }
        assertFailsWith<IllegalArgumentException> { DBusReader(bytes, 0).skip("z") }
        assertFailsWith<IllegalArgumentException> { DBusReader(bytes, 0).skip("a") }
        assertFailsWith<IndexOutOfBoundsException> { DBusReader(ByteArray(4), 0).skip("x") }
        // An array that claims more than there is.
        val long = DBusWriter().apply { u32(1_000_000) }.bytes()
        assertFailsWith<IndexOutOfBoundsException> { DBusReader(long, 0).skip("ax") }
    }

    // One request on the bus.

    private class Running(kind: FileDialogKind = FileDialogKind.OpenFile) {
        val bus = FakeBus()
        val call = PortalFileRequest(bus, request(kind), "x11:2a", "compose_9")
        val handle = handleOf("compose_9")

        init {
            assertTrue(call.start(1_000))
            bus.incoming.add(returnTo(PortalFileRequest.HELLO_SERIAL, "s") { string(":1.42") })
            bus.incoming.add(returnTo(PortalFileRequest.MATCH_SERIAL_RESPONSE, null))
            bus.incoming.add(returnTo(PortalFileRequest.MATCH_SERIAL_OWNER, null))
        }

        fun accepted() = bus.incoming.add(returnTo(PortalFileRequest.CALL_SERIAL, "o") { string(handle) })
    }

    @Test
    fun fr72_linux_the_response_of_the_request_ends_it_and_leaves_the_bus() {
        val running = Running()
        running.accepted()
        assertNull(running.call.pump(1_010))
        running.bus.incoming.add(response(running.handle, 0) { uris("file:///tmp/chosen.txt") })
        val outcome = running.call.pump(1_020)
        assertEquals(listOf("/tmp/chosen.txt"), assertIs<FileDialogOutcome.Chosen>(outcome).paths)
        assertTrue(running.bus.closed)
        // Nothing is asked to close: the dialog has already gone.
        assertEquals(4, running.bus.sent.size)
        assertIs<FileDialogOutcome.Chosen>(running.call.pump(1_030))
    }

    @Test
    fun fr72_linux_a_response_for_another_request_is_not_this_ones() {
        val running = Running()
        running.accepted()
        running.bus.incoming.add(response(handleOf("compose_8"), 0) { uris("file:///tmp/other.txt") })
        running.bus.incoming.add(response("/org/freedesktop/portal/desktop/request/1_42/xcompose_9", 1))
        assertNull(running.call.pump(1_010))
        running.bus.incoming.add(response(running.handle, 1))
        assertIs<FileDialogOutcome.Cancelled>(running.call.pump(1_020))
    }

    @Test
    fun fr72_linux_a_response_read_before_the_calls_answer_still_counts() {
        val running = Running()
        running.bus.incoming.add(response(running.handle, 0) { uris("file:///tmp/early.txt") })
        assertEquals(listOf("/tmp/early.txt"), assertIs<FileDialogOutcome.Chosen>(running.call.pump(1_010)).paths)
    }

    @Test
    fun fr72_linux_an_old_portal_answers_with_a_path_of_its_own_and_that_one_is_followed() {
        val running = Running()
        running.bus.incoming.add(returnTo(PortalFileRequest.CALL_SERIAL, "o") { string("/org/freedesktop/portal/desktop/request/1_42/t123") })
        running.bus.incoming.add(response("/org/freedesktop/portal/desktop/request/1_42/t123", 1))
        assertIs<FileDialogOutcome.Cancelled>(running.call.pump(1_010))
    }

    @Test
    fun fr72_linux_no_portal_on_the_bus_is_unavailable() {
        for (name in listOf(
            "org.freedesktop.DBus.Error.ServiceUnknown",
            "org.freedesktop.DBus.Error.NameHasNoOwner",
            "org.freedesktop.DBus.Error.UnknownMethod",
            "org.freedesktop.DBus.Error.Spawn.ChildExited",
        )) {
            val running = Running()
            running.bus.incoming.add(errorTo(PortalFileRequest.CALL_SERIAL, name))
            assertIs<FileDialogOutcome.Unavailable>(running.call.pump(1_010), name)
            assertTrue(running.bus.closed)
        }
    }

    @Test
    fun fr72_linux_an_error_that_answers_something_else_is_not_the_requests() {
        val running = Running()
        running.accepted()
        running.bus.incoming.add(errorTo(77, "org.freedesktop.DBus.Error.Failed"))
        assertNull(running.call.pump(1_010))
    }

    @Test
    fun fr72_linux_the_portal_leaving_the_bus_is_unavailable() {
        val running = Running()
        running.accepted()
        // It arriving, and some other name leaving, are not the portal leaving.
        running.bus.incoming.add(ownerChanged(FileChooserPortal.SERVICE, "", ":1.7"))
        running.bus.incoming.add(ownerChanged("org.example.Other", ":1.9", ""))
        assertNull(running.call.pump(1_010))
        running.bus.incoming.add(ownerChanged(FileChooserPortal.SERVICE, ":1.7", ""))
        assertIs<FileDialogOutcome.Unavailable>(running.call.pump(1_020))
    }

    @Test
    fun fr72_linux_a_bus_that_closed_is_unavailable() {
        val running = Running()
        running.accepted()
        assertNull(running.call.pump(1_010))
        running.bus.closed = true
        assertIs<FileDialogOutcome.Unavailable>(running.call.pump(1_020))
    }

    @Test
    fun fr72_linux_a_call_never_answered_is_unavailable_but_an_open_dialog_waits_for_ever() {
        val silent = Running()
        assertNull(silent.call.pump(1_000 + PortalFileRequest.CALL_TIMEOUT_MILLIS - 1))
        assertIs<FileDialogOutcome.Unavailable>(silent.call.pump(1_000 + PortalFileRequest.CALL_TIMEOUT_MILLIS))
        val open = Running()
        open.accepted()
        assertNull(open.call.pump(1_000 + 100 * PortalFileRequest.CALL_TIMEOUT_MILLIS))
    }

    @Test
    fun fr72_linux_a_bus_that_refuses_the_request_fails_the_start() {
        val bus = FakeBus()
        bus.refuseSends = true
        assertTrue(!PortalFileRequest(bus, request(), "x11:2a", "compose_1").start(0))
    }

    @Test
    fun fr72_linux_closing_an_open_dialog_asks_the_portal_to_close_its_request() {
        val running = Running()
        running.accepted()
        assertNull(running.call.pump(1_010))
        running.call.close()
        running.call.close()
        val close = AtspiWire.parseCall(running.bus.sent.last())!!
        assertEquals(5, running.bus.sent.size)
        assertEquals("Close", close.member)
        assertEquals(running.handle, close.path)
        assertEquals("org.freedesktop.portal.Request", close.interfaceName)
        assertTrue(running.bus.closed)
    }

    @Test
    fun fr72_linux_closing_before_the_portal_answered_just_leaves_the_bus() {
        val running = Running()
        running.call.close()
        assertEquals(4, running.bus.sent.size)
        assertTrue(running.bus.closed)
    }

    // The queue, with the portal under it.

    private class Desk(var window: String? = "w1", var parent: String? = "x11:2a", var hasBus: Boolean = true) {
        val buses = ArrayList<FakeBus>()
        var now = 0L
        val results = ArrayList<Pair<String, FileDialogOutcome>>()
        val dialogs = PortalFileDialogs(
            owner = { window },
            parentWindow = { _: String -> parent },
            openBus = { if (hasBus) FakeBus().also { buses.add(it) } else null },
            nowMillis = { now },
            tokenPrefix = "compose_77",
        )

        fun show(tag: String, kind: FileDialogKind = FileDialogKind.OpenFile) =
            dialogs.show(FileDialogRequest(kind)) { results.add(tag to it) }

        fun token(index: Int): String = Options(buses[index].chooserCall()).text("handle_token")

        fun answer(index: Int, code: Long, vararg uris: String) {
            buses[index].incoming.add(response(handleOf(token(index)), code) { if (uris.isNotEmpty()) uris(*uris) })
            dialogs.pump()
        }
    }

    @Test
    fun fr72_linux_requests_for_a_window_are_shown_one_at_a_time_in_order() {
        val desk = Desk()
        desk.show("first")
        desk.show("second", FileDialogKind.Save)
        desk.show("third")
        // One connection, one call: the others wait.
        assertEquals(1, desk.buses.size)
        assertEquals(1, desk.dialogs.connections.size)
        desk.dialogs.pump()
        assertTrue(desk.results.isEmpty())
        desk.answer(0, 0, "file:///tmp/one")
        assertEquals(listOf("first"), desk.results.map { it.first })
        assertEquals(2, desk.buses.size)
        assertTrue(desk.buses[0].closed)
        assertEquals("SaveFile", desk.buses[1].chooserCall().member)
        desk.answer(1, 1)
        desk.answer(2, 0, "file:///tmp/three")
        assertEquals(listOf("first", "second", "third"), desk.results.map { it.first })
        assertEquals(listOf("/tmp/one"), assertIs<FileDialogOutcome.Chosen>(desk.results[0].second).paths)
        assertIs<FileDialogOutcome.Cancelled>(desk.results[1].second)
        assertEquals(listOf("/tmp/three"), assertIs<FileDialogOutcome.Chosen>(desk.results[2].second).paths)
        // Each request had a token of its own, and nothing is left open.
        assertEquals(listOf("compose_77_1", "compose_77_2", "compose_77_3"), (0..2).map { desk.token(it) })
        assertTrue(desk.dialogs.connections.isEmpty())
        assertTrue(desk.buses.all { it.closed })
        desk.dialogs.pump()
        assertEquals(3, desk.results.size)
    }

    @Test
    fun fr72_linux_a_request_made_from_a_result_is_shown_next() {
        val desk = Desk()
        desk.dialogs.show(FileDialogRequest(FileDialogKind.OpenFile)) {
            desk.results.add("first" to it)
            desk.show("again")
        }
        desk.answer(0, 1)
        assertEquals(2, desk.buses.size)
        desk.answer(1, 0, "file:///tmp/again")
        assertEquals(listOf("first", "again"), desk.results.map { it.first })
    }

    @Test
    fun fr72_linux_cancel_all_ends_the_open_dialog_and_the_waiting_ones_once_each() {
        val desk = Desk()
        desk.show("open")
        desk.show("waiting")
        desk.buses[0].incoming.add(returnTo(PortalFileRequest.CALL_SERIAL, "o") { string(handleOf("compose_77_1")) })
        desk.dialogs.pump()
        desk.dialogs.cancelAll()
        assertEquals(listOf("open", "waiting"), desk.results.map { it.first })
        assertTrue(desk.results.all { it.second is FileDialogOutcome.Cancelled })
        // The open one was asked to close; the waiting one never reached the bus.
        assertEquals(1, desk.buses.size)
        assertEquals("Close", AtspiWire.parseCall(desk.buses[0].sent.last())!!.member)
        assertTrue(desk.buses[0].closed)
        assertTrue(desk.dialogs.connections.isEmpty())
        desk.dialogs.cancelAll()
        desk.dialogs.pump()
        assertEquals(2, desk.results.size)
        desk.show("late")
        assertIs<FileDialogOutcome.Unavailable>(desk.results.last().second)
        assertEquals(1, desk.buses.size)
    }

    @Test
    fun fr72_linux_no_bus_no_window_and_no_portal_are_each_unavailable_exactly_once() {
        val noBus = Desk(hasBus = false)
        noBus.show("a")
        assertIs<FileDialogOutcome.Unavailable>(noBus.results.single().second)

        val noWindow = Desk(window = null)
        noWindow.show("a")
        assertIs<FileDialogOutcome.Unavailable>(noWindow.results.single().second)
        assertTrue(noWindow.buses.isEmpty())

        val closedWindow = Desk(parent = null)
        closedWindow.show("a")
        assertIs<FileDialogOutcome.Unavailable>(closedWindow.results.single().second)
        assertTrue(closedWindow.buses.isEmpty())

        val noPortal = Desk()
        noPortal.show("a")
        noPortal.show("b")
        noPortal.buses[0].incoming.add(errorTo(PortalFileRequest.CALL_SERIAL, "org.freedesktop.DBus.Error.ServiceUnknown"))
        noPortal.dialogs.pump()
        // The next one is asked for all the same: a portal may have been started since.
        assertEquals(listOf("a"), noPortal.results.map { it.first })
        assertIs<FileDialogOutcome.Unavailable>(noPortal.results[0].second)
        assertEquals(2, noPortal.buses.size)
        noPortal.buses[1].incoming.add(errorTo(PortalFileRequest.CALL_SERIAL, "org.freedesktop.DBus.Error.ServiceUnknown"))
        noPortal.dialogs.pump()
        noPortal.dialogs.pump()
        assertEquals(2, noPortal.results.size)
    }

    @Test
    fun fr72_linux_the_bus_names_the_object_and_the_error_of_a_message() {
        val signal = DBusMessage.parse(response("/a/b", 1))!!
        assertEquals("/a/b", signal.path)
        assertNull(signal.errorName)
        val error = DBusMessage.parse(errorTo(4, "org.freedesktop.DBus.Error.ServiceUnknown"))!!
        assertEquals("org.freedesktop.DBus.Error.ServiceUnknown", error.errorName)
        assertEquals(4L, error.replySerial)
    }
}
