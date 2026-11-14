package org.thisisthepy.compose.window

/** Ids of the entries of the text edit menu, which come back from [WindowListener.onContextMenuChosen]. */
object EditMenuId {
    const val CUT = 1
    const val COPY = 2
    const val PASTE = 3
    const val SELECT_ALL = 4
}

/**
 * What a text selection offers when it is asked, in the order every other application puts
 * them. Which entries appear is decided afresh each time: there is nothing to copy without a
 * selection and nothing to paste without a clipboard. An entry the field does not support is
 * left out, not greyed.
 */
fun editMenuItems(
    canCut: Boolean,
    canCopy: Boolean,
    canPaste: Boolean,
    canSelectAll: Boolean,
): List<ContextMenuItem> {
    val items = ArrayList<ContextMenuItem>()
    if (canCut) items += ContextMenuItem(EditMenuId.CUT, "Cut")
    if (canCopy) items += ContextMenuItem(EditMenuId.COPY, "Copy")
    if (canPaste) items += ContextMenuItem(EditMenuId.PASTE, "Paste")
    if (canSelectAll) {
        if (items.isNotEmpty()) {
            items[items.lastIndex] = items.last().copy(separatorAfter = true)
        }
        items += ContextMenuItem(EditMenuId.SELECT_ALL, "Select All")
    }
    return items
}

/**
 * The menu a window that draws or pops up its own edit menu offers on a right click: all four
 * entries, in the usual order. Paste is greyed while [clipboardHasText] is false, because it
 * would do nothing. Cut and Copy cannot be judged from outside the field, which owns the
 * selection, and a Copy with nothing selected is ignored by the field.
 */
fun systemEditMenu(clipboardHasText: Boolean): List<ContextMenuItem> =
    editMenuItems(canCut = true, canCopy = true, canPaste = true, canSelectAll = true).map {
        if (it.id == EditMenuId.PASTE) it.copy(enabled = clipboardHasText) else it
    }

/**
 * What the focused text field allows, for a window that puts the edit menu up itself and
 * greys what cannot run: Cut needs a selection in an editable field, Copy a selection, Paste
 * an editable field (and text on the clipboard, which the window asks the system about),
 * Select All some text. [described] false is a field the caller cannot describe, for which
 * only Paste is judged, by the clipboard, as the macOS window judges it ([systemEditMenu]).
 */
data class EditMenuState(
    val hasSelection: Boolean = true,
    val editable: Boolean = true,
    val hasText: Boolean = true,
    val described: Boolean = true,
) {
    companion object {
        /** A focused field nobody described. */
        val Undescribed = EditMenuState(described = false)
    }
}

/**
 * The flags the Windows window's `dxc_native_set_edit_menu` takes (`win32_edit_menu.h`):
 * 0 for no field focused, which puts up no menu, and otherwise on, with what [state] says
 * the field allows.
 */
fun win32EditMenuFlags(state: EditMenuState?): Int {
    if (state == null) return 0
    var flags = WIN32_EDIT_MENU_ON
    if (!state.described) return flags
    flags = flags or WIN32_EDIT_MENU_KNOWN
    if (state.hasSelection) flags = flags or WIN32_EDIT_MENU_SELECTION
    if (state.editable) flags = flags or WIN32_EDIT_MENU_EDITABLE
    if (state.hasText) flags = flags or WIN32_EDIT_MENU_HAS_TEXT
    return flags
}

// The bits of win32_edit_menu.h, which the C test holds.
private const val WIN32_EDIT_MENU_ON = 1
private const val WIN32_EDIT_MENU_KNOWN = 2
private const val WIN32_EDIT_MENU_SELECTION = 4
private const val WIN32_EDIT_MENU_EDITABLE = 8
private const val WIN32_EDIT_MENU_HAS_TEXT = 16
