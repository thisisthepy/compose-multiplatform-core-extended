/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.text

import androidx.compose.foundation.text.contextmenu.builder.TextContextMenuBuilderScope
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.input.internal.selection.TextFieldSelectionState
import androidx.compose.foundation.text.selection.SelectionManager
import androidx.compose.foundation.text.selection.TextFieldSelectionManager
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Cut, Copy, Paste and Select All for an editable field, and Copy and Select All for text
 * that can only be read.
 *
 * Which entries a menu offers is decided here and how it is drawn is decided elsewhere, so
 * a platform that hands the menu to the system and one that draws its own both get the
 * same entries.
 */
private fun TextContextMenuBuilderScope.entry(
    item: NativeTextContextMenuItems,
    enabled: Boolean,
    closeAfter: () -> Boolean = { true },
    onClick: () -> Unit,
) {
    item(key = item.key, label = item.label, enabled = enabled) {
        onClick()
        if (closeAfter()) close()
    }
}

internal fun Modifier.addTextFieldTextContextMenuComponents(
    coroutineScope: CoroutineScope,
    entries: TextContextMenuBuilderScope.(suspending: (NativeTextContextMenuItems, Boolean, suspend () -> Unit) -> Unit) -> Unit,
): Modifier = appendTextContextMenuComponents {
    val suspending: (NativeTextContextMenuItems, Boolean, suspend () -> Unit) -> Unit =
        { item, enabled, onClick ->
            entry(item, enabled) {
                // Undispatched, so that a menu entry which reads the clipboard starts
                // running while the menu is still the thing the reader is looking at.
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { onClick() }
            }
        }
    entries(suspending)
}

internal fun Modifier.nativeBasicTextFieldTextContextMenuComponents(
    state: TextFieldSelectionState,
    coroutineScope: CoroutineScope,
): Modifier = addTextFieldTextContextMenuComponents(coroutineScope) { suspending ->
    with(state) {
        separator()
        suspending(NativeTextContextMenuItems.Cut, canShowCutMenuItem()) { cut() }
        suspending(NativeTextContextMenuItems.Copy, canShowCopyMenuItem()) {
            copy(cancelSelection = false)
        }
        suspending(NativeTextContextMenuItems.Paste, canShowPasteMenuItem()) { paste() }
        entry(NativeTextContextMenuItems.SelectAll, canShowSelectAllMenuItem()) { selectAll() }
        separator()
    }
}

internal fun Modifier.nativeBasicTextFieldTextContextMenuComponents(
    manager: TextFieldSelectionManager,
    coroutineScope: CoroutineScope,
): Modifier = addTextFieldTextContextMenuComponents(coroutineScope) { suspending ->
    with(manager) {
        separator()
        suspending(NativeTextContextMenuItems.Cut, canShowCutMenuItem()) { cut() }
        suspending(NativeTextContextMenuItems.Copy, canShowCopyMenuItem()) {
            copy(cancelSelection = false)
        }
        suspending(NativeTextContextMenuItems.Paste, canShowPasteMenuItem()) { paste() }
        entry(NativeTextContextMenuItems.SelectAll, canShowSelectAllMenuItem()) { selectAll() }
        separator()
    }
}

internal fun Modifier.nativeSelectionContainerTextContextMenuComponents(
    selectionManager: SelectionManager,
): Modifier = appendTextContextMenuComponents {
    with(selectionManager) {
        separator()
        entry(NativeTextContextMenuItems.Copy, enabled = isNonEmptySelection()) { copy() }
        entry(
            item = NativeTextContextMenuItems.SelectAll,
            enabled = !isEntireContainerSelected(),
            closeAfter = { !showToolbar || !isInTouchMode },
        ) {
            selectAll()
        }
        separator()
    }
}
