package org.thisisthepy.compose.window

/**
 * What a control is, in the set the scene and the platforms agree on. Each platform maps these to
 * its own roles (an AX role and subrole on macOS, an AT-SPI role on Linux).
 */
object ElementRole {
    const val GROUP = 0
    const val BUTTON = 1
    const val TEXT = 2
    const val FIELD = 3
    const val CHECKBOX = 4
    const val IMAGE = 5
    const val SWITCH = 6
    const val RADIO_BUTTON = 7
    const val TAB = 8
    const val SLIDER = 9
    const val PROGRESS = 10
    const val HEADING = 11
    const val COMBO_BOX = 12
    const val LINK = 13
}

/** What is true of a control right now, as bits of [AccessibleElement.states]. */
object AccessibleState {
    const val ENABLED = 1 shl 0
    const val FOCUSABLE = 1 shl 1
    /** Has input focus. The scene sets it on the element focus moved to, so a platform can announce the move. */
    const val FOCUSED = 1 shl 2
    /**
     * Can be checked: a checkbox, switch or radio button. Without it the control is not
     * checkable, and [CHECKED] and [MIXED] say nothing.
     */
    const val CHECKABLE = 1 shl 3
    /** Checked (on). With [CHECKABLE] and neither this nor [MIXED], it is unchecked. */
    const val CHECKED = 1 shl 4
    /** Neither checked nor unchecked: the third state of a tri-state checkbox. */
    const val MIXED = 1 shl 5
    /** Chosen from a set: a tab, a list item, a radio button. */
    const val SELECTED = 1 shl 6
    /** Takes typed text. */
    const val EDITABLE = 1 shl 7
    /** A field that shows text the reader may not change. */
    const val READ_ONLY = 1 shl 8
    /** A field whose text is not read out. */
    const val PASSWORD = 1 shl 9
    const val MULTI_LINE = 1 shl 10
}

/**
 * What a reader can ask a control to do, as bits of [AccessibleElement.actions], and the action
 * an answer names in [WindowListener.onAccessibilityAction].
 */
object AccessibleAction {
    /** Press, click, activate, toggle: what a tap would do. */
    const val PRESS = 1 shl 0
    /** Move input focus to it. */
    const val FOCUS = 1 shl 1
    /** Replace a field's text, or a slider's value; the new value is the argument. */
    const val SET_VALUE = 1 shl 2
    /** Move a slider or a stepper one step up or down. */
    const val INCREMENT = 1 shl 3
    const val DECREMENT = 1 shl 4
    /** Scroll a scrollable container one page on or back. */
    const val SCROLL_FORWARD = 1 shl 5
    const val SCROLL_BACKWARD = 1 shl 6
    /** Open its menu: a long press or a context menu. */
    const val SHOW_MENU = 1 shl 7
}

/**
 * One thing in the window, as a reader who cannot see it would meet it. Positions are window
 * points from the top left. Everything after [label] has a default, so a description that says
 * only what it is and where still compiles.
 */
data class AccessibleElement(
    val role: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
    /**
     * The scene's id for it (Compose's semantics node id), stable for as long as the control
     * exists, so a platform keeps one object per control across pushes and an action names it.
     * -1 where the description has none.
     */
    val id: Int = -1,
    /** The [id] of the element it is inside, or -1 for one directly in the window. */
    val parentId: Int = -1,
    /** A field's text, or a ranged control's value as it would be read; null for neither. */
    val value: String? = null,
    /** What doing it does, where the author said so (Compose's onClick label, a state description). */
    val hint: String? = null,
    /** [AccessibleState] bits. */
    val states: Int = AccessibleState.ENABLED,
    /** [AccessibleAction] bits: what it offers. */
    val actions: Int = 0,
    /** For a slider or progress indicator: its range and where it is. Null for anything else. */
    val rangeMin: Float? = null,
    val rangeMax: Float? = null,
    val rangeValue: Float? = null,
)

/**
 * The platform's copy of the accessibility tree, kept in step with the scene's.
 *
 * The platform asks for the tree on its own thread at moments nobody chose, so the tree is
 * described and pushed whenever it changes and the platform answers from what it was last
 * given. [describe] reads the scene (the renderer supplies it, because it needs Compose);
 * [push] hands a changed list to the platform.
 *
 * What was last handed over is kept, so reading again costs a comparison rather than a
 * crossing: a screen that is animating is placed anew every frame and says the same thing
 * about itself throughout.
 */
class AccessibilityCache(
    private val describe: () -> List<AccessibleElement>,
    private val push: (List<AccessibleElement>) -> Unit,
) {
    private var changed = false
    private var last: List<AccessibleElement> = emptyList()

    /** The scene said its tree changed. It is read after the next frame, when it is placed. */
    fun noteChanged() {
        changed = true
    }

    /** What the platform was last given. */
    val current: List<AccessibleElement> get() = last

    /**
     * Pushes the tree if it changed. After a frame that painted, it is read again whether or
     * not anything said so: a control that moved is at a new place without having changed.
     * Returns true when the platform was given a new list.
     */
    fun pushIfChanged(afterDrawing: Boolean = false): Boolean {
        if (!changed && !afterDrawing) return false
        changed = false
        val described = describe()
        if (described == last) return false
        last = described
        push(described)
        return true
    }
}
