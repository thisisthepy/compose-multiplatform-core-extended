package org.thisisthepy.compose.window.graalvm.linux

import org.graalvm.nativeimage.CurrentIsolate
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.graalvm.nativeimage.c.function.CEntryPointLiteral
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.word.WordFactory

/**
 * The table of functions C may call, and the only way C calls this side.
 *
 * Each entry is a `@CEntryPoint` and a [CEntryPointLiteral] created from a class literal,
 * which the image builder resolves while it builds. There is no `Class.forName`, no method
 * lookup by name at run time and nothing for a reflection configuration to list. An entry
 * that is renamed fails the image build rather than the first frame.
 *
 * `x11_window.c` calls [drawFrame] from inside the handling of a resize, because the frame
 * that belongs to the new size must reach the screen before the window manager shows the
 * edge. The scene, the surface and the decision to draw stay on this side.
 */
object X11Upcalls {
    private var painter: (() -> Boolean)? = null

    /**
     * Draws a frame where the window stands. Answers 1 when one was drawn and 0 when not,
     * because a resize the window manager is holding is released only by a drawn frame.
     * Nothing may unwind into C: a throw here would cross an X11 event handler.
     */
    @JvmStatic
    @CEntryPoint(name = "dxc_x11_draw_frame")
    fun drawFrame(@Suppress("UNUSED_PARAMETER") thread: IsolateThread?): Int =
        try {
            if (painter?.invoke() == true) 1 else 0
        } catch (t: Throwable) {
            t.printStackTrace()
            0
        }

    private val DRAW_FRAME: CEntryPointLiteral<CFunctionPointer> = CEntryPointLiteral.create(
        X11Upcalls::class.java,
        "drawFrame",
        IsolateThread::class.java,
    )

    /**
     * Registers what draws a frame. The thread goes with it: C calls back only on the
     * thread that registered, which is the thread frames are drawn on.
     */
    fun setFramePainter(paint: () -> Boolean) {
        painter = paint
        X11Natives.setFrameCallback(DRAW_FRAME.functionPointer, CurrentIsolate.getCurrentThread())
    }

    /** Removes the painter, so a closing window asks for nothing. */
    fun clearFramePainter() {
        painter = null
        X11Natives.setFrameCallback(WordFactory.nullPointer<CFunctionPointer>(), CurrentIsolate.getCurrentThread())
    }
}
