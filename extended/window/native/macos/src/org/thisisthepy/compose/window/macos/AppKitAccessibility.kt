@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.macos

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole

// What the window tells a reader who cannot see it.
//
// The scene already knows: every control Compose draws carries semantics, and a tree of
// them is what a screen reader wants. What is missing is a way for that tree to reach the
// platform, and the platform asks for it on its own thread at moments nobody chose. So
// the tree is flattened and pushed whenever it changes, and the shell answers from what
// it was last given.
//
// Flattened rather than kept as a tree. A first tree that says what is there and where is
// worth more than a perfect shape nobody has tested, and the nesting can follow once a
// reader has been heard using this.

/**
 * Everything in this tree worth announcing, in the order it is laid out, in window points
 * ([density] pixels to a point).
 *
 * A node with nothing to say and nothing to do is left out. A group that carries no label and
 * no action is scaffolding: announcing it would make a reader walk through the layout rather
 * than through the screen. Its children are still walked, and they name the nearest ancestor
 * that was described as their parent, so the nesting a reader meets is the screen's.
 */
internal fun SemanticsOwner.describe(density: Float = 1f): List<AccessibleElement> {
    val found = ArrayList<AccessibleElement>()
    val scale = if (density > 0f) density else 1f
    fun walk(node: SemanticsNode, parentId: Int) {
        val described = node.describe(parentId, scale)
        if (described != null) found += described
        val childParent = described?.id ?: parentId
        for (child in node.children) {
            walk(child, childParent)
        }
    }
    walk(rootSemanticsNode, -1)
    return found
}

private fun SemanticsNode.describe(parentId: Int, scale: Float): AccessibleElement? {
    val config = config
    val editable = config.getOrNull(SemanticsProperties.EditableText)
    val label = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: editable?.text
    val onClick = config.getOrNull(SemanticsActions.OnClick)
    val progress = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
    val setProgress = config.getOrNull(SemanticsActions.SetProgress) != null
    val toggle = config.getOrNull(SemanticsProperties.ToggleableState)
    val role = config.getOrNull(SemanticsProperties.Role)
    val elementRole = when {
        role == Role.Button -> ElementRole.BUTTON
        role == Role.Checkbox -> ElementRole.CHECKBOX
        role == Role.Switch -> ElementRole.SWITCH
        role == Role.RadioButton -> ElementRole.RADIO_BUTTON
        role == Role.Tab -> ElementRole.TAB
        role == Role.Image -> ElementRole.IMAGE
        role == Role.DropdownList -> ElementRole.COMBO_BOX
        editable != null -> ElementRole.FIELD
        progress != null -> if (setProgress) ElementRole.SLIDER else ElementRole.PROGRESS
        toggle != null -> ElementRole.CHECKBOX
        config.getOrNull(SemanticsProperties.Heading) != null -> ElementRole.HEADING
        onClick != null -> ElementRole.BUTTON
        label != null -> ElementRole.TEXT
        else -> ElementRole.GROUP
    }
    var actions = 0
    if (onClick != null) actions = actions or AccessibleAction.PRESS
    if (config.getOrNull(SemanticsActions.RequestFocus) != null) actions = actions or AccessibleAction.FOCUS
    if (config.getOrNull(SemanticsActions.SetText) != null || setProgress) actions = actions or AccessibleAction.SET_VALUE
    if (setProgress && progress != null) {
        actions = actions or AccessibleAction.INCREMENT or AccessibleAction.DECREMENT
    }
    if (config.getOrNull(SemanticsActions.ScrollBy) != null) {
        actions = actions or AccessibleAction.SCROLL_FORWARD or AccessibleAction.SCROLL_BACKWARD
    }
    if (config.getOrNull(SemanticsActions.OnLongClick) != null) actions = actions or AccessibleAction.SHOW_MENU
    // Nothing to say and nothing to do. The layout is not the screen.
    if (label == null && actions == 0 && progress == null && toggle == null) return null

    var states = 0
    if (config.getOrNull(SemanticsProperties.Disabled) == null) states = states or AccessibleState.ENABLED
    if (actions and AccessibleAction.FOCUS != 0) states = states or AccessibleState.FOCUSABLE
    if (config.getOrNull(SemanticsProperties.Focused) == true) states = states or AccessibleState.FOCUSED
    if (toggle != null) {
        states = states or AccessibleState.CHECKABLE
        when (toggle) {
            ToggleableState.On -> states = states or AccessibleState.CHECKED
            ToggleableState.Indeterminate -> states = states or AccessibleState.MIXED
            ToggleableState.Off -> {}
        }
    }
    if (config.getOrNull(SemanticsProperties.Selected) == true) states = states or AccessibleState.SELECTED
    if (editable != null) states = states or AccessibleState.EDITABLE
    if (config.getOrNull(SemanticsProperties.Password) != null) states = states or AccessibleState.PASSWORD

    val bounds = boundsInRoot
    return AccessibleElement(
        role = elementRole,
        x = bounds.left / scale,
        y = bounds.top / scale,
        width = bounds.width / scale,
        height = bounds.height / scale,
        label = label ?: "",
        id = id,
        parentId = parentId,
        value = editable?.text ?: config.getOrNull(SemanticsProperties.StateDescription),
        hint = onClick?.label,
        states = states,
        actions = actions,
        rangeMin = progress?.range?.start,
        rangeMax = progress?.range?.endInclusive,
        rangeValue = progress?.current,
    )
}

/**
 * Does what a reader asked of the control [id] in this tree, and answers whether it was done.
 *
 * [action] is one [AccessibleAction] value and [argument] the new value for
 * [AccessibleAction.SET_VALUE]: the text for a field, the number for a slider. A step up or
 * down moves a slider by one of its steps, or by a twentieth of its range where it has none.
 */
internal fun SemanticsOwner.performAccessibilityAction(id: Int, action: Int, argument: String?): Boolean {
    val node = findNode(rootSemanticsNode, id) ?: return false
    val config = node.config
    return when (action) {
        AccessibleAction.PRESS -> config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
        AccessibleAction.FOCUS -> config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() ?: false
        AccessibleAction.SHOW_MENU -> config.getOrNull(SemanticsActions.OnLongClick)?.action?.invoke() ?: false
        AccessibleAction.SET_VALUE -> {
            val text = argument ?: ""
            config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text))
                ?: text.toFloatOrNull()?.let { config.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(it) }
                ?: false
        }
        AccessibleAction.INCREMENT, AccessibleAction.DECREMENT -> {
            val range = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return false
            val span = range.range.endInclusive - range.range.start
            val step = if (range.steps > 0) span / (range.steps + 1) else span / 20f
            val target = range.current + if (action == AccessibleAction.INCREMENT) step else -step
            config.getOrNull(SemanticsActions.SetProgress)?.action
                ?.invoke(target.coerceIn(range.range.start, range.range.endInclusive)) ?: false
        }
        AccessibleAction.SCROLL_FORWARD, AccessibleAction.SCROLL_BACKWARD -> {
            val scroll = config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
            val vertical = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            val page = if (vertical) node.boundsInRoot.height else node.boundsInRoot.width
            val delta = if (action == AccessibleAction.SCROLL_FORWARD) page else -page
            if (vertical) scroll(0f, delta) else scroll(delta, 0f)
        }
        else -> false
    }
}

private fun findNode(node: SemanticsNode, id: Int): SemanticsNode? {
    if (node.id == id) return node
    for (child in node.children) {
        findNode(child, id)?.let { return it }
    }
    return null
}

/**
 * Keeps the platform's copy of the tree in step with the scene's.
 *
 * Pushed on change rather than asked for. What changes a tree is the screen changing, not
 * a frame being drawn, so this runs rarely even while something is animating.
 */
internal class NativeSemantics(
    /** Pixels to a point, which the scene's bounds are divided by: the tree is in window points. */
    private val density: () -> Float = { 1f },
    private val push: (List<AccessibleElement>) -> Unit,
) : PlatformContext.SemanticsOwnerListener {

    private var owner: SemanticsOwner? = null
    private var changed = false

    // Noted here and read after the next frame. What these say is that the tree is
    // different, not that it has been placed: asked for its bounds at this moment every
    // control answers with an empty rectangle, and a reader given those finds the whole
    // window stacked in its top left corner. Measured after the frame, they are real.
    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        owner = semanticsOwner
        changed = true
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        if (owner === semanticsOwner) {
            owner = null
            changed = true
        }
    }

    // Every one of these remembers which tree it was about, not only the one whose name
    // says a tree arrived. That one is not always called: a scene can report its first
    // change without ever having reported an appearance, and a listener that waits for
    // the appearance waits for good. This window described nothing at all until the
    // other callbacks were allowed to say which tree they meant.
    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        owner = semanticsOwner
        changed = true
    }

    // A control that moved says the same things from a different place.
    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        owner = semanticsOwner
        changed = true
    }

    /**
     * Hands over the tree if it has changed since the last time.
     *
     * Called after a frame, which is when everything in it has been placed. Doing nothing
     * is the ordinary case: a screen that is not changing has nothing new to say.
     */
    fun pushIfChanged(afterDrawing: Boolean = false) {
        // Read again after a frame that painted, whether or not anything said so. What
        // the listener reports is that a tree changed, and a tree that was placed
        // differently without changing says nothing: a control that moved is at a new
        // place and a reader pointed at the old one finds nothing there.
        if (!changed && !afterDrawing) return
        changed = false
        val owner = owner ?: return
        val described = owner.describe(density())
        if (described == last) return
        last = described
        push(described)
    }

    /**
     * Does what a reader asked of control [id], in the tree last described. Marks the tree
     * changed, because what a control does to itself (a box checked, a field filled) is
     * what the reader is about to ask for.
     */
    fun perform(id: Int, action: Int, argument: String?): Boolean {
        val owner = owner ?: return false
        val done = owner.performAccessibilityAction(id, action, argument)
        if (done) changed = true
        return done
    }

    // What was last handed over, so that reading again costs a comparison rather than a
    // crossing. A screen that is animating is placed anew on every frame and says the
    // same thing about itself throughout.
    private var last: List<AccessibleElement> = emptyList()
}
