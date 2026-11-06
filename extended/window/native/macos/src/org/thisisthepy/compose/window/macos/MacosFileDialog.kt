@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.FileDialogs
import org.thisisthepy.compose.window.normalizeFileDialogRequest
import platform.AppKit.NSModalResponseCancel
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSOpenPanel
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSSavePanel
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Target of the filter pop-up. The button holds its target weakly, so the sheet keeps this alive. */
private class FilterPicker(private val onPicked: () -> Unit) : NSObject() {
    @ObjCAction
    fun picked(sender: NSObject?) = onPicked()
}

/**
 * File dialogs shown as sheets on a window.
 *
 * A sheet is attached with `beginSheetModalForWindow`, so nothing here blocks the main
 * thread. A window takes one sheet at a time: further requests for it wait in order, and a
 * request for another window does not wait for this one. [owner] is asked when a request is
 * shown and returns the window to attach to, or null when that window is gone.
 */
class MacosFileDialogs(private val owner: () -> NSWindow?) : FileDialogs {

    private class Entry(
        val request: FileDialogRequest,
        val onResult: (FileDialogOutcome) -> Unit,
    ) {
        var done = false
        var panel: NSSavePanel? = null
        var picker: FilterPicker? = null
        var popUp: NSPopUpButton? = null
    }

    private class Lane(val window: NSWindow) {
        val waiting = ArrayDeque<Entry>()
        var active: Entry? = null
    }

    private val lanes = HashMap<NSWindow, Lane>()

    override fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        if (NSThread.isMainThread) {
            enqueue(request, onResult)
        } else {
            dispatch_async(dispatch_get_main_queue()) { enqueue(request, onResult) }
        }
    }

    /**
     * Ends every open sheet and every waiting request with Cancelled, each exactly once.
     * Call it when the window is being destroyed.
     */
    fun cancelAll() {
        val all = lanes.values.toList()
        lanes.clear()
        for (lane in all) {
            val queued = lane.waiting.toList()
            lane.waiting.clear()
            val active = lane.active
            lane.active = null
            if (active != null) {
                complete(active, FileDialogOutcome.Cancelled)
                active.panel?.let { lane.window.endSheet(it, NSModalResponseCancel) }
            }
            for (entry in queued) complete(entry, FileDialogOutcome.Cancelled)
        }
    }

    private fun enqueue(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        val entry = Entry(normalizeFileDialogRequest(request), onResult)
        val window = owner()
        if (window == null) {
            complete(entry, FileDialogOutcome.Unavailable)
            return
        }
        val lane = lanes.getOrPut(window) { Lane(window) }
        lane.waiting.addLast(entry)
        if (lane.active == null) startNext(lane)
    }

    private fun startNext(lane: Lane) {
        while (lane.active == null) {
            val entry = lane.waiting.removeFirstOrNull() ?: break
            // The window may have been closed while the request waited.
            if (owner() !== lane.window) {
                complete(entry, FileDialogOutcome.Unavailable)
                continue
            }
            lane.active = entry
            present(lane, entry)
        }
        if (lane.active == null && lane.waiting.isEmpty()) lanes.remove(lane.window)
    }

    private fun present(lane: Lane, entry: Entry) {
        val window = lane.window
        val request = entry.request
        val panel = buildPanel(entry)
        entry.panel = panel
        val previousResponder = window.firstResponder
        panel.beginSheetModalForWindow(window) { response ->
            if (entry.done) return@beginSheetModalForWindow
            val outcome = outcomeOf(entry, panel, response)
            if (lane.active === entry) lane.active = null
            complete(entry, outcome)
            restoreFocus(window, previousResponder)
            startNext(lane)
        }
    }

    private fun buildPanel(entry: Entry): NSSavePanel {
        val request = entry.request
        val panel: NSSavePanel
        if (request.kind == FileDialogKind.Save) {
            panel = NSSavePanel.savePanel()
            panel.nameFieldStringValue = request.name
            panel.canCreateDirectories = true
        } else {
            val open = NSOpenPanel.openPanel()
            val folders = request.kind == FileDialogKind.OpenFolder || request.kind == FileDialogKind.OpenFolders
            open.canChooseFiles = !folders
            open.canChooseDirectories = folders
            open.allowsMultipleSelection =
                request.kind == FileDialogKind.OpenFiles || request.kind == FileDialogKind.OpenFolders
            open.resolvesAliases = true
            panel = open
        }
        // A sheet does not show a title, so the text goes where a sheet does show it.
        if (request.title.isNotEmpty()) panel.message = request.title
        if (request.directory.isNotEmpty() && directoryExists(request.directory)) {
            panel.directoryURL = NSURL.fileURLWithPath(request.directory, isDirectory = true)
        }
        val folders = request.kind == FileDialogKind.OpenFolder || request.kind == FileDialogKind.OpenFolders
        if (!folders && request.filters.isNotEmpty()) {
            applyFilter(panel, request, request.defaultFilter)
            if (request.filters.size > 1) {
                val popUp = NSPopUpButton(frame = CGRectMake(0.0, 0.0, 240.0, 26.0), pullsDown = false)
                request.filters.forEach { popUp.addItemWithTitle(it.label) }
                popUp.selectItemAtIndex(request.defaultFilter.toLong())
                val picker = FilterPicker { applyFilter(panel, request, popUp.indexOfSelectedItem.toInt()) }
                popUp.target = picker
                popUp.action = NSSelectorFromString("picked:")
                popUp.sizeToFit()
                entry.popUp = popUp
                entry.picker = picker
                panel.accessoryView = popUp
            }
        }
        return panel
    }

    private fun applyFilter(panel: NSSavePanel, request: FileDialogRequest, index: Int) {
        val extensions = request.filters.getOrNull(index)?.extensions.orEmpty()
        // An empty list means every file.
        panel.allowedContentTypes = extensions.mapNotNull { UTType.typeWithFilenameExtension(it) }
    }

    private fun directoryExists(path: String): Boolean = memScoped {
        val isDirectory = alloc<BooleanVar>()
        NSFileManager.defaultManager.fileExistsAtPath(path, isDirectory = isDirectory.ptr) && isDirectory.value
    }

    private fun outcomeOf(entry: Entry, panel: NSSavePanel, response: Long): FileDialogOutcome {
        if (response != NSModalResponseOK) return FileDialogOutcome.Cancelled
        val urls: List<NSURL?> = if (panel is NSOpenPanel) {
            panel.URLs.map { it as? NSURL }
        } else {
            listOf(panel.URL)
        }
        val paths = urls.mapNotNull { it?.path }
        if (paths.isEmpty()) return FileDialogOutcome.Unavailable
        val request = entry.request
        val folders = request.kind == FileDialogKind.OpenFolder || request.kind == FileDialogKind.OpenFolders
        val filterIndex = when {
            folders || request.filters.isEmpty() -> -1
            else -> entry.popUp?.indexOfSelectedItem?.toInt() ?: request.defaultFilter
        }
        return FileDialogOutcome.Chosen(paths, filterIndex)
    }

    private fun restoreFocus(window: NSWindow, previous: Any?) {
        val view = previous as? NSView ?: return
        if (view.window === window && window.firstResponder !== view) window.makeFirstResponder(view)
    }

    private fun complete(entry: Entry, outcome: FileDialogOutcome) {
        if (entry.done) return
        entry.done = true
        entry.onResult(outcome)
    }
}
