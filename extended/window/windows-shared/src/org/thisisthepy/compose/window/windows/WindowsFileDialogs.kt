package org.thisisthepy.compose.window.windows

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.toLong
import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogLanes
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.FileDialogs
import org.thisisthepy.compose.window.SheetPresenter
import org.thisisthepy.compose.window.fileDialogOutcome
import org.thisisthepy.compose.window.normalizeFileDialogRequest

/** How one dialog ended, as the C side reports it. [paths] is empty unless [status] is [CHOSEN]. */
class Win32FileDialogAnswer(val id: Long, val status: Int, val filterIndex: Int, val paths: List<String>) {
    companion object {
        const val CHOSEN: Int = 1
        const val CANCELLED: Int = 2
        const val UNAVAILABLE: Int = 3
    }
}

/**
 * The dialogs of the process as [Win32FileDialogCenter] needs them: start one, ask one to
 * close, and read the answers that have arrived. The C one runs each dialog on a thread of
 * its own; the tests use one that is a few lists.
 */
interface Win32FileDialogBackend {
    /**
     * Starts a dialog owned by the window [window] (its HWND) under the number [id]. True when
     * it started, and then exactly one answer for [id] comes through [poll]; false when
     * nothing started and no answer will come.
     */
    fun start(window: Long, id: Long, request: FileDialogRequest): Boolean

    /** Asks the open dialog [id] to close. Its answer still comes through [poll]. */
    fun cancel(id: Long)

    /** The oldest answer not read yet, or null. */
    fun poll(): Win32FileDialogAnswer?
}

/** A process with no dialogs: nothing starts. */
internal object Win32NoFileDialogs : Win32FileDialogBackend {
    override fun start(window: Long, id: Long, request: FileDialogRequest): Boolean = false
    override fun cancel(id: Long) {}
    override fun poll(): Win32FileDialogAnswer? = null
}

/**
 * The type list as the C side reads it: one line per filter, the label, a tab and the
 * extensions separated by commas. A label keeps no tab or line break, which would be read as
 * the end of the label or of the filter.
 */
internal fun encodeFileDialogFilters(request: FileDialogRequest): String =
    request.filters.joinToString("\n") { filter ->
        val label = filter.label.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').ifEmpty { " " }
        label + "\t" + filter.extensions.joinToString(",")
    }

/** The number the C side knows a kind by. */
internal fun fileDialogKindCode(kind: FileDialogKind): Int = when (kind) {
    FileDialogKind.OpenFile -> 1
    FileDialogKind.OpenFiles -> 2
    FileDialogKind.OpenFolder -> 3
    FileDialogKind.OpenFolders -> 4
    FileDialogKind.Save -> 5
}

/**
 * Every dialog of the process that is open, and who is waiting for it.
 *
 * Dialogs end on threads of their own; their answers wait in the backend until [deliver]
 * reads them, and [deliver] calls each waiting completion on the thread that called it. That
 * is the thread that pumps, so a completion never runs anywhere else. Call everything here
 * on that thread.
 */
class Win32FileDialogCenter(private val backend: Win32FileDialogBackend) {
    private class Waiting(val request: FileDialogRequest, val completion: (FileDialogOutcome) -> Unit)

    private val waiting = HashMap<Long, Waiting>()
    private var lastId = 0L

    /** How many dialogs are open and waited for. */
    val openCount: Int get() = waiting.size

    /**
     * Starts [request] on [window] and returns its number, or 0 when no dialog could be
     * started. [completion] is called once, from [deliver], unless the dialog is cancelled
     * first; it is not called when this returns 0.
     */
    internal fun start(window: Long, request: FileDialogRequest, completion: (FileDialogOutcome) -> Unit): Long {
        val id = ++lastId
        if (!backend.start(window, id, request)) return 0L
        waiting[id] = Waiting(request, completion)
        return id
    }

    /** Asks dialog [id] to close and forgets who waited for it: its answer is dropped when it comes. */
    internal fun cancel(id: Long) {
        if (waiting.remove(id) != null) backend.cancel(id)
    }

    /**
     * Hands every answer that has arrived to the completion waiting for it. Call it after each
     * pump, on the thread that pumps. [Win32Windows.pump] does.
     */
    fun deliver() {
        while (true) {
            val answer = backend.poll() ?: break
            val entry = waiting.remove(answer.id) ?: continue
            entry.completion(outcomeOf(entry.request, answer))
        }
    }

    private fun outcomeOf(request: FileDialogRequest, answer: Win32FileDialogAnswer): FileDialogOutcome =
        when (answer.status) {
            Win32FileDialogAnswer.CHOSEN -> {
                val folders = request.kind == FileDialogKind.OpenFolder || request.kind == FileDialogKind.OpenFolders
                val filterIndex = when {
                    folders || request.filters.isEmpty() -> -1
                    answer.filterIndex in request.filters.indices -> answer.filterIndex
                    else -> request.defaultFilter
                }
                fileDialogOutcome(answer.paths, filterIndex)
            }
            Win32FileDialogAnswer.CANCELLED -> FileDialogOutcome.Cancelled
            else -> FileDialogOutcome.Unavailable
        }
}

/**
 * File dialogs owned by a window: the system's open and save dialogs (the Common Item
 * Dialog), modal to that window only.
 *
 * Nothing here blocks: each dialog runs on a thread of its own in the C side
 * (`c/win32_file_dialog.c`), so the window keeps pumping and drawing while it is open and
 * only input to the owning window waits. The queueing rules are [FileDialogLanes]: requests
 * for one window are shown one at a time, in order.
 *
 * [show] is called on the thread that pumps the window, and the result is delivered on that
 * same thread, exactly once, from [Win32Loop.deliverFileDialogs] (which [Win32Windows.pump]
 * calls). A caller that pumps with [Win32Loop.pump] calls [Win32Loop.deliverFileDialogs]
 * after it.
 *
 * [owner] is asked when a request is shown and returns the window's handle (the HWND), or
 * null when that window is gone.
 */
class WindowsFileDialogs internal constructor(
    private val center: Win32FileDialogCenter,
    private val owner: () -> Long?,
) : FileDialogs {
    /** Dialogs owned by the window [owner] returns: [Win32NativeWindow.window]. */
    constructor(owner: () -> COpaquePointer?) : this(
        Win32System.windows.fileDialogs,
        { owner()?.toLong()?.takeIf { it != 0L } },
    )

    /** One object per window for as long as the handle stays the same, which the lanes compare by identity. */
    private class Owner(val handle: Long)

    private var lastOwner: Owner? = null

    private fun currentOwner(): Owner? {
        val handle = owner() ?: return null
        val last = lastOwner
        if (last != null && last.handle == handle) return last
        return Owner(handle).also { lastOwner = it }
    }

    private inner class Presenter(private val handle: Long) : SheetPresenter {
        private var open = 0L

        override fun present(request: FileDialogRequest, completion: (FileDialogOutcome) -> Unit) {
            val id = center.start(handle, request, completion)
            if (id == 0L) completion(FileDialogOutcome.Unavailable) else open = id
        }

        override fun dismiss() {
            if (open != 0L) center.cancel(open)
        }

        override fun release() {
            open = 0L
        }
    }

    private val lanes = FileDialogLanes(::currentOwner) { window: Owner -> Presenter(window.handle) }

    override fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        lanes.show(normalizeFileDialogRequest(request), onResult)
    }

    /**
     * Closes the open dialog and ends it and every waiting request with Cancelled, each
     * exactly once, and answers Unavailable to anything shown afterwards. Call it on the
     * thread that pumps, before the window is destroyed.
     */
    fun cancelAll() = lanes.cancelAll()
}
