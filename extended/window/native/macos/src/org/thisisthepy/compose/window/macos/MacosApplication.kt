@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import org.thisisthepy.compose.window.WindowApplication
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationDelegateProtocol
import platform.Foundation.NSNotification
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.darwin.NSObject

/**
 * What the process has once however many windows it opens: the application delegate, the
 * appearance observer, the list of platforms that are open, and who waits in a pump turn.
 */
internal object MacosProcess {
    val platforms = mutableListOf<MacosWindowPlatform>()
    val turn = PumpTurn()

    /** Set when Escape reached any window; read and cleared by a follow in progress. */
    var escapePressed = false

    private var installed = false
    private var appDelegate: MacosAppDelegate? = null

    /** Done by the first window; later windows find it done. */
    fun install() {
        if (installed) return
        installed = true
        val app = NSApplication.sharedApplication()
        // A process that is not a bundled application starts with a policy under which none of
        // its windows can become key. The windows are the application's face, so it is regular.
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        if (app.delegate == null) {
            val delegate = MacosAppDelegate()
            appDelegate = delegate
            app.delegate = delegate
        }
        observeSystemAppearance { platforms.toList().forEach { it.reportTheme() } }
    }
}

/** The application delegate: Dock clicks reach the app, and the app decides when it ends. */
internal class MacosAppDelegate : NSObject(), NSApplicationDelegateProtocol {
    override fun applicationShouldHandleReopen(sender: NSApplication, hasVisibleWindows: Boolean): Boolean {
        WindowApplication.onReopen?.invoke()
        return true
    }

    override fun applicationShouldTerminateAfterLastWindowClosed(sender: NSApplication): Boolean = false
}

/** One window's delegate: what the window manager tells the window, handed to the platform. */
internal class MacosWindowDelegate(private val owner: MacosWindow) : NSObject(), NSWindowDelegateProtocol {
    override fun windowShouldClose(sender: NSWindow): Boolean = owner.onClose?.invoke() ?: true

    override fun windowDidBecomeKey(notification: NSNotification) {
        owner.onFocus?.invoke(true)
    }

    override fun windowDidResignKey(notification: NSNotification) {
        owner.onFocus?.invoke(false)
    }

    // Moves and resizes arrive in steps; the platform reports one per pump turn.
    override fun windowDidMove(notification: NSNotification) {
        owner.movedPending = true
    }

    override fun windowDidResize(notification: NSNotification) {
        owner.movedPending = true
    }

    override fun windowWillClose(notification: NSNotification) {
        owner.windowClosed()
    }
}
