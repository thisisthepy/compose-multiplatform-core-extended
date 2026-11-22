package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.thisisthepy.compose.window.NativeViewRegion
import org.thisisthepy.compose.window.NativeViews
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.windows.c.dxc_native_view_attach
import org.thisisthepy.compose.window.windows.c.dxc_native_view_detach
import org.thisisthepy.compose.window.windows.c.dxc_native_view_focus
import org.thisisthepy.compose.window.windows.c.dxc_native_view_focus_scene
import org.thisisthepy.compose.window.windows.c.dxc_native_view_parent
import org.thisisthepy.compose.window.windows.c.dxc_native_view_place
import org.thisisthepy.compose.window.windows.c.dxc_native_view_set_visible

/**
 * What [WindowsNativeViews] asks of one window: the shared C's `dxc_native_view_*` calls
 * ([Win32CNativeViewsBackend]), or a test's own.
 */
interface Win32NativeViewsBackend {
    /** The window's HWND as a number, 0 once it is closed. */
    val parentHandle: Long

    /** Puts the HWND [handle] into the window as slot [id], hidden. False where the window refused it. */
    fun attach(id: Int, handle: Long): Boolean

    /** Where slot [id] is and what of it shows; on the screen with the next frame. */
    fun place(id: Int, region: NativeViewRegion)

    fun setVisible(id: Int, visible: Boolean)
    fun focus(id: Int)
    fun focusScene()
    fun detach(id: Int)
}

/**
 * The [NativeViews] of a Win32 window: HWNDs the caller made against [parentHandle] (the
 * top-level window), placed above the scene.
 *
 * The window (`c/win32_window.c`) puts each view in a child window of its own, above every slot
 * attached before it and above the frame on both of its routes; cuts it to its clip and cuts
 * the holes out of it with a window region, so a click in a hole reaches the scene; gives the
 * windows their place with the next frame, after it is presented; and reports focus through its
 * event queue ([org.thisisthepy.compose.window.WindowEvent.NATIVE_VIEW_FOCUS_IN] and `_OUT`),
 * and the pointer leaving the scene for a slot and coming back
 * ([org.thisisthepy.compose.window.WindowEvent.POINTER_EXIT] and `_ENTER`). A detached view, and
 * every view still attached when the window closes, is hidden and kept for the caller to destroy.
 *
 * Positions are client pixels, which on Windows are the pixels the scene measures in: there is
 * nothing to convert at a change of DPI, when the caller lays out again and sends the new
 * frames. Here the slot's frame and region are kept, so a call that changes one sends both,
 * and a call that changes nothing sends nothing.
 */
class WindowsNativeViews internal constructor(private val backend: Win32NativeViewsBackend) : NativeViews {
    private class Slot {
        var region = NativeViewRegion(PixelRect(0, 0, 0, 0))
        var placed: NativeViewRegion? = null
        var visible = false
    }

    private val slots = LinkedHashMap<Int, Slot>()

    /** The attached slots, in the order they were attached, bottom first. */
    val attached: List<Int> get() = slots.keys.toList()

    override val parentHandle: Long get() = backend.parentHandle

    override fun attach(id: Int, handle: Long): Boolean {
        if (handle == 0L || id in slots) return false
        if (!backend.attach(id, handle)) return false
        slots[id] = Slot()
        return true
    }

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        val slot = slots[id] ?: return
        slot.region = slot.region.copy(frame = PixelRect(x, y, maxOf(0, width), maxOf(0, height)))
        place(id, slot)
    }

    override fun setVisibleRegion(id: Int, clip: PixelRect?, holes: List<PixelRoundRect>) {
        val slot = slots[id] ?: return
        slot.region = slot.region.copy(clip = clip, holes = holes.filter { !it.rect.isEmpty })
        place(id, slot)
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
        slot.region = NativeViewRegion(
            PixelRect(x, y, maxOf(0, width), maxOf(0, height)),
            clip,
            holes.filter { !it.rect.isEmpty },
        )
        place(id, slot)
    }

    override fun setVisible(id: Int, visible: Boolean) {
        val slot = slots[id] ?: return
        if (slot.visible == visible) return
        slot.visible = visible
        backend.setVisible(id, visible)
    }

    override fun focus(id: Int) {
        val slot = slots[id] ?: return
        if (!slot.visible) return
        backend.focus(id)
    }

    override fun focusScene() = backend.focusScene()

    override fun detach(id: Int) {
        if (slots.remove(id) != null) backend.detach(id)
    }

    /** Detaches every slot, as the window does itself when it closes. */
    fun detachAll() {
        for (id in slots.keys.toList()) detach(id)
    }

    private fun place(id: Int, slot: Slot) {
        if (slot.region == slot.placed) return
        slot.placed = slot.region
        backend.place(id, slot.region)
    }
}

/**
 * The holes of [region] as `dxc_native_view_place` takes them: five numbers each, the
 * rectangle's x, y, width and height and the corner radius as asked (the window keeps it
 * within half the shorter side).
 */
internal fun nativeViewHoleWords(region: NativeViewRegion): IntArray {
    val words = IntArray(region.holes.size * 5)
    for ((index, hole) in region.holes.withIndex()) {
        val at = index * 5
        words[at] = hole.rect.x
        words[at + 1] = hole.rect.y
        words[at + 2] = hole.rect.width
        words[at + 3] = hole.rect.height
        words[at + 4] = hole.cornerRadius
    }
    return words
}

/** [Win32NativeViewsBackend] over the shared C window [window], whichever window is selected. */
internal class Win32CNativeViewsBackend(private val window: COpaquePointer) : Win32NativeViewsBackend {
    override val parentHandle: Long get() = dxc_native_view_parent(window)

    override fun attach(id: Int, handle: Long): Boolean = dxc_native_view_attach(window, id, handle) != 0

    override fun place(id: Int, region: NativeViewRegion) {
        val frame = region.frame
        val clip = region.clip
        val holes = nativeViewHoleWords(region)
        if (holes.isEmpty()) {
            dxc_native_view_place(
                window, id, frame.x, frame.y, frame.width, frame.height,
                if (clip != null) 1 else 0, clip?.x ?: 0, clip?.y ?: 0, clip?.width ?: 0, clip?.height ?: 0,
                null, 0,
            )
        } else {
            holes.usePinned { pinned ->
                dxc_native_view_place(
                    window, id, frame.x, frame.y, frame.width, frame.height,
                    if (clip != null) 1 else 0, clip?.x ?: 0, clip?.y ?: 0, clip?.width ?: 0, clip?.height ?: 0,
                    pinned.addressOf(0), holes.size / 5,
                )
            }
        }
    }

    override fun setVisible(id: Int, visible: Boolean) = dxc_native_view_set_visible(window, id, if (visible) 1 else 0)

    override fun focus(id: Int) = dxc_native_view_focus(window, id)

    override fun focusScene() = dxc_native_view_focus_scene(window)

    override fun detach(id: Int) = dxc_native_view_detach(window, id)
}
