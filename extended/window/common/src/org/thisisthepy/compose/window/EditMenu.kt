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
