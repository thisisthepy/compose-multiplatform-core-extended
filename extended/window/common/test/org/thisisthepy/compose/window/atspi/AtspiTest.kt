package org.thisisthepy.compose.window.atspi

import kotlin.test.Test
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What a screen reader is told, asked the way a screen reader asks.
//
// Orca and Accerciser reach this process through the registry daemon: they call methods on the
// objects the window exports and listen for the signals it sends. Neither of them can run on
// the machines this is tested on, so the other end of the bus is a fake that does what the
// registry does (answers Hello, names the accessibility bus, accepts the embedding) and a test
// plays the client. What is asserted is the content of the answers, which is the part a
// reader's speech comes from. Whether Orca speaks them is a person at a desktop.

private const val ROOT = AtspiServer.ROOT_PATH
private const val WINDOW = AtspiServer.FRAME_PATH
private fun node(id: Int) = "${AtspiServer.NODE_PREFIX}$id"

/** One fake bus. [answer] is what its daemon says to each call made on it. */
private class FakeBus(private val answer: (DBusMessage) -> ByteArray?) : BusConnection {
    val incoming = ArrayDeque<ByteArray>()
    val sent = mutableListOf<ByteArray>()
    var closed = false

    override fun send(message: ByteArray): Boolean {
        sent += message
        val parsed = DBusMessage.parse(message) ?: return true
        if (parsed.type == DBusMessage.METHOD_CALL) answer(parsed)?.let { incoming.addLast(it) }
        return true
    }

    override fun receive(timeoutMillis: Int): ByteArray? = incoming.removeFirstOrNull()

    override fun close() {
        closed = true
    }

    /** The signals sent so far, with where from and what they said. */
    fun signals(): List<Signal> = sent.mapNotNull { Signal.of(it) }
}

private data class Signal(val path: String, val interfaceName: String, val member: String, val kind: String, val detail: Int) {
    companion object {
        fun of(bytes: ByteArray): Signal? {
            val header = Header.of(bytes) ?: return null
            if (header.type != DBusMessage.SIGNAL) return null
            val reader = header.body
            val kind = reader.string()
            val detail = reader.i32()
            return Signal(header.path ?: "", header.interfaceName ?: "", header.member ?: "", kind, detail)
        }
    }
}

/** The fields of a message, read by hand because the library's own parser does not keep them all. */
private class Header(
    val type: Int,
    val path: String?,
    val interfaceName: String?,
    val member: String?,
    val replySerial: Long?,
    val errorName: String?,
    val body: DBusReader,
) {
    companion object {
        fun of(bytes: ByteArray): Header? {
            val length = dbusMessageLength(bytes) ?: return null
            if (bytes.size < length) return null
            val fieldsEnd = 16 + DBusReader(bytes, 12).u32().toInt()
            val reader = DBusReader(bytes, 16)
            var path: String? = null
            var interfaceName: String? = null
            var member: String? = null
            var reply: Long? = null
            var error: String? = null
            while (reader.position < fieldsEnd) {
                reader.align(8)
                if (reader.position >= fieldsEnd) break
                val code = reader.byte()
                val value: Any = when (reader.signature()) {
                    "s", "o" -> reader.string()
                    "g" -> reader.signature()
                    "u" -> reader.u32()
                    else -> return null
                }
                when (code) {
                    1 -> path = value as String
                    2 -> interfaceName = value as String
                    3 -> member = value as String
                    4 -> error = value as String
                    5 -> reply = value as Long
                }
            }
            return Header(bytes[1].toInt(), path, interfaceName, member, reply, error, DBusReader(bytes, (fieldsEnd + 7) / 8 * 8))
        }
    }
}

private fun message(type: Int, serial: Long, signature: String?, body: ByteArray, fields: DBusWriter.() -> Unit): ByteArray {
    val out = DBusWriter()
    out.byte('l'.code)
    out.byte(type)
    out.byte(0)
    out.byte(1)
    out.u32(body.size.toLong())
    out.u32(serial)
    out.array(8) {
        fields()
        if (signature != null) {
            struct {
                byte(8)
                signature("g")
                signature(signature)
            }
        }
    }
    out.align(8)
    out.raw(body)
    return out.bytes()
}

private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) = struct {
    byte(code)
    signature(signature)
    value()
}

private var nextSerial = 900L

/** What the daemon answers a call with. */
private fun returnTo(call: DBusMessage, signature: String?, body: DBusWriter.() -> Unit): ByteArray =
    message(DBusMessage.METHOD_RETURN, nextSerial++, signature, DBusWriter().apply(body).bytes()) {
        field(5, "u") { u32(call.serial) }
    }

private fun failTo(call: DBusMessage): ByteArray =
    message(DBusMessage.ERROR, nextSerial++, null, ByteArray(0)) {
        field(4, "s") { string("org.freedesktop.DBus.Error.ServiceUnknown") }
        field(5, "u") { u32(call.serial) }
    }

/** A call from a client, as the registry or Orca would make it. */
private fun clientCall(
    serial: Long,
    path: String,
    interfaceName: String,
    member: String,
    signature: String? = null,
    body: DBusWriter.() -> Unit = {},
    noReply: Boolean = false,
): ByteArray {
    val content = DBusWriter().apply(body).bytes()
    val out = DBusWriter()
    out.byte('l'.code)
    out.byte(DBusMessage.METHOD_CALL)
    out.byte(if (noReply) 1 else 0)
    out.byte(1)
    out.u32(content.size.toLong())
    out.u32(serial)
    out.array(8) {
        field(1, "o") { string(path) }
        field(2, "s") { string(interfaceName) }
        field(3, "s") { string(member) }
        field(7, "s") { string(":1.99") }
        if (signature != null) field(8, "g") { signature(signature) }
    }
    out.align(8)
    out.raw(content)
    return out.bytes()
}

private class Harness(
    sessionAnswers: Boolean = true,
    registryAnswers: Boolean = true,
    /** What the session bus says of `org.a11y.Status.IsEnabled`, or null for no answer. */
    statusEnabled: Boolean? = null,
) {
    /** Every action a reader asked for: the control, the action and its argument. */
    val performed = mutableListOf<Triple<Int, Int, String?>>()
    val clicked: List<Int> get() = performed.filter { it.second == AccessibleAction.PRESS }.map { it.first }
    val focusRequests: List<Int> get() = performed.filter { it.second == AccessibleAction.FOCUS }.map { it.first }
    var origin = 100 to 50

    val a11y = FakeBus { call ->
        when {
            call.interfaceName == "org.freedesktop.DBus" && call.member == "Hello" ->
                returnTo(call, "s") { string(":1.42") }
            call.member == "Embed" && registryAnswers -> {
                val reader = call.body()
                reader.align(8)
                embedded = reader.string() to reader.string()
                returnTo(call, "(so)") { reference(":1.1", "/org/a11y/atspi/accessible/root") }
            }
            else -> failTo(call)
        }
    }

    val session = FakeBus { call ->
        when {
            call.member == "Hello" -> returnTo(call, "s") { string(":1.7") }
            call.member == "GetAddress" && sessionAnswers ->
                returnTo(call, "s") { string("unix:path=/run/user/1000/at-spi/bus_0,guid=abc") }
            call.member == "Get" && statusEnabled != null -> {
                val reader = call.body()
                reader.string()
                val name = reader.string()
                returnTo(call, "v") {
                    variant("b") { boolean(statusEnabled && name == "IsEnabled") }
                }
            }
            else -> failTo(call)
        }
    }

    var embedded: Pair<String, String>? = null
    var openedAddress: BusAddress? = null

    private val actions = object : AtspiActions {
        override fun perform(id: Int, action: Int, argument: String?): Boolean {
            performed += Triple(id, action, argument)
            return true
        }

        override fun windowOrigin() = origin
    }

    val bridge = AtspiBridge(
        openSession = { session },
        openAccessibility = { address ->
            openedAddress = address
            a11y
        },
        userId = 1000,
        applicationName = "notepad",
        actions = actions,
    )

    private var serial = 1000L

    /** Asks the window something and returns what it answered. */
    fun ask(
        path: String,
        interfaceName: String,
        member: String,
        signature: String? = null,
        body: DBusWriter.() -> Unit = {},
    ): Header {
        val id = serial++
        a11y.incoming.addLast(clientCall(id, path, interfaceName, member, signature, body))
        bridge.pump()
        return a11y.sent.mapNotNull { Header.of(it) }.last { it.replySerial == id }
    }

    fun references(reply: Header): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        reply.body.array(8) {
            reply.body.align(8)
            found += reply.body.string() to reply.body.string()
        }
        return found
    }
}

/** The records of a form: a heading, a text field with something in it, a checkbox and a button. */
private fun formElements(focused: Int? = null, checked: Boolean = false): List<AccessibleElement> {
    val enabled = AccessibleState.ENABLED or AccessibleState.FOCUSABLE
    fun focus(id: Int) = if (focused == id) AccessibleState.FOCUSED else 0
    return listOf(
        AccessibleElement(ElementRole.HEADING, 10f, 5f, 100f, 20f, "Profile", id = 10),
        AccessibleElement(
            ElementRole.FIELD, 10f, 30f, 200f, 24f, "Name", id = 11, value = "한글",
            states = enabled or AccessibleState.EDITABLE or focus(11),
            actions = AccessibleAction.FOCUS or AccessibleAction.SET_VALUE,
        ),
        AccessibleElement(
            ElementRole.CHECKBOX, 10f, 60f, 120f, 24f, "Remember", id = 12,
            states = enabled or AccessibleState.CHECKABLE or (if (checked) AccessibleState.CHECKED else 0),
            actions = AccessibleAction.PRESS,
        ),
        AccessibleElement(
            ElementRole.BUTTON, 10f, 90f, 80f, 32f, "Save", id = 13, hint = "Saves the profile",
            states = enabled, actions = AccessibleAction.PRESS or AccessibleAction.FOCUS,
        ),
    )
}

/** The form as a reader gets it. */
private fun form(focused: Int? = null, checked: Boolean = false): AtspiTree =
    atspiTreeOf("Profile", formElements(focused, checked), 1f, 400, 300)

private fun element(
    role: Int,
    label: String = "x",
    states: Int = AccessibleState.ENABLED,
    actions: Int = 0,
    value: String? = null,
) = AccessibleElement(role, 0f, 0f, 10f, 10f, label, id = 1, value = value, states = states, actions = actions)

private fun Description.has(state: Int) = states and AtspiState.bit(state) != 0L

class AtspiTest {

    // ---- the rule --------------------------------------------------------------------

    @Test
    fun nfr8_linux_the_roles_are_the_registrys_own_numbers() {
        // From atspi-constants.h. A role off by one is a button announced as something else.
        assertEquals(43, AtspiRole.PUSH_BUTTON)
        assertEquals(79, AtspiRole.ENTRY)
        assertEquals(29, AtspiRole.LABEL)
        assertEquals(23, AtspiRole.FRAME)
        assertEquals(75, AtspiRole.APPLICATION)
        assertEquals(7, AtspiRole.CHECK_BOX)
        assertEquals("push button", AtspiRole.nameOf(AtspiRole.PUSH_BUTTON))
    }

    @Test
    fun nfr8_linux_the_new_roles_and_states_are_the_registrys_own_numbers() {
        assertEquals(88, AtspiRole.LINK)
        assertEquals(41, AtspiState.CHECKABLE)
        assertEquals(43, AtspiState.READ_ONLY)
        assertEquals(32, AtspiState.INDETERMINATE)
    }

    @Test
    fun nfr8_linux_a_button_is_a_push_button_named_by_its_text_that_can_be_clicked() {
        val described = describeElement(
            element(ElementRole.BUTTON, "Save", AccessibleState.ENABLED or AccessibleState.FOCUSABLE, AccessibleAction.PRESS),
        )
        assertEquals(AtspiRole.PUSH_BUTTON, described.role)
        assertEquals("Save", described.name)
        assertEquals(listOf(ACTION_CLICK), described.actions)
        assertTrue(described.has(AtspiState.FOCUSABLE))
        assertTrue(described.has(AtspiState.ENABLED))
        assertTrue(described.has(AtspiState.SENSITIVE))
    }

    @Test
    fun nfr8_linux_a_disabled_button_is_not_sensitive_and_offers_no_action() {
        val described = describeElement(element(ElementRole.BUTTON, "Save", states = 0, actions = AccessibleAction.PRESS))
        assertFalse(described.has(AtspiState.ENABLED))
        assertFalse(described.has(AtspiState.SENSITIVE))
        assertTrue(described.actions.isEmpty())
    }

    @Test
    fun nfr8_linux_every_role_the_scene_names_has_its_atspi_role() {
        val expected = mapOf(
            ElementRole.BUTTON to AtspiRole.PUSH_BUTTON,
            ElementRole.TEXT to AtspiRole.LABEL,
            ElementRole.FIELD to AtspiRole.ENTRY,
            ElementRole.CHECKBOX to AtspiRole.CHECK_BOX,
            ElementRole.IMAGE to AtspiRole.IMAGE,
            ElementRole.SWITCH to AtspiRole.TOGGLE_BUTTON,
            ElementRole.RADIO_BUTTON to AtspiRole.RADIO_BUTTON,
            ElementRole.TAB to AtspiRole.PAGE_TAB,
            ElementRole.SLIDER to AtspiRole.SLIDER,
            ElementRole.PROGRESS to AtspiRole.PROGRESS_BAR,
            ElementRole.HEADING to AtspiRole.HEADING,
            ElementRole.COMBO_BOX to AtspiRole.COMBO_BOX,
            ElementRole.LINK to AtspiRole.LINK,
            ElementRole.GROUP to AtspiRole.PANEL,
        )
        for ((role, atspi) in expected) assertEquals(atspi, describeElement(element(role)).role, "role $role")
        assertEquals(AtspiRole.FILLER, describeElement(element(ElementRole.GROUP, label = "")).role)
    }

    @Test
    fun nfr8_linux_a_field_is_an_editable_entry_and_a_password_field_says_so() {
        val field = describeElement(
            element(ElementRole.FIELD, "Name", AccessibleState.ENABLED or AccessibleState.EDITABLE or AccessibleState.FOCUSED),
        )
        assertEquals(AtspiRole.ENTRY, field.role)
        assertTrue(field.has(AtspiState.EDITABLE))
        assertTrue(field.has(AtspiState.SINGLE_LINE))
        assertTrue(field.has(AtspiState.FOCUSED))
        val password = describeElement(element(ElementRole.FIELD, states = AccessibleState.ENABLED or AccessibleState.PASSWORD))
        assertEquals(AtspiRole.PASSWORD_TEXT, password.role)
        val many = describeElement(element(ElementRole.FIELD, states = AccessibleState.ENABLED or AccessibleState.MULTI_LINE))
        assertTrue(many.has(AtspiState.MULTI_LINE))
        val readOnly = describeElement(element(ElementRole.FIELD, states = AccessibleState.ENABLED or AccessibleState.READ_ONLY))
        assertTrue(readOnly.has(AtspiState.READ_ONLY))
        assertFalse(readOnly.has(AtspiState.EDITABLE), "a read-only field cannot be edited")
    }

    @Test
    fun nfr8_linux_a_checkbox_reports_whether_it_is_checked() {
        val checkable = AccessibleState.ENABLED or AccessibleState.CHECKABLE
        val on = describeElement(element(ElementRole.CHECKBOX, states = checkable or AccessibleState.CHECKED))
        val off = describeElement(element(ElementRole.CHECKBOX, states = checkable))
        val partial = describeElement(element(ElementRole.CHECKBOX, states = checkable or AccessibleState.MIXED))
        assertEquals(AtspiRole.CHECK_BOX, on.role)
        assertTrue(on.has(AtspiState.CHECKABLE))
        assertTrue(on.has(AtspiState.CHECKED))
        assertTrue(off.has(AtspiState.CHECKABLE))
        assertFalse(off.has(AtspiState.CHECKED))
        assertTrue(partial.has(AtspiState.INDETERMINATE))
        val notCheckable = describeElement(element(ElementRole.BUTTON, states = AccessibleState.ENABLED or AccessibleState.CHECKED))
        assertFalse(notCheckable.has(AtspiState.CHECKED), "checked says nothing without checkable")
    }

    @Test
    fun nfr8_linux_a_tab_is_selectable_and_says_whether_it_is_selected() {
        val tab = describeElement(element(ElementRole.TAB, states = AccessibleState.ENABLED or AccessibleState.SELECTED))
        assertTrue(tab.has(AtspiState.SELECTABLE))
        assertTrue(tab.has(AtspiState.SELECTED))
        assertFalse(describeElement(element(ElementRole.TAB)).has(AtspiState.SELECTED))
    }

    @Test
    fun nfr8_linux_each_action_the_scene_offers_has_its_atspi_name() {
        val all = AccessibleAction.PRESS or AccessibleAction.INCREMENT or AccessibleAction.DECREMENT or
            AccessibleAction.SCROLL_FORWARD or AccessibleAction.SCROLL_BACKWARD or AccessibleAction.SHOW_MENU or
            AccessibleAction.FOCUS or AccessibleAction.SET_VALUE
        val names = describeElement(element(ElementRole.SLIDER, actions = all)).actions
        assertEquals(listOf("click", "increment", "decrement", "scroll-forward", "scroll-backward", "show-menu"), names)
        for (name in names) assertTrue(accessibleActionNamed(name) != null, name)
        assertEquals(AccessibleAction.INCREMENT, accessibleActionNamed("increment"))
        assertNull(accessibleActionNamed("explode"))
    }

    @Test
    fun nfr8_linux_records_nest_by_their_parent_ids() {
        val tree = atspiTreeOf(
            "w",
            listOf(
                AccessibleElement(ElementRole.GROUP, 0f, 0f, 100f, 100f, "card", id = 1),
                AccessibleElement(ElementRole.BUTTON, 10f, 10f, 20f, 20f, "ok", id = 2, parentId = 1),
                AccessibleElement(ElementRole.TEXT, 10f, 40f, 20f, 20f, "hi", id = 3, parentId = 1),
                AccessibleElement(ElementRole.TEXT, 0f, 200f, 20f, 20f, "after", id = 4),
            ),
            1f, 300, 300,
        )
        assertEquals(listOf(1, 4), tree.roots)
        assertEquals(listOf(2, 3), tree.nodes.getValue(1).children)
        assertEquals(1, tree.nodes.getValue(2).parent)
        assertEquals(AtspiTree.FRAME, tree.nodes.getValue(4).parent)
    }

    @Test
    fun nfr8_linux_a_record_whose_parent_is_missing_or_a_loop_is_put_in_the_window() {
        val tree = atspiTreeOf(
            "w",
            listOf(
                AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "orphan", id = 1, parentId = 99),
                AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "a", id = 2, parentId = 3),
                AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "b", id = 3, parentId = 2),
                AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "self", id = 4, parentId = 4),
            ),
            1f, 10, 10,
        )
        assertEquals(setOf(1, 2, 3, 4), tree.roots.toSet(), "nothing is dropped and nothing loops")
        for (node in tree.nodes.values) assertEquals(AtspiTree.FRAME, node.parent)
    }

    @Test
    fun nfr8_linux_records_with_no_id_are_numbered_apart_from_the_scenes() {
        val tree = atspiTreeOf(
            "w",
            listOf(
                AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "a"),
                AccessibleElement(ElementRole.TEXT, 0f, 0f, 1f, 1f, "b"),
            ),
            1f, 10, 10,
        )
        assertEquals(listOf(UNNAMED_BASE, UNNAMED_BASE + 1), tree.roots)
    }

    @Test
    fun nfr8_linux_points_become_pixels_at_the_windows_scale() {
        val tree = atspiTreeOf("w", listOf(AccessibleElement(ElementRole.BUTTON, 10f, 20f, 30f, 40f, "b", id = 5)), 2f, 800, 600)
        val node = tree.nodes.getValue(5)
        assertEquals(listOf(20, 40, 60, 80), listOf(node.x, node.y, node.width, node.height))
        assertEquals(800, tree.width)
    }

    @Test
    fun nfr8_linux_the_focused_record_is_the_trees_focus() {
        assertEquals(11, form(focused = 11).focused)
        assertNull(form().focused)
    }

    @Test
    fun nfr8_linux_a_field_holds_its_value_and_a_range_its_numbers() {
        val tree = atspiTreeOf(
            "w",
            listOf(
                AccessibleElement(
                    ElementRole.SLIDER, 0f, 0f, 10f, 10f, "Volume", id = 1, value = "40 percent",
                    actions = AccessibleAction.SET_VALUE, rangeMin = 0f, rangeMax = 100f, rangeValue = 40f,
                ),
            ),
            1f, 10, 10,
        )
        val slider = tree.nodes.getValue(1)
        assertNull(slider.text, "a slider is not a field")
        assertEquals(AtspiRange(0.0, 100.0, 40.0, "40 percent"), slider.range)
        assertTrue(slider.settable)
        assertEquals("한글", form().nodes.getValue(11).text)
    }

    @Test
    fun nfr8_linux_the_deepest_control_under_a_point_is_found() {
        val tree = form()
        assertEquals(11, tree.nodeAt(20, 40))
        assertEquals(13, tree.nodeAt(15, 100))
        assertNull(tree.nodeAt(390, 290))
    }

    // ---- joining the bus -------------------------------------------------------------

    @Test
    fun nfr8_linux_the_window_joins_the_accessibility_bus_the_session_bus_names() {
        val harness = Harness()
        assertTrue(harness.bridge.start())
        assertEquals(":1.42", harness.bridge.busName)
        assertEquals(
            BusAddress("/run/user/1000/at-spi/bus_0", abstract = false),
            harness.openedAddress,
            "the address the session bus gave is where the window connects",
        )
        assertEquals(
            ":1.42" to ROOT,
            harness.embedded,
            "the registry is told this process's name on its bus and where the application object is",
        )
        assertTrue(harness.session.closed, "the session bus is not needed after it has named the other")
        assertTrue(harness.bridge.connected)
    }

    @Test
    fun nfr8_linux_a_desktop_with_no_accessibility_bus_is_left_alone() {
        val harness = Harness(sessionAnswers = false)
        assertFalse(harness.bridge.start())
        assertFalse(harness.bridge.connected)
        assertNull(harness.openedAddress)
        val noSession = AtspiBridge({ null }, { null }, 1000, "x", object : AtspiActions {
            override fun perform(id: Int, action: Int, argument: String?) = false
            override fun windowOrigin() = 0 to 0
        })
        assertFalse(noSession.start())
    }

    @Test
    fun nfr8_linux_a_registry_that_will_not_take_the_window_is_not_joined() {
        val harness = Harness(registryAnswers = false)
        assertFalse(harness.bridge.start())
        assertTrue(harness.a11y.closed)
    }

    // ---- what a client is told -------------------------------------------------------

    private fun joined(): Harness = Harness().also {
        assertTrue(it.bridge.start())
        it.bridge.update(form())
    }

    @Test
    fun nfr8_linux_a_reader_walks_from_the_application_to_the_window_to_the_controls() {
        val harness = joined()
        val root = harness.ask(ROOT, AtspiServer.ACCESSIBLE, "GetChildren")
        assertEquals(listOf(":1.42" to WINDOW), harness.references(root))
        val window = harness.ask(WINDOW, AtspiServer.ACCESSIBLE, "GetChildren")
        assertEquals((10..13).map { ":1.42" to node(it) }, harness.references(window))
    }

    @Test
    fun nfr8_linux_the_application_and_window_have_their_roles_and_names() {
        val harness = joined()
        fun role(path: String) = harness.ask(path, AtspiServer.ACCESSIBLE, "GetRole").body.u32()
        assertEquals(AtspiRole.APPLICATION.toLong(), role(ROOT))
        assertEquals(AtspiRole.FRAME.toLong(), role(WINDOW))
        fun name(path: String): String {
            val reply = harness.ask(path, "org.freedesktop.DBus.Properties", "Get", "ss") {
                string(AtspiServer.ACCESSIBLE)
                string("Name")
            }
            val reader = reply.body
            assertEquals("s", reader.signature())
            return reader.string()
        }
        assertEquals("notepad", name(ROOT))
        assertEquals("Profile", name(WINDOW))
        assertEquals("Save", name(node(13)))
    }

    @Test
    fun nfr8_linux_a_control_answers_its_role_its_states_and_its_actions() {
        val harness = joined()
        assertEquals(AtspiRole.PUSH_BUTTON.toLong(), harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetRole").body.u32())
        val states = harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetState").body
        val words = mutableListOf<Long>()
        states.array(4) { words += states.u32() }
        assertEquals(2, words.size, "states are two 32-bit words")
        assertTrue(words[0] and (1L shl AtspiState.FOCUSABLE) != 0L)
        assertTrue(words[0] and (1L shl AtspiState.ENABLED) != 0L)
        assertEquals(1L, harness.ask(node(13), AtspiServer.ACTION, "GetNActions").body.u32())
        val name = harness.ask(node(13), AtspiServer.ACTION, "GetName", "i") { u32(0) }.body.string()
        assertEquals(ACTION_CLICK, name)
        val interfaces = mutableListOf<String>()
        val reply = harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetInterfaces").body
        reply.array(4) { interfaces += reply.string() }
        assertTrue(AtspiServer.ACTION in interfaces)
        assertTrue(AtspiServer.COMPONENT in interfaces)
        assertFalse(AtspiServer.TEXT in interfaces, "a button has no text interface")
    }

    @Test
    fun nfr8_linux_doing_the_action_clicks_the_control() {
        val harness = joined()
        val done = harness.ask(node(13), AtspiServer.ACTION, "DoAction", "i") { u32(0) }.body.u32()
        assertEquals(1L, done)
        assertEquals(listOf(13), harness.clicked)
    }

    @Test
    fun nfr8_linux_grabbing_focus_asks_the_control_to_take_it() {
        val harness = joined()
        assertEquals(1L, harness.ask(node(11), AtspiServer.COMPONENT, "GrabFocus").body.u32())
        assertEquals(listOf(11), harness.focusRequests)
    }

    @Test
    fun nfr8_linux_a_text_field_gives_what_it_holds_and_where_the_caret_is() {
        val harness = joined()
        assertEquals(
            "한글",
            harness.ask(node(11), AtspiServer.TEXT, "GetText", "ii") { u32(0); u32(0xffff_ffffL) }.body.string(),
        )
        assertEquals(
            "글",
            harness.ask(node(11), AtspiServer.TEXT, "GetText", "ii") { u32(1); u32(2) }.body.string(),
            "offsets count characters",
        )
        assertEquals(2L, harness.ask(node(11), AtspiServer.TEXT, "GetCaretOffset").body.u32())
        val count = harness.ask(node(11), "org.freedesktop.DBus.Properties", "Get", "ss") {
            string(AtspiServer.TEXT)
            string("CharacterCount")
        }.body
        assertEquals("i", count.signature())
        assertEquals(2L, count.u32())
    }

    @Test
    fun nfr8_linux_a_field_with_no_text_interface_refuses_the_question_and_the_window_stays_up() {
        val harness = joined()
        val reply = harness.ask(node(13), AtspiServer.TEXT, "GetText", "ii") { u32(0); u32(0) }
        assertEquals(DBusMessage.ERROR, reply.type)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", reply.errorName)
        assertEquals(AtspiRole.PUSH_BUTTON.toLong(), harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetRole").body.u32())
    }

    @Test
    fun nfr8_linux_a_control_that_is_not_there_is_an_unknown_object() {
        val harness = joined()
        val reply = harness.ask(node(99), AtspiServer.ACCESSIBLE, "GetRole")
        assertEquals(DBusMessage.ERROR, reply.type)
        assertEquals("org.freedesktop.DBus.Error.UnknownObject", reply.errorName)
        val other = harness.ask("/somewhere/else", AtspiServer.ACCESSIBLE, "GetRole")
        assertEquals("org.freedesktop.DBus.Error.UnknownObject", other.errorName)
    }

    @Test
    fun nfr8_linux_a_method_the_interface_does_not_have_is_an_unknown_method() {
        val harness = joined()
        val reply = harness.ask(node(13), AtspiServer.ACCESSIBLE, "Explode")
        assertEquals("org.freedesktop.DBus.Error.UnknownMethod", reply.errorName)
    }

    @Test
    fun nfr8_linux_extents_are_in_the_system_the_client_asks_in() {
        val harness = joined()
        fun extents(type: Long): List<Long> {
            val reader = harness.ask(node(13), AtspiServer.COMPONENT, "GetExtents", "u") { u32(type) }.body
            reader.align(8)
            return List(4) { reader.u32() }
        }
        assertEquals(listOf(10L, 90L, 80L, 32L), extents(1), "window coordinates")
        assertEquals(listOf(110L, 140L, 80L, 32L), extents(0), "screen coordinates add the window's place")
        harness.origin = 0 to 0
        assertEquals(listOf(10L, 90L, 80L, 32L), extents(0))
    }

    @Test
    fun nfr8_linux_a_point_finds_the_control_under_it() {
        val harness = joined()
        val reply = harness.ask(WINDOW, AtspiServer.COMPONENT, "GetAccessibleAtPoint", "iiu") {
            u32(120)
            u32(145)
            u32(0)
        }
        reply.body.align(8)
        assertEquals(":1.42" to node(13), reply.body.string() to reply.body.string())
    }

    @Test
    fun nfr8_linux_the_properties_of_a_control_come_back_whole() {
        val harness = joined()
        val reply = harness.ask(node(13), "org.freedesktop.DBus.Properties", "GetAll", "s") {
            string(AtspiServer.ACCESSIBLE)
        }.body
        val names = mutableListOf<String>()
        reply.array(8) {
            reply.align(8)
            names += reply.string()
            val signature = reply.signature()
            when (signature) {
                "s" -> reply.string()
                "i" -> reply.u32()
                "(so)" -> {
                    reply.align(8)
                    reply.string()
                    reply.string()
                }
                else -> error("unexpected $signature")
            }
        }
        assertTrue(names.containsAll(listOf("Name", "Parent", "ChildCount")), names.toString())
    }

    @Test
    fun nfr8_linux_a_call_that_wants_no_answer_gets_none() {
        val harness = joined()
        val before = harness.a11y.sent.size
        harness.a11y.incoming.addLast(
            clientCall(5000, node(13), AtspiServer.ACTION, "DoAction", "i", { u32(0) }, noReply = true),
        )
        harness.bridge.pump()
        assertEquals(before, harness.a11y.sent.size)
        assertEquals(listOf(13), harness.clicked, "the action still happened")
    }

    // ---- what changes -----------------------------------------------------------------

    @Test
    fun nfr8_linux_the_first_tree_is_not_announced_control_by_control() {
        val harness = Harness()
        harness.bridge.start()
        harness.bridge.update(form())
        assertTrue(harness.a11y.signals().isEmpty(), "a client asks a fresh window; nothing was there to change")
    }

    @Test
    fun nfr8_linux_moving_focus_says_which_control_lost_it_and_which_gained_it() {
        val harness = joined()
        harness.bridge.update(form(focused = 11))
        harness.bridge.update(form(focused = null))
        val focus = harness.a11y.signals().filter { it.member == "StateChanged" && it.kind == "focused" }
        assertEquals(
            listOf(node(11) to 1, node(11) to 0),
            focus.map { it.path to it.detail },
        )
        assertTrue(focus.all { it.interfaceName == AtspiServer.EVENT_OBJECT })
    }

    @Test
    fun nfr8_linux_checking_a_box_says_so() {
        val harness = joined()
        harness.bridge.update(form(checked = true))
        val checked = harness.a11y.signals().single { it.kind == "checked" }
        assertEquals(node(12), checked.path)
        assertEquals(1, checked.detail)
    }

    @Test
    fun nfr8_linux_a_control_that_appears_or_goes_is_announced_on_its_parent() {
        val harness = joined()
        val smaller = form().let { tree ->
            tree.copy(nodes = tree.nodes - 13, roots = tree.roots - 13)
        }
        harness.bridge.update(smaller)
        harness.bridge.update(form())
        val changes = harness.a11y.signals().filter { it.member == "ChildrenChanged" }
        assertEquals(listOf("remove" to 3, "add" to 3), changes.map { it.kind to it.detail })
        assertTrue(changes.all { it.path == WINDOW }, "children of the window are announced on the window")
    }

    @Test
    fun nfr8_linux_renaming_a_control_says_so() {
        val harness = joined()
        val tree = form()
        val renamed = tree.copy(nodes = tree.nodes + (13 to tree.nodes.getValue(13).copy(name = "Saved")))
        harness.bridge.update(renamed)
        val change = harness.a11y.signals().single { it.member == "PropertyChange" }
        assertEquals("accessible-name", change.kind)
        assertEquals(node(13), change.path)
    }

    @Test
    fun nfr8_linux_the_window_announces_when_it_gains_and_loses_the_keyboard() {
        val harness = joined()
        harness.bridge.windowActive(true)
        harness.bridge.windowActive(true)
        harness.bridge.windowActive(false)
        val window = harness.a11y.signals().filter { it.interfaceName == AtspiServer.EVENT_WINDOW }
        assertEquals(listOf("Activate", "Deactivate"), window.map { it.member })
        assertTrue(window.all { it.path == WINDOW })
        val active = harness.ask(WINDOW, AtspiServer.ACCESSIBLE, "GetState").body
        val words = mutableListOf<Long>()
        active.array(4) { words += active.u32() }
        assertEquals(0L, words[0] and (1L shl AtspiState.ACTIVE), "deactivated windows are not active")
    }

    @Test
    fun nfr8_linux_an_unchanged_tree_says_nothing() {
        val harness = joined()
        val before = harness.a11y.sent.size
        harness.bridge.update(form())
        assertEquals(before, harness.a11y.sent.size)
        assertTrue(harness.a11y.signals().isEmpty())
    }

    // ---- what the records add ----------------------------------------------------------

    private fun sliderHarness(): Harness = Harness().also {
        assertTrue(it.bridge.start())
        it.bridge.update(
            atspiTreeOf(
                "w",
                formElements() + AccessibleElement(
                    ElementRole.SLIDER, 10f, 130f, 100f, 20f, "Volume", id = 14, value = "40 percent",
                    actions = AccessibleAction.SET_VALUE or AccessibleAction.INCREMENT,
                    rangeMin = 0f, rangeMax = 100f, rangeValue = 40f,
                ),
                1f, 400, 300,
            ),
        )
    }

    private fun Harness.interfaces(path: String): List<String> {
        val reply = ask(path, AtspiServer.ACCESSIBLE, "GetInterfaces").body
        val names = mutableListOf<String>()
        reply.array(4) { names += reply.string() }
        return names
    }

    private fun Harness.property(path: String, interfaceName: String, name: String): DBusReader =
        ask(path, "org.freedesktop.DBus.Properties", "Get", "ss") {
            string(interfaceName)
            string(name)
        }.body

    @Test
    fun nfr8_linux_a_field_a_reader_may_edit_has_editable_text_and_its_edits_reach_the_scene() {
        val harness = joined()
        assertTrue(AtspiServer.EDITABLE_TEXT in harness.interfaces(node(11)))
        assertFalse(AtspiServer.EDITABLE_TEXT in harness.interfaces(node(13)))
        assertEquals(1L, harness.ask(node(11), AtspiServer.EDITABLE_TEXT, "SetTextContents", "s") { string("abc") }.body.u32())
        harness.ask(node(11), AtspiServer.EDITABLE_TEXT, "InsertText", "isi") { u32(1); string("x"); u32(1) }
        harness.ask(node(11), AtspiServer.EDITABLE_TEXT, "DeleteText", "ii") { u32(0); u32(1) }
        assertEquals(
            listOf(
                Triple(11, AccessibleAction.SET_VALUE, "abc"),
                Triple(11, AccessibleAction.SET_VALUE, "한x글"),
                Triple(11, AccessibleAction.SET_VALUE, "글"),
            ),
            harness.performed,
            "every edit is said to the scene as the field's whole new text, counted in characters",
        )
    }

    @Test
    fun nfr8_linux_a_range_answers_through_value_and_takes_a_new_one() {
        val harness = sliderHarness()
        assertTrue(AtspiServer.VALUE in harness.interfaces(node(14)))
        val current = harness.property(node(14), AtspiServer.VALUE, "CurrentValue")
        assertEquals("d", current.signature())
        assertEquals(40.0, current.double())
        val maximum = harness.property(node(14), AtspiServer.VALUE, "MaximumValue")
        maximum.signature()
        assertEquals(100.0, maximum.double())
        val text = harness.property(node(14), AtspiServer.VALUE, "Text")
        text.signature()
        assertEquals("40 percent", text.string())
        val set = harness.ask(node(14), "org.freedesktop.DBus.Properties", "Set", "ssv") {
            string(AtspiServer.VALUE)
            string("CurrentValue")
            variant("d") { double(55.0) }
        }
        assertEquals(DBusMessage.METHOD_RETURN, set.type)
        assertEquals(listOf(Triple(14, AccessibleAction.SET_VALUE, "55.0")), harness.performed)
        val increment = harness.ask(node(14), AtspiServer.ACTION, "DoAction", "i") { u32(0) }.body.u32()
        assertEquals(1L, increment)
        assertEquals(Triple(14, AccessibleAction.INCREMENT, null), harness.performed.last())
    }

    @Test
    fun nfr8_linux_a_value_that_changes_is_announced() {
        val harness = sliderHarness()
        harness.bridge.update(
            atspiTreeOf(
                "w",
                formElements() + AccessibleElement(
                    ElementRole.SLIDER, 10f, 130f, 100f, 20f, "Volume", id = 14,
                    actions = AccessibleAction.SET_VALUE, rangeMin = 0f, rangeMax = 100f, rangeValue = 60f,
                ),
                1f, 400, 300,
            ),
        )
        val change = harness.a11y.signals().single { it.kind == "accessible-value" }
        assertEquals(node(14), change.path)
    }

    @Test
    fun nfr8_linux_the_hint_is_the_description() {
        val harness = joined()
        val description = harness.property(node(13), AtspiServer.ACCESSIBLE, "Description")
        description.signature()
        assertEquals("Saves the profile", description.string())
    }

    @Test
    fun nfr8_linux_focus_is_asked_only_of_a_control_that_can_take_it() {
        val harness = joined()
        assertEquals(0L, harness.ask(node(10), AtspiServer.COMPONENT, "GrabFocus").body.u32(), "a heading takes no focus")
        assertTrue(harness.focusRequests.isEmpty())
    }

    @Test
    fun nfr8_linux_a_reader_is_listening_once_it_asks_or_the_desktop_says_so() {
        val quiet = Harness()
        assertTrue(quiet.bridge.start())
        assertFalse(quiet.bridge.readerListening, "nobody has asked and the desktop said nothing")
        quiet.ask(ROOT, AtspiServer.ACCESSIBLE, "GetChildren")
        assertTrue(quiet.bridge.readerListening, "a question is a reader")
        val enabled = Harness(statusEnabled = true)
        assertTrue(enabled.bridge.start())
        assertTrue(enabled.bridge.readerListening, "org.a11y.Status says assistive technology is on")
        assertFalse(Harness(sessionAnswers = false).bridge.readerListening)
    }
}
