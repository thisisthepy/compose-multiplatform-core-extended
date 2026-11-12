package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.CaptionAction
import org.thisisthepy.compose.window.ContextMenuItem
import org.thisisthepy.compose.window.ScreenPointer
import org.thisisthepy.compose.window.WindowBounds
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.WindowVisibility
import org.thisisthepy.compose.window.windows.c.dxc_native_clipboard_read
import org.thisisthepy.compose.window.windows.c.dxc_native_clipboard_write
import org.thisisthepy.compose.window.windows.c.dxc_native_context_menu
import org.thisisthepy.compose.window.windows.c.dxc_native_follow_start
import org.thisisthepy.compose.window.windows.c.dxc_native_pointer_on_screen
import org.thisisthepy.compose.window.windows.c.dxc_native_post_quit
import org.thisisthepy.compose.window.windows.c.dxc_native_pump
import org.thisisthepy.compose.window.windows.c.dxc_native_quit_posted
import org.thisisthepy.compose.window.windows.c.dxc_native_set_ime_spot
import org.thisisthepy.compose.window.windows.c.dxc_native_system_dark
import org.thisisthepy.compose.window.windows.c.dxc_native_take_activation
import org.thisisthepy.compose.window.windows.c.dxc_native_take_close_request
import org.thisisthepy.compose.window.windows.c.dxc_native_take_follow_end
import org.thisisthepy.compose.window.windows.c.dxc_native_take_moved
import org.thisisthepy.compose.window.windows.c.dxc_native_window_action
import org.thisisthepy.compose.window.windows.c.dxc_native_window_bounds
import org.thisisthepy.compose.window.windows.c.dxc_native_window_closed
import org.thisisthepy.compose.window.windows.c.dxc_native_window_destroy
import org.thisisthepy.compose.window.windows.c.dxc_native_window_focus
import org.thisisthepy.compose.window.windows.c.dxc_native_window_is_active
import org.thisisthepy.compose.window.windows.c.dxc_native_window_release
import org.thisisthepy.compose.window.windows.c.dxc_native_window_select
import org.thisisthepy.compose.window.windows.c.dxc_native_window_set_content_size
import org.thisisthepy.compose.window.windows.c.dxc_native_window_set_min_size
import org.thisisthepy.compose.window.windows.c.dxc_native_window_set_position
import org.thisisthepy.compose.window.windows.c.dxc_native_window_set_title
import org.thisisthepy.compose.window.windows.c.dxc_native_window_set_visibility

/**
 * [Win32WindowBackend] over the shared C window. The C calls that name no window run for the
 * selected one, so every call here selects its window first; the tests of the platform use a
 * backend of their own and never reach this file.
 */
internal class Win32CWindowBackend(private val native: Win32NativeWindow) : Win32WindowBackend {
    private var released = false

    private inline fun <T> on(whenReleased: T, block: () -> T): T {
        if (released) return whenReleased
        dxc_native_window_select(native.window)
        return block()
    }

    override val closed: Boolean get() = on(true) { dxc_native_window_closed() != 0 }

    override fun measure(): WindowMeasurement = on(WindowMeasurement(0, 0, 1f)) { native.measure() }

    override fun drainEvents(): List<WindowEvent> = on(emptyList()) { Win32Loop.drainEvents() }

    override fun setTitle(title: String) = on(Unit) { dxc_native_window_set_title(title) }

    override fun setMinimumSize(width: Int, height: Int) =
        on(Unit) { dxc_native_window_set_min_size(width, height) }

    override fun setVisibility(visibility: WindowVisibility) = on(Unit) {
        val code = when (visibility) {
            WindowVisibility.Hidden -> 0
            WindowVisibility.Visible -> 1
            WindowVisibility.Minimized -> 2
            WindowVisibility.Fullscreen -> 3
        }
        dxc_native_window_set_visibility(code)
    }

    override fun readClipboardText(): String? = on(null) {
        memScoped {
            val buffer = allocArray<ByteVar>(CLIPBOARD_BYTES)
            val length = dxc_native_clipboard_read(buffer, CLIPBOARD_BYTES)
            if (length <= 0) null else buffer.readBytes(length).decodeToString()
        }
    }

    override fun writeClipboardText(text: String) = on(Unit) { dxc_native_clipboard_write(text) }

    override fun setImeSpot(x: Int, y: Int) = on(Unit) { dxc_native_set_ime_spot(x.toFloat(), y.toFloat()) }

    override fun showContextMenu(items: List<ContextMenuItem>): Int = on(-1) {
        val lines = StringBuilder()
        for ((index, item) in items.withIndex()) {
            if (index > 0) lines.append('\n')
            lines.append(index).append('\t').append(if (item.enabled) 1 else 0).append('\t').append(item.label)
        }
        dxc_native_context_menu(native.window, lines.toString())
    }

    override fun performCaptionAction(action: CaptionAction) = on(Unit) {
        dxc_native_window_action(
            when (action) {
                CaptionAction.Minimize -> 0
                CaptionAction.ToggleMaximize -> 1
                CaptionAction.Close -> 2
            },
        )
    }

    override fun systemDark(): Boolean = dxc_native_system_dark() != 0

    override fun setPosition(x: Int, y: Int) = on(Unit) { dxc_native_window_set_position(x, y) }

    override fun setContentSize(width: Int, height: Int) =
        on(Unit) { dxc_native_window_set_content_size(width, height) }

    override fun bounds(): WindowBounds? = on(null) { readBounds { x, y, w, h -> dxc_native_window_bounds(x, y, w, h) } }

    override fun focus(): Boolean = on(false) { dxc_native_window_focus() != 0 }

    override val isActive: Boolean get() = on(false) { dxc_native_window_is_active() != 0 }

    override fun pointerOnScreen(): ScreenPointer? = on(null) {
        memScoped {
            val x = alloc<FloatVar>()
            val y = alloc<FloatVar>()
            val down = alloc<IntVar>()
            if (dxc_native_pointer_on_screen(x.ptr, y.ptr, down.ptr) != 0) {
                ScreenPointer(x.value, y.value, down.value != 0)
            } else {
                null
            }
        }
    }

    override fun followStart(anchorX: Float, anchorY: Float): Boolean =
        on(false) { dxc_native_follow_start(anchorX, anchorY) != 0 }

    override fun takeActivation(): Boolean? = on(null) {
        memScoped {
            val active = alloc<IntVar>()
            if (dxc_native_take_activation(active.ptr) != 0) active.value != 0 else null
        }
    }

    override fun takeMoved(): WindowBounds? = on(null) {
        readBounds { x, y, w, h -> dxc_native_take_moved(x, y, w, h) }
    }

    override fun takeFollowEnd(): Boolean? = on(null) {
        memScoped {
            val cancelled = alloc<IntVar>()
            if (dxc_native_take_follow_end(cancelled.ptr) != 0) cancelled.value != 0 else null
        }
    }

    override fun takeCloseRequest(): Boolean = on(false) { dxc_native_take_close_request() != 0 }

    override fun destroy() = on(Unit) { dxc_native_window_destroy(native.window) }

    override fun release() {
        if (released) return
        dxc_native_window_release(native.window)
        released = true
    }

    private inline fun readBounds(
        call: (kotlinx.cinterop.CPointer<IntVar>, kotlinx.cinterop.CPointer<IntVar>,
            kotlinx.cinterop.CPointer<IntVar>, kotlinx.cinterop.CPointer<IntVar>) -> Int,
    ): WindowBounds? = memScoped {
        val x = alloc<IntVar>()
        val y = alloc<IntVar>()
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        if (call(x.ptr, y.ptr, width.ptr, height.ptr) != 0) {
            WindowBounds(x.value, y.value, width.value, height.value)
        } else {
            null
        }
    }

    private companion object {
        const val CLIPBOARD_BYTES = 64 * 1024
    }
}

/** [Win32ProcessBackend] over the shared C window. */
internal object Win32CProcessBackend : Win32ProcessBackend {
    override fun openWindow(config: WindowConfig): Win32WindowBackend? =
        Win32NativeWindow.open(config)?.let { Win32CWindowBackend(it) }

    override fun pump(seconds: Double) = dxc_native_pump(seconds)

    override fun postQuit(code: Int) = dxc_native_post_quit(code)

    override fun quitPosted(): Int? = memScoped {
        val code = alloc<IntVar>()
        if (dxc_native_quit_posted(code.ptr) != 0) code.value else null
    }
}

/** The windows of this process, over the shared C window. Created on first use. */
object Win32System {
    val windows: Win32Windows by lazy { Win32Windows(Win32CProcessBackend) }
}
