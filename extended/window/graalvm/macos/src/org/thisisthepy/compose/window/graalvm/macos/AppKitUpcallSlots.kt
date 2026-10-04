package org.thisisthepy.compose.window.graalvm.macos

/**
 * Where the generated [AppKitUpcalls] entry points deliver to.
 *
 * Set by whoever owns the window loop, on the thread that pumps it, because that is the
 * thread an upcall arrives on. A slot is read on every call, so it is volatile.
 */
object AppKitUpcallSlots {
    /** Draws one frame at the size the window has just become. */
    @Volatile
    var frame: Runnable? = null
}
