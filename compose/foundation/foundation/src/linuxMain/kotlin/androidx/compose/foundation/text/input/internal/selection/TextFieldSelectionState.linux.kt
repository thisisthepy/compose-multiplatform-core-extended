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

package androidx.compose.foundation.text.input.internal.selection

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.internal.hasText
import androidx.compose.foundation.text.TextDragObserver
import androidx.compose.foundation.text.nativeBasicTextFieldTextContextMenuComponents
import androidx.compose.foundation.text.selection.MouseSelectionObserver
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CoroutineScope

/** Runs platform-specific text tap gestures logic. */
internal actual suspend fun TextFieldSelectionState.detectTextFieldTapGestures(
    pointerInputScope: PointerInputScope,
    interactionSource: MutableInteractionSource?,
    requestFocus: () -> Unit,
    showKeyboard: () -> Unit,
) = defaultDetectTextFieldTapGestures(pointerInputScope, interactionSource, requestFocus, showKeyboard)

/** Runs platform-specific text selection gestures logic. */
internal actual suspend fun TextFieldSelectionState.textFieldSelectionGestures(
    pointerInputScope: PointerInputScope,
    mouseSelectionObserver: MouseSelectionObserver,
    textDragObserver: TextDragObserver
) = pointerInputScope.defaultTextFieldSelectionGestures(mouseSelectionObserver, textDragObserver)

internal actual fun Modifier.addBasicTextFieldTextContextMenuComponents(
    state: TextFieldSelectionState,
    coroutineScope: CoroutineScope
): Modifier = nativeBasicTextFieldTextContextMenuComponents(state, coroutineScope)

internal actual class ClipboardPasteState actual constructor(private val clipboard: Clipboard) {
    private var _hasClip = false
    private var _hasText = false

    actual val hasText: Boolean get() = _hasText
    actual val hasClip: Boolean get() = _hasClip

    actual suspend fun update() {
        // One question rather than two. The platforms that keep a list of what is on the
        // clipboard can be asked whether there is anything at all and separately whether
        // any of it is text; here the only way to know either is to fetch what is there.
        val nativeClipboard = clipboard.nativeClipboard
        val text = nativeClipboard.readText()
        _hasClip = !text.isNullOrEmpty()
        _hasText = _hasClip
    }
}