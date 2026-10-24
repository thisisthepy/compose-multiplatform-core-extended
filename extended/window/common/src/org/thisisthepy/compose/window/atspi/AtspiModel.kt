package org.thisisthepy.compose.window.atspi

import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.codePointsOf

// What the window tells a reader who cannot see it, in the words AT-SPI uses.
//
// Orca and Accerciser read a desktop through the accessibility registry, and what they ask a
// window is a tree of objects with a role, a name, a set of states and a list of actions. This
// file is that vocabulary and the rule that turns the scene's description of a control (an
// [AccessibleElement], the record every platform is handed) into it. It names neither Compose
// nor the bus, which is what makes the rule checkable: the scene fills in the records,
// `AtspiServer.kt` answers the bus from an [AtspiTree], and the arithmetic in between is here.
//
// The numbers are the ones in `atspi-constants.h`, which is the registry's own list. A role
// off by one is a button announced as something else, and the control still looks right, so
// they are written out here and asserted by name in the tests.

/** The roles this window announces. The values are `AtspiRole`'s. */
object AtspiRole {
    const val CHECK_BOX = 7
    const val COMBO_BOX = 11
    const val FILLER = 20
    const val FRAME = 23
    const val IMAGE = 27
    const val LABEL = 29
    const val LIST = 31
    const val LIST_ITEM = 32
    const val PAGE_TAB = 37
    const val PANEL = 39
    const val PASSWORD_TEXT = 40
    const val PROGRESS_BAR = 42
    const val PUSH_BUTTON = 43
    const val RADIO_BUTTON = 44
    const val SLIDER = 51
    const val TOGGLE_BUTTON = 62
    const val APPLICATION = 75
    const val ENTRY = 79
    const val HEADING = 83
    const val LINK = 88

    /** The name a reader speaks for a role, which is `atspi_role_get_name`'s. */
    fun nameOf(role: Int): String = when (role) {
        CHECK_BOX -> "check box"
        COMBO_BOX -> "combo box"
        FILLER -> "filler"
        FRAME -> "frame"
        IMAGE -> "image"
        LABEL -> "label"
        LIST -> "list"
        LIST_ITEM -> "list item"
        PAGE_TAB -> "page tab"
        PANEL -> "panel"
        PASSWORD_TEXT -> "password text"
        PROGRESS_BAR -> "progress bar"
        PUSH_BUTTON -> "push button"
        RADIO_BUTTON -> "radio button"
        SLIDER -> "slider"
        TOGGLE_BUTTON -> "toggle button"
        APPLICATION -> "application"
        ENTRY -> "text"
        HEADING -> "heading"
        LINK -> "link"
        else -> "unknown"
    }
}

/** The states this window reports, as bit positions. The values are `AtspiStateType`'s. */
object AtspiState {
    const val ACTIVE = 1
    const val CHECKED = 4
    const val EDITABLE = 7
    const val ENABLED = 8
    const val FOCUSABLE = 11
    const val FOCUSED = 12
    const val MULTI_LINE = 17
    const val SELECTABLE = 22
    const val SELECTED = 23
    const val SENSITIVE = 24
    const val SHOWING = 25
    const val SINGLE_LINE = 26
    const val VISIBLE = 30
    const val INDETERMINATE = 32
    const val CHECKABLE = 41
    const val READ_ONLY = 43

    fun bit(state: Int): Long = 1L shl state

    /** The name of a state in an event, which is what a listener subscribes to. */
    fun nameOf(state: Int): String = when (state) {
        ACTIVE -> "active"
        CHECKED -> "checked"
        EDITABLE -> "editable"
        ENABLED -> "enabled"
        FOCUSABLE -> "focusable"
        FOCUSED -> "focused"
        SELECTED -> "selected"
        SENSITIVE -> "sensitive"
        SHOWING -> "showing"
        VISIBLE -> "visible"
        INDETERMINATE -> "indeterminate"
        CHECKABLE -> "checkable"
        READ_ONLY -> "read-only"
        else -> "state-$state"
    }
}

/** Pressing: what a tap does. The name GTK and Qt give it, so a reader's command for it works. */
const val ACTION_CLICK = "click"

/**
 * The names of the Action interface's actions, in the order a control lists them, with the
 * [AccessibleAction] each one asks of the scene. Focus is not among them: a reader asks for it
 * through the Component interface's `GrabFocus`. A new value is not either: it is set through
 * EditableText for a field and through Value for a range.
 */
private val NAMED_ACTIONS = listOf(
    ACTION_CLICK to AccessibleAction.PRESS,
    "increment" to AccessibleAction.INCREMENT,
    "decrement" to AccessibleAction.DECREMENT,
    "scroll-forward" to AccessibleAction.SCROLL_FORWARD,
    "scroll-backward" to AccessibleAction.SCROLL_BACKWARD,
    "show-menu" to AccessibleAction.SHOW_MENU,
)

/** The [AccessibleAction] an Action interface name asks for, or null for one this window does not offer. */
fun accessibleActionNamed(name: String): Int? = NAMED_ACTIONS.firstOrNull { it.first == name }?.second

/** What a control is announced as. */
data class Description(
    val role: Int,
    val name: String,
    val states: Long,
    val actions: List<String>,
)

/**
 * The rule: what a reader is told about one control the scene described.
 *
 * Every record is kept. The scene has already left out what is layout and nothing else, and a
 * group it kept is one it nests other controls in, so leaving it out would leave them without a
 * parent. A group with nothing to say is a filler, which a reader passes through.
 */
fun describeElement(element: AccessibleElement): Description {
    val states = element.states
    fun has(bit: Int) = states and bit != 0
    val editable = has(AccessibleState.EDITABLE) || element.role == ElementRole.FIELD
    val role = when (element.role) {
        ElementRole.BUTTON -> AtspiRole.PUSH_BUTTON
        ElementRole.TEXT -> AtspiRole.LABEL
        ElementRole.FIELD -> if (has(AccessibleState.PASSWORD)) AtspiRole.PASSWORD_TEXT else AtspiRole.ENTRY
        ElementRole.CHECKBOX -> AtspiRole.CHECK_BOX
        ElementRole.IMAGE -> AtspiRole.IMAGE
        ElementRole.SWITCH -> AtspiRole.TOGGLE_BUTTON
        ElementRole.RADIO_BUTTON -> AtspiRole.RADIO_BUTTON
        ElementRole.TAB -> AtspiRole.PAGE_TAB
        ElementRole.SLIDER -> AtspiRole.SLIDER
        ElementRole.PROGRESS -> AtspiRole.PROGRESS_BAR
        ElementRole.HEADING -> AtspiRole.HEADING
        ElementRole.COMBO_BOX -> AtspiRole.COMBO_BOX
        ElementRole.LINK -> AtspiRole.LINK
        else -> if (element.label.isEmpty() && element.actions == 0) AtspiRole.FILLER else AtspiRole.PANEL
    }
    var atspi = AtspiState.bit(AtspiState.SHOWING) or AtspiState.bit(AtspiState.VISIBLE)
    val enabled = has(AccessibleState.ENABLED)
    if (enabled) atspi = atspi or AtspiState.bit(AtspiState.ENABLED) or AtspiState.bit(AtspiState.SENSITIVE)
    if (has(AccessibleState.FOCUSABLE) || element.actions and AccessibleAction.FOCUS != 0) {
        atspi = atspi or AtspiState.bit(AtspiState.FOCUSABLE)
    }
    if (has(AccessibleState.FOCUSED)) atspi = atspi or AtspiState.bit(AtspiState.FOCUSED)
    if (has(AccessibleState.CHECKABLE)) {
        atspi = atspi or AtspiState.bit(AtspiState.CHECKABLE)
        if (has(AccessibleState.CHECKED)) atspi = atspi or AtspiState.bit(AtspiState.CHECKED)
        if (has(AccessibleState.MIXED)) atspi = atspi or AtspiState.bit(AtspiState.INDETERMINATE)
    }
    if (has(AccessibleState.SELECTED) || element.role == ElementRole.TAB || element.role == ElementRole.RADIO_BUTTON) {
        atspi = atspi or AtspiState.bit(AtspiState.SELECTABLE)
        if (has(AccessibleState.SELECTED)) atspi = atspi or AtspiState.bit(AtspiState.SELECTED)
    }
    if (editable) {
        if (!has(AccessibleState.READ_ONLY)) atspi = atspi or AtspiState.bit(AtspiState.EDITABLE)
        atspi = atspi or AtspiState.bit(if (has(AccessibleState.MULTI_LINE)) AtspiState.MULTI_LINE else AtspiState.SINGLE_LINE)
    }
    if (has(AccessibleState.READ_ONLY)) atspi = atspi or AtspiState.bit(AtspiState.READ_ONLY)
    // A control that is not enabled offers nothing, whatever it would do when it is.
    val actions = if (!enabled) emptyList() else NAMED_ACTIONS.filter { element.actions and it.second != 0 }.map { it.first }
    return Description(role, element.label, atspi, actions)
}

/**
 * The tree for one window, from the records the scene pushed.
 *
 * Positions arrive in window points and leave in the window's pixels, [scale] to a point,
 * because AT-SPI's extents are pixels on the screen. A record whose parent is not among them
 * (or which, through its parents, is inside itself) is put directly in the window rather than
 * dropped, so a description that is wrong about nesting still says everything it says. A record
 * with no id gets one past any id Compose hands out, so it can be addressed but not acted on.
 */
fun atspiTreeOf(
    title: String,
    elements: List<AccessibleElement>,
    scale: Float,
    width: Int,
    height: Int,
): AtspiTree {
    val ids = IntArray(elements.size)
    val byId = HashMap<Int, Int>(elements.size * 2)
    for ((index, element) in elements.withIndex()) {
        val id = if (element.id >= 0 && element.id !in byId) element.id else UNNAMED_BASE + index
        ids[index] = id
        byId[id] = index
    }
    fun parentIndexOf(index: Int): Int? = elements[index].parentId.takeIf { it >= 0 }?.let { byId[it] }?.takeIf { it != index }
    val parents = IntArray(elements.size) { index ->
        // Walks up until the window or a record seen on this walk, which is a loop.
        val seen = HashSet<Int>()
        var at: Int? = index
        var loops = false
        while (at != null) {
            if (!seen.add(at)) {
                loops = true
                break
            }
            at = parentIndexOf(at)
        }
        val parent = parentIndexOf(index)
        if (loops || parent == null) AtspiTree.FRAME else ids[parent]
    }
    val children = HashMap<Int, MutableList<Int>>()
    val roots = ArrayList<Int>()
    for (index in elements.indices) {
        val parent = parents[index]
        if (parent == AtspiTree.FRAME) roots += ids[index] else children.getOrPut(parent) { ArrayList() } += ids[index]
    }
    val nodes = LinkedHashMap<Int, AtspiNode>(elements.size * 2)
    var focused: Int? = null
    for ((index, element) in elements.withIndex()) {
        val described = describeElement(element)
        val id = ids[index]
        if (focused == null && element.states and AccessibleState.FOCUSED != 0) focused = id
        val isField = element.role == ElementRole.FIELD || element.states and AccessibleState.EDITABLE != 0
        val text = if (isField) element.value ?: "" else null
        val range = if (element.rangeValue != null || element.rangeMin != null || element.rangeMax != null) {
            AtspiRange(
                minimum = (element.rangeMin ?: 0f).toDouble(),
                maximum = (element.rangeMax ?: 0f).toDouble(),
                current = (element.rangeValue ?: 0f).toDouble(),
                text = element.value ?: "",
            )
        } else {
            null
        }
        nodes[id] = AtspiNode(
            id = id,
            parent = parents[index],
            children = children[id] ?: emptyList(),
            role = described.role,
            name = described.name,
            states = described.states,
            actions = described.actions,
            x = (element.x * scale).toInt(),
            y = (element.y * scale).toInt(),
            width = (element.width * scale).toInt(),
            height = (element.height * scale).toInt(),
            text = text,
            caret = if (text != null) codePointsOf(text).size else 0,
            description = element.hint ?: "",
            settable = element.states and AccessibleState.ENABLED != 0 &&
                element.actions and AccessibleAction.SET_VALUE != 0 &&
                element.states and AccessibleState.READ_ONLY == 0,
            range = range,
            focusable = element.actions and AccessibleAction.FOCUS != 0 ||
                element.states and AccessibleState.FOCUSABLE != 0,
        )
    }
    return AtspiTree(title, nodes, roots, focused, width, height)
}

/** Where a record with no id of its own is numbered from: past the ids Compose gives out. */
const val UNNAMED_BASE = 1 shl 30

/** What a slider or a progress indicator reports through the Value interface. */
data class AtspiRange(
    val minimum: Double,
    val maximum: Double,
    val current: Double,
    /** The value as it would be read, such as "40 percent"; empty where the scene gave none. */
    val text: String,
)

/** One object in the tree a reader walks. */
data class AtspiNode(
    /** The scene's id for the control, stable for as long as the control exists. */
    val id: Int,
    /** The id of the parent, or [AtspiTree.FRAME] for a child of the window. */
    val parent: Int,
    val children: List<Int>,
    val role: Int,
    val name: String,
    val states: Long,
    val actions: List<String>,
    /** In the window's pixels, from its top left corner. */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** What a field holds, or null for something that is not one. */
    val text: String?,
    /** Where the caret is in [text], in characters. */
    val caret: Int = 0,
    /** What doing it does, where the author said: the Description property. */
    val description: String = "",
    /** Whether a reader may set its text (a field) or its value (a range). */
    val settable: Boolean = false,
    /** Its range and position, for a slider or a progress indicator. */
    val range: AtspiRange? = null,
    /** Whether a reader may give it the keyboard. */
    val focusable: Boolean = false,
)

/**
 * The tree for one window, nested the way the scene nests it.
 *
 * Equality is what decides whether anything is said: a frame that left the tree as it was
 * leaves the bus quiet.
 */
data class AtspiTree(
    val title: String,
    val nodes: Map<Int, AtspiNode>,
    /** The window's own children, in the order they are laid out. */
    val roots: List<Int>,
    /** The node that has the keyboard, or null where none does. */
    val focused: Int?,
    /** The window's size in pixels, which is the frame's own extents. */
    val width: Int = 0,
    val height: Int = 0,
) {
    /** The id of the deepest node under a point in the window's pixels, or null for none. */
    fun nodeAt(x: Int, y: Int): Int? {
        // The deepest node containing the point, and the last one laid out when two overlap,
        // because the one drawn later is the one on top.
        var found: Int? = null
        var depth = -1
        fun visit(id: Int, level: Int) {
            val node = nodes[id] ?: return
            if (x >= node.x && x < node.x + node.width && y >= node.y && y < node.y + node.height && level >= depth) {
                found = id
                depth = level
            }
            for (child in node.children) visit(child, level + 1)
        }
        for (root in roots) visit(root, 0)
        return found
    }

    companion object {
        /** The parent id of what sits directly in the window. */
        const val FRAME = -1

        val EMPTY = AtspiTree("", emptyMap(), emptyList(), null)
    }
}
