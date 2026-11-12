package org.thisisthepy.compose.window

/** What a file dialog lets the reader pick. */
enum class FileDialogKind { OpenFile, OpenFiles, OpenFolder, OpenFolders, Save }

/**
 * One entry of the file type list.
 *
 * [extensions] are written without a dot and compared case-insensitively. An empty list
 * means every file.
 */
class FileDialogFilter(val label: String, val extensions: List<String>)

/**
 * A request for a file dialog.
 *
 * [directory] is where the dialog starts, [name] is the suggested file name of a save, and
 * [defaultFilter] indexes [filters]. Empty text means "leave it to the system".
 */
class FileDialogRequest(
    val kind: FileDialogKind,
    val title: String = "",
    val directory: String = "",
    val name: String = "",
    val filters: List<FileDialogFilter> = emptyList(),
    val defaultFilter: Int = 0,
)

/** How a file dialog ended. */
sealed class FileDialogOutcome {
    /** The reader chose [paths]. [filterIndex] is the selected filter, or -1 when there is none. */
    class Chosen(val paths: List<String>, val filterIndex: Int) : FileDialogOutcome()

    /** The reader dismissed the dialog. */
    object Cancelled : FileDialogOutcome()

    /** No dialog could be shown, or it ended without a usable path. */
    object Unavailable : FileDialogOutcome()
}

/**
 * Shows file dialogs on the platform.
 *
 * The signature stays `(request, onResult)` on purpose: a renderer-side adapter wraps one
 * per-window implementation (such as one MacosFileDialogs per window) behind this interface.
 * The window is chosen by that adapter, never passed in a request.
 */
fun interface FileDialogs {
    /**
     * Shows [request] without blocking the caller.
     *
     * [onResult] is called exactly once, on the UI (main) thread.
     */
    fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit)
}

/** The most filters a dialog takes. The rest are dropped. */
const val FILE_DIALOG_MAX_FILTERS = 16

/** The most extensions one filter takes. The rest are dropped. */
const val FILE_DIALOG_MAX_EXTENSIONS = 32

/**
 * Brings a request into the shape every platform dialog can take.
 *
 * Too many filters or extensions are cut rather than refused, an out-of-range default
 * filter becomes the first, extensions lose a leading dot or `*.` and are lower-cased, and
 * a name keeps only what follows its last path separator. A filter whose extensions are all
 * empty after cleaning is dropped; a filter given with no extensions at all means every file.
 */
fun normalizeFileDialogRequest(request: FileDialogRequest): FileDialogRequest {
    val kept = ArrayList<FileDialogFilter>()
    var defaultFilter = 0
    request.filters.take(FILE_DIALOG_MAX_FILTERS).forEachIndexed { index, filter ->
        val extensions = filter.extensions
            .map { it.trim().removePrefix("*.").trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }
            .take(FILE_DIALOG_MAX_EXTENSIONS)
        // A filter that named extensions but kept none would turn into "all files"; drop it.
        if (extensions.isEmpty() && filter.extensions.isNotEmpty()) return@forEachIndexed
        if (index == request.defaultFilter) defaultFilter = kept.size
        kept.add(FileDialogFilter(filter.label, extensions))
    }
    val filters: List<FileDialogFilter> = kept
    val name = request.name.substring(maxOf(request.name.lastIndexOf('/'), request.name.lastIndexOf('\\')) + 1)
    return FileDialogRequest(
        kind = request.kind,
        title = request.title,
        directory = request.directory,
        name = name,
        filters = filters,
        defaultFilter = defaultFilter,
    )
}

/** The most paths one dialog result carries. A larger selection is refused, not cut. */
const val FILE_DIALOG_MAX_PATHS = 10_000

/** The most UTF-8 bytes one dialog result carries: all paths plus one separator each (32 MiB). */
const val FILE_DIALOG_MAX_PAYLOAD_BYTES = 32 * 1024 * 1024

/**
 * Turns the paths a platform dialog returned into an outcome.
 *
 * An empty list is Cancelled. A null entry (a selection with no usable path) makes the whole
 * outcome Unavailable, so the chosen count always equals the returned count. A result over
 * [FILE_DIALOG_MAX_PATHS] paths or [FILE_DIALOG_MAX_PAYLOAD_BYTES] bytes is Unavailable too,
 * never a truncated Chosen.
 */
fun fileDialogOutcome(paths: List<String?>, filterIndex: Int): FileDialogOutcome {
    if (paths.isEmpty()) return FileDialogOutcome.Cancelled
    if (paths.size > FILE_DIALOG_MAX_PATHS) return FileDialogOutcome.Unavailable
    var bytes = 0L
    val checked = ArrayList<String>(paths.size)
    for (path in paths) {
        if (path == null) return FileDialogOutcome.Unavailable
        bytes += path.encodeToByteArray().size + 1L
        if (bytes > FILE_DIALOG_MAX_PAYLOAD_BYTES) return FileDialogOutcome.Unavailable
        checked.add(path)
    }
    return FileDialogOutcome.Chosen(checked, filterIndex)
}

/**
 * Shows one sheet at a time on one window. [FileDialogLanes] drives it.
 *
 * [present] must call [completion] when the sheet ends, normally once. [dismiss] ends the
 * sheet early, and the platform may call the completion in response. [release] drops every
 * platform object the last [present] created and is safe to call more than once.
 */
interface SheetPresenter {
    fun present(request: FileDialogRequest, completion: (FileDialogOutcome) -> Unit)
    fun dismiss()
    fun release()
}

/**
 * The queueing rules of sheet dialogs, free of any platform type.
 *
 * A window takes one sheet at a time: further requests for it wait in order, and a request
 * for another window does not wait for this one. [owner] returns the window a request attaches
 * to, or null when it is gone; [presenterFor] makes the presenter of one window. Every request
 * gets its outcome exactly once. Call everything on one thread (the UI thread).
 */
class FileDialogLanes<W : Any>(
    private val owner: () -> W?,
    private val presenterFor: (W) -> SheetPresenter,
) {
    private class Entry(val request: FileDialogRequest, val onResult: (FileDialogOutcome) -> Unit) {
        var done = false
    }

    private class Lane<W>(val window: W, val presenter: SheetPresenter) {
        val waiting = ArrayDeque<Entry>()
        var active: Entry? = null
        var closed = false
    }

    private val lanes = HashMap<W, Lane<W>>()
    private var allClosed = false

    fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        val entry = Entry(request, onResult)
        if (allClosed) return deliver(entry, FileDialogOutcome.Unavailable)
        val window = owner() ?: return deliver(entry, FileDialogOutcome.Unavailable)
        val lane = lanes.getOrPut(window) { Lane(window, presenterFor(window)) }
        if (lane.closed) return deliver(entry, FileDialogOutcome.Unavailable)
        lane.waiting.addLast(entry)
        if (lane.active == null) startNext(lane)
    }

    /**
     * Ends every open sheet and every waiting request with Cancelled, each exactly once, and
     * refuses further requests. The sheet is ended first and only while its window still exists.
     */
    fun cancelAll() {
        allClosed = true
        val all = lanes.values.toList()
        for (lane in all) lane.closed = true
        lanes.clear()
        for (lane in all) {
            val queued = lane.waiting.toList()
            lane.waiting.clear()
            val active = lane.active
            lane.active = null
            if (active != null) {
                if (owner() === lane.window) lane.presenter.dismiss()
                deliver(active, FileDialogOutcome.Cancelled)
            }
            lane.presenter.release()
            for (entry in queued) deliver(entry, FileDialogOutcome.Cancelled)
        }
    }

    private fun startNext(lane: Lane<W>) {
        while (lane.active == null && !lane.closed) {
            val entry = lane.waiting.removeFirstOrNull() ?: break
            // The window may have been closed while the request waited.
            if (owner() !== lane.window) {
                deliver(entry, FileDialogOutcome.Unavailable)
                continue
            }
            lane.active = entry
            lane.presenter.present(entry.request) { outcome -> completed(lane, entry, outcome) }
        }
        if (!lane.closed && lane.active == null && lane.waiting.isEmpty()) {
            lanes.remove(lane.window)
            lane.presenter.release()
        }
    }

    private fun completed(lane: Lane<W>, entry: Entry, outcome: FileDialogOutcome) {
        if (entry.done) return
        if (lane.active === entry) lane.active = null
        // Release first: the callback may call show() again and start a new sheet on this
        // presenter, which the release of the finished one must not touch.
        lane.presenter.release()
        try {
            deliver(entry, outcome)
        } finally {
            if (!lane.closed) startNext(lane)
        }
    }

    private fun deliver(entry: Entry, outcome: FileDialogOutcome) {
        if (entry.done) return
        entry.done = true
        entry.onResult(outcome)
    }
}
