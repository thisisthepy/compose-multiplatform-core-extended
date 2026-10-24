@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValue
import kotlin.native.ref.WeakReference
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.MacosAxElement
import org.thisisthepy.compose.window.MacosAxValue
import org.thisisthepy.compose.window.macosAxElement
import org.thisisthepy.compose.window.macosAxKey
import org.thisisthepy.compose.window.macosAxParents
import platform.AppKit.NSAccessibilityCustomAction
import platform.AppKit.NSAccessibilityElement
import platform.AppKit.NSAccessibilityFocusedUIElementChangedNotification
import platform.AppKit.NSAccessibilityLayoutChangedNotification
import platform.AppKit.NSAccessibilityPostNotification
import platform.AppKit.NSAccessibilityValueChangedNotification
import platform.AppKit.NSView
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import platform.Foundation.numberWithDouble

/**
 * What a reader who cannot see the window is given: one AppKit accessibility element per
 * [AccessibleElement], nested as the scene nested them, answering the reader's requests by
 * handing them back to the scene.
 *
 * The tree arrives whole from [update] whenever the scene's changes, and the reader asks for
 * it on its own at moments nobody chose, so the elements are kept and answered from. An
 * element keeps its object across updates for as long as its id does, which is what lets
 * VoiceOver's cursor stay on a control while the screen changes around it.
 *
 * A request (press, set the value, take focus, step, scroll, open a menu) goes to [onAction]
 * with the element's id and an [AccessibleAction] value, on the thread AppKit asks on, which
 * is the window's. Its answer is what AppKit is told.
 *
 * Positions are window points from the top left of [view], which counts down from its top
 * left as the scene does, and each one is turned into screen coordinates when it is asked
 * for, so a window that moved answers with where its controls are now.
 */
class AppKitAccessibilityTree(
    view: NSView,
    private val onAction: (id: Int, action: Int, argument: String?) -> Boolean,
) {
    private val view = WeakReference(view)
    private val byKey = HashMap<Int, AxElement>()

    /** The elements directly in the window, in the order the scene gave them. */
    var roots: List<AxElement> = emptyList()
        private set

    /** Every element, in the order the scene gave them. */
    var all: List<AxElement> = emptyList()
        private set

    /** Replaces what the reader is given with [elements]. */
    fun update(elements: List<AccessibleElement>) {
        val host = view.get() ?: return
        val parents = macosAxParents(elements)
        val nodes = ArrayList<AxElement>(elements.size)
        val seen = HashSet<Int>(elements.size)
        var structureChanged = elements.size != all.size
        val focusMoved = ArrayList<AxElement>(1)
        val valueChanged = ArrayList<AxElement>(2)
        for ((index, element) in elements.withIndex()) {
            val key = macosAxKey(element, index)
            if (!seen.add(key)) continue
            val existing = byKey[key]
            val node = existing ?: AxElement(this).also {
                byKey[key] = it
                structureChanged = true
            }
            val wasFocused = existing?.isFocused == true
            val oldValue = existing?.ax?.value
            node.set(element, macosAxElement(element))
            if (node.isFocused && !wasFocused) focusMoved += node
            if (existing != null && oldValue != node.ax.value) valueChanged += node
            nodes += node
        }
        byKey.keys.retainAll(seen)
        val rootList = ArrayList<AxElement>()
        for (node in nodes) node.kids = ArrayList()
        for ((index, node) in nodes.withIndex()) {
            val parent = parents.getOrElse(index) { -1 }.let { if (it in nodes.indices) nodes[it] else null }
            if (parent == null) {
                node.parentRef = WeakReference(host)
                rootList += node
            } else {
                node.parentRef = WeakReference(parent)
                (parent.kids as ArrayList<AxElement>) += node
            }
        }
        roots = rootList
        all = nodes
        host.setAccessibilityChildren(rootList)
        if (structureChanged) post(host, NSAccessibilityLayoutChangedNotification)
        for (node in valueChanged) post(node, NSAccessibilityValueChangedNotification)
        for (node in focusMoved) post(node, NSAccessibilityFocusedUIElementChangedNotification)
    }

    /** The element the scene says has focus, or null. */
    val focused: AxElement? get() = all.firstOrNull { it.isFocused }

    internal fun perform(element: AxElement, action: Int, argument: String? = null): Boolean {
        val id = element.element.id
        if (id < 0) return false
        return onAction(id, action, argument)
    }

    internal fun frameOf(element: AccessibleElement): CValue<CGRect> {
        val local = CGRectMake(
            element.x.toDouble(),
            element.y.toDouble(),
            element.width.toDouble(),
            element.height.toDouble(),
        )
        val host = view.get() ?: return local
        val inWindow = host.convertRect(local, toView = null)
        return host.window?.convertRectToScreen(inWindow) ?: inWindow
    }

    private fun post(target: Any, notification: String?) {
        if (notification != null) NSAccessibilityPostNotification(target, notification)
    }
}

/**
 * One control as AppKit's accessibility sees it. Every answer is read from the last
 * [AccessibleElement] the scene gave, and every request is handed back to the scene.
 */
class AxElement internal constructor(private val tree: AppKitAccessibilityTree) : NSAccessibilityElement() {
    /** What the scene last said about it. */
    var element: AccessibleElement = AccessibleElement(0, 0f, 0f, 0f, 0f, "")
        private set

    /** The same, in AppKit's terms. */
    var ax: MacosAxElement = macosAxElement(element)
        private set

    internal var kids: List<AxElement> = emptyList()
    internal var parentRef: WeakReference<Any>? = null

    internal fun set(element: AccessibleElement, ax: MacosAxElement) {
        this.element = element
        this.ax = ax
    }

    val isFocused: Boolean get() = element.states and AccessibleState.FOCUSED != 0

    private fun has(action: Int) = element.actions and action != 0

    override fun isAccessibilityElement(): Boolean = true
    override fun accessibilityRole(): String? = ax.role
    override fun accessibilitySubrole(): String? = ax.subrole
    override fun accessibilityLabel(): String? = element.label
    override fun accessibilityHelp(): String? = element.hint
    override fun accessibilityValue(): Any? = when (val value = ax.value) {
        is MacosAxValue.Text -> value.text
        is MacosAxValue.Number -> NSNumber.numberWithDouble(value.number)
        null -> null
    }
    override fun accessibilityMinValue(): Any? = element.rangeMin?.let { NSNumber.numberWithDouble(it.toDouble()) }
    override fun accessibilityMaxValue(): Any? = element.rangeMax?.let { NSNumber.numberWithDouble(it.toDouble()) }
    override fun isAccessibilityEnabled(): Boolean = element.states and AccessibleState.ENABLED != 0
    override fun isAccessibilityFocused(): Boolean = isFocused
    override fun isAccessibilitySelected(): Boolean = element.states and AccessibleState.SELECTED != 0
    override fun accessibilityFrame(): CValue<CGRect> = tree.frameOf(element)
    override fun accessibilityParent(): Any? = parentRef?.get()
    override fun accessibilityChildren(): List<*>? = kids
    override fun accessibilityChildrenInNavigationOrder(): List<*>? = kids

    override fun accessibilityCustomActions(): List<*>? {
        val actions = ArrayList<NSAccessibilityCustomAction>(2)
        if (has(AccessibleAction.SCROLL_FORWARD)) {
            actions += NSAccessibilityCustomAction(name = MacosAx.SCROLL_FORWARD_NAME, handler = {
                tree.perform(this, AccessibleAction.SCROLL_FORWARD)
            })
        }
        if (has(AccessibleAction.SCROLL_BACKWARD)) {
            actions += NSAccessibilityCustomAction(name = MacosAx.SCROLL_BACKWARD_NAME, handler = {
                tree.perform(this, AccessibleAction.SCROLL_BACKWARD)
            })
        }
        return actions
    }

    override fun accessibilityPerformPress(): Boolean =
        has(AccessibleAction.PRESS) && tree.perform(this, AccessibleAction.PRESS)

    override fun accessibilityPerformIncrement(): Boolean =
        has(AccessibleAction.INCREMENT) && tree.perform(this, AccessibleAction.INCREMENT)

    override fun accessibilityPerformDecrement(): Boolean =
        has(AccessibleAction.DECREMENT) && tree.perform(this, AccessibleAction.DECREMENT)

    override fun accessibilityPerformShowMenu(): Boolean =
        has(AccessibleAction.SHOW_MENU) && tree.perform(this, AccessibleAction.SHOW_MENU)

    /** A reader typed into the field or moved the slider: the scene decides what that does. */
    override fun setAccessibilityValue(accessibilityValue: Any?) {
        if (!ax.valueSettable) return
        tree.perform(this, AccessibleAction.SET_VALUE, accessibilityValue?.toString() ?: "")
    }

    /** A reader moved focus here. Only taking focus is passed on; losing it follows from that. */
    override fun setAccessibilityFocused(accessibilityFocused: Boolean) {
        if (accessibilityFocused && ax.focusSettable) tree.perform(this, AccessibleAction.FOCUS)
    }

    /**
     * Which requests AppKit offers the reader: only the ones the scene said this control
     * answers, so VoiceOver does not list a press on a label or let a reader type into text.
     */
    override fun isAccessibilitySelectorAllowed(selector: COpaquePointer?): Boolean = when (selector) {
        SELECTOR_PRESS -> has(AccessibleAction.PRESS)
        SELECTOR_INCREMENT -> has(AccessibleAction.INCREMENT)
        SELECTOR_DECREMENT -> has(AccessibleAction.DECREMENT)
        SELECTOR_SHOW_MENU -> has(AccessibleAction.SHOW_MENU)
        SELECTOR_SET_VALUE -> ax.valueSettable
        SELECTOR_SET_FOCUSED -> ax.focusSettable
        else -> super.isAccessibilitySelectorAllowed(selector)
    }
}

private val SELECTOR_PRESS = NSSelectorFromString("accessibilityPerformPress")
private val SELECTOR_INCREMENT = NSSelectorFromString("accessibilityPerformIncrement")
private val SELECTOR_DECREMENT = NSSelectorFromString("accessibilityPerformDecrement")
private val SELECTOR_SHOW_MENU = NSSelectorFromString("accessibilityPerformShowMenu")
private val SELECTOR_SET_VALUE = NSSelectorFromString("setAccessibilityValue:")
private val SELECTOR_SET_FOCUSED = NSSelectorFromString("setAccessibilityFocused:")
