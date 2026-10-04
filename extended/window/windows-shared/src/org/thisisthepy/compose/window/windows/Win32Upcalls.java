package org.thisisthepy.compose.window.windows;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;

/**
 * The address the Win32 window calls to have a frame drawn from inside a message.
 *
 * Windows runs a loop of its own inside the handler for the press that starts a drag of
 * the window's edge, and the renderer's frame loop is stopped for all of it. The window
 * reaches back through this entry point to get a frame drawn at the size it just became.
 *
 * The address is a {@link CEntryPointLiteral} resolved while the image is built, so the
 * call from C is a plain function call into a static method: no lookup by name, no
 * reflection and no JNI at run time. The method forwards to {@link Win32UpcallSlots}.
 * Nothing may be thrown out of an entry point, because an exception crossing into C is
 * undefined.
 */
public final class Win32Upcalls {
    private Win32Upcalls() {
    }

    /** The address of the entry point {@code dxc_win32_draw_frame}. */
    public static final CEntryPointLiteral<CFunctionPointer> DRAW_FRAME =
        CEntryPointLiteral.create(Win32Upcalls.class, "call_frame", IsolateThread.class);

    @CEntryPoint(name = "dxc_win32_draw_frame")
    static void call_frame(IsolateThread thread) {
        try {
            Runnable handler = Win32UpcallSlots.INSTANCE.getFrame();
            if (handler != null) {
                handler.run();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
        }
    }
}
