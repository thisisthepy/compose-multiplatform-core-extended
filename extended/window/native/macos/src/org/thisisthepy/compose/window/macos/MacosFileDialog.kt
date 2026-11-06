@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.thisisthepy.compose.window.macos

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.FileDialogKind
import org.thisisthepy.compose.window.FileDialogLanes
import org.thisisthepy.compose.window.FileDialogOutcome
import org.thisisthepy.compose.window.FileDialogRequest
import org.thisisthepy.compose.window.FileDialogs
import org.thisisthepy.compose.window.SheetPresenter
import org.thisisthepy.compose.window.fileDialogOutcome
import org.thisisthepy.compose.window.normalizeFileDialogRequest
import platform.AppKit.NSModalResponseCancel
import platform.AppKit.NSOpenSavePanelDelegateProtocol
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSOpenPanel
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSSavePanel
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.AppKit.NSWindowWillCloseNotification
import platform.AppKit.NSWorkspace
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.native.ref.WeakReference

/** Target of the filter pop-up. The button holds its target weakly, so the presenter keeps this alive. */
private class FilterPicker(private val onPicked: () -> Unit) : NSObject() {
    @ObjCAction
    fun picked(sender: NSObject?) = onPicked()
}

/**
 * Restricts an open panel to the extensions of the selected filter. It matches the end of the
 * file name case-insensitively, so it works for extensions the system has no type for.
 * Plain directories stay enabled so the reader can move through them; packages such as .app
 * count as files.
 */
// NSWindowDelegateProtocol is kept only because the Kotlin binding types panel.delegate as the window delegate.
private class ExtensionDelegate(var extensions: List<String>) : NSObject(), NSOpenSavePanelDelegateProtocol, NSWindowDelegateProtocol {
    override fun panel(sender: Any, shouldEnableURL: NSURL): Boolean {
        if (extensions.isEmpty()) return true
        val path = shouldEnableURL.path ?: return false
        val name = path.substringAfterLast('/').lowercase()
        // The name test is cheap; stat only when it does not already match (slow on network volumes).
        if (extensions.any { name.endsWith(".$it") }) return true
        return isPlainDirectory(path)
    }
}

private fun isPlainDirectory(path: String): Boolean =
    isDirectory(path) && !NSWorkspace.sharedWorkspace.isFilePackageAtPath(path)

private fun isDirectory(path: String): Boolean = memScoped {
    val flag = alloc<BooleanVar>()
    NSFileManager.defaultManager.fileExistsAtPath(path, isDirectory = flag.ptr) && flag.value
}

private fun macosAtLeast(major: Int): Boolean =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }.toInt() >= major

private fun isFolderKind(kind: FileDialogKind) =
    kind == FileDialogKind.OpenFolder || kind == FileDialogKind.OpenFolders

/** Shows one sheet on one window and owns every AppKit object it creates until [release]. */
private class MacosSheetPresenter(window: NSWindow) : SheetPresenter {
    // Weak, so a presenter that outlives its sheet never keeps the window alive.
    private val windowRef = WeakReference(window)
    private var dismissed = false
    private var panel: NSSavePanel? = null
    private var picker: FilterPicker? = null
    private var popUp: NSPopUpButton? = null
    private var delegate: ExtensionDelegate? = null

    override fun present(request: FileDialogRequest, completion: (FileDialogOutcome) -> Unit) {
        release()
        dismissed = false
        val window = windowRef.get()
        if (window == null) {
            completion(FileDialogOutcome.Unavailable)
            return
        }
        val panel = buildPanel(request)
        this.panel = panel
        val previousResponder = window.firstResponder
        panel.beginSheetModalForWindow(window) { response ->
            // Stale: released or dismissed since, so focus must stay where it is.
            val live = this.panel === panel && !dismissed
            // The outcome is read while the pop-up and the panel still exist.
            val outcome = outcomeOf(request, panel, response)
            if (live) restoreFocus(window, previousResponder)
            completion(outcome)
        }
    }

    override fun dismiss() {
        dismissed = true
        val panel = panel ?: return
        windowRef.get()?.endSheet(panel, NSModalResponseCancel)
    }

    override fun release() {
        panel?.delegate = null
        popUp?.target = null
        panel = null
        popUp = null
        picker = null
        delegate = null
    }

    private fun buildPanel(request: FileDialogRequest): NSSavePanel {
        val folders = isFolderKind(request.kind)
        val panel: NSSavePanel
        if (request.kind == FileDialogKind.Save) {
            panel = NSSavePanel.savePanel()
            panel.nameFieldStringValue = request.name
            panel.canCreateDirectories = true
        } else {
            val open = NSOpenPanel.openPanel()
            open.canChooseFiles = !folders
            open.canChooseDirectories = folders
            open.allowsMultipleSelection =
                request.kind == FileDialogKind.OpenFiles || request.kind == FileDialogKind.OpenFolders
            open.resolvesAliases = true
            // A folder picker must let the reader make the folder they want to choose.
            if (folders) open.canCreateDirectories = true
            panel = open
        }
        // Show the real file name, including its extension, in the name field.
        panel.extensionHidden = false
        // A sheet does not show a title, so the text goes where a sheet does show it.
        if (request.title.isNotEmpty()) panel.message = request.title
        if (request.directory.isNotEmpty() && isDirectory(request.directory)) {
            panel.directoryURL = NSURL.fileURLWithPath(request.directory, isDirectory = true)
        }
        // Folder pickers ignore filters.
        if (!folders && request.filters.isNotEmpty()) {
            if (panel !is NSOpenPanel) {
                // Save: a type list rewrites the name's extension; there is nothing to restrict.
                applySaveFilter(panel, request.filters[request.defaultFilter].extensions)
            } else {
                val extensionDelegate = ExtensionDelegate(request.filters[request.defaultFilter].extensions)
                delegate = extensionDelegate
                panel.delegate = extensionDelegate
            }
            if (request.filters.size > 1) {
                val popUp = NSPopUpButton(frame = CGRectMake(0.0, 0.0, 240.0, 26.0), pullsDown = false)
                request.filters.forEach { popUp.addItemWithTitle(it.label) }
                popUp.selectItemAtIndex(request.defaultFilter.toLong())
                val picker = FilterPicker {
                    val extensions = request.filters[popUp.indexOfSelectedItem.toInt()].extensions
                    val current = delegate
                    if (current != null) {
                        current.extensions = extensions
                        panel.validateVisibleColumns()
                    } else {
                        applySaveFilter(panel, extensions)
                    }
                }
                popUp.target = picker
                popUp.action = NSSelectorFromString("picked:")
                popUp.sizeToFit()
                this.popUp = popUp
                this.picker = picker
                panel.accessoryView = popUp
            }
        }
        return panel
    }

    /**
     * Save panels restrict through content types, which need macOS 11; below that nothing is
     * restricted. UTType.typeWithFilenameExtension yields a dynamic type even for an unknown
     * extension. If it still yields nothing, the type list stays empty and allowsOtherFileTypes
     * is on, so the panel neither restricts the name nor rewrites its extension.
     */
    private fun applySaveFilter(panel: NSSavePanel, extensions: List<String>) {
        if (!macosAtLeast(11)) return
        val types = extensions.mapNotNull { UTType.typeWithFilenameExtension(it) }
        panel.allowedContentTypes = types
        panel.allowsOtherFileTypes = types.isEmpty()
    }

    private fun outcomeOf(request: FileDialogRequest, panel: NSSavePanel, response: Long): FileDialogOutcome {
        if (response != NSModalResponseOK) return FileDialogOutcome.Cancelled
        // Null entries are kept so one bad selection fails the whole result.
        val paths: List<String?> = if (panel is NSOpenPanel) {
            panel.URLs.map { (it as? NSURL)?.path }
        } else {
            listOf(panel.URL?.path)
        }
        val filterIndex = when {
            isFolderKind(request.kind) || request.filters.isEmpty() -> -1
            else -> popUp?.indexOfSelectedItem?.toInt() ?: request.defaultFilter
        }
        return fileDialogOutcome(paths, filterIndex)
    }

    private fun restoreFocus(window: NSWindow, previous: Any?) {
        val view = previous as? NSView ?: return
        if (view.window === window && window.firstResponder !== view) window.makeFirstResponder(view)
    }
}

/**
 * File dialogs shown as sheets on a window.
 *
 * A sheet is attached with `beginSheetModalForWindow`, so nothing here blocks the main
 * thread. The queueing rules live in [FileDialogLanes]; this class supplies the AppKit
 * presenter. [owner] is asked when a request is shown and returns the window to attach to,
 * or null when that window is gone.
 */
class MacosFileDialogs(owner: () -> NSWindow?) : FileDialogs {
    private val owner = owner
    private val lanes = FileDialogLanes(owner) { window: NSWindow -> MacosSheetPresenter(window) }
    private var closeObserver: Any? = null

    override fun show(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        val normalized = normalizeFileDialogRequest(request)
        if (NSThread.isMainThread) {
            showOnMain(normalized, onResult)
        } else {
            dispatch_async(dispatch_get_main_queue()) { showOnMain(normalized, onResult) }
        }
    }

    private fun showOnMain(request: FileDialogRequest, onResult: (FileDialogOutcome) -> Unit) {
        observeClose()
        lanes.show(request, onResult)
    }

    /** A window that closes without cancelAll must still end its requests, so watch for it. */
    private fun observeClose() {
        if (closeObserver != null) return
        val window = owner() ?: return
        closeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSWindowWillCloseNotification,
            `object` = window,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> cancelAll() }
    }

    /**
     * Ends every open sheet and every waiting request with Cancelled, each exactly once, and
     * answers Unavailable to anything shown afterwards. Call it on the main thread when the
     * window is being destroyed.
     */
    fun cancelAll() {
        closeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        closeObserver = null
        lanes.cancelAll()
    }
}
