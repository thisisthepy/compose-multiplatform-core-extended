@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.linux

import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.FileDialogs
import org.thisisthepy.compose.window.atspi.BusConnection
import org.thisisthepy.compose.window.portal.FileChooserPortal
import org.thisisthepy.compose.window.portal.PortalFileDialogs
import platform.posix.getpid

/** Something that reads a bus connection of its own on the turns of a window's loop. */
internal interface WindowBusClient {
    /** The sockets to watch beside the display's, so an answer ends the loop's wait. */
    fun descriptors(): List<Int>

    /** Called once a turn, on the window's thread, after the turn's events were delivered. */
    fun pump()

    /** The window is being destroyed. */
    fun windowClosing()
}

/**
 * File dialogs for an [X11Window], shown by the desktop portal
 * (`org.freedesktop.portal.FileChooser` on the session bus).
 *
 * The dialog is the desktop's own (GTK, KDE, ...) and runs in the portal's process, placed
 * over the window it is given. Nothing here blocks: [show] writes the request to the bus and
 * returns, and the answer is read on the window's loop, whose wait also watches the bus socket.
 * The result is delivered exactly once, from inside [X11Window.pump], on the thread that pumps
 * (or from [show] itself where no dialog could be asked for).
 *
 * One dialog at a time per window, the rest wait in order ([PortalFileDialogs]). Where there is
 * no session bus or no portal on it (`ServiceUnknown`), or the portal has no file chooser, the
 * outcome is [FileDialogOutcome.Unavailable]; there is no toolkit fallback.
 *
 * [owner] is asked when a request is shown and returns the window to attach to, or null when
 * that window is gone. Call [show] and [cancelAll] on the thread that pumps the window. The
 * window calls [cancelAll] itself when it closes.
 */
class LinuxFileDialogs internal constructor(
    owner: () -> X11Window?,
    openBus: () -> BusConnection?,
) : FileDialogs {
    constructor(owner: () -> X11Window?) : this(owner, { PosixBusConnection.open() })

    private val owner = owner
    private val portal = PortalFileDialogs(
        owner = owner,
        parentWindow = { window: X11Window ->
            val id = window.windowId
            if (window.isClosed || id == 0uL) null else FileChooserPortal.x11ParentWindow(id.toLong())
        },
        openBus = openBus,
        nowMillis = { monotonicClockNanos() / 1_000_000 },
        tokenPrefix = "compose_${getpid()}",
    )

    private val client = object : WindowBusClient {
        override fun descriptors(): List<Int> =
            portal.connections.mapNotNull { (it as? PosixBusConnection)?.descriptor }

        override fun pump() = portal.pump()

        override fun windowClosing() = cancelAll()
    }

    override fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        owner()?.attach(client)
        portal.show(request, onResult)
    }

    /**
     * Ends the open dialog and every waiting request with Cancelled, each exactly once, and
     * answers Unavailable to anything shown afterwards.
     */
    fun cancelAll() = portal.cancelAll()
}
