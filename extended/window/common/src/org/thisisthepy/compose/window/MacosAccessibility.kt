package org.thisisthepy.compose.window

/**
 * What one [AccessibleElement] is to macOS accessibility: its AX role and subrole, the value a
 * reader is told, and which of the reader's requests it answers.
 *
 * Decided here, once, for both macOS windows. The Kotlin/Native window applies it through
 * AppKit's interop and the GraalVM window hands it to its Objective-C side as strings, so a
 * control is the same thing to VoiceOver on both paths. The names are AppKit's public
 * `NSAccessibilityRole` and `NSAccessibilitySubrole` values, written out because the common
 * module names no AppKit type.
 */
data class MacosAxElement(
    /** The AX role, such as `AXButton`. */
    val role: String,
    /** The AX subrole, such as `AXSwitch`, or null for none. */
    val subrole: String?,
    /** What the reader is told the value is, or null where the control has none. */
    val value: MacosAxValue?,
    /** Whether the reader may set the value ([AccessibleAction.SET_VALUE]). */
    val valueSettable: Boolean,
    /** Whether the reader may move focus to it ([AccessibleAction.FOCUS]). */
    val focusSettable: Boolean,
    /** The AX actions it answers, in the order a reader lists them. */
    val actions: List<String>,
)

/** An AX value: text for a field, a number for a checkbox, a slider or a progress indicator. */
sealed interface MacosAxValue {
    data class Text(val text: String) : MacosAxValue
    data class Number(val number: Double) : MacosAxValue
}

/** AppKit's public role, subrole and action names this window uses. */
object MacosAx {
    const val GROUP = "AXGroup"
    const val BUTTON = "AXButton"
    const val STATIC_TEXT = "AXStaticText"
    const val TEXT_FIELD = "AXTextField"
    const val TEXT_AREA = "AXTextArea"
    const val CHECK_BOX = "AXCheckBox"
    const val IMAGE = "AXImage"
    const val RADIO_BUTTON = "AXRadioButton"
    const val SLIDER = "AXSlider"
    const val PROGRESS_INDICATOR = "AXProgressIndicator"
    const val HEADING = "AXHeading"
    const val COMBO_BOX = "AXComboBox"
    const val LINK = "AXLink"

    const val SWITCH_SUBROLE = "AXSwitch"
    const val TAB_BUTTON_SUBROLE = "AXTabButton"
    const val SECURE_TEXT_FIELD_SUBROLE = "AXSecureTextField"

    const val PRESS = "AXPress"
    const val INCREMENT = "AXIncrement"
    const val DECREMENT = "AXDecrement"
    const val SHOW_MENU = "AXShowMenu"

    /**
     * The names of the two scroll actions. AppKit's element protocol has no page scroll, so a
     * scrollable element offers them as custom actions (`NSAccessibilityCustomAction`), which
     * VoiceOver lists in its actions menu.
     */
    const val SCROLL_FORWARD_NAME = "Scroll forward"
    const val SCROLL_BACKWARD_NAME = "Scroll backward"
}

/** The AX form of [element]. */
fun macosAxElement(element: AccessibleElement): MacosAxElement {
    val states = element.states
    val password = states and AccessibleState.PASSWORD != 0
    val multiLine = states and AccessibleState.MULTI_LINE != 0
    val (role, subrole) = when (element.role) {
        ElementRole.BUTTON -> MacosAx.BUTTON to null
        ElementRole.TEXT -> MacosAx.STATIC_TEXT to null
        ElementRole.FIELD -> when {
            password -> MacosAx.TEXT_FIELD to MacosAx.SECURE_TEXT_FIELD_SUBROLE
            multiLine -> MacosAx.TEXT_AREA to null
            else -> MacosAx.TEXT_FIELD to null
        }
        ElementRole.CHECKBOX -> MacosAx.CHECK_BOX to null
        ElementRole.IMAGE -> MacosAx.IMAGE to null
        ElementRole.SWITCH -> MacosAx.CHECK_BOX to MacosAx.SWITCH_SUBROLE
        ElementRole.RADIO_BUTTON -> MacosAx.RADIO_BUTTON to null
        ElementRole.TAB -> MacosAx.RADIO_BUTTON to MacosAx.TAB_BUTTON_SUBROLE
        ElementRole.SLIDER -> MacosAx.SLIDER to null
        ElementRole.PROGRESS -> MacosAx.PROGRESS_INDICATOR to null
        ElementRole.HEADING -> MacosAx.HEADING to null
        ElementRole.COMBO_BOX -> MacosAx.COMBO_BOX to null
        ElementRole.LINK -> MacosAx.LINK to null
        else -> MacosAx.GROUP to null
    }
    val actions = ArrayList<String>(4)
    if (element.actions and AccessibleAction.PRESS != 0) actions += MacosAx.PRESS
    if (element.actions and AccessibleAction.INCREMENT != 0) actions += MacosAx.INCREMENT
    if (element.actions and AccessibleAction.DECREMENT != 0) actions += MacosAx.DECREMENT
    if (element.actions and AccessibleAction.SHOW_MENU != 0) actions += MacosAx.SHOW_MENU
    return MacosAxElement(
        role = role,
        subrole = subrole,
        value = macosAxValue(element),
        valueSettable = element.actions and AccessibleAction.SET_VALUE != 0 &&
            states and AccessibleState.READ_ONLY == 0,
        focusSettable = element.actions and AccessibleAction.FOCUS != 0 ||
            states and AccessibleState.FOCUSABLE != 0,
        actions = actions,
    )
}

/**
 * The value a reader is told. A checkable control's is 0, 1 or 2 (mixed), as AppKit's own
 * checkboxes report it; a ranged one's is where it is; a field's is its text, except a
 * password field's, which is never read out.
 */
fun macosAxValue(element: AccessibleElement): MacosAxValue? {
    val states = element.states
    if (states and AccessibleState.CHECKABLE != 0) {
        return MacosAxValue.Number(
            when {
                states and AccessibleState.MIXED != 0 -> 2.0
                states and AccessibleState.CHECKED != 0 -> 1.0
                else -> 0.0
            },
        )
    }
    element.rangeValue?.let { return MacosAxValue.Number(it.toDouble()) }
    if (element.role == ElementRole.FIELD && states and AccessibleState.PASSWORD != 0) {
        return MacosAxValue.Text("")
    }
    return element.value?.let { MacosAxValue.Text(it) }
}

/**
 * A key that names [element] across pushes: its id where it has one, otherwise its place in
 * the list, offset below every id a scene gives so the two never meet.
 */
fun macosAxKey(element: AccessibleElement, index: Int): Int =
    if (element.id >= 0) element.id else -(index + 2)

/**
 * The tree [elements] describe, as the index of each one's parent, or -1 for one directly in
 * the window. A parent named but not described (scaffolding the scene left out) puts the
 * element in the window, so nothing a reader should meet is lost.
 */
fun macosAxParents(elements: List<AccessibleElement>): IntArray {
    val indexById = HashMap<Int, Int>(elements.size)
    for ((index, element) in elements.withIndex()) {
        if (element.id >= 0) indexById[element.id] = index
    }
    val parents = IntArray(elements.size) { index ->
        val parentId = elements[index].parentId
        if (parentId < 0) -1 else indexById[parentId]?.takeIf { it != index } ?: -1
    }
    // A loop (two elements each naming the other) would leave both out of reach of the
    // window, so the element that closes it is put in the window instead.
    for (start in parents.indices) {
        var at = parents[start]
        var steps = 0
        while (at >= 0 && steps <= parents.size) {
            if (at == start) {
                parents[start] = -1
                break
            }
            at = parents[at]
            steps++
        }
    }
    return parents
}

/** The index of the element that has input focus, or -1 where none does. */
fun macosAxFocused(elements: List<AccessibleElement>): Int =
    elements.indexOfFirst { it.states and AccessibleState.FOCUSED != 0 }
