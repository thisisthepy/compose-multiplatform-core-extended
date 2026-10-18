@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package org.thisisthepy.compose.window.scene

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole

/**
 * The scene's semantics, flattened into what a reader is told about each control.
 *
 * The scene reports changes to one listener; this is that listener, and it remembers every
 * owner it hears of in every callback, because the first it hears of is not always an
 * "appended" one. It describes nothing itself when told: bounds read inside a callback are
 * empty, so a change only marks the tree as changed, and [describe] runs after the next frame,
 * when everything is placed. [others] hear every change too, for a platform that keeps a tree
 * of its own as well (an AT-SPI server beside the AX elements).
 */
class SceneSemantics(
    private vararg val others: PlatformContext.SemanticsOwnerListener,
) : PlatformContext.SemanticsOwnerListener {
    private val owners = LinkedHashSet<SemanticsOwner>()

    /** Set when the tree changed since it was last described. */
    var changed = false
        private set

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        owners += semanticsOwner
        changed = true
        for (other in others) other.onSemanticsOwnerAppended(semanticsOwner)
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        owners -= semanticsOwner
        changed = true
        for (other in others) other.onSemanticsOwnerRemoved(semanticsOwner)
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        owners += semanticsOwner
        changed = true
        for (other in others) other.onSemanticsChange(semanticsOwner)
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        owners += semanticsOwner
        changed = true
        for (other in others) other.onLayoutChange(semanticsOwner, semanticsNodeId)
    }

    /** Every control a reader is told about, in window points ([density] pixels to a point). */
    fun describe(density: Float): List<AccessibleElement> {
        changed = false
        val found = ArrayList<AccessibleElement>()
        for (owner in owners) describeTree(owner.rootSemanticsNode, -1, density, found)
        return found
    }

    /**
     * Does what a reader asked of the control with [id]: [action] is an [AccessibleAction]
     * value and [argument] the new value for [AccessibleAction.SET_VALUE]. Returns whether the
     * control offered it.
     */
    fun perform(id: Int, action: Int, argument: String?): Boolean {
        val node = owners.firstNotNullOfOrNull { owner ->
            owner.getAllSemanticsNodes(mergingEnabled = true).firstOrNull { it.id == id }
        } ?: return false
        return node.config.perform(action, argument, node.size.height.toFloat(), node.size.width.toFloat())
    }
}

/**
 * Walks [node] and its children. A node with nothing to tell a reader is scaffolding: it is left
 * out, and its children are attached to the nearest ancestor that was kept, [parentId].
 */
private fun describeTree(node: SemanticsNode, parentId: Int, density: Float, into: MutableList<AccessibleElement>) {
    val element = node.describe(parentId, density)
    if (element != null) into += element
    val childParent = element?.id ?: parentId
    for (child in node.children) describeTree(child, childParent, density, into)
}

/** What a reader is told about one node, or null for a node that is only arrangement. */
internal fun SemanticsNode.describe(parentId: Int, density: Float): AccessibleElement? {
    val config = config
    if (config.getOrNull(SemanticsProperties.HideFromAccessibility) != null) return null
    val editable = config.getOrNull(SemanticsProperties.EditableText)
    val label = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: editable?.text
    val composeRole = config.getOrNull(SemanticsProperties.Role)
    val toggle = config.getOrNull(SemanticsProperties.ToggleableState)
    val range = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
    val canClick = config.getOrNull(SemanticsActions.OnClick) != null
    val canSetText = config.getOrNull(SemanticsActions.SetText) != null
    val canSetProgress = config.getOrNull(SemanticsActions.SetProgress) != null
    val role = when {
        composeRole == Role.Button -> ElementRole.BUTTON
        composeRole == Role.Checkbox -> ElementRole.CHECKBOX
        composeRole == Role.Switch -> ElementRole.SWITCH
        composeRole == Role.RadioButton -> ElementRole.RADIO_BUTTON
        composeRole == Role.Tab -> ElementRole.TAB
        composeRole == Role.Image -> ElementRole.IMAGE
        composeRole == Role.DropdownList -> ElementRole.COMBO_BOX
        editable != null -> ElementRole.FIELD
        range != null -> if (canSetProgress) ElementRole.SLIDER else ElementRole.PROGRESS
        toggle != null -> ElementRole.CHECKBOX
        canClick -> ElementRole.BUTTON
        config.getOrNull(SemanticsProperties.Heading) != null && label != null -> ElementRole.HEADING
        label != null -> ElementRole.TEXT
        else -> return null
    }

    var states = 0
    if (config.getOrNull(SemanticsProperties.Disabled) == null) states = states or AccessibleState.ENABLED
    val focused = config.getOrNull(SemanticsProperties.Focused)
    if (focused == true) states = states or AccessibleState.FOCUSED
    if (focused != null || config.getOrNull(SemanticsActions.RequestFocus) != null) states = states or AccessibleState.FOCUSABLE
    if (toggle != null) {
        states = states or AccessibleState.CHECKABLE
        when (toggle) {
            ToggleableState.On -> states = states or AccessibleState.CHECKED
            ToggleableState.Indeterminate -> states = states or AccessibleState.MIXED
            ToggleableState.Off -> {}
        }
    }
    if (config.getOrNull(SemanticsProperties.Selected) == true) states = states or AccessibleState.SELECTED
    val password = config.getOrNull(SemanticsProperties.Password) != null
    if (editable != null) {
        states = states or if (canSetText) AccessibleState.EDITABLE else AccessibleState.READ_ONLY
        if (password) states = states or AccessibleState.PASSWORD
    }

    var actions = 0
    if (canClick) actions = actions or AccessibleAction.PRESS
    if (config.getOrNull(SemanticsActions.RequestFocus) != null) actions = actions or AccessibleAction.FOCUS
    if (canSetText) actions = actions or AccessibleAction.SET_VALUE
    if (canSetProgress) {
        actions = actions or AccessibleAction.SET_VALUE or AccessibleAction.INCREMENT or AccessibleAction.DECREMENT
    }
    if (config.getOrNull(SemanticsActions.ScrollBy) != null) {
        actions = actions or AccessibleAction.SCROLL_FORWARD or AccessibleAction.SCROLL_BACKWARD
    }
    if (config.getOrNull(SemanticsActions.OnLongClick) != null) actions = actions or AccessibleAction.SHOW_MENU

    val value = when {
        // A password is announced by its length, never its letters.
        editable != null && password -> "•".repeat(editable.text.length)
        editable != null -> editable.text
        range != null -> config.getOrNull(SemanticsProperties.StateDescription) ?: range.current.toString()
        else -> config.getOrNull(SemanticsProperties.StateDescription)
    }
    val hint = config.getOrNull(SemanticsActions.OnClick)?.label

    val bounds = boundsInRoot
    return AccessibleElement(
        role = role,
        x = bounds.left / density,
        y = bounds.top / density,
        width = bounds.width / density,
        height = bounds.height / density,
        label = label ?: "",
        id = id,
        parentId = parentId,
        value = value,
        hint = hint,
        states = states,
        actions = actions,
        rangeMin = range?.range?.start,
        rangeMax = range?.range?.endInclusive,
        rangeValue = range?.current,
    )
}

/**
 * Runs the semantics action an [AccessibleAction] stands for. [height] and [width] are the
 * node's size in pixels, the distance one page of scrolling moves.
 */
internal fun SemanticsConfiguration.perform(action: Int, argument: String?, height: Float, width: Float): Boolean {
    fun stepped(sign: Int): Boolean {
        val range = getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return false
        val set = getOrNull(SemanticsActions.SetProgress)?.action ?: return false
        val span = range.range.endInclusive - range.range.start
        val step = if (range.steps > 0) span / (range.steps + 1) else span / 20f
        val next = (range.current + sign * step).coerceIn(range.range.start, range.range.endInclusive)
        return set(next)
    }
    fun scrolled(sign: Int): Boolean {
        val scroll = getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
        val vertical = getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
            getOrNull(SemanticsProperties.HorizontalScrollAxisRange) == null
        return if (vertical) scroll(0f, sign * height) else scroll(sign * width, 0f)
    }
    return when (action) {
        AccessibleAction.PRESS -> getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
        AccessibleAction.FOCUS -> getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() ?: false
        AccessibleAction.SET_VALUE -> {
            val text = argument ?: return false
            getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text))
                ?: text.toFloatOrNull()?.let { getOrNull(SemanticsActions.SetProgress)?.action?.invoke(it) }
                ?: false
        }
        AccessibleAction.INCREMENT -> stepped(+1)
        AccessibleAction.DECREMENT -> stepped(-1)
        AccessibleAction.SCROLL_FORWARD -> scrolled(+1)
        AccessibleAction.SCROLL_BACKWARD -> scrolled(-1)
        AccessibleAction.SHOW_MENU -> getOrNull(SemanticsActions.OnLongClick)?.action?.invoke() ?: false
        else -> false
    }
}
