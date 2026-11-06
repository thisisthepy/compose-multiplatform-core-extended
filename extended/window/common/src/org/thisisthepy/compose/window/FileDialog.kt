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

/** Shows file dialogs on the platform. */
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
 * a name keeps only what follows its last path separator.
 */
fun normalizeFileDialogRequest(request: FileDialogRequest): FileDialogRequest {
    val filters = request.filters.take(FILE_DIALOG_MAX_FILTERS).map { filter ->
        val extensions = filter.extensions
            .map { it.trim().removePrefix("*.").trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }
            .take(FILE_DIALOG_MAX_EXTENSIONS)
        FileDialogFilter(filter.label, extensions)
    }
    val defaultFilter = if (request.defaultFilter in filters.indices) request.defaultFilter else 0
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
