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
