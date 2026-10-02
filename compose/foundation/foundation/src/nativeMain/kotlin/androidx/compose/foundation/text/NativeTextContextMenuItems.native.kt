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

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys

/**
 * The four entries a text context menu offers, with the label each one reads in the
 * reader's own language.
 *
 * The key is what identifies an entry to anything that wants to filter or replace it, so
 * it is the shared one rather than a private one: an application that removes Paste from
 * a menu should not have to know which platform drew it.
 */
internal enum class NativeTextContextMenuItems(
    val key: Any,
    private val string: ContextMenuStrings,
) {
    Cut(TextContextMenuKeys.CutKey, ContextMenuStrings.Cut),
    Copy(TextContextMenuKeys.CopyKey, ContextMenuStrings.Copy),
    Paste(TextContextMenuKeys.PasteKey, ContextMenuStrings.Paste),
    SelectAll(TextContextMenuKeys.SelectAllKey, ContextMenuStrings.SelectAll);

    val label: String get() = getLocalizedString(string)
}
