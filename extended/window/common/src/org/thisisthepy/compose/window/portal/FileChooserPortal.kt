package org.thisisthepy.compose.window.portal

import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogLanes
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.FileDialogs
import org.thisisthepy.compose.window.SheetPresenter
import org.thisisthepy.compose.window.atspi.BusConnection
import org.thisisthepy.compose.window.atspi.DBusMessage
import org.thisisthepy.compose.window.atspi.DBusReader
import org.thisisthepy.compose.window.atspi.DBusWriter
import org.thisisthepy.compose.window.atspi.Destination
import org.thisisthepy.compose.window.atspi.boolean
import org.thisisthepy.compose.window.atspi.entry
import org.thisisthepy.compose.window.fileDialogOutcome
import org.thisisthepy.compose.window.normalizeFileDialogRequest

// The desktop portal's file chooser (org.freedesktop.portal.FileChooser), spoken over the
// session bus with the same wire code the accessibility bridge uses. Plain Kotlin: the
// Kotlin/Native X11 window drives it today, and the GraalVM X11 window can with its own socket.
//
// The portal shows the dialog in a process of its own (the desktop's: GTK, KDE, ...), so the
// application links no toolkit and its loop is never inside a dialog's loop. A request is one
// method call that answers at once with the path of a Request object; the reader's choice
// arrives later as that object's Response signal.

/** One entry of the portal's filter list: a label and the globs it shows. */
class PortalFilter(val label: String, val globs: List<String>) {
    override fun equals(other: Any?): Boolean =
        other is PortalFilter && other.label == label && other.globs == globs

    override fun hashCode(): Int = 31 * label.hashCode() + globs.hashCode()
}

/** What a FileDialogRequest is on the portal's wire, and what its answer is here. */
object FileChooserPortal {
    const val SERVICE = "org.freedesktop.portal.Desktop"
    const val OBJECT_PATH = "/org/freedesktop/portal/desktop"
    const val INTERFACE = "org.freedesktop.portal.FileChooser"
    const val REQUEST_INTERFACE = "org.freedesktop.portal.Request"

    /** The signature of OpenFile and of SaveFile: parent window, title, options. */
    const val CALL_SIGNATURE = "ssa{sv}"

    /** How the portal is told which X11 window the dialog belongs to: the window id in hex. */
    fun x11ParentWindow(windowId: Long): String = "x11:" + windowId.toString(16)

    fun method(kind: FileDialogKind): String = if (kind == FileDialogKind.Save) "SaveFile" else "OpenFile"

    /**
     * The window title. The portal shows exactly what it is given and has no title of its own,
     * so a request without one gets a plain English one rather than an empty title bar.
     */
    fun title(request: FileDialogRequest): String = request.title.ifEmpty {
        when (request.kind) {
            FileDialogKind.OpenFile -> "Open File"
            FileDialogKind.OpenFiles -> "Open Files"
            FileDialogKind.OpenFolder -> "Select Folder"
            FileDialogKind.OpenFolders -> "Select Folders"
            FileDialogKind.Save -> "Save File"
        }
    }

    /**
     * The filters of a normalized request as the portal takes them. A folder picker has none.
     *
     * The portal matches globs with case, so each extension is given in lower and in upper
     * case; a filter with no extensions is every file. A filter without a label is labelled
     * with its globs, because the portal shows the label alone.
     */
    fun filters(request: FileDialogRequest): List<PortalFilter> {
        if (isFolderKind(request.kind)) return emptyList()
        return request.filters.map { filter ->
            val globs = ArrayList<String>()
            for (extension in filter.extensions) {
                globs.add("*.$extension")
                val upper = extension.uppercase()
                if (upper != extension) globs.add("*.$upper")
            }
            if (globs.isEmpty()) globs.add("*")
            PortalFilter(filter.label.ifEmpty { globs.joinToString(" ") }, globs)
        }
    }

    /** The arguments of OpenFile or SaveFile for a normalized [request]. */
    fun callBody(request: FileDialogRequest, parentWindow: String, token: String): ByteArray =
        DBusWriter().apply {
            string(parentWindow)
            string(title(request))
            options(request, token)
        }.bytes()

    private fun DBusWriter.options(request: FileDialogRequest, token: String) {
        val filters = filters(request)
        array(8) {
            entry("handle_token", "s") { string(token) }
            entry("modal", "b") { boolean(true) }
            when (request.kind) {
                FileDialogKind.OpenFiles -> entry("multiple", "b") { boolean(true) }
                FileDialogKind.OpenFolder -> entry("directory", "b") { boolean(true) }
                FileDialogKind.OpenFolders -> {
                    entry("directory", "b") { boolean(true) }
                    entry("multiple", "b") { boolean(true) }
                }
                FileDialogKind.Save -> if (request.name.isNotEmpty()) entry("current_name", "s") { string(request.name) }
                FileDialogKind.OpenFile -> Unit
            }
            if (filters.isNotEmpty()) {
                entry("filters", "a(sa(us))") { array(8) { filters.forEach { filter(it) } } }
                entry("current_filter", "(sa(us))") { filter(filters[request.defaultFilter.coerceIn(0, filters.size - 1)]) }
            }
            // A folder is a byte string that ends in a zero byte, because a path need not be text.
            if (request.directory.startsWith("/") && '\u0000' !in request.directory) {
                entry("current_folder", "ay") {
                    array(1) {
                        raw(request.directory.encodeToByteArray())
                        byte(0)
                    }
                }
            }
        }
    }

    private fun DBusWriter.filter(filter: PortalFilter) = struct {
        string(filter.label)
        array(8) {
            for (glob in filter.globs) {
                struct {
                    u32(GLOB)
                    string(glob)
                }
            }
        }
    }

    /**
     * What the body of a Response signal (`ua{sv}`) means for a normalized [request].
     *
     * Response 0 carries `uris`. Everything else is Cancelled: 1 is the dialog's own cancel
     * button, and 2 ("ended in some other way") is what the GTK portal answers when the reader
     * presses Escape or closes the dialog's window. Every URI has to be a local file, or the
     * whole result is Unavailable, as it is for a body that cannot be read.
     */
    fun outcome(request: FileDialogRequest, body: DBusReader): FileDialogOutcome = try {
        if (body.u32() == RESPONSE_SUCCESS) chosen(request, body) else FileDialogOutcome.Cancelled
    } catch (_: IndexOutOfBoundsException) {
        FileDialogOutcome.Unavailable
    } catch (_: IllegalArgumentException) {
        FileDialogOutcome.Unavailable
    }

    private fun chosen(request: FileDialogRequest, body: DBusReader): FileDialogOutcome {
        var uris: MutableList<String>? = null
        var picked: PortalFilter? = null
        body.array(8) {
            body.align(8)
            val key = body.string()
            val signature = body.signature()
            when {
                key == "uris" && signature == "as" -> {
                    val list = ArrayList<String>()
                    body.array(4) { list.add(body.string()) }
                    uris = list
                }
                key == "current_filter" && signature == "(sa(us))" -> {
                    body.align(8)
                    val label = body.string()
                    val globs = ArrayList<String>()
                    body.array(8) {
                        body.align(8)
                        body.u32()
                        globs.add(body.string())
                    }
                    picked = PortalFilter(label, globs)
                }
                else -> body.skip(signature)
            }
        }
        // Success with nothing chosen is not a choice, and not the reader's cancel either.
        val list = uris
        if (list.isNullOrEmpty()) return FileDialogOutcome.Unavailable
        return fileDialogOutcome(list.map { pathOfFileUri(it) }, filterIndex(request, picked))
    }

    /** Which of the request's filters the portal says was selected; the default where it does not say. */
    private fun filterIndex(request: FileDialogRequest, picked: PortalFilter?): Int {
        val sent = filters(request)
        if (sent.isEmpty()) return -1
        val fallback = request.defaultFilter.coerceIn(0, sent.size - 1)
        if (picked == null) return fallback
        val exact = sent.indexOf(picked)
        if (exact >= 0) return exact
        val byLabel = sent.indexOfFirst { it.label == picked.label }
        return if (byLabel >= 0) byLabel else fallback
    }

    /**
     * The absolute path a `file://` URI names, or null for anything else: another scheme,
     * another machine, a broken escape, a zero byte, or bytes that are not UTF-8.
     */
    fun pathOfFileUri(uri: String): String? {
        if (!uri.startsWith("file:", ignoreCase = true)) return null
        var rest = uri.substring(5)
        if (rest.startsWith("//")) {
            val slash = rest.indexOf('/', 2)
            if (slash < 0) return null
            val host = rest.substring(2, slash)
            if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) return null
            rest = rest.substring(slash)
        }
        if (!rest.startsWith("/") || '#' in rest) return null
        val source = rest.encodeToByteArray()
        val bytes = ByteArray(source.size)
        var size = 0
        var index = 0
        while (index < source.size) {
            var value = source[index]
            if (value == '%'.code.toByte()) {
                if (index + 2 >= source.size) return null
                val high = hexValue(source[index + 1])
                val low = hexValue(source[index + 2])
                if (high < 0 || low < 0) return null
                value = (high * 16 + low).toByte()
                index += 2
            }
            if (value == 0.toByte()) return null
            bytes[size++] = value
            index += 1
        }
        return try {
            bytes.decodeToString(0, size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hexValue(byte: Byte): Int = when (val code = byte.toInt()) {
        in '0'.code..'9'.code -> code - '0'.code
        in 'a'.code..'f'.code -> code - 'a'.code + 10
        in 'A'.code..'F'.code -> code - 'A'.code + 10
        else -> -1
    }

    private fun isFolderKind(kind: FileDialogKind) =
        kind == FileDialogKind.OpenFolder || kind == FileDialogKind.OpenFolders

    private const val GLOB = 0L
    private const val RESPONSE_SUCCESS = 0L
}

/**
 * One file dialog on the portal, from the call to its answer, on a bus connection of its own.
 *
 * Nothing here waits. [start] writes the whole conversation at once (the bus's greeting, the
 * two subscriptions and the call; the bus answers them in order), and [pump] reads whatever has
 * arrived without waiting, once a turn of the window's loop, until it has an outcome:
 *
 * - an error in answer to the call (no portal on the bus is `ServiceUnknown`, a portal with
 *   no file chooser is `UnknownMethod`) is Unavailable;
 * - the Response signal of the request is the reader's choice or cancel;
 * - the portal leaving the bus, the bus closing, or no answer to the call within
 *   [CALL_TIMEOUT_MILLIS] is Unavailable.
 *
 * The connection is the request's own, so [close] is also how an open dialog is taken down:
 * the portal closes the requests of a caller that left the bus.
 *
 * [request] must be normalized (normalizeFileDialogRequest). [token] names the request in its
 * object path and has to be letters, digits and underscores.
 */
class PortalFileRequest(
    private val bus: BusConnection,
    private val request: FileDialogRequest,
    private val parentWindow: String,
    private val token: String,
) {
    private var handle: String? = null
    private var deadlineMillis = 0L
    private var outcome: FileDialogOutcome? = null
    private var closed = false

    /** Sends the request. False where the bus did not take it, which is Unavailable. */
    fun start(nowMillis: Long): Boolean {
        deadlineMillis = nowMillis + CALL_TIMEOUT_MILLIS
        val rules = listOf(
            MATCH_SERIAL_RESPONSE to
                "type='signal',interface='${FileChooserPortal.REQUEST_INTERFACE}',member='Response'",
            MATCH_SERIAL_OWNER to
                "type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus'," +
                "member='NameOwnerChanged',arg0='${FileChooserPortal.SERVICE}'",
        )
        if (!bus.send(DBusMessage.methodCall(HELLO_SERIAL, BUS, "Hello", null, ByteArray(0)))) return false
        for ((serial, rule) in rules) {
            val body = DBusWriter().apply { string(rule) }.bytes()
            if (!bus.send(DBusMessage.methodCall(serial, BUS, "AddMatch", "s", body))) return false
        }
        return bus.send(
            DBusMessage.methodCall(
                CALL_SERIAL,
                PORTAL,
                FileChooserPortal.method(request.kind),
                FileChooserPortal.CALL_SIGNATURE,
                FileChooserPortal.callBody(request, parentWindow, token),
            ),
        )
    }

    /** Reads what has arrived without waiting. Null while the dialog is still open. */
    fun pump(nowMillis: Long): FileDialogOutcome? {
        outcome?.let { return it }
        if (closed) return finish(FileDialogOutcome.Cancelled)
        while (true) {
            val bytes = bus.receive(0) ?: break
            val message = DBusMessage.parse(bytes) ?: continue
            val result = read(message) ?: continue
            return finish(result)
        }
        if (!bus.isOpen) return finish(FileDialogOutcome.Unavailable)
        if (handle == null && nowMillis >= deadlineMillis) return finish(FileDialogOutcome.Unavailable)
        return null
    }

    /**
     * Ends the request: asks the portal to close the dialog if it is still open, and leaves
     * the bus. Safe to call more than once.
     */
    fun close() {
        if (closed) return
        closed = true
        val open = handle
        if (outcome == null && open != null) {
            val target = Destination(FileChooserPortal.SERVICE, open, FileChooserPortal.REQUEST_INTERFACE)
            bus.send(DBusMessage.methodCall(CLOSE_SERIAL, target, "Close", null, ByteArray(0)))
        }
        bus.close()
    }

    private fun finish(result: FileDialogOutcome): FileDialogOutcome {
        outcome = result
        close()
        return result
    }

    private fun read(message: DBusMessage): FileDialogOutcome? {
        when (message.type) {
            // Any of the four refused is a dialog that will never answer.
            DBusMessage.ERROR -> if (message.replySerial in HELLO_SERIAL.toLong()..CALL_SERIAL.toLong()) {
                return FileDialogOutcome.Unavailable
            }
            DBusMessage.METHOD_RETURN -> if (message.replySerial == CALL_SERIAL.toLong()) {
                handle = try {
                    message.body().string()
                } catch (_: IndexOutOfBoundsException) {
                    return FileDialogOutcome.Unavailable
                }
            }
            DBusMessage.SIGNAL -> return signal(message)
        }
        return null
    }

    private fun signal(message: DBusMessage): FileDialogOutcome? {
        if (message.interfaceName == FileChooserPortal.REQUEST_INTERFACE && message.member == "Response") {
            val path = message.path ?: return null
            // The path the call answered with, or the one the portal derives from the token:
            // the same thing on every current portal, and the signal may be read first.
            if (path != handle && !path.endsWith("/$token")) return null
            return FileChooserPortal.outcome(request, message.body())
        }
        if (message.interfaceName == "org.freedesktop.DBus" && message.member == "NameOwnerChanged") {
            return try {
                val body = message.body()
                val name = body.string()
                val oldOwner = body.string()
                val newOwner = body.string()
                if (name == FileChooserPortal.SERVICE && oldOwner.isNotEmpty() && newOwner.isEmpty()) {
                    FileDialogOutcome.Unavailable
                } else {
                    null
                }
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }
        return null
    }

    companion object {
        /** How long the portal has to answer the call itself; the dialog may stay open for ever. */
        const val CALL_TIMEOUT_MILLIS = 30_000L

        const val HELLO_SERIAL = 1
        const val MATCH_SERIAL_RESPONSE = 2
        const val MATCH_SERIAL_OWNER = 3
        const val CALL_SERIAL = 4
        const val CLOSE_SERIAL = 5

        private val BUS = Destination("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")
        private val PORTAL = Destination(
            FileChooserPortal.SERVICE,
            FileChooserPortal.OBJECT_PATH,
            FileChooserPortal.INTERFACE,
        )
    }
}

/**
 * File dialogs through the portal for the windows of one kind [W], free of any window system.
 *
 * The queueing rules are [FileDialogLanes]: one dialog at a time per window, the rest wait in
 * order. Each dialog is a [PortalFileRequest] on a connection [openBus] makes for it. The window
 * layer supplies the rest: [owner] is the window a request attaches to (null when it is gone),
 * [parentWindow] is how the portal is told about that window (`x11:<id in hex>`; null when the
 * window can no longer be named), [nowMillis] is a monotonic clock, and it calls [pump] once a
 * turn of its loop. Outcomes are delivered from [show], [pump] or [cancelAll], so always on the
 * thread that calls them, which must be one thread.
 *
 * Where [openBus] answers null (no session bus) the outcome is Unavailable, as it is where the
 * bus has no portal.
 */
class PortalFileDialogs<W : Any>(
    owner: () -> W?,
    private val parentWindow: (W) -> String?,
    private val openBus: () -> BusConnection?,
    private val nowMillis: () -> Long,
    /** The start of every request token: letters, digits and underscores. */
    private val tokenPrefix: String = "compose",
) : FileDialogs {
    private val presenting = ArrayList<Presenter>()
    private val lanes = FileDialogLanes(owner) { window: W -> Presenter(window) }
    private var requests = 0

    /** The connections of the dialogs open now, for a loop that waits on their sockets. */
    val connections: List<BusConnection> get() = presenting.mapNotNull { it.bus }

    override fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) =
        lanes.show(normalizeFileDialogRequest(request), onResult)

    /** Reads what the open dialogs have been answered, without waiting, and delivers what ended. */
    fun pump() {
        for (presenter in presenting.toList()) presenter.pump()
    }

    /**
     * Ends the open dialogs and every waiting request with Cancelled, each exactly once, and
     * answers Unavailable to anything shown afterwards. Call it when the window is destroyed.
     */
    fun cancelAll() = lanes.cancelAll()

    private inner class Presenter(private val window: W) : SheetPresenter {
        private var call: PortalFileRequest? = null
        private var completion: ((FileDialogOutcome) -> Unit)? = null
        var bus: BusConnection? = null
            private set

        override fun present(request: FileDialogRequest, completion: (FileDialogOutcome) -> Unit) {
            release()
            val parent = parentWindow(window)
            val connection = if (parent == null) null else openBus()
            if (parent == null || connection == null) {
                completion(FileDialogOutcome.Unavailable)
                return
            }
            requests += 1
            val made = PortalFileRequest(connection, request, parent, "${tokenPrefix}_$requests")
            if (!made.start(nowMillis())) {
                made.close()
                completion(FileDialogOutcome.Unavailable)
                return
            }
            call = made
            bus = connection
            this.completion = completion
            presenting.add(this)
        }

        fun pump() {
            val outcome = call?.pump(nowMillis()) ?: return
            val done = completion
            release()
            done?.invoke(outcome)
        }

        override fun dismiss() = release()

        override fun release() {
            call?.close()
            call = null
            bus = null
            completion = null
            presenting.remove(this)
        }
    }
}
