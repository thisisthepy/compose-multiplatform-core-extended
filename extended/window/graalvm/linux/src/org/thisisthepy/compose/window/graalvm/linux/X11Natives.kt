package org.thisisthepy.compose.window.graalvm.linux

import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.word.Pointer

/**
 * The functions `c/x11_window.c` exports, declared once for the native-image build.
 *
 * Every call here is a downcall into C on the thread that owns the window. Nothing in this
 * file reaches back out: what C needs to call on this side is in [X11Upcalls].
 */
internal object X11Natives {
    @JvmStatic
    @CFunction("dxc_native_window_open")
    external fun windowOpen(title: CCharPointer?, width: Int, height: Int, out: Pointer?): Int

    @JvmStatic
    @CFunction("dxc_native_window_size")
    external fun windowSize(window: Pointer?, width: CIntPointer?, height: CIntPointer?, scale: CFloatPointer?)

    @JvmStatic
    @CFunction("dxc_native_window_configure")
    external fun windowConfigure(resizable: Int, minWidth: Int, minHeight: Int, systemChrome: Int, backdrop: Int)

    @JvmStatic
    @CFunction("dxc_native_window_action")
    external fun windowAction(action: Int)

    @JvmStatic
    @CFunction("dxc_native_window_begin_drag")
    external fun windowBeginDrag(edge: Int)

    @JvmStatic
    @CFunction("dxc_native_window_closed")
    external fun windowClosed(): Int

    @JvmStatic
    @CFunction("dxc_native_frame_begin")
    external fun frameBegin(window: Pointer?): Int

    @JvmStatic
    @CFunction("dxc_native_frame_end")
    external fun frameEnd(display: Pointer?)

    @JvmStatic
    @CFunction("dxc_native_pump")
    external fun pump(seconds: Double)

    @JvmStatic
    @CFunction("dxc_native_poll_event")
    external fun pollEvent(out: Pointer?): Int

    @JvmStatic
    @CFunction("dxc_native_set_cursor")
    external fun setCursor(shape: Int)

    @JvmStatic
    @CFunction("dxc_native_set_icon")
    external fun setIcon(rgba: CCharPointer?, width: Int, height: Int)

    @JvmStatic
    @CFunction("dxc_native_set_title")
    external fun setTitle(title: CCharPointer?)

    @JvmStatic
    @CFunction("dxc_native_set_min_size")
    external fun setMinSize(width: Int, height: Int)

    @JvmStatic
    @CFunction("dxc_native_set_ime_spot")
    external fun setImeSpot(x: Float, y: Float)

    @JvmStatic
    @CFunction("dxc_native_clipboard_read")
    external fun clipboardRead(out: CCharPointer?, capacity: Int): Int

    @JvmStatic
    @CFunction("dxc_native_clipboard_write")
    external fun clipboardWrite(text: CCharPointer?)

    @JvmStatic
    @CFunction("dxc_native_dropped_paths")
    external fun droppedPaths(out: CCharPointer?, capacity: Int): Int

    @JvmStatic
    @CFunction("dxc_native_set_accessibility")
    external fun setAccessibility(elements: Pointer?, count: Int, window: Pointer?)

    @JvmStatic
    @CFunction("dxc_native_set_frame_callback")
    external fun setFrameCallback(callback: CFunctionPointer?, thread: IsolateThread?)
}

/** Sizes and offsets of the records `x11_window.c` fills in. */
internal object X11Layout {
    /** `struct dxc_native_window`: five pointer slots. */
    const val WINDOW_BYTES = 40

    /** `struct dxc_event`: kind, x, y, buttons, modifiers, key_code, code_point, then text. */
    const val EVENT_TEXT_BYTES = 96
    const val EVENT_BYTES = 28 + EVENT_TEXT_BYTES
}
