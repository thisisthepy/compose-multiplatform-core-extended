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

@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.foundation.internal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString

// Extended hook: an embedder that supplies its own Clipboard (no AWT display, no
// java.awt.datatransfer) wraps plain text as `ClipEntry("text")`. Copying from a text field
// produces `ClipEntry(annotatedString)`. Both are read without touching Transferable or
// DataFlavor, and the AWT clipboard converts them when it stores the entry. Every other
// nativeClipEntry keeps the Transferable path in AwtClipEntry.desktop.kt.
// The public API is unchanged: the constructor already accepts Any.

/** True when this entry holds text (a [String] or an [AnnotatedString]), not a Transferable. */
internal fun ClipEntry.isPlainText(): Boolean =
    nativeClipEntry is String || nativeClipEntry is AnnotatedString

/** The text of a plain-text entry, or null when the entry is not plain text. */
internal fun ClipEntry.plainTextOrNull(): String? =
    when (val native = nativeClipEntry) {
        is String -> native
        is AnnotatedString -> native.text
        else -> null
    }

/** The styled text of a plain-text entry, or null when the entry is not plain text. */
internal fun ClipEntry.annotatedStringOrNull(): AnnotatedString? =
    when (val native = nativeClipEntry) {
        is String -> AnnotatedString(native)
        is AnnotatedString -> native
        else -> null
    }
