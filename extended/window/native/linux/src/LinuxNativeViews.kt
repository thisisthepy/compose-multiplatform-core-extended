@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import kotlinx.cinterop.CPointer
import org.thisisthepy.compose.window.NativeViews
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent
import x11.CurrentTime
import x11.Display
import x11.RevertToParent
import x11.XDefaultRootWindow
import x11.XFlush
import x11.XMapWindow
import x11.XMoveResizeWindow
import x11.XRaiseWindow
import x11.XReparentWindow
import x11.XSetInputFocus
import x11.XUnmapWindow

/**
 * X11 [NativeViews] implementation for [X11Window]: reparents Host-owned child X windows into
 * the top-level X11 window, places and clips them in window pixels, and manages keyboard focus
 * between the scene and attached child windows.
 */
internal class LinuxNativeViews(
    private val displayProvider: () -> CPointer<Display>?,
    private val parentWindowProvider: () -> ULong,
    private val dispatchEvent: (WindowEvent) -> Unit,
) : NativeViews {
    private class Slot(
        val id: Int,
        val handle: Long,
        val childWindow: ULong,
    ) {
        var frame: PixelRect = PixelRect(0, 0, 0, 0)
        var clip: PixelRect? = null
        var holes: List<PixelRoundRect> = emptyList()
        var requestedVisible: Boolean = false
        var mapped: Boolean = false
    }

    private val slots = LinkedHashMap<Int, Slot>()
    private var focusedSlotId: Int? = null

    override val parentHandle: Long
        get() = parentWindowProvider().toLong()

    override fun attach(id: Int, handle: Long): Boolean {
        if (handle == 0L) return false
        val display = displayProvider() ?: return false
        val parent = parentWindowProvider()
        if (parent == 0UL) return false
        if (slots.containsKey(id)) return false
        if (slots.values.any { it.handle == handle }) return false

        val child = handle.toULong()
        XUnmapWindow(display, child)
        XReparentWindow(display, child, parent, 0, 0)
        XRaiseWindow(display, child)
        XFlush(display)

        slots[id] = Slot(id = id, handle = handle, childWindow = child)
        return true
    }

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        val slot = slots[id] ?: return
        slot.frame = PixelRect(x, y, width, height)
        applyLayout(slot)
    }

    override fun setVisibleRegion(id: Int, clip: PixelRect?, holes: List<PixelRoundRect>) {
        val slot = slots[id] ?: return
        slot.clip = clip
        slot.holes = holes
        applyLayout(slot)
    }

    override fun setFrameAndVisibleRegion(
        id: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        clip: PixelRect?,
        holes: List<PixelRoundRect>,
    ) {
        val slot = slots[id] ?: return
        slot.frame = PixelRect(x, y, width, height)
        slot.clip = clip
        slot.holes = holes
        applyLayout(slot)
    }

    override fun setVisible(id: Int, visible: Boolean) {
        val slot = slots[id] ?: return
        slot.requestedVisible = visible
        if (!visible && focusedSlotId == id) {
            focusScene()
        }
        applyLayout(slot)
    }

    private fun focusEvent(kind: Int, slotId: Int): WindowEvent = WindowEvent(
        kind = kind,
        x = 0f,
        y = 0f,
        buttons = 0,
        keyCode = slotId,
        modifiers = 0,
        codePoint = 0,
        text = "",
    )

    override fun focus(id: Int) {
        val slot = slots[id] ?: return
        val display = displayProvider() ?: return
        val previous = focusedSlotId
        if (previous != null && previous != id) {
            focusedSlotId = null
            dispatchEvent(focusEvent(WindowEvent.NATIVE_VIEW_FOCUS_OUT, previous))
        }
        XSetInputFocus(display, slot.childWindow, RevertToParent, CurrentTime.toULong())
        XFlush(display)
        focusedSlotId = id
        dispatchEvent(focusEvent(WindowEvent.NATIVE_VIEW_FOCUS_IN, id))
    }

    override fun focusScene() {
        val previous = focusedSlotId ?: return
        focusedSlotId = null
        val display = displayProvider()
        val parent = parentWindowProvider()
        if (display != null && parent != 0UL) {
            XSetInputFocus(display, parent, RevertToParent, CurrentTime.toULong())
            XFlush(display)
        }
        dispatchEvent(focusEvent(WindowEvent.NATIVE_VIEW_FOCUS_OUT, previous))
    }

    override fun detach(id: Int) {
        val slot = slots.remove(id) ?: return
        if (focusedSlotId == id) {
            focusScene()
        }
        val display = displayProvider() ?: return
        XUnmapWindow(display, slot.childWindow)
        val root = XDefaultRootWindow(display)
        XReparentWindow(display, slot.childWindow, root, 0, 0)
        XFlush(display)
    }

    internal fun detachAll() {
        for (id in slots.keys.toList()) {
            detach(id)
        }
    }

    private fun applyLayout(slot: Slot) {
        val display = displayProvider() ?: return
        val effective = slot.clip?.intersect(slot.frame) ?: slot.frame
        if (effective.isEmpty || !slot.requestedVisible) {
            if (slot.mapped) {
                XUnmapWindow(display, slot.childWindow)
                XFlush(display)
                slot.mapped = false
            }
            return
        }
        XMoveResizeWindow(
            display,
            slot.childWindow,
            effective.x,
            effective.y,
            effective.width.toUInt(),
            effective.height.toUInt(),
        )
        if (!slot.mapped) {
            XMapWindow(display, slot.childWindow)
            slot.mapped = true
        }
        XFlush(display)
    }
}
